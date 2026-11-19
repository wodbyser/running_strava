package com.running.tenant

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Wraps the auto-configured owner pool (design §5.2/§5.3). Fails closed: no tenant on the thread means an exception,
 * never the owner's database.
 */
class TenantRoutingDataSource(
    private val registry: () -> TenantRegistry,
    val ownerPool: DataSource,
    private val poolFactory: (tenantId: String, database: String) -> DataSource,
) : AbstractDataSource(), AutoCloseable {
    private val tenantPools = ConcurrentHashMap<String, DataSource>()

    override fun getConnection(): Connection = target().connection

    override fun getConnection(username: String, password: String): Connection = target().getConnection(username, password)

    private fun target(): DataSource {
        val id = TenantContext.currentOrFail()
        val reg = registry()
        if (id == reg.ownerId) return ownerPool
        return tenantPools.computeIfAbsent(id) { poolFactory(it, reg.requireActive(it).db) }
    }

    fun closeTenant(id: String) {
        (tenantPools.remove(id) as? AutoCloseable)?.close()
    }

    fun openTenantPools(): Set<String> = tenantPools.keys.toSet()

    override fun close() {
        tenantPools.keys.toList().forEach(::closeTenant)
        (ownerPool as? AutoCloseable)?.close()
    }

    companion object {
        /** Same credentials as the owner pool, only the database differs (design §5.3). */
        fun tenantPool(owner: HikariDataSource, tenantId: String, database: String): DataSource {
            check(database != JdbcUrls.databaseName(owner.jdbcUrl)) { "Tenant '$tenantId' must not use the owner's database" }
            return HikariDataSource(
                HikariConfig().apply {
                    poolName = "tenant-$tenantId"
                    jdbcUrl = JdbcUrls.withDatabase(owner.jdbcUrl, database)
                    username = owner.username
                    password = owner.password
                    maximumPoolSize = 2
                    minimumIdle = 0
                    idleTimeout = 120_000
                },
            )
        }
    }
}
