package com.running.strava.analysis.performance

import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.EfVerdict
import com.running.strava.domain.ActivityStream
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One steady run with activity-level power (#29). No power stream exists, so this is not time-resolved. */
data class PowerRun(
    val activityId: Long,
    val date: LocalDate,
    val seconds: Double,
    val distanceMeters: Double,
    val avgHr: Double,
    val avgWatts: Double,
) {
    val wattsPerBpm: Double get() = avgWatts / avgHr
    val speedPerWatt: Double get() = (distanceMeters / seconds) / avgWatts
}

object PowerEfficiency {
    /** Power measured by a device (`device_watts = true`), not estimated by Strava. */
    fun hasMeasuredPower(activity: com.running.strava.domain.Activity): Boolean =
        activity.deviceWatts == true && (activity.averageWatts ?: 0f) > 0f

    data class Window(val n: Int, val medianWattsPerBpm: Double?, val medianSpeedPerWatt: Double?)

    data class Result(
        val eligible: Int,
        val withPower: Int,
        val coverage: Double,
        val available: Boolean,
        val reason: String?,
        val windowA: Window,
        val windowB: Window,
        val wattsPerBpmStatus: EfVerdict.Status,
        val wattsPerBpmChangePct: Double?,
        val speedPerWattStatus: EfVerdict.Status,
        val speedPerWattChangePct: Double?,
    )

    /**
     * [eligibleSteady]: the steady runs of the recent window (with or without measured power); [powerRuns]: the subset with
     * `device_watts = true` and average watts. Windows: last [windowDays] days against the [windowDays] before.
     */
    fun analyze(
        eligibleSteadyDates: List<LocalDate>,
        powerRuns: List<PowerRun>,
        today: LocalDate,
        windowDays: Int,
        config: PerformanceConfig = PerformanceConfig.DEFAULT,
    ): Result {
        fun inB(d: LocalDate) = d.isAfter(today.minusDays(windowDays.toLong())) && !d.isAfter(today)
        fun inA(d: LocalDate) = d.isAfter(today.minusDays(2L * windowDays)) && !d.isAfter(today.minusDays(windowDays.toLong()))
        val eligible = eligibleSteadyDates.count { inA(it) || inB(it) }
        val a = powerRuns.filter { inA(it.date) }
        val b = powerRuns.filter { inB(it.date) }
        val withPower = a.size + b.size
        val coverage = if (eligible > 0) withPower.toDouble() / eligible else 0.0
        fun pooledRatio(runs: List<PowerRun>, num: (PowerRun) -> Double, den: (PowerRun) -> Double): Double? {
            val d = runs.sumOf(den)
            return if (d > 0) runs.sumOf(num) / d else null
        }
        val wA = pooledRatio(a, { it.avgWatts * it.seconds }, { it.avgHr * it.seconds })
        val wB = pooledRatio(b, { it.avgWatts * it.seconds }, { it.avgHr * it.seconds })
        val sA = pooledRatio(a, { it.distanceMeters }, { it.avgWatts * it.seconds })
        val sB = pooledRatio(b, { it.distanceMeters }, { it.avgWatts * it.seconds })
        val v1 = EfVerdict.evaluate(wA, wB, a.map { it.wattsPerBpm }, b.map { it.wattsPerBpm }, config.minPowerRuns)
        val v2 = EfVerdict.evaluate(sA, sB, a.map { it.speedPerWatt }, b.map { it.speedPerWatt }, config.minPowerRuns)
        val available = eligible > 0 && coverage >= config.minPowerCoverage
        return Result(
            eligible, withPower, coverage, available,
            if (available) null else "vermogen niet betrouwbaar aanwezig",
            Window(a.size, Stats.median(a.map { it.wattsPerBpm }), Stats.median(a.map { it.speedPerWatt })),
            Window(b.size, Stats.median(b.map { it.wattsPerBpm }), Stats.median(b.map { it.speedPerWatt })),
            if (available) v1.status else EfVerdict.Status.INSUFFICIENT_DATA, v1.changePct,
            if (available) v2.status else EfVerdict.Status.INSUFFICIENT_DATA, v2.changePct,
        )
    }
}

/** #22 critical speed (CS) from maximal-ish efforts. Not a lactate threshold. */
object CriticalSpeed {
    val DISTANCES = listOf(1000.0, 3000.0, 5000.0, 10000.0)
    const val MIN_SECONDS = 180.0
    const val MAX_SECONDS = 1800.0
    const val MIN_ANCHORS = 3
    const val MIN_DURATION_RATIO = 2.0
    const val MIN_R2 = 0.98
    const val MAX_RANGE_PCT = 5.0
    const val WINDOW_DAYS = 90L

    /** A fastest effort of an exact distance inside one run. */
    data class Effort(
        val activityId: Long,
        val date: LocalDate,
        val distanceMeters: Double,
        val seconds: Double,
        val isRace: Boolean,
        val avgHr: Double?,
    )

    data class Fit(
        val csMs: Double,
        val dPrimeMeters: Double,
        val r2: Double,
        val anchors: List<Effort>,
        val csRangeMs: Pair<Double, Double>,
    ) {
        val rangePct: Double get() = (csRangeMs.second - csRangeMs.first) / csMs * 100
        val csSecondsPerKm: Double get() = 1000 / csMs
    }

    sealed interface Outcome {
        data class Ok(val fit: Fit) : Outcome
        data class Abstain(val reason: String, val anchors: List<Effort>) : Outcome
    }

    data class Validation(val n: Int, val meanAbsErrorPct: Double?)

    /** Fastest segment of [distance] m in a stream, with the mean HR over that segment when available. */
    fun effort(activityId: Long, date: LocalDate, isRace: Boolean, stream: ActivityStream, distance: Double): Effort? {
        val e = BestEffortFinder.fastest(stream.time, stream.distance, distance) ?: return null
        if (e.seconds < MIN_SECONDS || e.seconds > MAX_SECONDS) return null
        val hr = stream.heartrate
        val time = stream.time!!
        var avgHr: Double? = null
        if (hr != null && hr.size == time.size && e.endIndex > e.startIndex) {
            var sum = 0.0
            var secs = 0.0
            for (k in e.startIndex + 1..e.endIndex) {
                val dt = (time[k] - time[k - 1]).toDouble()
                if (dt in 0.0..CleanRun.MAX_GAP_SECONDS && hr[k] in CleanRun.MIN_HR..CleanRun.MAX_HR) { sum += hr[k] * dt; secs += dt }
            }
            if (secs > 0) avgHr = sum / secs
        }
        return Effort(activityId, date, distance, e.seconds, isRace, avgHr)
    }

    /** Fastest effort per distance among [efforts] dated in (today - [windowDays], today]. */
    fun anchors(efforts: List<Effort>, today: LocalDate, windowDays: Long = WINDOW_DAYS): List<Effort> =
        efforts.filter { it.date.isAfter(today.minusDays(windowDays)) && !it.date.isAfter(today) }
            .groupBy { it.distanceMeters }.values.map { g -> g.minBy { it.seconds } }
            .sortedBy { it.seconds }

    private fun regress(points: List<Effort>): Triple<Double, Double, Double>? {
        val n = points.size
        if (n < 2) return null
        val mx = points.sumOf { it.seconds } / n
        val my = points.sumOf { it.distanceMeters } / n
        val sxx = points.sumOf { (it.seconds - mx) * (it.seconds - mx) }
        if (sxx <= 0) return null
        val sxy = points.sumOf { (it.seconds - mx) * (it.distanceMeters - my) }
        val cs = sxy / sxx
        val dp = my - cs * mx
        val ssTot = points.sumOf { (it.distanceMeters - my) * (it.distanceMeters - my) }
        val ssRes = points.sumOf { val p = cs * it.seconds + dp; (it.distanceMeters - p) * (it.distanceMeters - p) }
        val r2 = if (ssTot > 0) 1 - ssRes / ssTot else 1.0
        return Triple(cs, dp, r2)
    }

    fun fit(anchors: List<Effort>): Outcome {
        if (anchors.size < MIN_ANCHORS) return Outcome.Abstain("minder dan $MIN_ANCHORS ankers (n = ${anchors.size})", anchors)
        val ratio = anchors.maxOf { it.seconds } / anchors.minOf { it.seconds }
        if (ratio < MIN_DURATION_RATIO) return Outcome.Abstain("duurverhouding van de ankers onder $MIN_DURATION_RATIO", anchors)
        val (cs, dp, r2) = regress(anchors) ?: return Outcome.Abstain("regressie niet mogelijk", anchors)
        if (cs <= 0) return Outcome.Abstain("geen positieve kritische snelheid", anchors)
        if (r2 < MIN_R2) return Outcome.Abstain("model past slecht (R2 ${"%.3f".format(java.util.Locale.ROOT, r2)} onder $MIN_R2)", anchors)
        val loo = anchors.indices.mapNotNull { i -> regress(anchors.filterIndexed { j, _ -> j != i })?.first }
        val range = (loo.minOrNull() ?: cs) to (loo.maxOrNull() ?: cs)
        val lo = min(range.first, cs)
        val hi = max(range.second, cs)
        val fit = Fit(cs, dp, r2, anchors, lo to hi)
        if (fit.rangePct > MAX_RANGE_PCT) return Outcome.Abstain("schatting te onzeker (bereik ${"%.1f".format(java.util.Locale.ROOT, fit.rangePct)} % van CS)", anchors)
        return Outcome.Ok(fit)
    }

    /** Median HR over the segments of anchors lasting 10 to 30 minutes. */
    fun hrAtCs(fit: Fit): Pair<Double?, Int> {
        val hrs = fit.anchors.filter { it.seconds in 600.0..1800.0 }.mapNotNull { it.avgHr }
        return Stats.median(hrs) to hrs.size
    }

    /**
     * Walk-forward check (spec rule 10): every effort that is the fastest of its distance in the 90 days up to its
     * own date is predicted from a fit on efforts of earlier dates only. Error = (predicted - actual time) / actual.
     */
    fun walkForward(all: List<Effort>, windowDays: Long = WINDOW_DAYS): Validation {
        val errors = ArrayList<Double>()
        for (e in all) {
            val windowBest = all.filter {
                it.distanceMeters == e.distanceMeters && !it.date.isAfter(e.date) && it.date.isAfter(e.date.minusDays(windowDays))
            }.minOf { it.seconds }
            if (e.seconds > windowBest) continue
            val prior = anchors(all.filter { it.date.isBefore(e.date) }, e.date.minusDays(1), windowDays)
            val fit = (fit(prior) as? Outcome.Ok)?.fit ?: continue
            val predicted = (e.distanceMeters - fit.dPrimeMeters) / fit.csMs
            errors += abs(predicted - e.seconds) / e.seconds * 100
        }
        return Validation(errors.size, if (errors.isEmpty()) null else errors.average())
    }
}
