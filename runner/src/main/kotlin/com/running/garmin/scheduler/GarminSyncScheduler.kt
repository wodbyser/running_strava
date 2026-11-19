package com.running.garmin.scheduler

import com.running.garmin.adapter.GarminStartOutcome
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.tenant.TenantJobRunner
import com.running.tenant.runPerTenant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Not created at all when `garmin.enabled=false`. */
@Component
@ConditionalOnProperty(prefix = "garmin", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GarminSyncScheduler(
    private val coordinator: GarminSyncCoordinator,
    private val tenantJobs: ObjectProvider<TenantJobRunner>,
) {

    private val log = LoggerFactory.getLogger(GarminSyncScheduler::class.java)

    @Scheduled(cron = "\${garmin.sync.cron:0 30 6 * * *}")
    fun syncGarmin() = tenantJobs.runPerTenant("garmin-sync") { syncOnce() }

    private fun syncOnce() {
        when (coordinator.startSync(async = false)) {
            GarminStartOutcome.NOT_CONFIGURED -> log.warn("Garmin API key not configured, skipping scheduled sync")
            GarminStartOutcome.BUSY -> log.info("Garmin sync already running, skipping scheduled sync")
            GarminStartOutcome.DISABLED, GarminStartOutcome.STARTED -> Unit
        }
    }
}
