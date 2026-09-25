package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.SyncStatus
import com.running.strava.spi.ActivityRepository
import java.time.ZonedDateTime

class FakeActivityRepository(
    private val activities: List<Activity>,
    private val laps: Map<Long, List<Lap>> = emptyMap(),
    private val streams: Map<Long, ActivityStream> = emptyMap(),
) : ActivityRepository {
    override fun save(activity: Activity) = error("read-only")
    override fun saveAll(activities: List<Activity>) = error("read-only")
    override fun saveStreams(activityId: Long, streams: ActivityStream) = error("read-only")
    override fun saveLaps(activityId: Long, laps: List<Lap>) = error("read-only")
    override fun findById(id: Long) = activities.firstOrNull { it.id == id }
    override fun findStreams(activityId: Long) = streams[activityId]
    override fun findLaps(activityId: Long) = laps[activityId].orEmpty()
    override fun findLapsForActivities(activityIds: Collection<Long>) = activityIds.associateWith { laps[it].orEmpty() }
    override fun findAll(after: ZonedDateTime?) = activities.filter { after == null || it.startDate.isAfter(after) }
    override fun findAllIds() = activities.map { it.id }.toSet()
    override fun findLatestActivityTimestamp() = activities.maxOfOrNull { it.startDate }
    override fun getSyncStatus() = SyncStatus(null, null, activities.size, false)
    override fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime) = Unit
}

object Fixtures {
    fun run(
        id: Long,
        start: ZonedDateTime,
        distance: Double,
        time: Int,
        hr: Double? = 145.0,
        type: String = "Run",
        workoutType: Int? = null,
        trainer: Boolean = false,
        maxHr: Double? = hr?.plus(20),
    ) = Activity(
        id = id, name = "run $id", distance = distance.toFloat(), movingTime = time, elapsedTime = time,
        totalElevationGain = 0f, type = type, sportType = type, startDate = start, timezone = "(GMT+01:00) Europe/Brussels",
        averageSpeed = (distance / time).toFloat(), maxSpeed = 0f, averageHeartrate = hr?.toFloat(),
        maxHeartrate = maxHr?.toFloat(), averageCadence = 84f, averageWatts = null, maxWatts = null,
        weightedAverageWatts = null, kilojoules = null, deviceWatts = null, description = null, calories = null,
        sufferScore = null, hasHeartrate = hr != null, elevHigh = null, elevLow = null, gearId = null,
        startLatlng = null, endLatlng = null, isTrainer = trainer, isCommute = false, isManual = false,
        isFlagged = false, workoutType = workoutType, originalStartDate = null, laps = null, splits = null,
        bestEfforts = null,
    )

    /** 1 Hz stream from (seconds, m/s) blocks. */
    fun stream(vararg blocks: Pair<Int, Double>): ActivityStream {
        val t = mutableListOf(0)
        val d = mutableListOf(0f)
        var dist = 0.0
        var sec = 0
        blocks.forEach { (dur, v) -> repeat(dur) { sec++; dist += v; t += sec; d += dist.toFloat() } }
        return ActivityStream(t, d, null, null, null, null, null, null, null)
    }
}
