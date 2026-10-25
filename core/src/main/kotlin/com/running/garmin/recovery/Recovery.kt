package com.running.garmin.recovery

import com.running.garmin.domain.GarminBodyBatteryDay
import com.running.garmin.domain.GarminBodyBatterySample
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminRestingHrDay
import com.running.garmin.domain.GarminSleepDay
import com.running.garmin.domain.GarminStressDay
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------------ Readiness (#4): components, never a score

enum class ReadinessKind { HRV, RESTING_HR, SLEEP, BODY_BATTERY }

data class ReadinessComponent(
    val kind: ReadinessKind,
    val hasData: Boolean,
    val value: Double?,
    val median: Double?,
    val deviation: Double?,
    val band: DeviationBand?,
    val n: Int,
)

data class ReadinessDeviation(val kind: ReadinessKind, val deviation: Double, val band: DeviationBand)

/** A count summary, only when at least 3 of 4 components have data. No number that combines components. */
data class ReadinessSummary(
    val available: Boolean,
    val componentsWithData: Int,
    val within: Int,
    val deviating: List<ReadinessDeviation>,
)

data class Readiness(val components: List<ReadinessComponent>, val summary: ReadinessSummary)

object ReadinessAnalysis {
    const val SUMMARY_MIN_COMPONENTS = 3

    private fun component(kind: ReadinessKind, m: MetricAnalysis?): ReadinessComponent {
        val p = m?.primary
        val has = p != null && p.status == BaselineStatus.OK && p.deviation != null
        return ReadinessComponent(kind, has, m?.value, p?.median, p?.deviation, p?.band, p?.n ?: 0)
    }

    fun evaluate(hrv: HrvResult, restingHr: RestingHrResult, sleep: SleepQuality, battery: BodyBatteryResult): Readiness {
        val components = listOf(
            component(ReadinessKind.HRV, hrv.metric),
            component(ReadinessKind.RESTING_HR, restingHr.metric),
            component(ReadinessKind.SLEEP, sleep.duration),
            component(ReadinessKind.BODY_BATTERY, battery.wakeLevelMetric),
        )
        val withData = components.filter { it.hasData }
        val available = withData.size >= SUMMARY_MIN_COMPONENTS
        val deviating = withData.filter { it.band != DeviationBand.WITHIN }.map { ReadinessDeviation(it.kind, it.deviation!!, it.band!!) }
        return Readiness(components, ReadinessSummary(available, withData.size, withData.size - deviating.size, if (available) deviating else emptyList()))
    }
}

// ------------------------------------------------------------------ Aggregate

data class RecoveryInputs(
    val hrv: List<GarminHrvDay>,
    val sleep: List<GarminSleepDay>,
    val restingHr: List<GarminRestingHrDay>,
    val bodyBattery: List<GarminBodyBatteryDay>,
    val bodyBatterySamples: List<GarminBodyBatterySample>,
    val stress: List<GarminStressDay>,
    val manualRestingHr: Int?,
)

data class DayValue(val date: LocalDate, val value: Double?)

data class RecoveryReport(
    val algorithmVersion: String,
    val date: LocalDate,
    val isToday: Boolean,
    val hrv: HrvResult,
    val restingHr: RestingHrResult,
    val sleep: SleepQuality,
    val sleepDebt: SleepDebt,
    val bodyBattery: BodyBatteryResult,
    val stress: StressResult,
    val readiness: Readiness,
    val hrvSeries: List<DayValue>,
    val restingHrSeries: List<DayValue>,
    val recentNights: List<RecentNight>,
)

data class RecentNight(val date: LocalDate, val hrv: Int?, val restingHr: Int?, val sleepMinutes: Int?)

object RecoveryAnalyzer {
    const val CHART_DAYS = 60
    const val RECENT_DAYS = 7
    /** Rows needed before D: the longest window. */
    const val HISTORY_DAYS = 90

    fun analyze(inputs: RecoveryInputs, date: LocalDate, today: LocalDate, sleepTargetMinutes: Int, zone: ZoneId): RecoveryReport {
        val hrv = HrvAnalysis.analyze(inputs.hrv, date)
        val rhr = RestingHrAnalysis.analyze(inputs.restingHr, inputs.manualRestingHr, date)
        val sleep = SleepAnalysis.quality(inputs.sleep, date, zone)
        val battery = BodyBatteryAnalysis.analyze(inputs.bodyBattery, inputs.bodyBatterySamples, inputs.sleep, date, today)
        val hrvByDate = inputs.hrv.associateBy { it.calendarDate }
        val rhrByDate = inputs.restingHr.associateBy { it.calendarDate }
        val sleepByDate = inputs.sleep.associateBy { it.calendarDate }
        fun span(n: Int) = (n - 1 downTo 0).map { date.minusDays(it.toLong()) }
        return RecoveryReport(
            RecoveryAlgorithm.VERSION, date, date == today, hrv, rhr, sleep,
            SleepAnalysis.debt(inputs.sleep, date, sleepTargetMinutes), battery,
            StressAnalysis.analyze(inputs.stress, date, today),
            ReadinessAnalysis.evaluate(hrv, rhr, sleep, battery),
            span(CHART_DAYS).map { DayValue(it, hrvByDate[it]?.lastNightAvg?.toDouble()) },
            span(CHART_DAYS).map { DayValue(it, rhrByDate[it]?.restingHr?.toDouble()) },
            span(RECENT_DAYS).map { RecentNight(it, hrvByDate[it]?.lastNightAvg, rhrByDate[it]?.restingHr, sleepByDate[it]?.sleepTimeSeconds?.let { s -> s / 60 }) },
        )
    }
}
