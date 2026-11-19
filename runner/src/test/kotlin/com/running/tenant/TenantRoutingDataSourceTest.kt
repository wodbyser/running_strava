package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class TenantRoutingDataSourceTest {
    private class CountingDataSource(val name: String) : AbstractDataSource() {
        val opened = AtomicInteger()
        override fun getConnection(): Connection { opened.incrementAndGet(); throw java.sql.SQLException("fake $name") }
        override fun getConnection(username: String, password: String): Connection = getConnection()
    }

    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false), Tenant("piet", "running_u_piet", false)) }
        .also { it.reload() }
    private val owner = CountingDataSource("owner")
    private val created = mutableMapOf<String, CountingDataSource>()
    private val router = TenantRoutingDataSource({ registry }, owner) { id, db -> CountingDataSource(db).also { created[id] = it } }

    private fun open(ds: DataSource) = runCatching { ds.connection }

    @Test
    fun `no tenant means an exception and zero connections on every pool`() {
        assertThrows(TenantNotResolvedException::class.java) { router.connection }
        assertEquals(0, owner.opened.get())
        assertEquals(emptyMap<String, CountingDataSource>(), created)
    }

    @Test
    fun `unknown or disabled tenant fails closed without touching the owner`() {
        TenantContext.asTenant("nobody") { assertThrows(TenantDisabledException::class.java) { router.connection } }
        assertEquals(0, owner.opened.get())
    }

    @Test
    fun `owner id uses the owner pool, tenants get their own pool once`() {
        TenantContext.asTenant("wout") { open(router) }
        assertEquals(1, owner.opened.get())
        TenantContext.asTenant("anna") { open(router); open(router) }
        TenantContext.asTenant("piet") { open(router) }
        assertEquals(1, owner.opened.get())
        assertEquals(setOf("anna", "piet"), created.keys)
        assertEquals(2, created["anna"]!!.opened.get())
        assertEquals(1, created["piet"]!!.opened.get())
        assertSame(created["anna"], created["anna"])
    }

    @Test
    fun `a tenant that was removed from the registry is refused after closeTenant`() {
        TenantContext.asTenant("anna") { open(router) }
        registry.remove("anna")
        router.closeTenant("anna")
        TenantContext.asTenant("anna") { assertThrows(TenantDisabledException::class.java) { router.connection } }
    }
}
