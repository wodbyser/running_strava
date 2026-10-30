package com.running.strava.usecase.backfill.impl

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.domain.SyncStatus
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class BackfillStreamsDataImplTest {
    private val now = ZonedDateTime.parse("2026-10-30T10:00:00Z")

    private fun activity(id: Long, type: String = "Run", manual: Boolean = false) = Activity(
        id = id, name = "run $id", distance = 10000f, movingTime = 3000, elapsedTime = 3000, totalElevationGain = 0f,
        type = type, sportType = type, startDate = now.minusDays(id), timezone = "(GMT+01:00) Europe/Brussels",
        averageSpeed = 3.3f, maxSpeed = 5f, averageHeartrate = 150f, maxHeartrate = 170f, averageCadence = 84f,
        averageWatts = null, maxWatts = null, weightedAverageWatts = null, kilojoules = null, deviceWatts = null,
        description = null, calories = null, sufferScore = null, hasHeartrate = true, elevHigh = null, elevLow = null,
        gearId = null, startLatlng = null, endLatlng = null, isTrainer = false, isCommute = false, isManual = manual,
        isFlagged = false, workoutType = 0, originalStartDate = null, laps = null, splits = null, bestEfforts = null,
    )

    private val stream = ActivityStream(listOf(0, 1), listOf(0f, 3f), null, null, listOf(140, 141), null, null, null, null)

    private class Repo(val activities: List<Activity>, initialStreams: Set<Long>) : ActivityRepository {
        val streams = initialStreams.toMutableSet()
        override fun save(activity: Activity) {}
        override fun saveAll(activities: List<Activity>) {}
        override fun saveStreams(activityId: Long, streams: ActivityStream) { this.streams += activityId }
        override fun saveLaps(activityId: Long, laps: List<Lap>) {}
        override fun findById(id: Long) = activities.firstOrNull { it.id == id }
        override fun findStreams(activityId: Long): ActivityStream? = null
        override fun findIdsWithStreams() = streams.toSet()
        override fun findLaps(activityId: Long): List<Lap> = emptyList()
        override fun findLapsForActivities(activityIds: Collection<Long>): Map<Long, List<Lap>> = emptyMap()
        override fun findAll(after: ZonedDateTime?) = activities
        override fun findAllIds() = activities.map { it.id }.toSet()
        override fun findLatestActivityTimestamp() = activities.maxOfOrNull { it.startDate }
        override fun getSyncStatus() = SyncStatus(null, null, activities.size, false)
        override fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime) {}
    }

    private inner class Api(val limitAfter: Int = Int.MAX_VALUE, val failing: Set<Long> = emptySet()) : StravaApiClient {
        val calls = mutableListOf<Long>()
        override fun exchangeToken(code: String) = error("unused")
        override fun refreshToken(refreshToken: String) = error("unused")
        override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?) = error("unused")
        override fun getActivity(token: StravaToken, activityId: Long) = error("unused")
        override fun getActivityStreams(token: StravaToken, activityId: Long): ActivityStream {
            if (calls.size >= limitAfter) throw RateLimitExceededException("429")
            calls += activityId
            if (activityId in failing) error("404")
            return stream
        }
    }

    private val tokens = object : StravaTokenRepository {
        override fun save(token: StravaToken) {}
        override fun get() = StravaToken("a", "r", Long.MAX_VALUE, 1)
        override fun delete() {}
    }

    @Test
    fun `only runs without streams are fetched, newest first, rides and manual runs are skipped`() {
        val repo = Repo(listOf(activity(1), activity(2), activity(3), activity(4, type = "Ride"), activity(5, manual = true)), setOf(2))
        val api = Api()
        val r = BackfillStreamsDataImpl(api, tokens, repo).execute()
        assertEquals(listOf(1L, 3L), api.calls)
        assertEquals(2, r.missing)
        assertEquals(2, r.fetched)
        assertEquals(0, r.remaining)
        assertFalse(r.rateLimited)
        assertEquals(setOf(1L, 2L, 3L), repo.streams)
    }

    @Test
    fun `stops at the rate limit and continues on the next run`() {
        val repo = Repo((1L..5L).map { activity(it) }, emptySet())
        val first = BackfillStreamsDataImpl(Api(limitAfter = 2), tokens, repo).execute()
        assertTrue(first.rateLimited)
        assertEquals(2, first.fetched)
        assertEquals(3, first.remaining)
        val api = Api()
        val second = BackfillStreamsDataImpl(api, tokens, repo).execute()
        assertEquals(listOf(3L, 4L, 5L), api.calls)
        assertEquals(0, second.remaining)
    }

    @Test
    fun `a failing run is reported and does not stop the others`() {
        val repo = Repo((1L..3L).map { activity(it) }, emptySet())
        val r = BackfillStreamsDataImpl(Api(failing = setOf(2)), tokens, repo).execute()
        assertEquals(2, r.fetched)
        assertEquals(1, r.remaining)
        assertEquals(1, r.errors.size)
    }
}
