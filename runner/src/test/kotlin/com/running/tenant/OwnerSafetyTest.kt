package com.running.tenant

import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Invariants 3 and 4: the reset layer itself refuses the owner and any database that is not a tenant's own. */
class OwnerSafetyTest {
    private class Recording : DatabaseMaintenance {
        val calls = mutableListOf<String>()
        override fun exists(db: String) = true.also { calls += "exists $db" }
        override fun terminateConnections(db: String) { calls += "terminate $db" }
        override fun rename(from: String, to: String) { calls += "rename $from -> $to" }
        override fun create(db: String) { calls += "create $db" }
    }

    private val recording = Recording()
    private val evicted = mutableListOf<String>()
    private val users = AppUserRepository(
        JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:safety-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")).also { ControlSchema.create(it) },
    )
    private val registry = TenantRegistry {
        listOf(
            Tenant("wout", "running", true),
            Tenant("anna", "running_u_anna", false),
            Tenant("sneaky", "running", false),
            Tenant("odd", "somethingelse", false),
        )
    }.also { it.reload() }
    private val clock = Clock.fixed(Instant.parse("2026-11-19T10:05:00Z"), ZoneOffset.UTC)
    private val schema = TenantSchemaUpdater({ error("not in these tests") }) { }
    private fun reset(ownerDb: String? = "running") = TenantReset(registry, users, schema, recording, ownerDb, { evicted += it }, clock)

    @Test
    fun `the owner cannot be reset and nothing is touched`() {
        assertThrows<TenantResetRefused> { reset().reset("wout") }
        assertTrue(recording.calls.isEmpty())
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `a tenant row that points at the owner's database or a foreign name is refused`() {
        assertThrows<TenantResetRefused> { reset().reset("sneaky") }
        assertThrows<TenantResetRefused> { reset().reset("odd") }
        assertThrows<TenantResetRefused> { reset().reset("nobody") }
        assertTrue(recording.calls.isEmpty())
    }

    @Test
    fun `an ordinary tenant is archived by rename, never dropped, and gets a fresh database`() {
        val failing = TenantSchemaUpdater({ throw IllegalStateException("schema update runs after the rename") }) { }
        val tenantReset = TenantReset(registry, users, failing, recording, "running", { evicted += it }, clock)
        assertThrows<IllegalStateException> { tenantReset.reset("anna") }
        assertEquals(
            listOf("exists running_u_anna", "terminate running_u_anna", "rename running_u_anna -> running_u_anna_old_202611191005", "create running_u_anna"),
            recording.calls,
        )
        assertEquals(listOf("anna"), evicted)
        assertTrue(recording.calls.none { it.contains("drop", ignoreCase = true) })
        assertEquals("anna", registry.find("anna")?.id, "the tenant is back in the registry")
    }
}
