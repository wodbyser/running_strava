package com.running.strava.analysis

import com.running.strava.domain.Activity
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Efficiency factor (EF) = speed (m/s) / heart rate (bpm). ×60 this is metres per heartbeat (EF 0.022 ≈ 1.3 m
 * per beat). A trend PROXY for aerobic
 * fitness (known from TrainingPeaks), strongly affected by terrain, heat, fatigue and HR-strap quality.
 *
 * Pooled over several items it is computed as total distance / total heartbeats:
 *   EF = (Σd / Σt) / (Σ(hr·t) / Σt) = Σd / Σ(hr·t)
 * i.e. distance-weighted speed over time-weighted HR, using ONLY items that have both speed and HR.
 */
object EfficiencyFactor {
    data class Sample(val distanceMeters: Double, val seconds: Double, val hr: Double?)

    fun usable(samples: List<Sample>) = samples.filter { it.hr != null && it.hr > 0 && it.seconds > 0 && it.distanceMeters > 0 }

    fun pooled(samples: List<Sample>): Double? {
        val u = usable(samples)
        if (u.isEmpty()) return null
        val beats = u.sumOf { it.hr!! * it.seconds }
        return u.sumOf { it.distanceMeters } / beats
    }

    /** Time-weighted average HR over the usable samples. */
    fun avgHr(samples: List<Sample>): Double? {
        val u = usable(samples)
        val t = u.sumOf { it.seconds }
        return if (t > 0) u.sumOf { it.hr!! * it.seconds } / t else null
    }

    /** Distance / time over the usable samples (so pace and HR describe the same population). */
    fun avgSpeed(samples: List<Sample>): Double? {
        val u = usable(samples)
        val t = u.sumOf { it.seconds }
        return if (t > 0) u.sumOf { it.distanceMeters } / t else null
    }

    /** EF shown with 3 significant digits, e.g. 0.0214. */
    fun format(ef: Double?): String = if (ef == null) "-" else "%.3g".format(java.util.Locale.ROOT, ef)
}

/**
 * App verdict on an EF change between two periods. HEURISTIC:
 * - fewer than [minSessions] sessions with HR in either period → INSUFFICIENT_DATA;
 * - pooled change AND per-session-mean change both within ±[THRESHOLD_PCT] % → STABLE;
 * - pooled and per-session-mean change point in opposite directions, or only one of them exceeds the
 *   threshold → UNCERTAIN (the statistics disagree);
 * - difference of the per-session means smaller than 2 standard errors (≈ 95 %, Welch) → UNCERTAIN;
 * - else IMPROVED / DECLINED, with the direction taken from the per-session means (which then agrees with
 *   the pooled change).
 */
object EfVerdict {
    const val THRESHOLD_PCT = 3.0
    const val MIN_EASY_RUNS = 5
    const val MIN_INTERVAL_SESSIONS = 3

    enum class Status { INSUFFICIENT_DATA, STABLE, UNCERTAIN, IMPROVED, DECLINED }

    data class Result(
        val status: Status,
        val changePct: Double?,
        val nA: Int,
        val nB: Int,
        val sdA: Double?,
        val sdB: Double?,
    )

    fun evaluate(pooledA: Double?, pooledB: Double?, perSessionA: List<Double>, perSessionB: List<Double>, minSessions: Int): Result {
        val nA = perSessionA.size
        val nB = perSessionB.size
        val sdA = sd(perSessionA)
        val sdB = sd(perSessionB)
        if (pooledA == null || pooledB == null || pooledA == 0.0 || nA < minSessions || nB < minSessions) {
            val change = if (pooledA != null && pooledB != null && pooledA != 0.0) (pooledB - pooledA) / pooledA * 100 else null
            return Result(Status.INSUFFICIENT_DATA, change, nA, nB, sdA, sdB)
        }
        val change = (pooledB - pooledA) / pooledA * 100
        // Direction and significance are both judged on the per-session means (the statistic the spread belongs
        // to). The pooled change (shown to the user) must point the same way and exceed the threshold; if the two
        // statistics disagree in sign the data do not support a direction → UNCERTAIN.
        val meanA = perSessionA.average()
        val meanB = perSessionB.average()
        val diff = meanB - meanA
        val sessionChange = if (meanA != 0.0) diff / meanA * 100 else 0.0
        val se = sqrt((sdA ?: 0.0).pow(2) / nA + (sdB ?: 0.0).pow(2) / nB)
        val status = when {
            abs(change) <= THRESHOLD_PCT && abs(sessionChange) <= THRESHOLD_PCT -> Status.STABLE
            sign(change) != sign(diff) -> Status.UNCERTAIN
            abs(change) <= THRESHOLD_PCT || abs(sessionChange) <= THRESHOLD_PCT -> Status.UNCERTAIN
            abs(diff) < 2 * se -> Status.UNCERTAIN
            diff > 0 -> Status.IMPROVED
            else -> Status.DECLINED
        }
        return Result(status, change, nA, nB, sdA, sdB)
    }

    /** Sample standard deviation (n - 1), null for fewer than 2 values. */
    fun sd(v: List<Double>): Double? {
        if (v.size < 2) return null
        val m = v.average()
        return sqrt(v.sumOf { (it - m).pow(2) } / (v.size - 1))
    }
}

/**
 * Riegel (1981): T2 = T1 · (D2 / D1)^1.06. Established model, derived from continuous maximal efforts of
 * roughly 3.5 to 230 minutes. It assumes the anchor was an all-out effort and that the runner is equally
 * trained for the target distance (typically optimistic for the marathon).
 */
object RacePredictor {
    const val RIEGEL_EXPONENT = 1.06
    const val MIN_RATIO = 0.4
    const val MAX_RATIO = 2.5
    const val MIN_ANCHORS = 2
    const val MIN_VALID_SECONDS = 210.0
    const val MAX_VALID_SECONDS = 230.0 * 60

    data class Anchor(val distanceMeters: Double, val seconds: Double, val source: String)

    data class Prediction(
        val targetMeters: Double,
        val seconds: Double,
        val lowSeconds: Double,
        val highSeconds: Double,
        val anchors: List<Anchor>,
        /** Predicted time outside Riegel's 3.5-230 min range: show, but flag. */
        val outsideValidRange: Boolean,
    )

    fun riegel(knownSeconds: Double, knownMeters: Double, targetMeters: Double): Double =
        knownSeconds * (targetMeters / knownMeters).pow(RIEGEL_EXPONENT)

    /** Median of the Riegel predictions from all valid anchors within 0.4-2.5x of the target, or null if < 2. */
    fun predict(targetMeters: Double, anchors: List<Anchor>): Prediction? {
        val usable = anchors.filter {
            it.distanceMeters > 0 && it.seconds >= MIN_VALID_SECONDS && it.seconds <= MAX_VALID_SECONDS &&
                (targetMeters / it.distanceMeters) in MIN_RATIO..MAX_RATIO
        }
        if (usable.size < MIN_ANCHORS) return null
        val predictions = usable.map { riegel(it.seconds, it.distanceMeters, targetMeters) }.sorted()
        val median = median(predictions)
        return Prediction(
            targetMeters = targetMeters,
            seconds = median,
            lowSeconds = predictions.first(),
            highSeconds = predictions.last(),
            anchors = usable,
            outsideValidRange = median < MIN_VALID_SECONDS || median > MAX_VALID_SECONDS,
        )
    }

    fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /** Avoid false precision: 5 s below 20 min, 15 s below 1 h, whole minutes above. */
    fun roundSeconds(s: Double): Double {
        val step = when {
            s < 20 * 60 -> 5.0
            s < 60 * 60 -> 15.0
            else -> 60.0
        }
        return Math.round(s / step) * step
    }
}

/** Heart-rate zones. Percentages are textbook conventions; individual thresholds differ. */
object HrZones {
    /**
     * A run with average HR >= this fraction of (estimated) max HR is a hard continuous effort (race-like), not
     * an easy run. HEURISTIC: 85 % HRmax is the usual lower bound of threshold/tempo intensity; easy running is
     * typically below ~75-80 %.
     */
    const val HARD_EFFORT_FRACTION = 0.85

    fun isHardEffort(avgHr: Number?, maxHr: Int?): Boolean =
        avgHr != null && maxHr != null && maxHr > 0 && avgHr.toDouble() >= maxHr * HARD_EFFORT_FRACTION

    data class Zone(val zone: Int, val name: String, val description: String, val minBpm: Int, val maxBpm: Int, val pct: String)

    private val BOUNDS = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)
    private val NAMES = listOf("Herstel", "Duur", "Matig", "Drempel", "VO2max")
    private val DESCRIPTIONS = listOf(
        "Zeer licht: herstellopen, warming-up en cooling-down.",
        "Licht: rustige duurlopen, aerobe basis. Moet vlot en ontspannen aanvoelen.",
        "Matig: steady duurloop; rond marathontempo voor veel lopers.",
        "Zwaar: tempolopen rond de (lactaat)drempel.",
        "Zeer zwaar: korte intervallen richting maximale inspanning.",
    )

    /** Non-overlapping integer ranges: zone i covers [round(p_i·X), round(p_{i+1}·X) − 1], last zone up to max. */
    private fun build(maxHr: Int, bpmAt: (Double) -> Int, suffix: String): List<Zone> =
        (0 until 5).map { i ->
            val lo = bpmAt(BOUNDS[i])
            val hi = if (i == 4) maxHr else bpmAt(BOUNDS[i + 1]) - 1
            Zone(i + 1, NAMES[i], DESCRIPTIONS[i], lo, hi, "${(BOUNDS[i] * 100).roundToInt()}-${(BOUNDS[i + 1] * 100).roundToInt()}%$suffix")
        }

    fun pctMax(maxHr: Int): List<Zone> = build(maxHr, { p -> (p * maxHr).roundToInt() }, "")

    /** Karvonen / heart-rate reserve: RHR + p · (HRmax − RHR). Requires a real resting HR. */
    fun karvonen(maxHr: Int, restingHr: Int): List<Zone> =
        build(maxHr, { p -> (restingHr + p * (maxHr - restingHr)).roundToInt() }, " HRR")

    /**
     * App ESTIMATE of max HR: 95th percentile (nearest rank) of the per-run max HR values, to ignore strap
     * spikes. Falls back to the plain maximum for fewer than 5 runs. It is a lower bound of the true max HR
     * unless the runner regularly runs all-out.
     */
    fun estimateMaxHr(perRunMax: List<Int>): Int? {
        if (perRunMax.isEmpty()) return null
        val s = perRunMax.sorted()
        if (s.size < 5) return s.last()
        val rank = ceil(0.95 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    /**
     * The runs the max-HR estimate is based on: the 12 months before [now]; if there are none, all runs.
     * Shared by the coach page and the period comparison so both use the same max HR.
     */
    fun maxHrWindow(runs: List<Activity>, now: ZonedDateTime): List<Activity> =
        runs.filter { it.startDate.isAfter(now.minusYears(1)) }.ifEmpty { runs }

    /** [estimateMaxHr] over the per-run max HR of [maxHrWindow]. */
    fun estimateMaxHrForRuns(runs: List<Activity>, now: ZonedDateTime): Int? =
        estimateMaxHr(maxHrWindow(runs, now).mapNotNull { it.maxHeartrate?.toInt() })
}
