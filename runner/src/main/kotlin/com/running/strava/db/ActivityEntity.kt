package com.running.strava.db

import jakarta.persistence.*
import java.time.ZonedDateTime

@Entity
@Table(name = "activities")
class ActivityEntity(
    @Id
    var id: Long = 0,
    var name: String = "",
    var distance: Float = 0f,
    @Column(name = "moving_time")
    var movingTime: Int = 0,
    @Column(name = "elapsed_time")
    var elapsedTime: Int = 0,
    @Column(name = "total_elevation_gain")
    var totalElevationGain: Float = 0f,
    var type: String = "",
    @Column(name = "sport_type")
    var sportType: String? = null,
    @Column(name = "start_date", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    var startDate: ZonedDateTime = ZonedDateTime.now(),
    var timezone: String = "",
    @Column(name = "average_speed")
    var averageSpeed: Float = 0f,
    @Column(name = "max_speed")
    var maxSpeed: Float = 0f,
    @Column(name = "average_heartrate")
    var averageHeartrate: Float? = null,
    @Column(name = "max_heartrate")
    var maxHeartrate: Float? = null,
    @Column(name = "average_cadence")
    var averageCadence: Float? = null,
    @Column(name = "average_watts")
    var averageWatts: Float? = null,
    @Column(name = "max_watts")
    var maxWatts: Float? = null,
    @Column(name = "weighted_average_watts")
    var weightedAverageWatts: Int? = null,
    var kilojoules: Float? = null,
    @Column(name = "device_watts")
    var deviceWatts: Boolean? = null,
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    var calories: Float? = null,
    @Column(name = "suffer_score")
    var sufferScore: Int? = null,
    @Column(name = "has_heartrate")
    var hasHeartrate: Boolean = false,
    @Column(name = "elev_high")
    var elevHigh: Float? = null,
    @Column(name = "elev_low")
    var elevLow: Float? = null,
    @Column(name = "gear_id")
    var gearId: String? = null,
    @Column(name = "start_latlng")
    var startLatlng: String? = null,
    @Column(name = "end_latlng")
    var endLatlng: String? = null,
    @Column(name = "is_trainer")
    var isTrainer: Boolean = false,
    @Column(name = "is_commute")
    var isCommute: Boolean = false,
    @Column(name = "is_manual")
    var isManual: Boolean = false,
    @Column(name = "is_flagged")
    var isFlagged: Boolean = false,
    @Column(name = "workout_type")
    var workoutType: Int? = null,
)

@Entity
@Table(name = "activity_streams")
class ActivityStreamEntity(
    @Id
    @Column(name = "activity_id")
    var activityId: Long = 0,
    @Column(columnDefinition = "TEXT")
    var time: String? = null,
    @Column(columnDefinition = "TEXT")
    var distance: String? = null,
    @Column(columnDefinition = "TEXT")
    var latlng: String? = null,
    @Column(columnDefinition = "TEXT")
    var altitude: String? = null,
    @Column(columnDefinition = "TEXT")
    var heartrate: String? = null,
    @Column(columnDefinition = "TEXT")
    var cadence: String? = null,
    @Column(name = "velocity_smooth", columnDefinition = "TEXT")
    var velocitySmooth: String? = null,
    @Column(name = "grade_smooth", columnDefinition = "TEXT")
    var gradeSmooth: String? = null,
    @Column(columnDefinition = "TEXT")
    var temp: String? = null,
)

@Entity
@Table(name = "sync_status")
class SyncStatusEntity(
    @Id
    var id: Long = 1,
    @Column(name = "last_sync_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    var lastSyncAt: ZonedDateTime? = null,
    @Column(name = "last_activity_id")
    var lastActivityId: Long? = null,
    @Column(name = "activities_count")
    var activitiesCount: Int = 0,
    @Column(name = "is_syncing")
    var isSyncing: Boolean = false,
)
