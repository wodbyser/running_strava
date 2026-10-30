package com.running.strava.usecase.backfill.impl

import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillStreamsData
import org.slf4j.LoggerFactory

private val runTypes = setOf("Run", "TrailRun", "VirtualRun")

/**
 * One Strava request per run, newest first. Stops at the first rate limit; already fetched streams are kept, so
 * running it again continues where it stopped. Manual runs are skipped: Strava has no streams for them.
 */
class BackfillStreamsDataImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
    private val activityRepository: ActivityRepository,
) : BackfillStreamsData {

    private val log = LoggerFactory.getLogger(BackfillStreamsDataImpl::class.java)

    override fun execute(): BackfillStreamsData.BackfillResult {
        val token = tokenRepository.get()
            ?: return BackfillStreamsData.BackfillResult(0, 0, 0, false, listOf("Geen Strava-token gevonden. Koppel eerst Strava."))

        val withStreams = activityRepository.findIdsWithStreams()
        val candidates = activityRepository.findAll()
            .filter { it.type in runTypes && !it.isManual && it.id !in withStreams }
            .sortedByDescending { it.startDate }
        if (candidates.isEmpty()) return BackfillStreamsData.BackfillResult(0, 0, 0, false, emptyList())

        val refreshedToken = ensureValidToken(token)
        val errors = mutableListOf<String>()
        var fetched = 0
        var failed = 0
        var rateLimited = false

        for (activity in candidates) {
            try {
                val streams = stravaApiClient.getActivityStreams(refreshedToken, activity.id)
                activityRepository.saveStreams(activity.id, streams)
                fetched++
            } catch (e: RateLimitExceededException) {
                log.warn("Rate limit exceeded during stream backfill after {} of {} runs", fetched, candidates.size)
                rateLimited = true
                break
            } catch (e: Exception) {
                failed++
                log.warn("Failed to backfill streams for activity {}: {}", activity.id, e.message)
                errors.add("Activiteit ${activity.id}: ${e.message}")
            }
        }
        log.info("Stream backfill: {} missing, {} fetched, {} failed, rate limited: {}", candidates.size, fetched, failed, rateLimited)
        return BackfillStreamsData.BackfillResult(candidates.size, fetched, candidates.size - fetched, rateLimited, errors)
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
