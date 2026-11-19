package com.running.tenant

import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

class TenantProvisionException(message: String) : RuntimeException(message)

/**
 * Creates the user row and an empty tenant database (design §5.5). Never drops anything: a failed run leaves the
 * PENDING row (and database) behind and running it again resumes. Never touches the owner's database.
 */
class TenantProvisioner(
    private val users: AppUserRepository,
    private val registry: TenantRegistry,
    private val schema: TenantSchemaUpdater,
    private val closeTenant: (String) -> Unit,
    private val ownerUrl: String,
    private val dbUsername: String,
    private val dbPassword: String?,
    private val encoder: PasswordEncoder,
) {
    private val log = LoggerFactory.getLogger(TenantProvisioner::class.java)

    /** [password] is the user's own (signup); without it the row gets an unusable one (CLI provisioning). */
    fun provision(username: String, password: String? = null): Tenant {
        val id = username.trim().lowercase()
        if (!OwnerBootstrap.TENANT_ID.matches(id) || id in RESERVED) throw TenantProvisionException("Invalid user name")
        val db = DB_PREFIX + id
        if (db == JdbcUrls.databaseName(ownerUrl)) throw TenantProvisionException("Refusing to use the owner's database")
        val existing = users.findByUsername(id)
        if (existing != null && existing.status != "PENDING") throw TenantProvisionException("User '$id' already exists")
        if (existing == null) {
            users.insertUser(id, encoder.encode(password ?: UUID.randomUUID().toString()), "USER", id, db, "PENDING")
            users.audit(id, "PROVISION_STARTED")
        }
        val tenant = Tenant(id, db, owner = false)
        try {
            ControlSchema.ensureDatabase(JdbcUrls.withDatabase(ownerUrl, db), dbUsername, dbPassword)
            registry.add(tenant)
            TenantContext.asTenant(id) { schema.update() }
            users.setStatus(id, "ACTIVE")
            users.audit(id, "PROVISIONED")
            log.info("Provisioned tenant '{}' (database '{}')", id, db)
            return tenant
        } catch (e: Exception) {
            registry.remove(id)
            closeTenant(id)
            log.error("Provisioning tenant '{}' failed ({}); the row stays PENDING, run it again to resume", id, e.javaClass.simpleName)
            throw TenantProvisionException("Provisioning '$id' failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DB_PREFIX = "running_u_"
        val RESERVED = setOf("running", "running_control", "postgres", "template0", "template1")
    }
}
