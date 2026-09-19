package com.running.strava.usecase.backfill.impl

import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillLapsData
import org.slf4j.LoggerFactory

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

        val candidates = activityRepository.findAll()
            .filter { it.type in runTypes }
            .sortedByDescending { it.startDate }

        for (activity in candidates) {
            if (activityRepository.findLaps(activity.id).isNotEmpty()) {
                skipped++
                continue
            }

            checked++
            try {
                val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                val laps = detail.laps
                if (!laps.isNullOrEmpty()) {
                    activityRepository.saveLaps(activity.id, laps)
                    updated++
                    log.info("Backfilled {} laps for activity {}", laps.size, activity.id)
                }
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
