package com.running.strava.scheduler

import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.sync.SyncStravaData
import com.running.tenant.DataModeGuard
import com.running.tenant.TenantBackfill
import com.running.tenant.TenantContext
import com.running.tenant.TenantJobRunner
import com.running.tenant.runPerTenant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StravaSyncScheduler(
    private val syncStravaData: SyncStravaData,
    private val tokenRepository: StravaTokenRepository,
    private val tenantJobs: ObjectProvider<TenantJobRunner>,
    private val modeGuard: ObjectProvider<DataModeGuard>,
    private val backfill: ObjectProvider<TenantBackfill>,
) {

    private val log = LoggerFactory.getLogger(StravaSyncScheduler::class.java)

    @Scheduled(cron = "\${strava.sync.cron:0 0 6 * * *}")
    fun syncNewActivities() = tenantJobs.runPerTenant("strava-sync") { syncOnce() }

    private fun syncOnce() {
        modeGuard.ifAvailable?.let {
            if (!it.stravaAllowed(TenantContext.current())) {
                log.info("Tenant is not set up for Strava, skipping scheduled sync")
                return
            }
        }
        // A tenant's first full fetch is paced by the shared read budget; the plain sync would fetch everything unpaced.
        backfill.ifAvailable?.let {
            if (it.pending()) {
                it.enqueue()
                return
            }
        }
        if (tokenRepository.get() == null) {
            log.info("No Strava token configured, skipping scheduled sync")
            return
        }

        log.info("Starting scheduled Strava sync...")
        val result = syncStravaData.execute()
        log.info("Scheduled sync complete: {} fetched, {} new, {} streams",
            result.activitiesFetched, result.newActivities, result.streamsFetched)

        if (result.errors.isNotEmpty()) {
            log.warn("Sync had {} errors: {}", result.errors.size, result.errors.joinToString("; "))
        }
    }
}
