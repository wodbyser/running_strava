package com.running.strava.usecase.backfill.impl

import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillLapsData
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

private val runTypes = listOf("Run", "TrailRun", "VirtualRun")

class BackfillLapsDataImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
    private val activityRepository: ActivityRepository,
) : BackfillLapsData {

    private val log = LoggerFactory.getLogger(BackfillLapsDataImpl::class.java)

    override fun execute(): BackfillLapsData.BackfillResult {
        val token = tokenRepository.get()
            ?: return BackfillLapsData.BackfillResult(0, 0, 0, listOf("No Strava token found. Complete OAuth first."))

        val refreshedToken = ensureValidToken(token)
        val errors = mutableListOf<String>()
        var checked = 0
        var updated = 0
        var skipped = 0

        val missingDetail = activityRepository.findIdsMissingDetail()
        val runs = activityRepository.findAll().filter { it.type in runTypes }
        val withLaps = activityRepository.findLapsForActivities(runs.map { it.id }).keys
        // Either the detail call failed earlier, or laps were never stored. Rows that already have laps are done.
        val candidates = runs.filter { it.id in missingDetail || it.id !in withLaps }
            .sortedByDescending { it.startDate }
        skipped = runs.size - candidates.size

        for (activity in candidates) {
            checked++
            try {
                val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                val laps = detail.laps
                if (!laps.isNullOrEmpty()) {
                    activityRepository.saveLaps(activity.id, laps)
                    updated++
                    log.info("Backfilled {} laps for activity {}", laps.size, activity.id)
                }
                // Also mark runs without laps on Strava: otherwise they are fetched on every backfill.
                activityRepository.markDetailFetched(activity.id, ZonedDateTime.now())
            } catch (e: RateLimitExceededException) {
                log.warn("Rate limit exceeded during lap backfill, stopping after {} checked", checked)
                errors.add("Rate limit exceeded — stopped after checking $checked activities. Klik later opnieuw om verder te gaan.")
                break
            } catch (e: Exception) {
                log.warn("Failed to backfill laps for activity {}: {}", activity.id, e.message)
                errors.add("Activity ${activity.id}: ${e.message}")
            }
        }

        return BackfillLapsData.BackfillResult(checked, updated, skipped, errors)
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
