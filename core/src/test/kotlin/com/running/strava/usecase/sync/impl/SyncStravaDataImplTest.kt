package com.running.strava.usecase.sync.impl

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

class SyncStravaDataImplTest {

    private val now = ZonedDateTime.now()

    private fun activity(id: Long, name: String = "Ochtendloop", workoutType: Int? = 0, distance: Float = 10000f, movingTime: Int = 3000) = Activity(
        id = id, name = name, distance = distance, movingTime = movingTime, elapsedTime = movingTime, totalElevationGain = 20f,
        type = "Run", sportType = "Run", startDate = now.minusDays(id), timezone = "(GMT+01:00) Europe/Brussels",
        averageSpeed = distance / movingTime, maxSpeed = 5f, averageHeartrate = 150f, maxHeartrate = 170f, averageCadence = 84f,
        averageWatts = null, maxWatts = null, weightedAverageWatts = null, kilojoules = null, deviceWatts = null,
        description = null, calories = null, sufferScore = null, hasHeartrate = true, elevHigh = null, elevLow = null,
        gearId = null, startLatlng = null, endLatlng = null, isTrainer = false, isCommute = false, isManual = false,
        isFlagged = false, workoutType = workoutType, originalStartDate = null, laps = null, splits = null, bestEfforts = null,
    )

    private class Repo(initial: List<Activity>) : ActivityRepository {
        val store = initial.associateBy { it.id }.toMutableMap()
        override fun save(activity: Activity) { store[activity.id] = activity }
        override fun saveAll(activities: List<Activity>) { activities.forEach { save(it) } }
        override fun saveStreams(activityId: Long, streams: ActivityStream) {}
        override fun saveLaps(activityId: Long, laps: List<Lap>) {}
        override fun findById(id: Long) = store[id]
        override fun findStreams(activityId: Long): ActivityStream? = null
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
        var lastAfter: Long? = null
        override fun exchangeToken(code: String) = error("unused")
        override fun refreshToken(refreshToken: String) = error("unused")
        override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?): List<Activity> {
            lastAfter = after
            return if (page == 1) summaries else emptyList()
        }
        override fun getActivity(token: StravaToken, activityId: Long): Activity {
            detailCalls += activityId
            return summaries.first { it.id == activityId }.copy(description = "detail")
        }
        override fun getActivityStreams(token: StravaToken, activityId: Long) = ActivityStream(null, null, null, null, null, null, null, null, null)
    }

    private val tokens = object : StravaTokenRepository {
        override fun save(token: StravaToken) {}
        override fun get() = StravaToken("a", "r", Long.MAX_VALUE, 1)
        override fun delete() {}
    }

    @Test
    fun `activities edited on Strava are updated, details only re-fetched when the recording changed`() {
        val stored = listOf(
            activity(1).copy(description = "mijn notitie"),
            activity(2),
            activity(3),
        )
        val repo = Repo(stored)
        val api = Api(listOf(
            activity(1, name = "10 km wedstrijd", workoutType = 1), // renamed + tagged as race on Strava
            activity(2),                                           // unchanged
            activity(3, distance = 9500f, movingTime = 2850),      // cropped
            activity(4),                                           // new
        ))
        val result = SyncStravaDataImpl(api, tokens, repo).execute()

        assertEquals(1, result.newActivities)
        assertEquals(2, result.updatedActivities)
        assertEquals("10 km wedstrijd", repo.store.getValue(1).name)
        assertEquals(1, repo.store.getValue(1).workoutType)
        assertEquals("mijn notitie", repo.store.getValue(1).description) // detail field kept, no detail call
        assertEquals(9500f, repo.store.getValue(3).distance)
        assertEquals(listOf(4L, 3L), api.detailCalls) // new + cropped; not the renamed or unchanged one
    }

    @Test
    fun `sync always looks back at least 30 days for edits`() {
        val repo = Repo(listOf(activity(1)))
        val api = Api(emptyList())
        SyncStravaDataImpl(api, tokens, repo).execute()
        val expectedMax = now.minusDays(SyncStravaDataImpl.RECHECK_DAYS).toEpochSecond()
        assert(api.lastAfter!! <= expectedMax) { "after=${api.lastAfter} should be <= $expectedMax" }
    }
}
