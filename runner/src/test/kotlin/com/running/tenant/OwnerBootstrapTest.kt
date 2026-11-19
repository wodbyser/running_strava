package com.running.tenant

import com.running.config.AppSecurityProperties
import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.util.UUID

class OwnerBootstrapTest {
    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    private lateinit var jdbc: JdbcTemplate
    private lateinit var users: AppUserRepository

    @BeforeEach
    fun setUp() {
        val ds = DriverManagerDataSource("jdbc:h2:mem:control-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
        jdbc = JdbcTemplate(ds)
        ControlSchema.create(jdbc)
        ControlSchema.create(jdbc)
        users = AppUserRepository(jdbc)
    }

    private fun boot(password: String, username: String = "wout", db: String = "running") =
        OwnerBootstrap(users, AppSecurityProperties(username, password, ""), db).run()

    @Test
    fun `fresh database creates the owner on the owner's database`() {
        boot("secret-password-1")
        val owner = users.findByUsername("wout")!!
        assertEquals("OWNER", owner.role)
        assertEquals("wout", owner.tenantId)
        assertEquals("running", owner.tenantDb)
        assertEquals("ACTIVE", owner.status)
        assertTrue(encoder.matches("secret-password-1", owner.passwordHash))
        assertTrue(owner.toPrincipal().isEnabled)
    }

    @Test
    fun `unchanged password keeps the same hash so remember-me cookies stay valid`() {
        boot("secret-password-1")
        val before = users.findByUsername("wout")!!.passwordHash
        boot("secret-password-1")
        boot("secret-password-1")
        assertEquals(before, users.findByUsername("wout")!!.passwordHash)
    }

    @Test
    fun `changed password is re-hashed`() {
        boot("secret-password-1")
        val before = users.findByUsername("wout")!!.passwordHash
        boot("another-password-2")
        val after = users.findByUsername("wout")!!.passwordHash
        assertNotEquals(before, after)
        assertTrue(encoder.matches("another-password-2", after))
    }

    @Test
    fun `blank password leaves the stored hash alone`() {
        boot("secret-password-1")
        val before = users.findByUsername("wout")!!.passwordHash
        boot("")
        assertEquals(before, users.findByUsername("wout")!!.passwordHash)
    }

    @Test
    fun `a changed username is refused instead of creating a second owner`() {
        boot("secret-password-1")
        assertThrows(IllegalStateException::class.java) { boot("secret-password-1", username = "anna") }
        assertEquals(1, users.findOwners().size)
    }

    @Test
    fun `more than one owner refuses to start`() {
        boot("secret-password-1")
        users.insertOwner("second", "{noop}x", "second", "running_u_second")
        assertThrows(IllegalStateException::class.java) { boot("secret-password-1") }
    }

    @Test
    fun `owner database that differs from the configured one refuses to start`() {
        boot("secret-password-1")
        assertThrows(IllegalStateException::class.java) { boot("secret-password-1", db = "running_restore_test") }
    }

    @Test
    fun `lookup is case-insensitive and unknown users fail, disabled users are not enabled`() {
        boot("secret-password-1")
        val service = ControlUserDetailsService(users)
        assertNotNull(service.loadUserByUsername(" Wout "))
        assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException::class.java) { service.loadUserByUsername("nobody") }
        jdbc.update("UPDATE app_user SET status = 'DISABLED'")
        assertTrue(!service.loadUserByUsername("wout").isEnabled)
    }

    @Test
    fun `flag on with blank password or H2 url is refused`() {
        assertThrows(IllegalStateException::class.java) { MultiUserGuards.check(false, "jdbc:postgresql://localhost:5432/running") }
        assertThrows(IllegalStateException::class.java) { MultiUserGuards.check(true, "jdbc:h2:file:./data/running") }
        MultiUserGuards.check(true, "jdbc:postgresql://localhost:5432/running")
    }

    @Test
    fun `jdbc url database name is swapped and the query kept`() {
        val url = "jdbc:postgresql://db:5432/running?sslmode=require"
        assertEquals("running", JdbcUrls.databaseName(url))
        assertEquals("jdbc:postgresql://db:5432/running_control?sslmode=require", JdbcUrls.withDatabase(url, "running_control"))
    }
}
