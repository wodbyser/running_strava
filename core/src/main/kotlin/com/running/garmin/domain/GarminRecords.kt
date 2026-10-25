package com.running.garmin.domain

import java.time.Instant
import java.time.LocalDate

/** Where a stored Garmin value came from. `syncedAt` is added by the repository when the row is written. */
data class GarminSource(
    val endpoint: String,
    val sourceTimestamp: Instant?,
    val originalUnits: String,
    val rawJson: String? = null,
)

/** A Garmin value row keyed by calendar date. Missing fields are `null`, never defaulted. */
sealed interface GarminDailyRecord {
    val calendarDate: LocalDate
    val source: GarminSource
    fun nullFieldCount(): Int
}

private fun countNulls(vararg values: Any?): Int = values.count { it == null }

const val EP_DAILY_SUMMARIES = "/usersummary-service/v2/dailysummaries"
const val EP_SLEEP_SUMMARIES = "/sleep-service/sleep/dailySleepSummariesByDate"
const val EP_HRV_SUMMARIES = "/hrv-service/hrv/daily/{start}/{end}"
const val EP_ACTIVITY_SEARCH = "/activitylist-service/activities/search/activities"
const val EP_HEART_RATE_ZONES = "/biometric-service/heartRateZones/v2"

data class GarminHrvDay(
    override val calendarDate: LocalDate,
    val weeklyAvg: Int?,
    val lastNightAvg: Int?,
    val lastNight5MinHigh: Int?,
    val baselineLowUpper: Int?,
    val baselineBalancedLow: Int?,
    val baselineBalancedUpper: Int?,
    val baselineMarkerValue: Double?,
    val status: String?,
    val feedbackPhrase: String?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(
        weeklyAvg, lastNightAvg, lastNight5MinHigh, baselineLowUpper, baselineBalancedLow,
        baselineBalancedUpper, baselineMarkerValue, status, feedbackPhrase,
    )
}

data class GarminSleepDay(
    override val calendarDate: LocalDate,
    val sleepTimeSeconds: Int?,
    val deepSleepSeconds: Int?,
    val lightSleepSeconds: Int?,
    val remSleepSeconds: Int?,
    val awakeSleepSeconds: Int?,
    val unmeasurableSleepSeconds: Int?,
    val napTimeSeconds: Int?,
    val sleepStartGmtMs: Long?,
    val sleepEndGmtMs: Long?,
    val avgRespiration: Double?,
    val avgSleepStress: Double?,
    val avgHeartRate: Double?,
    val overallScore: Int?,
    val overallQualifier: String?,
    /** Garmin's own sleep need estimate. Unit UNVERIFIED. */
    val garminSleepNeedBaseline: Int?,
    val garminSleepNeedActual: Int?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(
        sleepTimeSeconds, deepSleepSeconds, lightSleepSeconds, remSleepSeconds, awakeSleepSeconds,
        unmeasurableSleepSeconds, napTimeSeconds, sleepStartGmtMs, sleepEndGmtMs, avgRespiration,
        avgSleepStress, avgHeartRate, overallScore, overallQualifier, garminSleepNeedBaseline, garminSleepNeedActual,
    )
}

data class GarminRestingHrDay(
    override val calendarDate: LocalDate,
    val restingHr: Int?,
    val minHr: Int?,
    val maxHr: Int?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(restingHr, minHr, maxHr)
}

data class GarminBodyBatteryDay(
    override val calendarDate: LocalDate,
    val minValue: Int?,
    val maxValue: Int?,
    val charged: Int?,
    val drained: Int?,
    val latestValue: Int?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(minValue, maxValue, charged, drained, latestValue)
}

data class GarminStressDay(
    override val calendarDate: LocalDate,
    val avgLevel: Int?,
    val maxLevel: Int?,
    val restDurationMs: Long?,
    val lowDurationMs: Long?,
    val mediumDurationMs: Long?,
    val highDurationMs: Long?,
    val activityDurationMs: Long?,
    val uncategorizedDurationMs: Long?,
    val totalDurationMs: Long?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(
        avgLevel, maxLevel, restDurationMs, lowDurationMs, mediumDurationMs, highDurationMs,
        activityDurationMs, uncategorizedDurationMs, totalDurationMs,
    )
}

data class GarminStepsDay(
    override val calendarDate: LocalDate,
    val steps: Int?,
    val distanceMeters: Double?,
    val stepGoal: Int?,
    override val source: GarminSource,
) : GarminDailyRecord {
    override fun nullFieldCount() = countNulls(steps, distanceMeters, stepGoal)
}

/** Everything one `dailysummaries` day gives us. A part is `null` when Garmin sent no such block or only nulls. */
data class GarminDailySummary(
    val calendarDate: LocalDate,
    val restingHr: GarminRestingHrDay?,
    val bodyBattery: GarminBodyBatteryDay?,
    val stress: GarminStressDay?,
    val steps: GarminStepsDay?,
)

data class GarminActivityRef(
    val activityId: Long,
    val startTimeGmt: Instant,
    val typeKey: String?,
    val parentTypeId: Int?,
    val distanceMeters: Double?,
    val durationSeconds: Double?,
    val elapsedSeconds: Double?,
    val movingSeconds: Double?,
    val averageHr: Double?,
    val maxHr: Double?,
    val aerobicTrainingEffect: Double?,
    val anaerobicTrainingEffect: Double?,
    /** Garmin estimate. Formula and unit UNVERIFIED. */
    val activityTrainingLoad: Double?,
    val trainingEffectLabel: String?,
    val manufacturer: String?,
    val isManual: Boolean,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val source: GarminSource,
) {
    fun nullFieldCount() = countNulls(
        typeKey, parentTypeId, distanceMeters, durationSeconds, elapsedSeconds, movingSeconds, averageHr, maxHr,
        aerobicTrainingEffect, anaerobicTrainingEffect, activityTrainingLoad, trainingEffectLabel, manufacturer,
        startLatitude, startLongitude,
    )
}

/** Garmin's configured heart rate zones for one sport. Reference data, labelled as Garmin's setting. */
data class GarminHeartRateZones(
    val sport: String,
    val trainingMethod: String?,
    val restingHr: Int?,
    val lactateThresholdHr: Int?,
    val zone1Floor: Int?,
    val zone2Floor: Int?,
    val zone3Floor: Int?,
    val zone4Floor: Int?,
    val zone5Floor: Int?,
    val maxHr: Int?,
    val source: GarminSource,
) {
    fun nullFieldCount() = countNulls(
        trainingMethod, restingHr, lactateThresholdHr, zone1Floor, zone2Floor, zone3Floor, zone4Floor, zone5Floor, maxHr,
    )
}
