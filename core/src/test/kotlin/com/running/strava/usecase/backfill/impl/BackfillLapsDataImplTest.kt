package com.running.strava.usecase.backfill.impl

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

class BackfillLapsDataImplTest {
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

    private class Repo(activities: List<Activity>) : ActivityRepository {
        val list = activities
        var missingDetail: Set<Long> = emptySet()
        val laps = mutableMapOf<Long, List<Lap>>()
        val marked = mutableListOf<Long>()
        override fun save(activity: Activity) {}
        override fun saveAll(activities: List<Activity>) {}
        override fun saveStreams(activityId: Long, streams: ActivityStream) {}
        override fun saveLaps(activityId: Long, laps: List<Lap>) { this.laps[activityId] = laps }
        override fun findById(id: Long) = list.firstOrNull { it.id == id }
        override fun findStreams(activityId: Long): ActivityStream? = null
        override fun findIdsMissingDetail() = missingDetail
        override fun markDetailFetched(activityId: Long, at: ZonedDateTime) { marked += activityId }
        override fun findLaps(activityId: Long) = laps[activityId] ?: emptyList()
        override fun findLapsForActivities(activityIds: Collection<Long>) = laps.filterKeys { it in activityIds }
        override fun findAll(after: ZonedDateTime?) = list
        override fun findAllIds() = list.map { it.id }.toSet()
        override fun findLatestActivityTimestamp() = list.maxOfOrNull { it.startDate }
        override fun getSyncStatus() = SyncStatus(null, null, list.size, false)
        override fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime) {}
    }

    private inner class Api : StravaApiClient {
        val detailCalls = mutableListOf<Long>()
        override fun exchangeToken(code: String) = error("unused")
        override fun refreshToken(refreshToken: String) = error("unused")
        override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?) = error("unused")
        override fun getActivity(token: StravaToken, activityId: Long): Activity {
            detailCalls += activityId
            // Activity 2 has laps on Strava, activity 1 has none.
            return activity(activityId).copy(laps = if (activityId == 2L) listOf(lap(1), lap(2)) else null)
        }
        override fun getActivityStreams(token: StravaToken, activityId: Long) =
            ActivityStream(null, null, null, null, null, null, null, null, null)

        private fun lap(index: Int) = Lap(
            id = index.toLong(), name = "lap", elapsedTime = 60, movingTime = 60,
            startDate = now, startIndex = 0, endIndex = 10, distance = 300f, averageSpeed = 5f,
            maxSpeed = 6f, averageHeartrate = 160f, maxHeartrate = 175f, averageCadence = 90f,
            lapIndex = index, split = 0,
        )
    }

    private val tokens = object : StravaTokenRepository {
        override fun save(token: StravaToken) {}
        override fun get() = StravaToken("a", "r", Long.MAX_VALUE, 1)
        override fun delete() {}
    }

    @Test
    fun `retries failed details and marks runs even when Strava has no laps`() {
        val repo = Repo(listOf(activity(1), activity(2), activity(3)))
        repo.missingDetail = setOf(2L)
        repo.laps[3L] = listOf(Lap(
            id = 1, name = "lap", elapsedTime = 60, movingTime = 60, startDate = now,
            startIndex = 0, endIndex = 10, distance = 300f, averageSpeed = 5f, maxSpeed = 6f,
            averageHeartrate = 160f, maxHeartrate = 175f, averageCadence = 90f, lapIndex = 1, split = 0,
        ))
        val api = Api()

        val result = BackfillLapsDataImpl(api, tokens, repo).execute()

        assertEquals(listOf(1L, 2L), api.detailCalls) // newest first; 3 already has laps
        assertEquals(listOf(1L, 2L), repo.marked)
        assertEquals(2, result.checked)
        assertEquals(1, result.updated)
        assertEquals(1, result.skipped)
    }
}
