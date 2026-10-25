package com.running.garmin.recovery

import com.running.garmin.domain.GarminBodyBatteryDay
import com.running.garmin.domain.GarminBodyBatterySample
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminRestingHrDay
import com.running.garmin.domain.GarminSleepDay
import com.running.garmin.domain.GarminStressDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

// ------------------------------------------------------------------ HRV (#1, #2)

/** `lastNightAvg` series only. Garmin's own balanced band and status are passed through and never merged with the app baseline. */
data class HrvResult(
    val date: LocalDate,
    val hasNight: Boolean,
    val lastNightAvg: Int?,
    val weeklyAvg: Int?,
    val garminBalancedLow: Int?,
    val garminBalancedUpper: Int?,
    val garminStatus: String?,
    val metric: MetricAnalysis,
)

object HrvAnalysis {
    fun analyze(days: List<GarminHrvDay>, date: LocalDate): HrvResult {
        val series = days.toSeries { it.lastNightAvg }
        val day = days.firstOrNull { it.calendarDate == date }
        return HrvResult(
            date, series.containsKey(date), day?.lastNightAvg, day?.weeklyAvg, day?.baselineBalancedLow, day?.baselineBalancedUpper,
            day?.status, MetricAnalyzer.analyze(series, date),
        )
    }
}

// ------------------------------------------------------------------ Resting heart rate (#3)

enum class RestingHrSource { GARMIN, MANUAL, NONE }

/** Garmin and manual values are never mixed in one series. The manual value is a single number without history, so it has no baseline. */
data class RestingHrResult(val date: LocalDate, val source: RestingHrSource, val value: Int?, val metric: MetricAnalysis?)

object RestingHrAnalysis {
    fun analyze(days: List<GarminRestingHrDay>, manualRestingHr: Int?, date: LocalDate): RestingHrResult {
        val series = days.toSeries { it.restingHr }
        return when {
            series.containsKey(date) -> RestingHrResult(date, RestingHrSource.GARMIN, series.getValue(date).toInt(), MetricAnalyzer.analyze(series, date))
            manualRestingHr != null -> RestingHrResult(date, RestingHrSource.MANUAL, manualRestingHr, null)
            else -> RestingHrResult(date, RestingHrSource.NONE, null, null)
        }
    }
}

// ------------------------------------------------------------------ Sleep quality (#6) and sleep debt (#7)

data class SleepNight(
    val date: LocalDate,
    val durationSeconds: Int?,
    val awakeSharePercent: Double?,
    val deepSharePercent: Double?,
    val lightSharePercent: Double?,
    val remSharePercent: Double?,
    val unmeasurableSharePercent: Double?,
    /** More than 10 % of the night unmeasurable: stage shares are not shown. */
    val stagesHidden: Boolean,
    val overallScore: Int?,
    val overallQualifier: String?,
)

data class SleepRegularity(
    val status: BaselineStatus,
    val nights: Int,
    val needed: Int,
    val startSdMinutes: Double?,
    val endSdMinutes: Double?,
)

data class SleepQuality(
    val date: LocalDate,
    val night: SleepNight?,
    val duration: MetricAnalysis,
    val awakeShare: MetricAnalysis,
    val deepShare: MetricAnalysis,
    val lightShare: MetricAnalysis,
    val remShare: MetricAnalysis,
    val regularity: SleepRegularity,
    val nightsWithDurationLast14: Int,
    val deepReferenceLowPercent: Int,
    val deepReferenceHighPercent: Int,
)

/**
 * Average shortfall per night against the owner's target over the nights with data. Bands use the same 1 and 2 steps as the
 * deviation bands, with one step = 20 minutes per night (PROPOSAL, UNVERIFIED). A long night never offsets a short one.
 * `deviation` is minus (average shortfall / 20), so it sits on the same spectrum as the other components.
 */
data class SleepTargetCheck(
    val nights: Int,
    val averageShortfallMinutes: Double,
    val deviation: Double,
    val band: DeviationBand,
    val targetMinutes: Int,
)

data class SleepDebt(
    val date: LocalDate,
    val status: BaselineStatus,
    val nightsWithData: Int,
    val nightsNeeded: Int,
    val targetSeconds: Int,
    val shortfallSeconds: Long?,
    val nightsBelowTarget: Int?,
    val netBalanceSeconds: Long?,
    /** Garmin's own value, unit UNVERIFIED, shown only as a reference. */
    val garminSleepNeedBaseline: Int?,
    val target: SleepTargetCheck? = null,
)

object SleepAnalysis {
    const val TARGET_BAND_MINUTES = 20.0

    fun targetCheck(shortfallSeconds: Long?, nights: Int, targetSeconds: Int): SleepTargetCheck? {
        if (shortfallSeconds == null || nights <= 0) return null
        val avg = shortfallSeconds / 60.0 / nights
        val z = if (avg == 0.0) 0.0 else -avg / TARGET_BAND_MINUTES
        return SleepTargetCheck(nights, avg, z, Baseline.band(z), targetSeconds / 60)
    }

    const val DEEP_REFERENCE_LOW = 15
    const val DEEP_REFERENCE_HIGH = 20
    const val UNMEASURABLE_LIMIT_PERCENT = 10.0
    const val REGULARITY_DAYS = 14
    const val REGULARITY_MIN_NIGHTS = 7
    const val DEBT_DAYS = 7
    const val DEBT_MIN_NIGHTS = 5

    private fun totalNight(d: GarminSleepDay): Double? {
        val sleep = d.sleepTimeSeconds ?: return null
        return (sleep + (d.awakeSleepSeconds ?: 0) + (d.unmeasurableSleepSeconds ?: 0)).toDouble()
    }

    private fun night(d: GarminSleepDay): SleepNight {
        val sleep = d.sleepTimeSeconds?.takeIf { it > 0 }
        val awake = if (sleep != null && d.awakeSleepSeconds != null) 100.0 * d.awakeSleepSeconds / (sleep + d.awakeSleepSeconds) else null
        val total = totalNight(d)?.takeIf { it > 0 }
        val unmeasurable = if (total != null && d.unmeasurableSleepSeconds != null) 100.0 * d.unmeasurableSleepSeconds / total else null
        val hidden = unmeasurable != null && unmeasurable > UNMEASURABLE_LIMIT_PERCENT
        fun share(v: Int?) = if (sleep != null && v != null && !hidden) 100.0 * v / sleep else null
        return SleepNight(
            d.calendarDate, d.sleepTimeSeconds, awake, share(d.deepSleepSeconds), share(d.lightSleepSeconds), share(d.remSleepSeconds),
            unmeasurable, hidden, d.overallScore, d.overallQualifier,
        )
    }

    /** Minutes after 18:00 local time, so a bedtime around midnight does not wrap. */
    fun minutesAfter18(epochMs: Long, zone: ZoneId): Double {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val m = t.hour * 60 + t.minute
        return (((m - 18 * 60) % 1440) + 1440) % 1440.toDouble()
    }

    /** Population standard deviation. */
    fun sd(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    fun quality(days: List<GarminSleepDay>, date: LocalDate, zone: ZoneId): SleepQuality {
        val nights = days.map { night(it) }
        val byDate = nights.associateBy { it.date }
        fun series(select: (SleepNight) -> Double?) = nights.mapNotNull { n -> select(n)?.let { n.date to it } }.toMap()
        fun metric(select: (SleepNight) -> Double?) = MetricAnalyzer.analyze(series(select), date)

        val window = (0 until REGULARITY_DAYS).map { date.minusDays(it.toLong()) }
        val timed = days.filter { it.calendarDate in window && it.sleepStartGmtMs != null && it.sleepEndGmtMs != null }
        val regularity = if (timed.size >= REGULARITY_MIN_NIGHTS) {
            SleepRegularity(
                BaselineStatus.OK, timed.size, REGULARITY_MIN_NIGHTS,
                sd(timed.map { minutesAfter18(it.sleepStartGmtMs!!, zone) }), sd(timed.map { minutesAfter18(it.sleepEndGmtMs!!, zone) }),
            )
        } else SleepRegularity(BaselineStatus.INSUFFICIENT, timed.size, REGULARITY_MIN_NIGHTS, null, null)

        return SleepQuality(
            date, byDate[date],
            metric { n -> n.durationSeconds?.let { it / 60.0 } },
            metric { it.awakeSharePercent }, metric { it.deepSharePercent }, metric { it.lightSharePercent }, metric { it.remSharePercent },
            regularity,
            days.count { it.calendarDate in window && it.sleepTimeSeconds != null },
            DEEP_REFERENCE_LOW, DEEP_REFERENCE_HIGH,
        )
    }

    /** Shortfall against the target over the last 7 nights. Longer nights do not offset shorter ones; the net balance is a second number. */
    fun debt(days: List<GarminSleepDay>, date: LocalDate, targetMinutes: Int): SleepDebt {
        val target = targetMinutes * 60
        val window = (0 until DEBT_DAYS).map { date.minusDays(it.toLong()) }.toSet()
        val nights = days.filter { it.calendarDate in window && it.sleepTimeSeconds != null }
        val need = days.firstOrNull { it.calendarDate == date }?.garminSleepNeedBaseline
        if (nights.size < DEBT_MIN_NIGHTS) {
            return SleepDebt(date, BaselineStatus.INSUFFICIENT, nights.size, DEBT_MIN_NIGHTS, target, null, null, null, need)
        }
        val seconds = nights.map { it.sleepTimeSeconds!! }
        val shortfall = seconds.sumOf { maxOf(0, target - it).toLong() }
        return SleepDebt(
            date, BaselineStatus.OK, nights.size, DEBT_MIN_NIGHTS, target,
            shortfall, seconds.count { it < target }, seconds.sumOf { (it - target).toLong() }, need,
            targetCheck(shortfall, nights.size, target),
        )
    }
}

// ------------------------------------------------------------------ Body Battery (#8)

data class BodyBatteryResult(
    val date: LocalDate,
    /** The day is today: the daily values are partial and excluded from every baseline. */
    val incomplete: Boolean,
    val minValue: Int?,
    val maxValue: Int?,
    val charged: Int?,
    val drained: Int?,
    val sleepStartLevel: Int?,
    val wakeLevel: Int?,
    val recharge: Int?,
    val wakeLevelMetric: MetricAnalysis,
    val rechargeMetric: MetricAnalysis,
    val chargedMetric: MetricAnalysis,
    val drainedMetric: MetricAnalysis,
)

object BodyBatteryAnalysis {
    const val SAMPLE_TOLERANCE_MS = 15L * 60 * 1000

    private class Levels(val start: Int?, val wake: Int?)

    private fun levels(sorted: List<GarminBodyBatterySample>, sleep: GarminSleepDay?): Levels {
        val startMs = sleep?.sleepStartGmtMs
        val endMs = sleep?.sleepEndGmtMs
        var start: Int? = null
        var wake: Int? = null
        if (startMs != null) {
            val idx = upperBound(sorted, startMs) - 1
            if (idx >= 0 && startMs - sorted[idx].timestampGmtMs <= SAMPLE_TOLERANCE_MS) start = sorted[idx].level
        }
        if (endMs != null) {
            val idx = lowerBound(sorted, endMs)
            if (idx < sorted.size && sorted[idx].timestampGmtMs - endMs <= SAMPLE_TOLERANCE_MS) wake = sorted[idx].level
        }
        return Levels(start, wake)
    }

    private fun lowerBound(l: List<GarminBodyBatterySample>, ts: Long): Int {
        var lo = 0; var hi = l.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (l[m].timestampGmtMs < ts) lo = m + 1 else hi = m }
        return lo
    }

    private fun upperBound(l: List<GarminBodyBatterySample>, ts: Long): Int {
        var lo = 0; var hi = l.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (l[m].timestampGmtMs <= ts) lo = m + 1 else hi = m }
        return lo
    }

    /** Wake level per wake-up date (first sample within 15 minutes after wake-up). Dates without such a sample are absent. */
    fun wakeLevels(samples: List<GarminBodyBatterySample>, sleepDays: List<GarminSleepDay>): Map<LocalDate, Int> {
        val sorted = samples.sortedBy { it.timestampGmtMs }
        return sleepDays.mapNotNull { d -> levels(sorted, d).wake?.let { d.calendarDate to it } }.toMap()
    }

    fun analyze(
        days: List<GarminBodyBatteryDay>,
        samples: List<GarminBodyBatterySample>,
        sleepDays: List<GarminSleepDay>,
        date: LocalDate,
        today: LocalDate,
    ): BodyBatteryResult {
        val sorted = samples.sortedBy { it.timestampGmtMs }
        val incomplete = date == today
        val perDay = sleepDays.associate { it.calendarDate to levels(sorted, it) }
        val wakeSeries = perDay.mapNotNull { (d, l) -> l.wake?.let { d to it.toDouble() } }.toMap()
        val rechargeSeries = perDay.mapNotNull { (d, l) -> if (l.wake != null && l.start != null) d to (l.wake - l.start).toDouble() else null }.toMap()
        val usable = days.filter { it.calendarDate != today }
        val day = days.firstOrNull { it.calendarDate == date }
        val chargedSeries = usable.toSeries { it.charged }
        val drainedSeries = usable.toSeries { it.drained }
        val levels = perDay[date]
        val recharge = if (levels?.wake != null && levels.start != null) levels.wake - levels.start else null
        return BodyBatteryResult(
            date, incomplete, day?.minValue, day?.maxValue, day?.charged, day?.drained, levels?.start, levels?.wake, recharge,
            MetricAnalyzer.analyze(wakeSeries, date),
            MetricAnalyzer.analyze(rechargeSeries, date),
            MetricAnalyzer.analyze(chargedSeries, date, day?.charged?.toDouble(), incomplete),
            MetricAnalyzer.analyze(drainedSeries, date, day?.drained?.toDouble(), incomplete),
        )
    }
}

// ------------------------------------------------------------------ Stress (#9)

data class StressResult(
    val date: LocalDate,
    val incomplete: Boolean,
    val avgLevel: Int?,
    /** (medium + high) / (rest + low + medium + high), in percent. Null when the measured time is too short. */
    val highSharePercent: Double?,
    val measuredHours: Double?,
    val tooShort: Boolean,
    val avgLevelMetric: MetricAnalysis,
    val highShareMetric: MetricAnalysis,
)

object StressAnalysis {
    const val MIN_MEASURED_MS = 8L * 3_600_000

    private fun measuredMs(d: GarminStressDay): Long? {
        val parts = listOf(d.restDurationMs, d.lowDurationMs, d.mediumDurationMs, d.highDurationMs)
        return if (parts.any { it == null }) null else parts.sumOf { it!! }
    }

    private fun highShare(d: GarminStressDay): Double? {
        val measured = measuredMs(d) ?: return null
        if (measured < MIN_MEASURED_MS || measured <= 0) return null
        return 100.0 * (d.mediumDurationMs!! + d.highDurationMs!!) / measured
    }

    fun analyze(days: List<GarminStressDay>, date: LocalDate, today: LocalDate): StressResult {
        val incomplete = date == today
        val usable = days.filter { it.calendarDate != today }
        val day = days.firstOrNull { it.calendarDate == date }
        val avg = day?.avgLevel
        val share = day?.let { highShare(it) }
        val measured = day?.let { measuredMs(it) }
        return StressResult(
            date, incomplete, avg, share, measured?.let { it / 3_600_000.0 }, measured != null && measured < MIN_MEASURED_MS,
            MetricAnalyzer.analyze(usable.toSeries { it.avgLevel }, date, avg?.toDouble(), incomplete),
            MetricAnalyzer.analyze(usable.mapNotNull { d -> highShare(d)?.let { d.calendarDate to it } }.toMap(), date, share, incomplete),
        )
    }
}
