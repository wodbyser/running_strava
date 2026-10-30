package com.running.strava.analysis.performance

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object PerformanceAlgorithm {
    const val VERSION = "performance-v1"
}

/** All thresholds are PROPOSALS (design section 2.4), UNVERIFIED, and configurable via `garmin.performance.*`. */
data class PerformanceConfig(
    val warmupMinutes: Int = 10,
    val minSteadyWindowMinutes: Int = 30,
    val speedCvLimit: Double = 0.10,
    val elevationLimitMPerKm: Double = 15.0,
    val minHrCoverage: Double = 0.90,
    val minRunsTrend: Int = 5,
    val minRunsDrift: Int = 5,
    val minDurabilityMinutes: Int = 75,
    val minRunsDurability: Int = 5,
    val minLongRunsList: Int = 3,
    val minLongRunsTrend: Int = 5,
    val minIntervalSessions: Int = 3,
    val minHrrMeasurements: Int = 10,
    val minPowerRuns: Int = 5,
    val minPowerCoverage: Double = 0.70,
    val minConsistencyWeeks: Int = 8,
    val maxTempGapC: Double = 8.0,
) {
    val warmupSeconds: Double get() = warmupMinutes * 60.0

    companion object {
        val DEFAULT = PerformanceConfig()
    }
}

/** Direction of a slow variable between two windows. Whether a direction is favourable is stated per indicator. */
enum class Direction(val text: String) {
    HIGHER("hoger"),
    LOWER("lager"),
    STABLE("stabiel"),
    UNCERTAIN("onzeker"),
    INSUFFICIENT("onvoldoende data"),
}

object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    /** Sample standard deviation (n - 1), null for fewer than 2 values. */
    fun sd(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m).pow(2) } / (values.size - 1))
    }

    /** Coefficient of variation (sample SD / mean), null when undefined. */
    fun cv(values: List<Double>): Double? {
        val m = mean(values) ?: return null
        val sd = sd(values) ?: return null
        return if (m > 0) sd / m else null
    }
}

/**
 * Verdict on the difference of per-run values (percentage points, bpm) between an earlier window A and a recent
 * window B. Same logic as the EF verdict (threshold and 2 standard errors) but on an absolute difference, because
 * a percentage of a percentage is meaningless. APP HEURISTIC.
 */
object DifferenceTrend {
    data class Result(
        val direction: Direction,
        val difference: Double?,
        val nA: Int,
        val nB: Int,
        val medianA: Double?,
        val medianB: Double?,
    )

    fun evaluate(a: List<Double>, b: List<Double>, minN: Int, threshold: Double): Result {
        val medA = Stats.median(a)
        val medB = Stats.median(b)
        if (a.size < minN || b.size < minN) return Result(Direction.INSUFFICIENT, null, a.size, b.size, medA, medB)
        val diff = b.average() - a.average()
        val se = sqrt((Stats.sd(a) ?: 0.0).pow(2) / a.size + (Stats.sd(b) ?: 0.0).pow(2) / b.size)
        val direction = when {
            abs(diff) <= threshold -> Direction.STABLE
            abs(diff) < 2 * se -> Direction.UNCERTAIN
            diff > 0 -> Direction.HIGHER
            else -> Direction.LOWER
        }
        return Result(direction, diff, a.size, b.size, medA, medB)
    }
}
