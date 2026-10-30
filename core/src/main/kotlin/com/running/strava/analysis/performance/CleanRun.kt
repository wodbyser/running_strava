package com.running.strava.analysis.performance

import com.running.strava.analysis.BestEffortFinder
import com.running.strava.domain.ActivityStream

/**
 * One cleaned stream sample. It covers the interval before [t]: [dt] seconds and [dd] metres.
 * [clock] is the cumulative valid time at the end of the sample (pauses and dropped samples do not advance it).
 * [speed] is the 30 s centred window speed in m/s (not Strava's `velocity_smooth`, whose smoothing is undocumented).
 */
data class Sample(
    val t: Double,
    val dt: Double,
    val dd: Double,
    val hr: Int,
    val speed: Double,
    val gradePct: Double?,
    val cadenceSpm: Double?,
    val temp: Int?,
    val clock: Double,
)

data class CleanedRun(
    val samples: List<Sample>,
    val validSeconds: Double,
    val movingSeconds: Double,
    val medianIntervalSeconds: Double?,
    val dropped: Map<String, Int>,
    val hasGrade: Boolean,
    val hasTemp: Boolean,
) {
    /** Valid HR time as a share of the moving time, capped at 1. */
    val coverage: Double get() = if (movingSeconds > 0) minOf(1.0, validSeconds / movingSeconds) else 0.0
}

/** Stream cleaning per design section 2.2. Pure, never assumes a 1 Hz stream. */
object CleanRun {
    const val MAX_GAP_SECONDS = 30.0
    const val MIN_HR = 40
    const val MAX_HR = 230
    const val MIN_SPEED_MS = 0.5
    const val HR_DROPOUT_RUN = 30
    const val SPEED_WINDOW_SECONDS = 30.0

    fun clean(stream: ActivityStream, movingSeconds: Double): CleanedRun? {
        val time = stream.time ?: return null
        val dist = stream.distance ?: return null
        val hr = stream.heartrate ?: return null
        val n = minOf(time.size, dist.size, hr.size)
        if (n < 2) return null

        val grade = stream.gradeSmooth?.takeIf { it.size == n }
        val cadence = stream.cadence?.takeIf { it.size == n }
        val temp = stream.temp?.takeIf { it.size == n }

        val dropout = BooleanArray(n)
        var runStart = 0
        for (k in 1..n) {
            if (k == n || hr[k] != hr[runStart]) {
                if (k - runStart >= HR_DROPOUT_RUN) for (j in runStart until k) dropout[j] = true
                runStart = k
            }
        }

        val dropped = linkedMapOf<String, Int>()
        fun drop(reason: String) { dropped[reason] = (dropped[reason] ?: 0) + 1 }

        val rawIntervals = ArrayList<Double>()
        class Kept(val t: Double, val dt: Double, val dd: Double, val hr: Int, val k: Int)
        val kept = ArrayList<Kept>()
        for (k in 1 until n) {
            val dt = (time[k] - time[k - 1]).toDouble()
            if (dt > 0) rawIntervals += dt
            val dd = (dist[k] - dist[k - 1]).toDouble()
            when {
                dt <= 0 -> drop("dt<=0")
                dt > MAX_GAP_SECONDS -> drop("gap")
                hr[k] < MIN_HR || hr[k] > MAX_HR -> drop("hr-range")
                dropout[k] -> drop("hr-dropout")
                dd / dt > BestEffortFinder.MAX_SAMPLE_SPEED_MS -> drop("gps-jump")
                dd / dt < MIN_SPEED_MS -> drop("standing")
                else -> kept += Kept(time[k].toDouble(), dt, dd, hr[k], k)
            }
        }

        val m = kept.size
        val prefixDd = DoubleArray(m + 1)
        val prefixDt = DoubleArray(m + 1)
        for (i in 0 until m) {
            prefixDd[i + 1] = prefixDd[i] + kept[i].dd
            prefixDt[i + 1] = prefixDt[i] + kept[i].dt
        }
        val half = SPEED_WINDOW_SECONDS / 2
        var lo = 0
        var hi = 0
        var clock = 0.0
        val samples = ArrayList<Sample>(m)
        for (i in 0 until m) {
            val c = kept[i]
            while (lo < i && kept[lo].t < c.t - half) lo++
            if (hi < i) hi = i
            while (hi + 1 < m && kept[hi + 1].t <= c.t + half) hi++
            val wt = prefixDt[hi + 1] - prefixDt[lo]
            val speed = if (wt > 0) (prefixDd[hi + 1] - prefixDd[lo]) / wt else c.dd / c.dt
            clock += c.dt
            samples += Sample(
                t = c.t, dt = c.dt, dd = c.dd, hr = c.hr, speed = speed,
                gradePct = grade?.get(c.k)?.toDouble(),
                cadenceSpm = cadence?.get(c.k)?.takeIf { it > 0 }?.let { it * 2.0 },
                temp = temp?.get(c.k),
                clock = clock,
            )
        }
        return CleanedRun(
            samples = samples,
            validSeconds = clock,
            movingSeconds = movingSeconds,
            medianIntervalSeconds = Stats.median(rawIntervals),
            dropped = dropped,
            hasGrade = grade != null,
            hasTemp = temp != null,
        )
    }
}

/** Time-weighted helpers shared by the metrics. Every average weights by `dt`. */
object Series {
    fun seconds(s: List<Sample>) = s.sumOf { it.dt }
    fun distance(s: List<Sample>) = s.sumOf { it.dd }
    fun avgHr(s: List<Sample>): Double? = seconds(s).takeIf { it > 0 }?.let { t -> s.sumOf { it.hr * it.dt } / t }
    fun avgSpeed(s: List<Sample>): Double? = seconds(s).takeIf { it > 0 }?.let { distance(s) / it }

    /** Efficiency factor: metres per heartbeat divided by... see [com.running.strava.analysis.EfficiencyFactor]: Σd / Σ(hr·t). */
    fun ef(s: List<Sample>): Double? {
        val beats = s.sumOf { it.hr * it.dt }
        return if (beats > 0) distance(s) / beats else null
    }

    fun avgCadence(s: List<Sample>): Double? {
        val c = s.filter { it.cadenceSpm != null }
        val t = c.sumOf { it.dt }
        return if (t > 0) c.sumOf { it.cadenceSpm!! * it.dt } / t else null
    }

    fun avgGrade(s: List<Sample>): Double? {
        val g = s.filter { it.gradePct != null }
        val t = g.sumOf { it.dt }
        return if (t > 0) g.sumOf { it.gradePct!! * it.dt } / t else null
    }

    fun avgAbsGrade(s: List<Sample>): Double? {
        val g = s.filter { it.gradePct != null }
        val t = g.sumOf { it.dt }
        return if (t > 0) g.sumOf { kotlin.math.abs(it.gradePct!!) * it.dt } / t else null
    }

    fun medianTemp(s: List<Sample>): Double? = Stats.median(s.mapNotNull { it.temp?.toDouble() })

    /** Samples after [warmupSeconds] of valid time. */
    fun afterWarmup(s: List<Sample>, warmupSeconds: Double): List<Sample> = s.filter { it.clock - it.dt >= warmupSeconds }

    /**
     * Cuts samples into consecutive blocks of [blockSeconds] valid time, counted from [startClock].
     * A sample belongs to the block in which its end falls.
     */
    fun blocks(s: List<Sample>, startClock: Double, blockSeconds: Double): List<List<Sample>> {
        val groups = LinkedHashMap<Int, MutableList<Sample>>()
        for (x in s) {
            if (x.clock <= startClock) continue
            val idx = ((x.clock - startClock - 1e-9) / blockSeconds).toInt()
            groups.getOrPut(idx) { mutableListOf() } += x
        }
        return groups.toSortedMap().values.toList()
    }

    /** Splits at half of the valid time: first and second half. */
    fun halves(s: List<Sample>): Pair<List<Sample>, List<Sample>> {
        val total = seconds(s)
        var cum = 0.0
        val first = ArrayList<Sample>()
        val second = ArrayList<Sample>()
        for (x in s) {
            if (cum + x.dt / 2 < total / 2) first += x else second += x
            cum += x.dt
        }
        return first to second
    }

    /** Splits into [parts] equal parts of valid time. */
    fun parts(s: List<Sample>, parts: Int): List<List<Sample>> {
        val total = seconds(s)
        val out = List(parts) { ArrayList<Sample>() }
        var cum = 0.0
        for (x in s) {
            val idx = (((cum + x.dt / 2) / total) * parts).toInt().coerceIn(0, parts - 1)
            out[idx] += x
            cum += x.dt
        }
        return out
    }
}
