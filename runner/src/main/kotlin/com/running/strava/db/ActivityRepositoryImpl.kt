package com.running.strava.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.SyncStatus
import com.running.strava.spi.ActivityRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.ZonedDateTime

interface ActivityJpaRepository : JpaRepository<ActivityEntity, Long> {
    @Query("SELECT a.id FROM ActivityEntity a")
    fun findAllIds(): List<Long>
}
interface ActivityStreamJpaRepository : JpaRepository<ActivityStreamEntity, Long>
interface SyncStatusJpaRepository : JpaRepository<SyncStatusEntity, Long>

@Repository
class ActivityRepositoryImpl(
    private val activityJpaRepository: ActivityJpaRepository,
    private val streamJpaRepository: ActivityStreamJpaRepository,
    private val syncStatusJpaRepository: SyncStatusJpaRepository,
    private val mapper: ObjectMapper,
) : ActivityRepository {

    override fun save(activity: Activity) {
        activityJpaRepository.save(toEntity(activity))
    }

    override fun saveAll(activities: List<Activity>) {
        activityJpaRepository.saveAll(activities.map { toEntity(it) })
    }

    override fun saveStreams(activityId: Long, streams: ActivityStream) {
        streamJpaRepository.save(ActivityStreamEntity(
            activityId = activityId,
            time = toJson(streams.time),
            distance = toJson(streams.distance),
            latlng = toJson(streams.latlng),
            altitude = toJson(streams.altitude),
            heartrate = toJson(streams.heartrate),
            cadence = toJson(streams.cadence),
            velocitySmooth = toJson(streams.velocitySmooth),
            gradeSmooth = toJson(streams.gradeSmooth),
            temp = toJson(streams.temp),
        ))
    }

    override fun findAllIds(): Set<Long> {
        return activityJpaRepository.findAllIds().toSet()
    }

    override fun findStreams(activityId: Long): ActivityStream? {
        return streamJpaRepository.findById(activityId)
            .map { toDomainStream(it) }
            .orElse(null)
    }

    override fun findById(id: Long): Activity? {
        return activityJpaRepository.findById(id).map { toDomain(it) }.orElse(null)
    }

    override fun findAll(after: ZonedDateTime?): List<Activity> {
        val entities = if (after != null) {
            activityJpaRepository.findAll().filter { it.startDate.isAfter(after) }
        } else {
            activityJpaRepository.findAll()
        }
        return entities.map { toDomain(it) }
    }

    override fun findLatestActivityTimestamp(): ZonedDateTime? {
        return activityJpaRepository.findAll()
            .maxByOrNull { it.startDate }
            ?.startDate
    }

    override fun getSyncStatus(): SyncStatus {
        val entity = syncStatusJpaRepository.findById(1).orElse(SyncStatusEntity())
        return SyncStatus(
            lastSyncAt = entity.lastSyncAt,
            lastActivityId = entity.lastActivityId,
            activitiesCount = entity.activitiesCount,
            isSyncing = entity.isSyncing,
        )
    }

    override fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime) {
        val entity = syncStatusJpaRepository.findById(1).orElse(SyncStatusEntity())
        entity.lastSyncAt = lastSyncAt
        entity.lastActivityId = lastActivityId
        entity.activitiesCount = activityJpaRepository.count().toInt()
        syncStatusJpaRepository.save(entity)
    }

    private fun toEntity(activity: Activity) = ActivityEntity(
        id = activity.id,
        name = activity.name,
        distance = activity.distance,
        movingTime = activity.movingTime,
        elapsedTime = activity.elapsedTime,
        totalElevationGain = activity.totalElevationGain,
        type = activity.type,
        sportType = activity.sportType,
        startDate = activity.startDate,
        timezone = activity.timezone,
        averageSpeed = activity.averageSpeed,
        maxSpeed = activity.maxSpeed,
        averageHeartrate = activity.averageHeartrate,
        maxHeartrate = activity.maxHeartrate,
        averageCadence = activity.averageCadence,
        averageWatts = activity.averageWatts,
        maxWatts = activity.maxWatts,
        weightedAverageWatts = activity.weightedAverageWatts,
        kilojoules = activity.kilojoules,
        deviceWatts = activity.deviceWatts,
        description = activity.description,
        calories = activity.calories,
        sufferScore = activity.sufferScore,
        hasHeartrate = activity.hasHeartrate,
        elevHigh = activity.elevHigh,
        elevLow = activity.elevLow,
        gearId = activity.gearId,
        startLatlng = toJson(activity.startLatlng),
        endLatlng = toJson(activity.endLatlng),
        isTrainer = activity.isTrainer,
        isCommute = activity.isCommute,
        isManual = activity.isManual,
        isFlagged = activity.isFlagged,
        workoutType = activity.workoutType,
    )

    private fun toDomain(entity: ActivityEntity) = Activity(
        id = entity.id,
        name = entity.name,
        distance = entity.distance,
        movingTime = entity.movingTime,
        elapsedTime = entity.elapsedTime,
        totalElevationGain = entity.totalElevationGain,
        type = entity.type,
        sportType = entity.sportType,
        startDate = entity.startDate,
        timezone = entity.timezone,
        averageSpeed = entity.averageSpeed,
        maxSpeed = entity.maxSpeed,
        averageHeartrate = entity.averageHeartrate,
        maxHeartrate = entity.maxHeartrate,
        averageCadence = entity.averageCadence,
        averageWatts = entity.averageWatts,
        maxWatts = entity.maxWatts,
        weightedAverageWatts = entity.weightedAverageWatts,
        kilojoules = entity.kilojoules,
        deviceWatts = entity.deviceWatts,
        description = entity.description,
        calories = entity.calories,
        sufferScore = entity.sufferScore,
        hasHeartrate = entity.hasHeartrate,
        elevHigh = entity.elevHigh,
        elevLow = entity.elevLow,
        gearId = entity.gearId,
        startLatlng = fromJson(entity.startLatlng),
        endLatlng = fromJson(entity.endLatlng),
        isTrainer = entity.isTrainer,
        isCommute = entity.isCommute,
        isManual = entity.isManual,
        isFlagged = entity.isFlagged,
        workoutType = entity.workoutType,
        originalStartDate = null,
        laps = null,
        splits = null,
        bestEfforts = null,
    )

    private fun toDomainStream(entity: ActivityStreamEntity) = ActivityStream(
        time = fromJson(entity.time),
        distance = fromJson(entity.distance),
        latlng = entity.latlng?.let { mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, List::class.java)) as? List<List<Double>> },
        altitude = fromJson(entity.altitude),
        heartrate = fromJson(entity.heartrate),
        cadence = fromJson(entity.cadence),
        velocitySmooth = fromJson(entity.velocitySmooth),
        gradeSmooth = fromJson(entity.gradeSmooth),
        temp = fromJson(entity.temp),
    )

    private fun toJson(value: Any?): String? {
        if (value == null) return null
        return mapper.writeValueAsString(value)
    }

    private inline fun <reified T> fromJson(json: String?): List<T>? {
        if (json == null) return null
        return mapper.readValue(json, mapper.typeFactory.constructCollectionType(List::class.java, T::class.java))
    }
}
