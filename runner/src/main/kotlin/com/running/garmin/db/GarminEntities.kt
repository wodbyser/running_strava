package com.running.garmin.db

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate

/** Lineage of every stored Garmin value (docs/garmin-data-provenance.md section 6). */
@Embeddable
class GarminProvenance(
    @Column(name = "source_endpoint", length = 200)
    var sourceEndpoint: String = "",
    @Column(name = "source_timestamp")
    var sourceTimestamp: Instant? = null,
    @Column(name = "synced_at")
    var syncedAt: Instant = Instant.EPOCH,
    @Column(name = "original_units", length = 300)
    var originalUnits: String = "",
    @Column(name = "raw_payload_id")
    var rawPayloadId: Long? = null,
)

@Entity
@Table(name = "garmin_daily_hrv")
class GarminHrvEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "weekly_avg") var weeklyAvg: Int? = null,
    @Column(name = "last_night_avg") var lastNightAvg: Int? = null,
    @Column(name = "last_night_5min_high") var lastNight5MinHigh: Int? = null,
    @Column(name = "baseline_low_upper") var baselineLowUpper: Int? = null,
    @Column(name = "baseline_balanced_low") var baselineBalancedLow: Int? = null,
    @Column(name = "baseline_balanced_upper") var baselineBalancedUpper: Int? = null,
    @Column(name = "baseline_marker_value") var baselineMarkerValue: Double? = null,
    var status: String? = null,
    @Column(name = "feedback_phrase") var feedbackPhrase: String? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_daily_sleep")
class GarminSleepEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "sleep_time_seconds") var sleepTimeSeconds: Int? = null,
    @Column(name = "deep_sleep_seconds") var deepSleepSeconds: Int? = null,
    @Column(name = "light_sleep_seconds") var lightSleepSeconds: Int? = null,
    @Column(name = "rem_sleep_seconds") var remSleepSeconds: Int? = null,
    @Column(name = "awake_sleep_seconds") var awakeSleepSeconds: Int? = null,
    @Column(name = "unmeasurable_sleep_seconds") var unmeasurableSleepSeconds: Int? = null,
    @Column(name = "nap_time_seconds") var napTimeSeconds: Int? = null,
    @Column(name = "sleep_start_gmt_ms") var sleepStartGmtMs: Long? = null,
    @Column(name = "sleep_end_gmt_ms") var sleepEndGmtMs: Long? = null,
    @Column(name = "avg_respiration") var avgRespiration: Double? = null,
    @Column(name = "avg_sleep_stress") var avgSleepStress: Double? = null,
    @Column(name = "avg_heart_rate") var avgHeartRate: Double? = null,
    @Column(name = "overall_score") var overallScore: Int? = null,
    @Column(name = "overall_qualifier") var overallQualifier: String? = null,
    @Column(name = "garmin_sleep_need_baseline") var garminSleepNeedBaseline: Int? = null,
    @Column(name = "garmin_sleep_need_actual") var garminSleepNeedActual: Int? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_daily_resting_hr")
class GarminRestingHrEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "resting_hr") var restingHr: Int? = null,
    @Column(name = "min_hr") var minHr: Int? = null,
    @Column(name = "max_hr") var maxHr: Int? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_daily_body_battery")
class GarminBodyBatteryEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "min_value") var minValue: Int? = null,
    @Column(name = "max_value") var maxValue: Int? = null,
    var charged: Int? = null,
    var drained: Int? = null,
    @Column(name = "latest_value") var latestValue: Int? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_body_battery_sample")
class GarminBodyBatterySampleEntity(
    @Id @Column(name = "timestamp_gmt_ms") var timestampGmtMs: Long = 0,
    @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    var level: Int = 0,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_daily_stress")
class GarminStressEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "avg_level") var avgLevel: Int? = null,
    @Column(name = "max_level") var maxLevel: Int? = null,
    @Column(name = "rest_duration_ms") var restDurationMs: Long? = null,
    @Column(name = "low_duration_ms") var lowDurationMs: Long? = null,
    @Column(name = "medium_duration_ms") var mediumDurationMs: Long? = null,
    @Column(name = "high_duration_ms") var highDurationMs: Long? = null,
    @Column(name = "activity_duration_ms") var activityDurationMs: Long? = null,
    @Column(name = "uncategorized_duration_ms") var uncategorizedDurationMs: Long? = null,
    @Column(name = "total_duration_ms") var totalDurationMs: Long? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_daily_steps")
class GarminStepsEntity(
    @Id @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    var steps: Int? = null,
    @Column(name = "distance_m") var distanceMeters: Double? = null,
    @Column(name = "step_goal") var stepGoal: Int? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_activity_ref")
class GarminActivityEntity(
    @Id @Column(name = "activity_id") var activityId: Long = 0,
    @Column(name = "start_time_gmt") var startTimeGmt: Instant = Instant.EPOCH,
    @Column(name = "calendar_date") var calendarDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "type_key") var typeKey: String? = null,
    @Column(name = "parent_type_id") var parentTypeId: Int? = null,
    @Column(name = "distance_m") var distanceMeters: Double? = null,
    @Column(name = "duration_s") var durationSeconds: Double? = null,
    @Column(name = "elapsed_s") var elapsedSeconds: Double? = null,
    @Column(name = "moving_s") var movingSeconds: Double? = null,
    @Column(name = "avg_hr") var averageHr: Double? = null,
    @Column(name = "max_hr") var maxHr: Double? = null,
    @Column(name = "aerobic_te") var aerobicTrainingEffect: Double? = null,
    @Column(name = "anaerobic_te") var anaerobicTrainingEffect: Double? = null,
    @Column(name = "training_load") var activityTrainingLoad: Double? = null,
    @Column(name = "training_effect_label") var trainingEffectLabel: String? = null,
    var manufacturer: String? = null,
    @Column(name = "is_manual") var isManual: Boolean = false,
    @Column(name = "start_lat") var startLatitude: Double? = null,
    @Column(name = "start_lon") var startLongitude: Double? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(name = "garmin_hr_zones")
class GarminHrZonesEntity(
    @Id var sport: String = "",
    @Column(name = "training_method") var trainingMethod: String? = null,
    @Column(name = "resting_hr") var restingHr: Int? = null,
    @Column(name = "lactate_threshold_hr") var lactateThresholdHr: Int? = null,
    @Column(name = "zone1_floor") var zone1Floor: Int? = null,
    @Column(name = "zone2_floor") var zone2Floor: Int? = null,
    @Column(name = "zone3_floor") var zone3Floor: Int? = null,
    @Column(name = "zone4_floor") var zone4Floor: Int? = null,
    @Column(name = "zone5_floor") var zone5Floor: Int? = null,
    @Column(name = "max_hr") var maxHr: Int? = null,
    @Embedded var provenance: GarminProvenance = GarminProvenance(),
)

@Entity
@Table(
    name = "garmin_raw_payload",
    uniqueConstraints = [UniqueConstraint(name = "uq_garmin_raw_endpoint_key", columnNames = ["endpoint", "record_key"])],
)
class GarminRawPayloadEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(length = 200) var endpoint: String = "",
    @Column(name = "record_key", length = 100) var recordKey: String = "",
    @Column(columnDefinition = "TEXT") var payload: String = "",
    @Column(name = "synced_at") var syncedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "garmin_sync_status")
class GarminSyncStatusEntity(
    @Id var id: Long = 1,
    @Column(name = "last_run_kind") var lastRunKind: String? = null,
    @Column(name = "last_run_started_at") var lastRunStartedAt: Instant? = null,
    @Column(name = "last_run_finished_at") var lastRunFinishedAt: Instant? = null,
    @Column(name = "last_success_at") var lastSuccessAt: Instant? = null,
    @Column(name = "last_success_date") var lastSuccessDate: LocalDate? = null,
    @Column(name = "last_error_category") var lastErrorCategory: String? = null,
    @Column(name = "last_error_detail", length = 500) var lastErrorDetail: String? = null,
    @Column(name = "records_per_endpoint", columnDefinition = "TEXT") var recordsPerEndpoint: String? = null,
    @Column(name = "null_fields_per_endpoint", columnDefinition = "TEXT") var nullFieldsPerEndpoint: String? = null,
    @Column(name = "failed_calls") var failedCalls: Int = 0,
)
