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
        if (candidates.isEmpty()) {
            log.info("Stream backfill: every run already has streams, nothing to do")
            return BackfillStreamsData.BackfillResult(0, 0, 0, false, emptyList())
        }
        val total = candidates.size
        log.info(
            "Stream backfill started: {} runs without streams ({} to {}), newest first",
            total, candidates.last().startDate.toLocalDate(), candidates.first().startDate.toLocalDate(),
        )
        val started = System.nanoTime()

        val refreshedToken = ensureValidToken(token)
        val errors = mutableListOf<String>()
        var fetched = 0
        var failed = 0
        var rateLimited = false

        for ((i, activity) in candidates.withIndex()) {
            val n = i + 1
            try {
                val streams = stravaApiClient.getActivityStreams(refreshedToken, activity.id)
                activityRepository.saveStreams(activity.id, streams)
                fetched++
                log.info("Stream backfill [{}/{}] {}%: activity {} ({}) saved{}", n, total, n * 100 / total,
                    activity.id, activity.startDate.toLocalDate(), progress(started, n, total))
            } catch (e: RateLimitExceededException) {
                log.warn("Stream backfill [{}/{}]: Strava rate limit reached after {} fetched, {} still missing. Run it again in about 15 minutes to continue.",
                    n, total, fetched, total - fetched)
                rateLimited = true
                break
            } catch (e: Exception) {
                failed++
                log.warn("Stream backfill [{}/{}]: activity {} ({}) failed: {}", n, total, activity.id, activity.startDate.toLocalDate(), e.message)
                errors.add("Activiteit ${activity.id}: ${e.message}")
            }
        }
        log.info("Stream backfill finished in {}: {} missing, {} fetched, {} failed, {} still missing, rate limited: {}",
            duration((System.nanoTime() - started) / 1_000_000_000), total, fetched, failed, total - fetched, rateLimited)
        return BackfillStreamsData.BackfillResult(candidates.size, fetched, candidates.size - fetched, rateLimited, errors)
    }

    /** ", elapsed 1m 05s, about 3m 10s left" (estimate from the average time per run so far). */
    private fun progress(startedNanos: Long, done: Int, total: Int): String {
        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000
        val left = if (done > 0) elapsed * (total - done) / done else 0
        return ", elapsed ${duration(elapsed)}" + if (done < total) ", about ${duration(left)} left" else ""
    }

    private fun duration(seconds: Long) = if (seconds >= 60) "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s" else "${seconds}s"

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
