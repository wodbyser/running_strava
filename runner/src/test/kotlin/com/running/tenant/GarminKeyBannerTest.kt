package com.running.tenant

import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.domain.GarminSyncStatus
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.garmin.web.GarminKeyBanner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider

class GarminKeyBannerTest {
    private val status = mock(GarminSyncStatusRepository::class.java)
    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false)) }.also { it.reload() }

    @Suppress("UNCHECKED_CAST")
    private fun banner(r: TenantRegistry?) = GarminKeyBanner(
        mock(ObjectProvider::class.java).also { `when`(it.ifAvailable).thenReturn(r) } as ObjectProvider<TenantRegistry>, status,
    )

    @Test
    fun `flag off, owner and no tenant never touch the database`() {
        assertFalse(banner(null).expired())
        assertFalse(TenantContext.asTenant("wout") { banner(registry).expired() })
        assertFalse(banner(registry).expired())
        verifyNoInteractions(status)
    }

    @Test
    fun `a tenant with an AUTH error sees the banner, other errors do not`() {
        `when`(status.get()).thenReturn(GarminSyncStatus.EMPTY.copy(lastErrorCategory = GarminErrorCategory.AUTH))
        assertTrue(TenantContext.asTenant("anna") { banner(registry).expired() })
        `when`(status.get()).thenReturn(GarminSyncStatus.EMPTY.copy(lastErrorCategory = GarminErrorCategory.NETWORK))
        assertFalse(TenantContext.asTenant("anna") { banner(registry).expired() })
    }
}
