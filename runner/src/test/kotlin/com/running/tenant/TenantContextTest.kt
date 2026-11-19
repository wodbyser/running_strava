package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TenantContextTest {
    @Test
    fun `nested asTenant restores the outer tenant and then clears`() {
        assertNull(TenantContext.current())
        TenantContext.asTenant("a") {
            TenantContext.asTenant("b") { assertEquals("b", TenantContext.current()) }
            assertEquals("a", TenantContext.current())
        }
        assertNull(TenantContext.current())
    }

    @Test
    fun `restores even when the block throws`() {
        runCatching { TenantContext.asTenant("a") { error("boom") } }
        assertNull(TenantContext.current())
    }

    @Test
    fun `currentOrFail throws without a tenant`() {
        assertThrows(TenantNotResolvedException::class.java) { TenantContext.currentOrFail() }
    }

    @Test
    fun `cache keys differ per tenant for the same id`() {
        val a = TenantContext.asTenant("a") { TenantKey.of(42L) }
        val b = TenantContext.asTenant("b") { TenantKey.of(42L) }
        assertNotEquals(a, b)
        assertEquals(a, TenantContext.asTenant("a") { TenantKey.of(42L) })
        assertNull(TenantKey.of(42L).tenant)
    }
}
