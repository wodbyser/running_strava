package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class TenantRegistryAndJobsTest {
    private fun registry(vararg t: Tenant) = TenantRegistry { t.toList() }.also { it.reload() }

    @Test
    fun `owner comes first whatever the loader order`() {
        val r = registry(Tenant("anna", "running_u_anna", false), Tenant("wout", "running", true))
        assertEquals(listOf("wout", "anna"), r.activeOrdered().map { it.id })
        assertEquals("wout", r.ownerId)
    }

    @Test
    fun `exactly one owner is required and the owner cannot be removed`() {
        assertThrows(IllegalStateException::class.java) { registry(Tenant("anna", "running_u_anna", false)) }
        val r = registry(Tenant("wout", "running", true))
        r.remove("wout")
        assertEquals(1, r.activeCount())
    }

    @Test
    fun `job runner is sequential, owner first, pauses between tenants and survives a failing tenant`() {
        val r = registry(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false), Tenant("piet", "running_u_piet", false))
        val events = mutableListOf<String>()
        val runner = TenantJobRunner(r, Duration.ofSeconds(5)) { events += "pause" }
        runner.runAll("test") {
            val id = TenantContext.currentOrFail()
            events += id
            if (id == "anna") error("anna fails")
        }
        assertEquals(listOf("wout", "pause", "anna", "pause", "piet"), events)
        assertEquals(null, TenantContext.current())
    }

    @Test
    fun `owner only means no pause`() {
        val events = mutableListOf<String>()
        TenantJobRunner(registry(Tenant("wout", "running", true)), Duration.ofSeconds(5)) { events += "pause" }.runAll("t") { events += "run" }
        assertEquals(listOf("run"), events)
    }
}
