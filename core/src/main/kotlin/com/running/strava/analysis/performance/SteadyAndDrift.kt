package com.running.strava.analysis.performance

import com.running.strava.domain.Activity
import com.running.strava.domain.SessionType
import com.running.strava.analysis.HrZones
import kotlin.math.pow

enum class TerrainClass(val text: String) {
    FLAT("vlak"),
    ROLLING("glooiend"),
    HILLY("heuvelachtig"),
    ;

    companion object {
        /** Strava elevation gain per km: flat below 5, rolling 5 to 15 (inclusive), hilly above 15. PROPOSAL. */
        fun of(elevationGainPerKm: Double): TerrainClass = when {
            elevationGainPerKm < 5.0 -> FLAT
            elevationGainPerKm <= 15.0 -> ROLLING
            else -> HILLY
        }

        fun of(activity: Activity): TerrainClass = of(elevationPerKm(activity))

        fun elevationPerKm(activity: Activity): Double =
            if (activity.distance > 0) activity.totalElevationGain / (activity.distance / 1000.0) else 0.0
    }
}

/**
 * Grade-adjusted speed (#30) with the energy cost of running of Minetti et al. (2002):
 * C(i) = 155.4 i^5 - 30.4 i^4 - 43.3 i^3 + 46.3 i^2 + 19.5 i + 3.6 (J/kg/m, i = grade fraction, -0.45..0.45).
 * Flat-equivalent speed = speed x C(i) / C(0). APP ESTIMATE, not Strava's GAP (that formula is not published).
 */
object GradeAdjusted {
    const val IGNORE_ABOVE_PCT = 20.0
    const val EXTREME_PCT = 25.0
    const val MAX_EXTREME_SHARE = 0.05
    private const val FLAT_COST = 3.6

    fun cost(i: Double): Double =
        155.4 * i.pow(5) - 30.4 * i.pow(4) - 43.3 * i.pow(3) + 46.3 * i.pow(2) + 19.5 * i + 3.6

    fun factor(gradePct: Double): Double = cost(gradePct / 100.0) / FLAT_COST

    data class Result(
        val available: Boolean,
        val reason: String?,
        val rawSpeed: Double?,
        val gapSpeed: Double?,
        val extremeShare: Double,
    )

    /** GAP over [samples]: Σ(distance × factor) / Σtime over samples with |grade| <= 20 %. */
    fun assess(samples: List<Sample>, hasGradeStream: Boolean): Result {
        if (!hasGradeStream) return Result(false, "geen hellingsstroom", null, null, 0.0)
        val withGrade = samples.filter { it.gradePct != null }
        val total = withGrade.sumOf { it.dt }
        if (total <= 0) return Result(false, "geen hellingsstroom", null, null, 0.0)
        val extreme = withGrade.filter { kotlin.math.abs(it.gradePct!!) > EXTREME_PCT }.sumOf { it.dt } / total
        val raw = Series.avgSpeed(samples)
        if (extreme > MAX_EXTREME_SHARE) return Result(false, "te veel extreme hellingen (> 25 %)", raw, null, extreme)
        val used = withGrade.filter { kotlin.math.abs(it.gradePct!!) <= IGNORE_ABOVE_PCT }
        val t = used.sumOf { it.dt }
        if (t <= 0) return Result(false, "geen bruikbare hellingen", raw, null, extreme)
        val gap = used.sumOf { it.dd * factor(it.gradePct!!) } / t
        return Result(true, null, used.sumOf { it.dd } / t, gap, extreme)
    }

    /** EF on GAP: flat-equivalent distance per heartbeat. Null when the grade is not plausible. */
    fun efGap(samples: List<Sample>, hasGradeStream: Boolean): Double? {
        if (!assess(samples, hasGradeStream).available) return null
        val used = samples.filter { it.gradePct != null && kotlin.math.abs(it.gradePct) <= IGNORE_ABOVE_PCT }
        val beats = used.sumOf { it.hr * it.dt }
        return if (beats > 0) used.sumOf { it.dd * factor(it.gradePct!!) } / beats else null
    }
}

enum class SteadyFailure(val text: String) {
    NO_STREAM("geen stroomdata"),
    NO_HR("geen hartslagstroom"),
    HR_COVERAGE("te weinig hartslagdekking"),
    SESSION_TYPE("geen rustige training (race, interval, tempo of strides)"),
    HARD_EFFORT("zware inspanning (gem. HR boven 85 % van max-HR)"),
    HILLY("heuvelachtig (meer dan 15 hoogtemeter per km)"),
    WINDOW_SHORT("stabiel deel korter dan het minimum"),
    SPEED_VARIES("tempo te wisselend (variatie van 5-minutenblokken)"),
}

data class SteadyResult(
    val failure: SteadyFailure?,
    val window: List<Sample>,
    val windowSeconds: Double,
    val speedCv: Double?,
    val coverage: Double,
) {
    val steady: Boolean get() = failure == null
    val avgHr: Double? get() = Series.avgHr(window)
    val avgSpeed: Double? get() = Series.avgSpeed(window)
    val ef: Double? get() = Series.ef(window)
}

/** Steady run per design section 2.3. The window W is the valid time after the warm-up. */
object SteadyRun {
    const val BLOCK_SECONDS = 300.0

    fun evaluate(
        activity: Activity,
        sessionType: SessionType,
        cleaned: CleanedRun?,
        maxHr: Int?,
        config: PerformanceConfig = PerformanceConfig.DEFAULT,
    ): SteadyResult {
        if (cleaned == null) return SteadyResult(SteadyFailure.NO_STREAM, emptyList(), 0.0, null, 0.0)
        val window = Series.afterWarmup(cleaned.samples, config.warmupSeconds)
        val windowSeconds = Series.seconds(window)

        val blockSpeeds = Series.blocks(window, config.warmupSeconds, BLOCK_SECONDS)
            .filter { Series.seconds(it) >= BLOCK_SECONDS / 2 }
            .mapNotNull { Series.avgSpeed(it) }
        val cv = if (blockSpeeds.size >= 2) Stats.cv(blockSpeeds) else null

        val failure = when {
            cleaned.coverage < config.minHrCoverage -> SteadyFailure.HR_COVERAGE
            sessionType != SessionType.STEADY -> SteadyFailure.SESSION_TYPE
            HrZones.isHardEffort(activity.averageHeartrate, maxHr) -> SteadyFailure.HARD_EFFORT
            TerrainClass.elevationPerKm(activity) > config.elevationLimitMPerKm -> SteadyFailure.HILLY
            windowSeconds < config.minSteadyWindowMinutes * 60.0 -> SteadyFailure.WINDOW_SHORT
            cv == null || cv > config.speedCvLimit -> SteadyFailure.SPEED_VARIES
            else -> null
        }
        return SteadyResult(failure, window, windowSeconds, cv, cleaned.coverage)
    }
}

/** #18 heart rate drift and #19 aerobic decoupling over the two halves of a steady window. */
object DriftAnalysis {
    const val MAX_SPEED_RATIO = 1.05
    const val MIN_SPEED_RATIO = 0.95

    data class Result(
        val hr1: Double,
        val hr2: Double,
        val speed1: Double,
        val speed2: Double,
        val driftBpm: Double,
        val driftPct: Double,
        val speedRatio: Double,
        /** Drift is only reported when the speed stayed within 0.95 to 1.05 between the halves. */
        val driftAvailable: Boolean,
        val decouplingPct: Double,
        /** Decoupling on grade-adjusted speed, when the grade stream is plausible. */
        val gapDecouplingPct: Double?,
    )

    fun analyze(window: List<Sample>, hasGradeStream: Boolean): Result? {
        val (h1, h2) = Series.halves(window)
        if (h1.isEmpty() || h2.isEmpty()) return null
        val hr1 = Series.avgHr(h1) ?: return null
        val hr2 = Series.avgHr(h2) ?: return null
        val s1 = Series.avgSpeed(h1) ?: return null
        val s2 = Series.avgSpeed(h2) ?: return null
        if (hr1 <= 0 || s1 <= 0) return null
        val ratio = s2 / s1
        val ef1 = s1 / hr1
        val ef2 = s2 / hr2
        val gap1 = GradeAdjusted.assess(h1, hasGradeStream).gapSpeed
        val gap2 = GradeAdjusted.assess(h2, hasGradeStream).gapSpeed
        val gapDec = if (gap1 != null && gap2 != null && gap1 > 0) {
            val g1 = gap1 / hr1
            val g2 = gap2 / hr2
            (g1 - g2) / g1 * 100
        } else null
        return Result(
            hr1 = hr1, hr2 = hr2, speed1 = s1, speed2 = s2,
            driftBpm = hr2 - hr1, driftPct = (hr2 - hr1) / hr1 * 100,
            speedRatio = ratio,
            driftAvailable = ratio in MIN_SPEED_RATIO..MAX_SPEED_RATIO,
            decouplingPct = (ef1 - ef2) / ef1 * 100,
            gapDecouplingPct = gapDec,
        )
    }
}
