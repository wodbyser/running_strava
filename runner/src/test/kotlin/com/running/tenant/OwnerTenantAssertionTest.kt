package com.running.tenant

import com.running.config.AppSecurityProperties
import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.util.UUID

/** The database in the JDBC URL must be the OWNER row's `tenant_db` (design §5.6): a wrong URL makes start-up fail. */
class OwnerTenantAssertionTest {
    private val users = AppUserRepository(
        JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:assert-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")).also { ControlSchema.create(it) },
    )

    private fun props(url: String) = DataSourceProperties().apply { this.url = url }

    private fun config(url: String) = MultiUserConfig().let { c ->
        val bootstrap = OwnerBootstrap(users, AppSecurityProperties("wout", "secret-password-1", ""), "running")
        bootstrap.run()
        c.tenantRegistry(users, bootstrap, props(url))
    }

    @Test
    fun `matching database starts, the owner is the only tenant and comes first`() {
        val registry = config("jdbc:postgresql://db:5432/running")
        assertEquals(listOf("wout"), registry.activeOrdered().map { it.id })
        assertEquals("running", registry.activeOrdered().single().db)
    }

    @Test
    fun `a URL that points at another database refuses to start`() {
        assertThrows(IllegalStateException::class.java) { config("jdbc:postgresql://db:5432/running_restore_test") }
    }

    @Test
    fun `provisioner refuses bad names, reserved names and existing users before touching any database`() {
        val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
        users.insertUser("anna", encoder.encode("x"), "USER", "anna", "running_u_anna", "ACTIVE")
        val registry = TenantRegistry { listOf(Tenant("wout", "running", true)) }.also { it.reload() }
        val provisioner = TenantProvisioner(users, registry, TenantSchemaUpdater({ error("must not run") }) { }, { }, "jdbc:postgresql://nowhere:1/running", "u", "p", encoder)
        for (bad in listOf("Robert'); DROP DATABASE running;--", "ab", "running_control", "postgres", "1abc", "anna")) {
            assertThrows(TenantProvisionException::class.java, { provisioner.provision(bad) }, bad)
        }
        assertEquals(1, registry.activeCount())
    }
}
