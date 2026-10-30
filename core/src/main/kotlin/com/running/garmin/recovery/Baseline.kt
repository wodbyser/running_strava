package com.running.garmin.recovery

import com.running.garmin.domain.GarminDailyRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

object RecoveryAlgorithm {
    const val VERSION = "recovery-v1"
}

enum class DeviationBand { WITHIN, DEVIATING, STRONG }

/**
 * Every period the recovery analysis uses. Defaults are the documented ones; the page lets the user change them.
 * Minimum counts scale with the window (70 % of the days, as [Baseline.minimumCount]).
 */
data class RecoveryWindows(
    /** Own norm: median of the N days before the day. */
    val normDays: Int = Baseline.PRIMARY_WINDOW,
    /** Trend: median of the last N days ... */
    val trendRecentDays: Int = Trend.RECENT_DAYS,
    /** ... against the N days before those. */
    val trendBaselineDays: Int = Trend.BASELINE_DAYS,
    val debtNights: Int = SleepAnalysis.DEBT_DAYS,
    val regularityNights: Int = SleepAnalysis.REGULARITY_DAYS,
    val chartDays: Int = RecoveryAnalyzer.CHART_DAYS,
    val tableDays: Int = RecoveryAnalyzer.RECENT_DAYS,
) {
    /** Days of stored rows needed before the shown day. */
    val historyDays: Int get() = maxOf(RecoveryAnalyzer.HISTORY_DAYS, chartDays + normDays, trendRecentDays + trendBaselineDays, trendRecentDays + normDays, tableDays + normDays)

    companion object {
        val DEFAULT = RecoveryWindows()
    }
}

enum class BaselineStatus { OK, INSUFFICIENT }

/**
 * One baseline window. `n` is the number of days with a value in the window, `needed` the minimum (70 % of the days).
 * Below the minimum there is no median and no deviation. `value` and `deviation` refer to the evaluated day.
 */
data class BaselineResult(
    val windowDays: Int,
    val n: Int,
    val needed: Int,
    val status: BaselineStatus,
    val median: Double?,
    val robustSd: Double?,
    val value: Double?,
    val deviation: Double?,
    val band: DeviationBand?,
)

/** Pure personal-baseline machinery (docs/garmin-phase-2-design.md section 2.1). Spring-free. */
object Baseline {
    val WINDOWS = listOf(7, 28, 60, 90)
    const val PRIMARY_WINDOW = 28
    const val SD_FLOOR = 1.0
    const val MAD_SCALE = 1.4826

    /** 70 % of the window, rounded up, in integer arithmetic: 7 gives 5, 28 gives 20, 60 gives 42, 90 gives 63. */
    fun minimumCount(windowDays: Int): Int = (windowDays * 7 + 9) / 10

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of an empty list" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    fun robustSd(values: List<Double>, median: Double = median(values)): Double =
        max(MAD_SCALE * median(values.map { abs(it - median) }), SD_FLOOR)

    fun band(deviation: Double): DeviationBand {
        val a = abs(deviation)
        return when {
            a < 1.0 -> DeviationBand.WITHIN
            a < 2.0 -> DeviationBand.DEVIATING
            else -> DeviationBand.STRONG
        }
    }

    /** Values of the `days` calendar days before `date`. `date` itself is never part of its own window. */
    fun windowBefore(series: Map<LocalDate, Double>, date: LocalDate, days: Int): List<Double> =
        (1..days).mapNotNull { series[date.minusDays(it.toLong())] }

    fun compute(series: Map<LocalDate, Double>, date: LocalDate, windowDays: Int, value: Double? = series[date]): BaselineResult =
        fromValues(windowBefore(series, date, windowDays), windowDays, minimumCount(windowDays), value)

    fun fromValues(values: List<Double>, windowDays: Int, needed: Int, value: Double?): BaselineResult {
        if (values.size < needed) {
            return BaselineResult(windowDays, values.size, needed, BaselineStatus.INSUFFICIENT, null, null, value, null, null)
        }
        val median = median(values)
        val sd = robustSd(values, median)
        val deviation = value?.let { (it - median) / sd }
        return BaselineResult(windowDays, values.size, needed, BaselineStatus.OK, median, sd, value, deviation, deviation?.let(::band))
    }
}

enum class SustainedDirection { HIGHER, LOWER, NONE, INSUFFICIENT }

/**
 * Trend: median of the 7 days ending at D (at least 5 values) against the 60 day baseline of days D-66 to D-7
 * (at least 42 values), plus the sustained-deviation rule: at least 5 of the last 7 daily deviations against the
 * 28 day baseline point the same way with |deviation| >= 1.
 */
data class TrendResult(
    val status: BaselineStatus,
    val recentN: Int,
    val recentMedian: Double?,
    val baselineN: Int,
    val baselineMedian: Double?,
    val baselineSd: Double?,
    val deviation: Double?,
    val sustained: SustainedDirection,
    val sustainedDaysEvaluated: Int,
    val sustainedDaysHigher: Int,
    val sustainedDaysLower: Int,
)

object Trend {
    const val RECENT_DAYS = 7
    const val RECENT_MIN = 5
    const val BASELINE_DAYS = 60
    const val SUSTAINED_MIN = 5

    fun evaluate(series: Map<LocalDate, Double>, date: LocalDate, w: RecoveryWindows = RecoveryWindows.DEFAULT): TrendResult {
        val recentDays = w.trendRecentDays
        // 5 of 7 for the defaults; scales with the chosen window.
        val recentMin = Baseline.minimumCount(recentDays)
        val recent = (0 until recentDays).mapNotNull { series[date.minusDays(it.toLong())] }
        val baselineEnd = date.minusDays(recentDays.toLong())
        val baseline = (0 until w.trendBaselineDays).mapNotNull { series[baselineEnd.minusDays(it.toLong())] }
        val baselineNeeded = Baseline.minimumCount(w.trendBaselineDays)
        val ok = recent.size >= recentMin && baseline.size >= baselineNeeded
        val recentMedian = if (recent.size >= recentMin) Baseline.median(recent) else null
        val baselineMedian = if (baseline.size >= baselineNeeded) Baseline.median(baseline) else null
        val baselineSd = baselineMedian?.let { Baseline.robustSd(baseline, it) }
        val deviation = if (ok) (recentMedian!! - baselineMedian!!) / baselineSd!! else null

        val dailyDeviations = (0 until recentDays).mapNotNull { i ->
            val d = date.minusDays(i.toLong())
            val value = series[d] ?: return@mapNotNull null
            Baseline.compute(series, d, w.normDays, value).deviation
        }
        val higher = dailyDeviations.count { it >= 1.0 }
        val lower = dailyDeviations.count { it <= -1.0 }
        val sustainedMin = Baseline.minimumCount(recentDays)
        val sustained = when {
            dailyDeviations.size < sustainedMin -> SustainedDirection.INSUFFICIENT
            higher >= sustainedMin -> SustainedDirection.HIGHER
            lower >= sustainedMin -> SustainedDirection.LOWER
            else -> SustainedDirection.NONE
        }
        return TrendResult(
            if (ok) BaselineStatus.OK else BaselineStatus.INSUFFICIENT,
            recent.size, recentMedian, baseline.size, baselineMedian, baselineSd, deviation,
            sustained, dailyDeviations.size, higher, lower,
        )
    }
}

/** Baselines for the four windows, the primary (28 day) result for the day, and the trend. */
data class MetricAnalysis(
    val value: Double?,
    val incomplete: Boolean,
    val windows: List<BaselineResult>,
    val primary: BaselineResult,
    val trend: TrendResult,
)

object MetricAnalyzer {
    fun analyze(
        series: Map<LocalDate, Double>,
        date: LocalDate,
        value: Double? = series[date],
        incomplete: Boolean = false,
        w: RecoveryWindows = RecoveryWindows.DEFAULT,
    ): MetricAnalysis {
        val windows = (Baseline.WINDOWS + w.normDays).distinct().sorted().map { Baseline.compute(series, date, it, value) }
        return MetricAnalysis(value, incomplete, windows, windows.first { it.windowDays == w.normDays }, Trend.evaluate(series, date, w))
    }
}

fun <T : GarminDailyRecord> List<T>.toSeries(select: (T) -> Number?): Map<LocalDate, Double> =
    mapNotNull { r -> select(r)?.let { r.calendarDate to it.toDouble() } }.toMap()
