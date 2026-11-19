package com.running.tenant

import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminStartOutcome
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.usecase.BackfillGarminData
import com.running.garmin.usecase.SyncGarminData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.concurrent.Executor

/** Until per-tenant Garmin keys exist (Phase 3) the one configured key may only be used for the owner's tenant. */
class GarminTenantGateTest {
    private val sync = mock(SyncGarminData::class.java)
    private val backfill = mock(BackfillGarminData::class.java)
    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false)) }.also { it.reload() }
    private val queued = mutableListOf<Runnable>()
    private val coordinator = GarminSyncCoordinator(
        GarminProperties(apiKey = "fake-key-for-test"), sync, backfill, Executor { queued += it },
    ) { registry.ownerId == TenantContext.current() }

    @Test
    fun `another tenant gets NOT_CONFIGURED and no sync is made`() {
        assertEquals(GarminStartOutcome.NOT_CONFIGURED, TenantContext.asTenant("anna") { coordinator.startSync(async = false) })
        assertEquals(GarminStartOutcome.NOT_CONFIGURED, coordinator.startSync(async = false))
        verifyNoInteractions(sync, backfill)
    }

    @Test
    fun `the owner starts, and an async run keeps the submitting tenant on the executor thread`() {
        var seen: String? = "unset"
        `when`(sync.execute()).thenAnswer { seen = TenantContext.current(); null }
        assertEquals(GarminStartOutcome.STARTED, TenantContext.asTenant("wout") { coordinator.startSync(async = true) })
        assertEquals(1, queued.size)
        assertEquals(null, TenantContext.current())
        queued.single().run()
        assertEquals("wout", seen)
        assertEquals(null, TenantContext.current())
    }
}
