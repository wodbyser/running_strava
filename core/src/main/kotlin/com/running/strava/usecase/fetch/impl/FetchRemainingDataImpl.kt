package com.running.strava.usecase.fetch.impl

import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.fetch.FetchRemainingData
import org.slf4j.LoggerFactory

class FetchRemainingDataImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
    private val activityRepository: ActivityRepository,
) : FetchRemainingData {

    private val log = LoggerFactory.getLogger(FetchRemainingDataImpl::class.java)

    override fun execute(): FetchRemainingData.FetchResult {
        val token = tokenRepository.get()
            ?: return FetchRemainingData.FetchResult(0, 0, listOf("No Strava token found. Complete OAuth first."))

        val refreshedToken = ensureValidToken(token)
        val existingIds = activityRepository.findAllIds().toMutableSet()
        val errors = mutableListOf<String>()
        var newCount = 0
        var streamsCount = 0
        var page = 1

        log.info("Found {} existing activities. Scanning Strava pages for missing ones...", existingIds.size)

        while (true) {
            try {
                val activities = stravaApiClient.getAthleteActivities(
                    token = refreshedToken,
                    page = page,
                    perPage = 200,
                    after = null,
                )

                if (activities.isEmpty()) {
                    log.info("No more activities on page {}", page)
                    break
                }

                val newActivities = activities.filter { it.id !in existingIds }

                if (newActivities.isNotEmpty()) {
                    log.info("Page {}: found {} new activities out of {}", page, newActivities.size, activities.size)

                    activityRepository.saveAll(newActivities)

                    newActivities.forEach { activity ->
                        try {
                            val detail = stravaApiClient.getActivity(refreshedToken, activity.id)
                            activityRepository.save(detail)

                            val streams = stravaApiClient.getActivityStreams(refreshedToken, activity.id)
                            activityRepository.saveStreams(activity.id, streams)
                            streamsCount++

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

                    newCount += newActivities.size
                    existingIds.addAll(newActivities.map { it.id })
                } else {
                    log.info("Page {}: all {} activities already exist in DB", page, activities.size)
                }

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

        return FetchRemainingData.FetchResult(newCount, streamsCount, errors)
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
