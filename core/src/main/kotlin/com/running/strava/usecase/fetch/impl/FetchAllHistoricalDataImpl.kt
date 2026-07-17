package com.running.strava.usecase.fetch.impl

import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.fetch.FetchAllHistoricalData
import org.slf4j.LoggerFactory

class FetchAllHistoricalDataImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
    private val activityRepository: ActivityRepository,
) : FetchAllHistoricalData {

    private val log = LoggerFactory.getLogger(FetchAllHistoricalDataImpl::class.java)

    override fun execute(): FetchAllHistoricalData.FetchResult {
        val token = tokenRepository.get()
            ?: return FetchAllHistoricalData.FetchResult(0, listOf("No Strava token found. Complete OAuth first."))

        val refreshedToken = ensureValidToken(token)
        val errors = mutableListOf<String>()
        var totalFetched = 0
        var page = 1

        while (true) {
            try {
                val activities = stravaApiClient.getAthleteActivities(
                    token = refreshedToken,
                    page = page,
                    perPage = 200,
                    after = null,
                )

                if (activities.isEmpty()) break

                activityRepository.saveAll(activities)

                activities.forEach { activity ->
                    try {
                        val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                        activityRepository.save(detail)

                        val streams = stravaApiClient.getActivityStreams(refreshedToken, activity.id)
                        activityRepository.saveStreams(activity.id, streams)

                        log.info("Fetched activity {}: {}", activity.id, activity.name)
                    } catch (e: RateLimitExceededException) {
                        log.warn("Rate limit exceeded while fetching details for activity {}, aborting", activity.id)
                        errors.add("Activity ${activity.id}: Rate limit exceeded — stopped fetching details")
                        throw e
                    } catch (e: Exception) {
                        log.warn("Failed to fetch details for activity {}: {}", activity.id, e.message)
                        errors.add("Activity ${activity.id}: ${e.message}")
                    }
                }

                totalFetched += activities.size
                log.info("Fetched page {} ({} activities, total: {})", page, activities.size, totalFetched)
                page++

            } catch (e: RateLimitExceededException) {
                log.warn("Rate limit exceeded on page {}, aborting fetch", page)
                errors.add("Rate limit exceeded — stopped after page $page")
                break
            } catch (e: Exception) {
                log.error("Failed to fetch page {}: {}", page, e.message)
                errors.add("Page $page: ${e.message}")
                break
            }
        }

        return FetchAllHistoricalData.FetchResult(totalFetched, errors)
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
