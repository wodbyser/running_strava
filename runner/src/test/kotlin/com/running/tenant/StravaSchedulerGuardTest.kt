package com.running.tenant

import com.running.strava.adapter.BackfillGate
import com.running.strava.adapter.StravaRateBudget
import com.running.strava.domain.StravaToken
import com.running.strava.scheduler.StravaSyncScheduler
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillLapsData
import com.running.strava.usecase.backfill.BackfillStreamsData
import com.running.strava.usecase.fetch.FetchRemainingData
import com.running.strava.usecase.sync.SyncStravaData
import com.running.analysis.FakeActivityRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.Duration
import java.util.concurrent.Executor

class StravaSchedulerGuardTest {
    private val registry = TenantRegistry {
        listOf(Tenant("wout", "running", true), Tenant("unset", "running_u_unset", false), Tenant("garmin", "running_u_garmin", false), Tenant("both", "running_u_both", false), Tenant("fresh", "running_u_fresh", false))
    }.also { it.reload() }
    private val settings = DataModeTest.MapSettings()
    private val modes = TenantModes(registry, settings).also {
        it.complete("garmin", DataMode.GARMIN)
        it.complete("both", DataMode.BOTH)
        it.complete("fresh", DataMode.STRAVA)
    }
    private val synced = mutableListOf<String>()
    private val queued = mutableListOf<Runnable>()
    private val sync = object : SyncStravaData {
        override fun execute() = SyncStravaData.SyncResult(0, 0, 0, emptyList()).also { synced += TenantContext.current()!! }
        override fun executeFull() = execute()
    }
    private val token = object : StravaTokenRepository {
        override fun save(token: StravaToken) = Unit
        override fun get() = StravaToken("a", "r", 0, 1)
        override fun delete() = Unit
    }
    private val backfill = TenantBackfill(
        BackfillGate(), StravaRateBudget(),
        object : FetchRemainingData { override fun execute() = error("not run in this test") },
        object : BackfillStreamsData { override fun execute() = error("not run in this test") },
        object : BackfillLapsData { override fun execute() = error("not run in this test") },
        FakeActivityRepository(emptyList()), settings, modes, Executor { queued += it },
    )

    private fun scheduler(): StravaSyncScheduler {
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("runner", TenantJobRunner(registry, Duration.ZERO))
        beans.registerSingleton("guard", DataModeGuard(modes))
        beans.registerSingleton("backfill", backfill)
        return StravaSyncScheduler(sync, token, beans.getBeanProvider(TenantJobRunner::class.java), beans.getBeanProvider(DataModeGuard::class.java), beans.getBeanProvider(TenantBackfill::class.java))
    }

    @Test
    fun `the nightly sync skips tenants that are not set up for Strava and leaves a pending first fetch to the paced backfill`() {
        settings.values.clear()
        TenantContext.asTenant("fresh") { settings.put(TenantBackfill.PENDING, "true") }
        scheduler().syncNewActivities()
        assertEquals(listOf("wout", "both"), synced, "owner first; unset and garmin-only tenants make no Strava call; the pending one is not synced unpaced")
        assertEquals(1, queued.size, "the pending first fetch is queued instead")
        assertTrue(modes.mode("fresh").usesStrava)
    }
}
