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
        var totalUpdated = 0
        var streamsFetched = 0

        // Look back a week before the newest stored start: activities uploaded late (watch synced days later,
        // start time before our newest activity) would otherwise never be fetched. Also always re-check the last
        // RECHECK_DAYS: activities edited on Strava (name, type, race/long-run/workout tag, trainer, cropped
        // distance/time) are updated. Unchanged stored activities cost no extra API calls.
        val lastSync = activityRepository.findLatestActivityTimestamp()
        val afterEpoch = lastSync?.let {
            minOf(it.minusDays(OVERLAP_DAYS), ZonedDateTime.now().minusDays(RECHECK_DAYS)).toEpochSecond()
        }
        val knownIds = activityRepository.findAllIds().toMutableSet()

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
                    val newActivities = activities.filter { it.id !in knownIds }
                    totalNew += newActivities.size

                    // Edited on Strava: update the summary fields; re-fetch laps/streams when the recording itself
                    // changed (distance or time, e.g. a crop).
                    val needDetails = mutableListOf<Activity>()
                    activities.filter { it.id in knownIds }.forEach { summary ->
                        val stored = activityRepository.findById(summary.id) ?: return@forEach
                        if (summaryChanged(stored, summary)) {
                            activityRepository.save(mergeSummary(stored, summary))
                            totalUpdated++
                            if (recordingChanged(stored, summary)) needDetails += summary
                            log.info("Activity {} was edited on Strava; updated", summary.id)
                        }
                    }

                    activityRepository.saveAll(newActivities)
                    knownIds += newActivities.map { it.id }

                    (newActivities + needDetails).forEach { activity ->
                        val base = activityRepository.findById(activity.id)?.takeIf { activity in needDetails } ?: activity
                        try {
                            val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                            val fullActivity = base.copy(
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

        if (totalUpdated > 0) log.info("Sync updated {} activities edited on Strava", totalUpdated)
        return SyncStravaData.SyncResult(totalFetched, totalNew, streamsFetched, errors, totalUpdated)
    }

    companion object {
        const val OVERLAP_DAYS = 7L

        /** Always re-check this many days back for activities edited on Strava. */
        const val RECHECK_DAYS = 30L

        /** Fields the Strava summary list carries that a user can edit (or that change on a crop). */
        internal fun summaryChanged(stored: Activity, summary: Activity): Boolean =
            stored.name != summary.name ||
                stored.type != summary.type ||
                stored.sportType != summary.sportType ||
                stored.workoutType != summary.workoutType ||
                stored.isTrainer != summary.isTrainer ||
                stored.isCommute != summary.isCommute ||
                stored.gearId != summary.gearId && summary.gearId != null ||
                recordingChanged(stored, summary)

        internal fun recordingChanged(stored: Activity, summary: Activity): Boolean =
            stored.distance != summary.distance ||
                stored.movingTime != summary.movingTime ||
                stored.elapsedTime != summary.elapsedTime

        /** Stored activity with the editable summary fields taken from Strava; detail-only fields are kept. */
        internal fun mergeSummary(stored: Activity, summary: Activity): Activity = stored.copy(
            name = summary.name,
            type = summary.type,
            sportType = summary.sportType,
            workoutType = summary.workoutType,
            isTrainer = summary.isTrainer,
            isCommute = summary.isCommute,
            gearId = summary.gearId ?: stored.gearId,
            distance = summary.distance,
            movingTime = summary.movingTime,
            elapsedTime = summary.elapsedTime,
            totalElevationGain = summary.totalElevationGain,
            averageSpeed = summary.averageSpeed,
            maxSpeed = summary.maxSpeed,
            laps = null,
        )
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
