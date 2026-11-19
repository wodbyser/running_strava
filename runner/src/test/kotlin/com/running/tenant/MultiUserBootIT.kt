package com.running.tenant

import com.running.tenant.db.AppUserRepository
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

class MultiUserBootIT @Autowired constructor(
    private val env: Environment,
    private val dataSource: DataSource,
    private val capture: HibernateMetadataCapture,
    private val updater: TenantSchemaUpdater,
    private val users: AppUserRepository,
) : MultiUserIT() {

    @Test
    fun `flag on switches Hibernate's own start-up schema work off and keeps it off the boot thread`() {
        assertEquals("none", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("false", env.getProperty("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access"))
    }

    @Test
    fun `owner pool is the auto-configured one, wrapped, with the same size`() {
        val router = dataSource as TenantRoutingDataSource
        val pool = router.ownerPool as HikariDataSource
        assertEquals(2, pool.maximumPoolSize)
        assertEquals(TestPostgres.container.jdbcUrl, pool.jdbcUrl)
    }

    @Test
    fun `no tenant bound means no connection, even for the owner`() {
        assertThrows(TenantNotResolvedException::class.java) { dataSource.connection }
    }

    @Test
    fun `an empty owner database got the full schema at boot and is up to date afterwards`() {
        val tables = TenantContext.asTenant("wout") {
            JdbcTemplate(dataSource).queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String::class.java)
        }
        assertTrue(tables.containsAll(listOf("activities", "activity_laps", "activity_streams", "strava_tokens", "garmin_raw_payload")), "tables: $tables")
        val uniques = TenantContext.asTenant("wout") {
            JdbcTemplate(dataSource).queryForList("SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace", String::class.java)
        }
        assertTrue(uniques.contains("uq_garmin_raw_endpoint_key"), "unique constraints: $uniques")
        assertTrue(TenantContext.asTenant("wout") { updater.update() }.isEmpty(), "an up-to-date database must produce zero statements")
        assertTrue(TenantContext.asTenant("wout") { updater.update() }.isEmpty())
    }

    @Test
    fun `owner row points at the configured database`() {
        val owner = users.findOwners().single()
        assertEquals("wout", owner.tenantId)
        assertEquals("running", owner.tenantDb)
        assertNotNull(owner.passwordHash)
    }
}
