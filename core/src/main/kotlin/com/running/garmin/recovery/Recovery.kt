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

enum class ReadinessBasis { OWN_NORM, SLEEP_TARGET }

data class ReadinessDeviation(val kind: ReadinessKind, val deviation: Double, val band: DeviationBand, val basis: ReadinessBasis = ReadinessBasis.OWN_NORM)

/** A count summary, only when at least 3 of 4 components have data. No number that combines components. */
data class ReadinessSummary(
    val available: Boolean,
    val componentsWithData: Int,
    val within: Int,
    val deviating: List<ReadinessDeviation>,
)

/**
 * Sleep counts once but is judged twice: against the own norm and against the owner's target (shortfall per night).
 * The larger deviation decides. `sleepTarget` is kept so the page can show both.
 */
data class Readiness(val components: List<ReadinessComponent>, val summary: ReadinessSummary, val sleepTarget: SleepTargetCheck? = null)

object ReadinessAnalysis {
    const val SUMMARY_MIN_COMPONENTS = 3

    private fun component(kind: ReadinessKind, m: MetricAnalysis?): ReadinessComponent {
        val p = m?.primary
        val has = p != null && p.status == BaselineStatus.OK && p.deviation != null
        return ReadinessComponent(kind, has, m?.value, p?.median, p?.deviation, p?.band, p?.n ?: 0)
    }

    fun fromComponents(components: List<ReadinessComponent>, sleepTarget: SleepTargetCheck?): Readiness {
        val effective = components.mapNotNull { c ->
            val own = if (c.hasData) ReadinessDeviation(c.kind, c.deviation!!, c.band!!, ReadinessBasis.OWN_NORM) else null
            val target = if (c.kind == ReadinessKind.SLEEP && sleepTarget != null) ReadinessDeviation(c.kind, sleepTarget.deviation, sleepTarget.band, ReadinessBasis.SLEEP_TARGET) else null
            listOfNotNull(own, target).maxByOrNull { it.band.ordinal }
        }
        val available = effective.size >= SUMMARY_MIN_COMPONENTS
        val deviating = effective.filter { it.band != DeviationBand.WITHIN }
        return Readiness(components, ReadinessSummary(available, effective.size, effective.size - deviating.size, if (available) deviating else emptyList()), sleepTarget)
    }

    fun evaluate(hrv: HrvResult, restingHr: RestingHrResult, sleep: SleepQuality, battery: BodyBatteryResult, sleepTarget: SleepTargetCheck?): Readiness =
        fromComponents(
            listOf(
                component(ReadinessKind.HRV, hrv.metric),
                component(ReadinessKind.RESTING_HR, restingHr.metric),
                component(ReadinessKind.SLEEP, sleep.duration),
                component(ReadinessKind.BODY_BATTERY, battery.wakeLevelMetric),
            ),
            sleepTarget,
        )
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
        val debt = SleepAnalysis.debt(inputs.sleep, date, sleepTargetMinutes)
        val hrvByDate = inputs.hrv.associateBy { it.calendarDate }
        val rhrByDate = inputs.restingHr.associateBy { it.calendarDate }
        val sleepByDate = inputs.sleep.associateBy { it.calendarDate }
        fun span(n: Int) = (n - 1 downTo 0).map { date.minusDays(it.toLong()) }
        return RecoveryReport(
            RecoveryAlgorithm.VERSION, date, date == today, hrv, rhr, sleep,
            debt, battery,
            StressAnalysis.analyze(inputs.stress, date, today),
            ReadinessAnalysis.evaluate(hrv, rhr, sleep, battery, debt.target),
            span(CHART_DAYS).map { DayValue(it, hrvByDate[it]?.lastNightAvg?.toDouble()) },
            span(CHART_DAYS).map { DayValue(it, rhrByDate[it]?.restingHr?.toDouble()) },
            span(RECENT_DAYS).map { RecentNight(it, hrvByDate[it]?.lastNightAvg, rhrByDate[it]?.restingHr, sleepByDate[it]?.sleepTimeSeconds?.let { s -> s / 60 }) },
        )
    }
}
