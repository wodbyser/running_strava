package com.running.strava.scheduler

import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.sync.SyncStravaData
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StravaSyncScheduler(
    private val syncStravaData: SyncStravaData,
    private val tokenRepository: StravaTokenRepository,
) {

    private val log = LoggerFactory.getLogger(StravaSyncScheduler::class.java)

    @Scheduled(cron = "\${strava.sync.cron:0 0 6 * * *}")
    fun syncNewActivities() {
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
