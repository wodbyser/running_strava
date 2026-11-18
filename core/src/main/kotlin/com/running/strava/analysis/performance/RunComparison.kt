package com.running.strava.analysis.performance

import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.TrainingAssessment.RatingLevel
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.SessionType
import kotlin.math.abs
import kotlin.math.floor

/**
 * What the side-by-side comparison of 2 to 6 chosen runs needs to know about one run. Values that do not exist for
 * a run (no stream, not a steady run, not an interval session) stay null; the comparison never invents them.
 */
data class ComparedRun(
    val id: Long,
    val sessionType: SessionType,
    val isTrainer: Boolean,
    val distanceMeters: Double,
    val movingSeconds: Int,
    val avgHr: Double?,
    val terrain: TerrainClass,
    val tempC: Double?,
    /** Grade-adjusted speed of the whole run (m/s). */
    val gapSpeed: Double?,
    /** EF of the steady window (m/s per bpm), only for steady runs. */
    val steadyEf: Double?,
    /** Average HR of that steady window. */
    val steadyEfHr: Double?,
    /** Aerobic decoupling of the steady window, in %. */
    val decouplingPct: Double?,
    /** Length of the steady window in seconds (the decoupling depends on it). */
    val steadySeconds: Double?,
    /** Variation of the 5-minute block speeds after the warm-up, as a fraction. Only meaningful for non-interval runs. */
    val speedCv: Double?,
    val interval: IntervalSummary?,
    val execution: RatingLevel?,
) {
    val speed: Double get() = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0

    /** Metres per heartbeat over the whole run (m/s per bpm), from the Strava summary. */
    val wholeEf: Double? get() = avgHr?.takeIf { it > 0 && speed > 0 }?.let { speed / it }
}

data class IntervalSummary(
    /** "6 x 1000 m"; null for ladders and mixed rep sizes. */
    val signature: String?,
    val repCount: Int,
    /** Mean speed of the reps, distance over time (m/s). */
    val repSpeed: Double,
    /** Variation of the rep speeds, as a fraction; null below 3 reps. */
    val repSpeedCv: Double?,
    /** Last rep slower than the first, in %; null below 3 reps. */
    val fadePct: Double?,
    /** Median heart rate recovery 60 s after the reps, and the recovery protocol; null without measurements. */
    val hrr60: Double?,
    val hrrProtocol: RecoveryProtocol?,
)

/**
 * Side-by-side comparison of a few chosen runs, aspect by aspect. APP HEURISTIC on measured and calculated values.
 *
 * Rules that keep it honest:
 * - There is no total score. Per aspect the best run is named, with the margin in real units.
 * - A difference inside the noise of the measurement is a tie ([CompareAspect.tieAbs] / [CompareAspect.tieRelPct]):
 *   then no run "wins" that aspect.
 * - When the runs differ in something that changes the meaning of an aspect (type, distance, terrain, heat, heart
 *   rate level), the aspect keeps its best run but carries a [Caution], so the page can say "onder voorbehoud".
 * - When an aspect cannot be compared at all (rep speed over different rep structures, heart rate recovery with a
 *   different recovery protocol), there is no best run.
 */
object RunComparison {
    const val MIN_RUNS = 2
    const val MAX_RUNS = 6
    const val MAX_DISTANCE_RATIO = 1.25
    const val MAX_TEMP_DIFF_C = 5.0
    /** Steady EF depends on the intensity: windows whose heart rate differs more than this are compared with a caution. */
    const val STEADY_EF_HR_SPREAD = 10.0
    /** Whole-run EF depends even more on intensity: a smaller spread already needs a caution. */
    const val WHOLE_EF_HR_SPREAD = 5.0

    enum class Better { HIGHER, LOWER }

    /** One aspect with what counts as better and how large a difference must be before it means anything. */
    /**
     * [floor]: for a lower-is-better aspect, every value at or below it is equally good. A negative decoupling or a
     * faster last rep means "no loss"; a more negative number is not a better run (it often means a slow start).
     */
    enum class CompareAspect(val better: Better, val tieAbs: Double?, val tieRelPct: Double?, val inTally: Boolean = true, val floor: Double? = null) {
        /** Average moving speed, m/s. GPS distance is good to about 1 %. */
        SPEED(Better.HIGHER, null, 1.0),
        /** Grade-adjusted speed, m/s. Shown, but not counted separately: it is the same speed, corrected for hills. */
        GAP(Better.HIGHER, null, 1.0, inTally = false),
        /** Metres per heartbeat (m/s per bpm); 3 % is the app's EF band for "stable". */
        EFFICIENCY(Better.HIGHER, null, 3.0),
        /** Decoupling in %, lower = held the efficiency better. */
        DECOUPLING(Better.LOWER, 1.0, null, floor = 0.0),
        /** Speed variation between 5-minute blocks in % (CV x 100), lower = more even. */
        EVENNESS(Better.LOWER, 1.0, null),
        /** Mean rep speed, m/s. */
        REP_SPEED(Better.HIGHER, null, 1.0),
        /** Variation of the rep speeds in % (CV x 100). */
        REP_EVENNESS(Better.LOWER, 1.0, null),
        /** Last rep slower than the first, in %. */
        REP_FADE(Better.LOWER, 1.0, null, floor = 0.0),
        /** Heart rate drop 60 s after the reps, bpm. */
        HRR(Better.HIGHER, 3.0, null),
        /** Execution verdict of the training assessment: 3 sound, 2 mixed, 1 off. */
        EXECUTION(Better.HIGHER, 0.0, null),
    }

    /** Why an aspect is only compared with reservations. */
    enum class Caution { SESSION_TYPE, DISTANCE, TERRAIN, TREADMILL, HEAT, HR_LEVEL, DURATION, REP_STRUCTURE, WHOLE_RUN }

    enum class Status {
        /** One run is clearly best. */
        WINNER,
        /** Several runs are best within the noise, the others are clearly behind. */
        SHARED,
        /** Every run is within the noise of the best. */
        NO_DIFFERENCE,
        /** The values exist but do not mean the same thing (e.g. rep speed over different rep structures). */
        NOT_COMPARABLE,
        /** Fewer than two runs have a value. */
        TOO_FEW,
    }

    data class AspectResult(
        val aspect: CompareAspect,
        /** Values by run id, only runs that have one. */
        val values: Map<Long, Double>,
        val best: Double?,
        /** Runs within the noise of the best; empty unless [status] is WINNER or SHARED. */
        val winners: Set<Long>,
        val status: Status,
        val cautions: List<Caution>,
        /** EFFICIENCY only: true when no steady window was available and the whole run was used instead. */
        val wholeRun: Boolean = false,
    ) {
        val decided: Boolean get() = status == Status.WINNER || status == Status.SHARED
    }

    enum class Check { SAME_TYPE, SIMILAR_DISTANCE, SAME_TERRAIN, SAME_SURFACE, SIMILAR_TEMPERATURE, ALL_HR }

    enum class Level { GOOD, PARTLY, HARD }

    /** Checks are true (same), false (differs) or null (unknown, e.g. no temperature). */
    data class Comparability(val checks: Map<Check, Boolean?>, val level: Level)

    data class Result(
        val aspects: List<AspectResult>,
        val comparability: Comparability,
        /** Aspects won outright (single winner) per run, counted over the aspects that are in the tally. */
        val wins: Map<Long, Int>,
        /** Aspects where the run shared the first place. */
        val shared: Map<Long, Int>,
        /** Aspects in the tally that had at least two values and were comparable. */
        val compared: Int,
        /**
         * The run that was most often among the best (outright or shared); on a tie, the one with strictly the most
         * outright wins. Null when nobody stands out.
         */
        val leader: Long?,
    ) {
        fun firsts(id: Long): Int = (wins[id] ?: 0) + (shared[id] ?: 0)

        fun aspect(a: CompareAspect): AspectResult = aspects.first { it.aspect == a }
    }

    fun compare(runs: List<ComparedRun>): Result {
        require(runs.isNotEmpty())
        val comparability = comparability(runs)
        val aspects = buildList {
            add(speed(runs, comparability))
            add(gap(runs))
            add(efficiency(runs))
            add(decoupling(runs))
            add(evenness(runs))
            add(repSpeed(runs))
            add(repEvenness(runs))
            add(repFade(runs))
            add(hrr(runs))
            add(execution(runs))
        }
        val tally = aspects.filter { it.aspect.inTally }
        val wins = runs.associate { r -> r.id to tally.count { it.status == Status.WINNER && r.id in it.winners } }
        val shared = runs.associate { r -> r.id to tally.count { it.status == Status.SHARED && r.id in it.winners } }
        val compared = tally.count { it.status != Status.TOO_FEW && it.status != Status.NOT_COMPARABLE }
        val firsts = runs.associate { it.id to (wins[it.id] ?: 0) + (shared[it.id] ?: 0) }
        val top = firsts.values.maxOrNull() ?: 0
        val tied = firsts.filterValues { it == top }.keys
        val leader = when {
            top == 0 -> null
            tied.size == 1 -> tied.single()
            else -> {
                val bestWins = tied.maxOf { wins[it] ?: 0 }
                tied.filter { (wins[it] ?: 0) == bestWins }.singleOrNull()?.takeIf { bestWins > 0 }
            }
        }
        return Result(aspects, comparability, wins, shared, compared, leader)
    }

    // ---------------------------------------------------------------- comparability

    fun comparability(runs: List<ComparedRun>): Comparability {
        val distances = runs.map { it.distanceMeters }.filter { it > 0 }
        val temps = runs.mapNotNull { it.tempC }
        val checks = linkedMapOf<Check, Boolean?>(
            Check.SAME_TYPE to (runs.map { it.sessionType }.distinct().size == 1),
            Check.SIMILAR_DISTANCE to (distances.size == runs.size && distances.max() / distances.min() <= MAX_DISTANCE_RATIO),
            Check.SAME_TERRAIN to (runs.map { it.terrain }.distinct().size == 1),
            Check.SAME_SURFACE to (runs.map { it.isTrainer }.distinct().size == 1),
            Check.SIMILAR_TEMPERATURE to if (temps.size < 2) null else temps.max() - temps.min() <= MAX_TEMP_DIFF_C,
            Check.ALL_HR to runs.all { it.avgHr != null },
        )
        val level = when {
            checks[Check.SAME_TYPE] == false || checks[Check.SAME_SURFACE] == false -> Level.HARD
            checks.values.all { it != false } -> Level.GOOD
            else -> Level.PARTLY
        }
        return Comparability(checks, level)
    }

    // ---------------------------------------------------------------- aspects

    private fun speed(runs: List<ComparedRun>, c: Comparability): AspectResult {
        val with = runs.filter { it.speed > 0 }
        val cautions = buildList {
            if (c.checks[Check.SAME_TYPE] == false) add(Caution.SESSION_TYPE)
            if (c.checks[Check.SIMILAR_DISTANCE] == false) add(Caution.DISTANCE)
            if (c.checks[Check.SAME_TERRAIN] == false) add(Caution.TERRAIN)
            if (c.checks[Check.SAME_SURFACE] == false) add(Caution.TREADMILL)
            if (c.checks[Check.SIMILAR_TEMPERATURE] == false) add(Caution.HEAT)
        }
        return decide(CompareAspect.SPEED, with.associate { it.id to it.speed }, cautions)
    }

    private fun gap(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.gapSpeed != null }
        val cautions = buildList {
            if (with.map { it.sessionType }.distinct().size > 1) add(Caution.SESSION_TYPE)
            if (!similarDistance(with)) add(Caution.DISTANCE)
            if (tempDiffers(with)) add(Caution.HEAT)
        }
        return decide(CompareAspect.GAP, with.associate { it.id to it.gapSpeed!! }, cautions)
    }

    /** Steady-window EF when at least two runs have one; otherwise the whole run, with a caution. */
    private fun efficiency(runs: List<ComparedRun>): AspectResult {
        val steady = runs.filter { it.steadyEf != null }
        if (steady.size >= MIN_RUNS) {
            val cautions = buildList {
                if (spread(steady.mapNotNull { it.steadyEfHr }) > STEADY_EF_HR_SPREAD) add(Caution.HR_LEVEL)
                if (steady.map { it.terrain }.distinct().size > 1) add(Caution.TERRAIN)
                if (tempDiffers(steady)) add(Caution.HEAT)
            }
            return decide(CompareAspect.EFFICIENCY, steady.associate { it.id to it.steadyEf!! }, cautions)
        }
        val whole = runs.filter { it.wholeEf != null }
        val cautions = buildList {
            add(Caution.WHOLE_RUN)
            if (whole.map { it.sessionType }.distinct().size > 1) add(Caution.SESSION_TYPE)
            if (spread(whole.mapNotNull { it.avgHr }) > WHOLE_EF_HR_SPREAD) add(Caution.HR_LEVEL)
            if (whole.map { it.terrain }.distinct().size > 1) add(Caution.TERRAIN)
            if (whole.map { it.isTrainer }.distinct().size > 1) add(Caution.TREADMILL)
            if (tempDiffers(whole)) add(Caution.HEAT)
        }
        return decide(CompareAspect.EFFICIENCY, whole.associate { it.id to it.wholeEf!! }, cautions).copy(wholeRun = true)
    }

    private fun decoupling(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.decouplingPct != null }
        val windows = with.mapNotNull { it.steadySeconds }.filter { it > 0 }
        val cautions = buildList {
            if (windows.size >= 2 && windows.max() / windows.min() > MAX_DISTANCE_RATIO) add(Caution.DURATION)
            if (tempDiffers(with)) add(Caution.HEAT)
        }
        return decide(CompareAspect.DECOUPLING, with.associate { it.id to it.decouplingPct!! }, cautions)
    }

    private fun evenness(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.speedCv != null && it.sessionType != SessionType.INTERVAL }
        val cautions = buildList {
            if (with.map { it.sessionType }.distinct().size > 1) add(Caution.SESSION_TYPE)
            if (with.map { it.terrain }.distinct().size > 1) add(Caution.TERRAIN)
        }
        return decide(CompareAspect.EVENNESS, with.associate { it.id to it.speedCv!! * 100 }, cautions)
    }

    private fun sameStructure(with: List<ComparedRun>): Boolean {
        val signatures = with.map { it.interval?.signature }
        return signatures.none { it == null } && signatures.distinct().size == 1
    }

    private fun repSpeed(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.interval != null && it.interval.repSpeed > 0 }
        val values = with.associate { it.id to it.interval!!.repSpeed }
        // Shorter reps are run faster: comparing rep speed over different structures says nothing about the session.
        if (with.size >= MIN_RUNS && !sameStructure(with)) {
            return AspectResult(CompareAspect.REP_SPEED, values, null, emptySet(), Status.NOT_COMPARABLE, listOf(Caution.REP_STRUCTURE))
        }
        return decide(CompareAspect.REP_SPEED, values, if (tempDiffers(with)) listOf(Caution.HEAT) else emptyList())
    }

    private fun repEvenness(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.interval?.repSpeedCv != null }
        val cautions = if (with.size >= MIN_RUNS && !sameStructure(with)) listOf(Caution.REP_STRUCTURE) else emptyList()
        return decide(CompareAspect.REP_EVENNESS, with.associate { it.id to it.interval!!.repSpeedCv!! * 100 }, cautions)
    }

    private fun repFade(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.interval?.fadePct != null }
        val cautions = if (with.size >= MIN_RUNS && !sameStructure(with)) listOf(Caution.REP_STRUCTURE) else emptyList()
        return decide(CompareAspect.REP_FADE, with.associate { it.id to it.interval!!.fadePct!! }, cautions)
    }

    private fun hrr(runs: List<ComparedRun>): AspectResult {
        val with = runs.filter { it.interval?.hrr60 != null && it.interval.hrrProtocol != null }
        val values = with.associate { it.id to it.interval!!.hrr60!! }
        // Standing, walking and jogging recover at a different rate: never mixed (same rule as the Prestaties page).
        if (with.size >= MIN_RUNS && with.map { it.interval!!.hrrProtocol }.distinct().size > 1) {
            return AspectResult(CompareAspect.HRR, values, null, emptySet(), Status.NOT_COMPARABLE, emptyList())
        }
        return decide(CompareAspect.HRR, values, if (with.size >= MIN_RUNS && !sameStructure(with)) listOf(Caution.REP_STRUCTURE) else emptyList())
    }

    private fun execution(runs: List<ComparedRun>): AspectResult {
        val values = runs.mapNotNull { r ->
            when (r.execution) {
                RatingLevel.SOUND -> r.id to 3.0
                RatingLevel.MIXED -> r.id to 2.0
                RatingLevel.OFF -> r.id to 1.0
                RatingLevel.INSUFFICIENT, null -> null
            }
        }.toMap()
        val judged = runs.filter { it.id in values }
        // Each run is judged against its own kind of session, so different kinds are comparable with a caution.
        val cautions = if (judged.map { it.sessionType }.distinct().size > 1) listOf(Caution.SESSION_TYPE) else emptyList()
        return decide(CompareAspect.EXECUTION, values, cautions)
    }

    // ---------------------------------------------------------------- helpers

    /** The value used for ranking: lower-is-better values below the [CompareAspect.floor] count as the floor. */
    fun effective(aspect: CompareAspect, value: Double): Double = aspect.floor?.let { maxOf(value, it) } ?: value

    /** True when [value] is within the noise of [best] for this aspect. */
    fun tied(aspect: CompareAspect, value: Double, best: Double): Boolean {
        aspect.tieAbs?.let { return abs(effective(aspect, value) - effective(aspect, best)) <= it + 1e-9 }
        val rel = aspect.tieRelPct ?: return value == best
        return best != 0.0 && abs(value - best) / abs(best) * 100 <= rel + 1e-9
    }

    private fun decide(aspect: CompareAspect, values: Map<Long, Double>, cautions: List<Caution>): AspectResult {
        if (values.size < MIN_RUNS) {
            return AspectResult(aspect, values, values.values.firstOrNull(), emptySet(), Status.TOO_FEW, emptyList())
        }
        val best = if (aspect.better == Better.HIGHER) values.values.max() else values.values.min()
        val winners = values.filterValues { tied(aspect, it, best) }.keys
        val status = when {
            winners.size == values.size -> Status.NO_DIFFERENCE
            winners.size == 1 -> Status.WINNER
            else -> Status.SHARED
        }
        return AspectResult(aspect, values, best, if (status == Status.NO_DIFFERENCE) emptySet() else winners, status, cautions)
    }

    private fun spread(values: List<Double>) = if (values.size < 2) 0.0 else values.max() - values.min()

    private fun similarDistance(runs: List<ComparedRun>): Boolean {
        val d = runs.map { it.distanceMeters }.filter { it > 0 }
        return d.size < 2 || d.max() / d.min() <= MAX_DISTANCE_RATIO
    }

    private fun tempDiffers(runs: List<ComparedRun>): Boolean = spread(runs.mapNotNull { it.tempC }) > MAX_TEMP_DIFF_C
}

/**
 * Pace and heart rate per distance block, read straight from the Strava stream, so several runs can be drawn on
 * one distance axis. Pauses, gaps and GPS jumps are left out (same limits as [CleanRun]); a block with too little
 * moving distance or heart rate time gets no value instead of a guess.
 */
object RunCurves {
    data class Point(val km: Double, val paceSecondsPerKm: Double?, val hr: Double?)

    /** One block size for all runs on a chart: about 40 to 80 points over the longest run. */
    fun blockMeters(longestMeters: Double): Double = when {
        longestMeters <= 8_000 -> 200.0
        longestMeters <= 12_000 -> 250.0
        longestMeters <= 25_000 -> 500.0
        else -> 1_000.0
    }

    fun byDistance(stream: ActivityStream, blockMeters: Double): List<Point> {
        val time = stream.time ?: return emptyList()
        val dist = stream.distance ?: return emptyList()
        val hr = stream.heartrate
        val n = minOf(time.size, dist.size)
        if (n < 2 || blockMeters <= 0) return emptyList()
        val blocks = (floor(dist[n - 1] / blockMeters).toInt() + 1).coerceAtLeast(1)
        val seconds = DoubleArray(blocks)
        val metres = DoubleArray(blocks)
        val hrSeconds = DoubleArray(blocks)
        val hrSum = DoubleArray(blocks)
        for (k in 1 until n) {
            val dt = (time[k] - time[k - 1]).toDouble()
            val dd = (dist[k] - dist[k - 1]).toDouble()
            if (dt <= 0 || dt > CleanRun.MAX_GAP_SECONDS || dd < 0) continue
            val v = dd / dt
            if (v < CleanRun.MIN_SPEED_MS || v > BestEffortFinder.MAX_SAMPLE_SPEED_MS) continue
            val b = floor((dist[k - 1] + dd / 2) / blockMeters).toInt().coerceIn(0, blocks - 1)
            seconds[b] += dt
            metres[b] += dd
            val h = hr?.getOrNull(k)
            if (h != null && h >= CleanRun.MIN_HR && h <= CleanRun.MAX_HR) {
                hrSeconds[b] += dt
                hrSum[b] += h * dt
            }
        }
        return (0 until blocks).mapNotNull { b ->
            if (metres[b] < blockMeters * 0.5) return@mapNotNull null
            val pace = seconds[b] / (metres[b] / 1000)
            val avgHr = if (hrSeconds[b] >= seconds[b] * 0.5) hrSum[b] / hrSeconds[b] else null
            Point((b + 1) * blockMeters / 1000, pace, avgHr)
        }
    }
}
