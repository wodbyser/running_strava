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

/** The coordinator asks the credential resolver, so only a tenant with a usable key can start a run, and "busy" is per tenant. */
class GarminTenantGateTest {
    private val sync = mock(SyncGarminData::class.java)
    private val backfill = mock(BackfillGarminData::class.java)
    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false)) }.also { it.reload() }
    private val queued = mutableListOf<Runnable>()
    private val properties = GarminProperties(apiKey = "fake-key-for-test")
    private val credentials = CredentialResolver(properties, registry)
    private val coordinator = GarminSyncCoordinator(properties, sync, backfill, Executor { queued += it }) { credentials.garminConfigured() }

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

    @Test
    fun `busy is per tenant`() {
        val withKey = CredentialResolver(properties, registry, { "enc" }, null)
        assertEquals(GarminStartOutcome.NOT_CONFIGURED, TenantContext.asTenant("anna") { GarminSyncCoordinator(properties, sync, backfill, Executor { queued += it }) { withKey.garminConfigured() }.startSync() })
        val open = GarminSyncCoordinator(properties, sync, backfill, Executor { queued += it }) { true }
        assertEquals(GarminStartOutcome.STARTED, TenantContext.asTenant("wout") { open.startSync() })
        assertEquals(GarminStartOutcome.BUSY, TenantContext.asTenant("wout") { open.startSync() })
        assertEquals(GarminStartOutcome.STARTED, TenantContext.asTenant("anna") { open.startSync() })
        assertEquals(true, TenantContext.asTenant("wout") { open.isRunning })
        assertEquals(false, TenantContext.asTenant("piet") { open.isRunning })
        queued.forEach { it.run() }
        assertEquals(false, TenantContext.asTenant("wout") { open.isRunning })
    }
}
