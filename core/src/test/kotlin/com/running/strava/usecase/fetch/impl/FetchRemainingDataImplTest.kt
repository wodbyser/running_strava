package com.running.strava.usecase.fetch.impl

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.StravaToken
import com.running.strava.domain.SyncStatus
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class FetchRemainingDataImplTest {
    private val now = ZonedDateTime.parse("2026-11-01T10:00:00Z")

    private fun activity(id: Long) = Activity(
        id = id, name = "run $id", distance = 10000f, movingTime = 3000, elapsedTime = 3000,
        totalElevationGain = 0f, type = "Run", sportType = "Run", startDate = now.minusDays(id),
        timezone = "(GMT+01:00) Europe/Brussels", averageSpeed = 3.3f, maxSpeed = 5f,
        averageHeartrate = 150f, maxHeartrate = 170f, averageCadence = 84f, averageWatts = null,
        maxWatts = null, weightedAverageWatts = null, kilojoules = null, deviceWatts = null,
        description = null, calories = null, sufferScore = null, hasHeartrate = true, elevHigh = null,
        elevLow = null, gearId = null, startLatlng = null, endLatlng = null, isTrainer = false,
        isCommute = false, isManual = false, isFlagged = false, workoutType = 0,
        originalStartDate = null, laps = null, splits = null, bestEfforts = null,
    )

    private class Repo(initial: List<Activity>) : ActivityRepository {
        val store = initial.associateBy { it.id }.toMutableMap()
        var missingDetail: Set<Long> = emptySet()
        val marked = mutableListOf<Long>()
        override fun save(activity: Activity) { store[activity.id] = activity }
        override fun saveAll(activities: List<Activity>) { activities.forEach { save(it) } }
        override fun saveStreams(activityId: Long, streams: ActivityStream) {}
        override fun saveLaps(activityId: Long, laps: List<Lap>) {}
        override fun findById(id: Long) = store[id]
        override fun findStreams(activityId: Long): ActivityStream? = null
        override fun findIdsMissingDetail() = missingDetail
        override fun markDetailFetched(activityId: Long, at: ZonedDateTime) { marked += activityId }
        override fun findLaps(activityId: Long): List<Lap> = emptyList()
        override fun findLapsForActivities(activityIds: Collection<Long>): Map<Long, List<Lap>> = emptyMap()
        override fun findAll(after: ZonedDateTime?) = store.values.toList()
        override fun findAllIds() = store.keys.toSet()
        override fun findLatestActivityTimestamp() = store.values.maxOfOrNull { it.startDate }
        override fun getSyncStatus() = SyncStatus(null, null, store.size, false)
        override fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime) {}
    }

    private class Api(val summaries: List<Activity>) : StravaApiClient {
        val detailCalls = mutableListOf<Long>()
        override fun exchangeToken(code: String) = error("unused")
        override fun refreshToken(refreshToken: String) = error("unused")
        override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?) =
            if (page == 1) summaries else emptyList()
        override fun getActivity(token: StravaToken, activityId: Long): Activity {
            detailCalls += activityId
            return summaries.first { it.id == activityId }.copy(description = "detail")
        }
        override fun getActivityStreams(token: StravaToken, activityId: Long) =
            ActivityStream(null, null, null, null, null, null, null, null, null)
    }

    private val tokens = object : StravaTokenRepository {
        override fun save(token: StravaToken) {}
        override fun get() = StravaToken("a", "r", Long.MAX_VALUE, 1)
        override fun delete() {}
    }

    @Test
    fun `an existing activity with a failed detail fetch gets its detail again and is marked`() {
        val repo = Repo(listOf(activity(1), activity(2)))
        repo.missingDetail = setOf(2L)
        val api = Api(listOf(activity(1), activity(2)))

        val result = FetchRemainingDataImpl(api, tokens, repo).execute()

        assertEquals(0, result.newActivities)
        assertEquals(listOf(2L), api.detailCalls)
        assertEquals(listOf(2L), repo.marked)
        assertEquals("detail", repo.store.getValue(2).description)
        assertEquals(1, result.streamsFetched)
    }
}
