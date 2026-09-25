package com.running.strava.usecase.sync.impl

import com.running.strava.domain.Activity
import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.sync.SyncStravaData
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SyncStravaDataImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
    private val activityRepository: ActivityRepository,
) : SyncStravaData {

    private val log = LoggerFactory.getLogger(SyncStravaDataImpl::class.java)

    override fun execute(): SyncStravaData.SyncResult {
        val token = tokenRepository.get()
            ?: return SyncStravaData.SyncResult(0, 0, 0, listOf("No Strava token found. Complete OAuth first."))

        val refreshedToken = ensureValidToken(token)
        val errors = mutableListOf<String>()
        var totalFetched = 0
        var totalNew = 0
        var streamsFetched = 0

        // Look back a week before the newest stored start: activities uploaded late (watch synced days later,
        // start time before our newest activity) would otherwise never be fetched. Already-stored ids are skipped.
        val lastSync = activityRepository.findLatestActivityTimestamp()
        val afterEpoch = lastSync?.minusDays(OVERLAP_DAYS)?.toEpochSecond()

        var page = 1
        var hasMore = true

        while (hasMore) {
            try {
                val activities = stravaApiClient.getAthleteActivities(
                    token = refreshedToken,
                    page = page,
                    perPage = 100,
                    after = afterEpoch,
                )

                if (activities.isEmpty()) {
                    hasMore = false
                } else {
                    totalFetched += activities.size
                    val newActivities = activities.filter { activityRepository.findById(it.id) == null }
                    totalNew += newActivities.size

                    activityRepository.saveAll(newActivities)

                    newActivities.forEach { activity ->
                        try {
                            val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                            val fullActivity = activity.copy(
                                description = detail.description,
                                calories = detail.calories,
                                sufferScore = detail.sufferScore,
                                averageHeartrate = detail.averageHeartrate,
                                maxHeartrate = detail.maxHeartrate,
                                averageCadence = detail.averageCadence,
                                averageWatts = detail.averageWatts,
                                maxWatts = detail.maxWatts,
                                weightedAverageWatts = detail.weightedAverageWatts,
                                kilojoules = detail.kilojoules,
                                deviceWatts = detail.deviceWatts,
                                gearId = detail.gearId,
                                startLatlng = detail.startLatlng,
                                endLatlng = detail.endLatlng,
                                elevHigh = detail.elevHigh,
                                elevLow = detail.elevLow,
                                laps = detail.laps,
                                splits = detail.splits,
                                bestEfforts = detail.bestEfforts,
                            )
                            activityRepository.save(fullActivity)

                            val streams = stravaApiClient.getActivityStreams(refreshedToken, activity.id)
                            activityRepository.saveStreams(activity.id, streams)
                            streamsFetched++

                            log.info("Fetched details + streams for activity {}", activity.id)
                        } catch (e: RateLimitExceededException) {
                            log.warn("Rate limit exceeded while fetching details for activity {}, aborting", activity.id)
                            errors.add("Activity ${activity.id}: Rate limit exceeded — stopped fetching details")
                            throw e
                        } catch (e: Exception) {
                            log.warn("Failed to fetch details for activity {}: {}", activity.id, e.message)
                            errors.add("Activity ${activity.id}: ${e.message}")
                        }
                    }

                    page++
                }
            } catch (e: RateLimitExceededException) {
                log.warn("Rate limit exceeded on page {}, aborting sync", page)
                errors.add("Rate limit exceeded — stopped after page $page")
                hasMore = false
            } catch (e: Exception) {
                log.error("Error fetching page {}: {}", page, e.message)
                errors.add("Page $page: ${e.message}")
                hasMore = false
            }
        }

        activityRepository.updateSyncStatus(
            lastActivityId = null,
            lastSyncAt = ZonedDateTime.now(),
        )

        return SyncStravaData.SyncResult(totalFetched, totalNew, streamsFetched, errors)
    }

    companion object {
        const val OVERLAP_DAYS = 7L
    }

    private fun ensureValidToken(token: StravaToken): StravaToken {
        val now = System.currentTimeMillis() / 1000
        if (token.expiresAt <= now + 60) {
            val refreshed = stravaApiClient.refreshToken(token.refreshToken)
            tokenRepository.save(refreshed)
            return refreshed
        }
        return token
    }
}
