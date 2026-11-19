package com.running.tenant

import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TenantResetRefused(message: String) : RuntimeException(message)

/** The few statements that run on the maintenance connection (database `postgres`, autocommit). */
interface DatabaseMaintenance {
    fun exists(db: String): Boolean
    fun terminateConnections(db: String)
    fun rename(from: String, to: String)
    fun create(db: String)
}

class JdbcDatabaseMaintenance(private val ownerUrl: String, private val username: String, private val password: String?) : DatabaseMaintenance {
    private fun <T> maintenance(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(JdbcUrls.withDatabase(ownerUrl, "postgres"), username, password).use { conn ->
            conn.autoCommit = true
            block(conn)
        }

    override fun exists(db: String): Boolean = maintenance { conn ->
        conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { st ->
            st.setString(1, db)
            st.executeQuery().use { it.next() }
        }
    }

    override fun terminateConnections(db: String) {
        maintenance { conn ->
            conn.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()").use { st ->
                st.setString(1, db)
                st.executeQuery().use { while (it.next()) Unit }
            }
        }
    }

    override fun rename(from: String, to: String) {
        maintenance { conn -> conn.createStatement().use { it.execute("ALTER DATABASE \"$from\" RENAME TO \"$to\"") } }
    }

    override fun create(db: String) = ControlSchema.ensureDatabase(JdbcUrls.withDatabase(ownerUrl, db), username, password)
}

/**
 * D11: the only way to change a tenant's source. The database is archived by rename (never dropped, never copied) and a
 * fresh one is created. Refuses the OWNER and anything that is not a `running_u_*` database, in this layer and not
 * only in the controller (design §9.3, invariants 3 and 4).
 */
class TenantReset(
    private val registry: TenantRegistry,
    private val users: AppUserRepository,
    private val schema: TenantSchemaUpdater,
    private val maintenance: DatabaseMaintenance,
    private val ownerDb: String?,
    private val onReset: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(TenantReset::class.java)
    private val lock = Any()

    /** Returns the name of the archived database. */
    fun reset(tenantId: String): String = synchronized(lock) {
        val tenant = registry.find(tenantId) ?: throw TenantResetRefused("Unknown tenant")
        if (tenant.owner || tenant.id == registry.ownerId) throw TenantResetRefused("The owner cannot be reset")
        if (tenant.db == ownerDb || !ARCHIVABLE.matches(tenant.db) || tenant.db != TenantProvisioner.DB_PREFIX + tenant.id) {
            throw TenantResetRefused("Refusing to archive database '${tenant.db}'")
        }
        val archive = tenant.db + "_old_" + STAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        check(archive.length <= 63 && archive != ownerDb) { "Invalid archive name" }

        registry.remove(tenant.id)
        onReset(tenant.id)
        try {
            if (maintenance.exists(tenant.db)) {
                maintenance.terminateConnections(tenant.db)
                maintenance.rename(tenant.db, archive)
            }
            maintenance.create(tenant.db)
        } finally {
            registry.add(tenant)
        }
        TenantContext.asTenant(tenant.id) { schema.update() }
        users.audit(tenant.id, "RESET", "archived as $archive")
        log.info("Tenant '{}' reset: database archived as '{}'", tenant.id, archive)
        archive
    }

    /**
     * Delete-user: same guards as [reset], but the fresh database is not created. The tenant leaves the registry for good.
     * Returns the archive name; the owner drops it by hand after a dump (design §14, Phase 7).
     */
    fun archive(tenantId: String, tenantDb: String): String = synchronized(lock) {
        if (tenantId == registry.ownerId || registry.find(tenantId)?.owner == true) throw TenantResetRefused("The owner cannot be deleted")
        if (tenantDb == ownerDb || !ARCHIVABLE.matches(tenantDb) || tenantDb != TenantProvisioner.DB_PREFIX + tenantId) {
            throw TenantResetRefused("Refusing to archive database '$tenantDb'")
        }
        val archive = tenantDb + "_old_" + STAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        check(archive.length <= 63 && archive != ownerDb) { "Invalid archive name" }
        registry.remove(tenantId)
        onReset(tenantId)
        if (maintenance.exists(tenantDb)) {
            maintenance.terminateConnections(tenantDb)
            maintenance.rename(tenantDb, archive)
        }
        log.info("Tenant '{}' deleted: database archived as '{}'", tenantId, archive)
        archive
    }

    private companion object {
        val ARCHIVABLE = Regex("^running_u_[a-z][a-z0-9_]{2,30}$")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
