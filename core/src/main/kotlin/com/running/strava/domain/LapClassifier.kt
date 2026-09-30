package com.running.strava.domain

import kotlin.math.max
import kotlin.math.min

/** Role of a single lap inside a session. All roles are APP HEURISTICS, not measurements. */
enum class LapKind {
    /** Hard interval rep (part of >= 2 reps separated by recovery or float laps). */
    REP,

    /** Moderate "float" lap between reps: clearly faster than easy running, clearly slower than the reps. */
    FLOAT,

    /** Sustained faster-than-easy running that is not an interval rep (tempo block, progression, kick). */
    TEMPO,

    /** Easy running: warm-up, cool-down, jog/walk recovery, normal easy laps. */
    EASY,

    /** Accidental lap-button press or implausible GPS lap. Ignored for all statistics. */
    NOISE,
}

/** What kind of session an activity was, as far as the app can tell from laps + Strava `workout_type`. */
enum class SessionType {
    /** Marked as race on Strava (`workout_type` = 1). */
    RACE,

    /** >= 2 interval reps of at least [LapClassifier.MIN_REP_SECONDS] s. */
    INTERVAL,

    /** Contains a tempo/progression block (or strides combined with tempo) but no real interval reps. */
    TEMPO,

    /** Easy running with only short strides (< [LapClassifier.MIN_REP_SECONDS] s). */
    STRIDES,

    /** Nothing detected: easy / steady running, or not enough laps to tell. */
    STEADY,
}

/**
 * Lap-role detection from lap-level average speed (and, for GPS-glitch detection only, lap HR).
 *
 * HEURISTIC / UNVALIDATED. All thresholds below were chosen by hand and checked against the golden dataset
 * in `LapClassifierGoldenTest`, not against labelled ground truth. They must be communicated as
 * "automatisch herkend".
 *
 * Algorithm:
 * 1. NOISE: laps < 10 s or < 45 m (accidental lap press) or faster than 8 m/s (impossible lap average).
 * 2. Easy reference speed E: lower quartile of the speeds of "substantial" laps (>= 4 min) outside the
 *    fast cluster (falling back to all substantial laps, then the slow 2-means centre). Short recovery jogs
 *    are excluded so they don't drag E down.
 * 3. Rep candidates: 1-D 2-means on lap speed; the fast cluster counts only if its centre is >= 1.15x the
 *    slow centre. If the fast cluster itself splits clearly (>= 1.15x) and its slower half is >= 1.15x E,
 *    that slower half are float candidates.
 * 4. Laps of the slow cluster that lie between rep candidates and are >= 1.15x E are float candidates too
 *    (fast/float alternation after a short warm-up).
 * 5. A first/last rep candidate whose size (distance AND duration) differs by more than 1.6x from every
 *    other candidate is a warm-up/cool-down, not a rep (only when >= 3 candidates).
 * 6. Rep candidates only become REP if there are >= 2 *separate* runs of them (separated by non-rep laps)
 *    and on average <= 2 laps per run. A single contiguous fast block (progression, tempo, finishing kick)
 *    or long fast stretches split by one slow lap (easy run with a hill) are not interval sessions.
 * 7. An isolated single fast lap >= 1.4x E whose HR is not above the easy laps' median HR + 3 bpm is a
 *    GPS glitch → NOISE (only when HR is available).
 * 8. Every remaining lap >= 1.15x E is TEMPO, provided (when HR is available) its HR is >= 5 bpm above the
 *    median HR of the easy laps; everything else EASY. A fast lap directly next to such an HR-confirmed TEMPO lap is
 *    TEMPO too (HR lag at the start/end of a block); this is not transitive.
 * For Strava races (`workout_type` = 1) no reps/floats are assigned.
 * For Strava long runs (`workout_type` = 2) reps need HR confirmation (rep HR >= other laps + 8 bpm) and, without
 * HR, faster laps are never TEMPO (downhill / tailwind km in a long run).
 * 9. HR-assisted fallback when step 3 finds nothing (not for races/long runs): speed ratio >= 1.08 is enough if
 *    the fast laps' median HR is >= 8 bpm above the other laps (hill reps with little speed contrast); without
 *    HR only when the activity is marked as workout on Strava (`workout_type` = 3).
 * 10. Contiguous REP laps form one rep effort ([repEfforts]): auto-lapped 3x 2 km is 3 reps, not 6. A standing
 *     rest lap ([isRestBreak]: degenerate but >= [REST_BREAK_SECONDS] on the clock) separates reps, both for
 *     rep detection and for merging; a short stub lap (double-press) does not.
 */
object LapClassifier {

    const val MIN_SPEED_RATIO = 1.15
    const val MIN_VALID_DURATION_SECONDS = 10
    const val MIN_VALID_DISTANCE_METERS = 45.0
    const val MAX_PLAUSIBLE_LAP_SPEED_MS = 8.0
    const val SUBSTANTIAL_LAP_SECONDS = 240
    const val EDGE_SIZE_FACTOR = 1.6
    const val GLITCH_SPEED_RATIO = 1.4
    const val GLITCH_HR_MARGIN = 3.0
    const val MAX_MEAN_LAPS_PER_REP = 2.0
    const val TEMPO_HR_MARGIN = 5.0

    /** HR-assisted detection: minimum speed ratio between fast and slow laps (instead of [MIN_SPEED_RATIO]). */
    const val ASSISTED_SPEED_RATIO = 1.08

    /** HR-assisted detection / long-run confirmation: rep HR must be this many bpm above the other laps. */
    const val HR_CONFIRM_MARGIN = 8.0

    /** Minimum clock time for a degenerate lap to count as a standing rest ([isRestBreak]). */
    const val REST_BREAK_SECONDS = 15

    /** Reps shorter than this are strides, not interval reps, for session typing. */
    const val MIN_REP_SECONDS = 30

    private const val RACE = 1
    private const val LONG_RUN = 2
    private const val WORKOUT = 3

    /**
     * A degenerate lap that lasted [REST_BREAK_SECONDS]+ on the clock: a standing (or walking-on-the-spot) rest
     * between reps, recorded with the lap button. Unlike a sub-second double-press or GPS stub, it SEPARATES reps.
     */
    fun isRestBreak(lap: Lap): Boolean = isDegenerate(lap) && lap.elapsedTime >= REST_BREAK_SECONDS

    fun isDegenerate(lap: Lap): Boolean =
        lap.averageSpeed <= 0f ||
            lap.movingTime < MIN_VALID_DURATION_SECONDS ||
            lap.distance < MIN_VALID_DISTANCE_METERS ||
            lap.averageSpeed > MAX_PLAUSIBLE_LAP_SPEED_MS

    /** Classifies every lap into a [LapKind], parallel to the input list. */
    fun classify(laps: List<Lap>, workoutType: Int? = null): List<LapKind> {
        val result = Array(laps.size) { i -> if (isDegenerate(laps[i])) LapKind.NOISE else LapKind.EASY }
        val valid = laps.indices.filter { result[it] != LapKind.NOISE }
        if (valid.size < 2) return result.toList()
        val speed = { i: Int -> laps[i].averageSpeed.toDouble() }

        val split = twoMeans(valid.map(speed))
        val fast = if (split != null) valid.filter { speed(it) >= split.midpoint } else emptyList()
        val easyRef = easyReference(laps, valid, fast.toSet(), split?.low)
        val isRace = workoutType == RACE

        val isLongRun = workoutType == LONG_RUN
        var repCand: Set<Int> = emptySet()
        var floatCand: Set<Int> = emptySet()
        if (split != null && !isRace) {
            findReps(laps, valid, split, easyRef)?.let { (r, f) ->
                // Strava "long run": laps that are merely faster (downhill, tailwind) are not reps unless heart rate
                // confirms a real effort. Without HR the app cannot tell, so it keeps the user's label.
                if (!isLongRun || hrConfirmsReps(laps, valid, r)) {
                    repCand = r
                    floatCand = f
                }
            }
        }
        // Secondary, HR-assisted detection for low speed contrast (e.g. hill reps 5:25 up / 6:00 down): a smaller
        // speed ratio is accepted when the fast laps also have clearly higher HR, or (without HR) when the runner
        // marked the activity as a workout on Strava.
        if (repCand.isEmpty() && !isRace && !isLongRun && valid.size >= 4) {
            val weak = twoMeans(valid.map(speed), ASSISTED_SPEED_RATIO)
            if (weak != null) {
                val weakFast = valid.filter { speed(it) >= weak.midpoint }.toSet()
                val weakEasy = easyReference(laps, valid, weakFast, weak.low)
                findReps(laps, valid, weak, weakEasy, allowSplitFloats = false)?.let { (r, f) ->
                    val repsHaveHr = r.count { laps[it].averageHeartrate != null } >= 2
                    val accepted = if (repsHaveHr) hrConfirmsReps(laps, valid, r) else workoutType == WORKOUT
                    if (accepted) {
                        repCand = r
                        floatCand = f
                    }
                }
            }
        }

        repCand.forEach { result[it] = LapKind.REP }
        floatCand.forEach { result[it] = LapKind.FLOAT }

        // GPS glitch: an isolated single fast lap without any heart-rate response.
        val easyHr = valid.filter { it !in repCand && it !in floatCand && speed(it) < easyRef * MIN_SPEED_RATIO }
            .mapNotNull { laps[it].averageHeartrate?.toDouble() }
            .sorted()
        val medianEasyHr = if (easyHr.isNotEmpty()) easyHr[easyHr.size / 2] else null
        for ((pos, i) in valid.withIndex()) {
            if (result[i] != LapKind.EASY) continue
            if (speed(i) < easyRef * GLITCH_SPEED_RATIO) continue
            val prevFast = valid.getOrNull(pos - 1)?.let { speed(it) >= easyRef * MIN_SPEED_RATIO } ?: false
            val nextFast = valid.getOrNull(pos + 1)?.let { speed(it) >= easyRef * MIN_SPEED_RATIO } ?: false
            val hr = laps[i].averageHeartrate?.toDouble()
            if (!prevFast && !nextFast && hr != null && medianEasyHr != null && hr <= medianEasyHr + GLITCH_HR_MARGIN) {
                result[i] = LapKind.NOISE
            }
        }

        // TEMPO needs a heart-rate response when HR is available: a lap that is only "fast" relative to slow
        // uphill laps, without higher HR, is normal running on varied terrain.
        for (i in valid) {
            if (result[i] != LapKind.EASY || speed(i) < easyRef * MIN_SPEED_RATIO) continue
            val hr = laps[i].averageHeartrate?.toDouble()
            // Strava "long run" without HR: a faster lap is most likely terrain, not a tempo block.
            if (hr == null && isLongRun) continue
            if (hr != null && medianEasyHr != null && hr < medianEasyHr + TEMPO_HR_MARGIN) continue
            result[i] = LapKind.TEMPO
        }

        // Heart-rate lag: HR needs 1-3 min to rise at the start of a tempo block, so the first (or last) fast lap of a
        // block can fail the HR check although it is part of the same effort. A fast lap directly adjacent to an
        // HR-confirmed TEMPO lap is TEMPO as well. Deliberately NON-transitive (only neighbours of laps that passed
        // the HR check themselves), so one HR spike cannot turn a whole fast-but-easy stretch into tempo.
        val hrConfirmedTempo = valid.filter { result[it] == LapKind.TEMPO }.toSet()
        for ((pos, i) in valid.withIndex()) {
            if (result[i] != LapKind.EASY || speed(i) < easyRef * MIN_SPEED_RATIO) continue
            if (laps[i].averageHeartrate == null) continue
            val prev = valid.getOrNull(pos - 1)
            val next = valid.getOrNull(pos + 1)
            if (prev in hrConfirmedTempo || next in hrConfirmedTempo) result[i] = LapKind.TEMPO
        }
        return result.toList()
    }

    /** Session type from laps and Strava `workout_type`. */
    fun sessionType(laps: List<Lap>, workoutType: Int? = null): SessionType = SessionClassifier.classify(laps, workoutType)

    private fun nextValid(valid: List<Int>, idx: Int): Int? = valid.firstOrNull { it > idx }

    /**
     * Rep/float candidates for a speed split. Option A: the fast cluster splits again into reps + floats.
     * Option B: every fast lap is a rep. A is only kept when it yields a valid interval pattern (e.g. one stray
     * sprint must not turn all real reps into "floats"). Returns null if no option is a valid interval pattern.
     */
    private fun findReps(
        laps: List<Lap>,
        valid: List<Int>,
        split: Split,
        easyRef: Double,
        allowSplitFloats: Boolean = true,
    ): Pair<Set<Int>, Set<Int>>? {
        val speed = { i: Int -> laps[i].averageSpeed.toDouble() }
        val fast = valid.filter { speed(it) >= split.midpoint }
        val inner = if (allowSplitFloats && fast.size >= 2) twoMeans(fast.map(speed)) else null
        val options = mutableListOf<Pair<Set<Int>, Set<Int>>>()
        if (inner != null && inner.low >= easyRef * MIN_SPEED_RATIO) {
            options += fast.filter { speed(it) >= inner.midpoint }.toSet() to fast.filter { speed(it) < inner.midpoint }.toSet()
        }
        options += fast.toSet() to emptySet()
        for ((reps, floats) in options) {
            val r = reps.toMutableSet()
            val f = floats.toMutableSet()
            completeCandidates(r, f, laps, valid, easyRef, split.midpoint)
            if (isValidIntervalPattern(r, valid, laps)) return r to f
        }
        return null
    }

    /** True if >= 2 reps have HR and their median HR is >= [HR_CONFIRM_MARGIN] bpm above the median HR of the
     * other valid laps (that have HR). */
    private fun hrConfirmsReps(laps: List<Lap>, valid: List<Int>, reps: Set<Int>): Boolean {
        val repHr = reps.mapNotNull { laps[it].averageHeartrate?.toDouble() }.sorted()
        val otherHr = valid.filter { it !in reps }.mapNotNull { laps[it].averageHeartrate?.toDouble() }.sorted()
        if (repHr.size < 2 || otherHr.isEmpty()) return false
        return repHr[repHr.size / 2] >= otherHr[otherHr.size / 2] + HR_CONFIRM_MARGIN
    }

    private fun completeCandidates(
        repCand: MutableSet<Int>,
        floatCand: MutableSet<Int>,
        laps: List<Lap>,
        valid: List<Int>,
        easyRef: Double,
        fastMidpoint: Double,
    ) {
        val speed = { i: Int -> laps[i].averageSpeed.toDouble() }
        demoteDissimilarEdges(repCand, laps)
        if (repCand.isEmpty()) return
        val first = repCand.min()
        val last = repCand.max()
        valid.filter { it in first..last && it !in repCand && speed(it) >= easyRef * MIN_SPEED_RATIO }
            .forEach { floatCand += it }
        // Floats must sit between (or directly after) reps; drop any that ended up elsewhere.
        val afterLast = nextValid(valid, last)
        floatCand.retainAll { it in first..(afterLast ?: last) }
        // A fast/float alternation ends on a float: the lap right after the last rep belongs to the set.
        if (floatCand.isNotEmpty() && afterLast != null && speed(afterLast) >= easyRef * MIN_SPEED_RATIO &&
            speed(afterLast) < fastMidpoint
        ) {
            floatCand += afterLast
        }
    }

    /** Real intervals are mostly single-lap reps separated by recovery: >= 2 separate runs of candidates with on
     * average <= [MAX_MEAN_LAPS_PER_REP] laps each. Long contiguous fast stretches (progression, tempo, an easy
     * run split by one slow hill lap, cruise-tempo blocks) are not interval sessions. */
    private fun isValidIntervalPattern(repCand: Set<Int>, valid: List<Int>, laps: List<Lap>): Boolean {
        val runs = countRuns(repCand, valid, laps)
        return runs >= 2 && repCand.size.toDouble() / runs <= MAX_MEAN_LAPS_PER_REP
    }

    /** Number of separate runs of candidates, separated by non-candidate valid laps or by standing rests
     * ([isRestBreak]); other degenerate laps (double-press, GPS stubs) are ignored. */
    private fun countRuns(cand: Set<Int>, valid: List<Int>, laps: List<Lap>): Int {
        var runs = 0
        var inRun = false
        val validSet = valid.toSet()
        for (i in laps.indices) {
            if (i !in validSet) {
                if (isRestBreak(laps[i])) inRun = false
                continue
            }
            if (i in cand) {
                if (!inRun) runs++
                inRun = true
            } else {
                inRun = false
            }
        }
        return runs
    }

    private fun demoteDissimilarEdges(cand: MutableSet<Int>, laps: List<Lap>) {
        while (cand.size >= 3) {
            val first = cand.min()
            val last = cand.max()
            val toDrop = listOf(first, last).firstOrNull { edge -> cand.filter { it != edge }.none { similar(laps[edge], laps[it]) } }
                ?: return
            cand.remove(toDrop)
        }
    }

    private fun similar(a: Lap, b: Lap): Boolean =
        ratio(a.distance.toDouble(), b.distance.toDouble()) <= EDGE_SIZE_FACTOR ||
            ratio(a.movingTime.toDouble(), b.movingTime.toDouble()) <= EDGE_SIZE_FACTOR

    private fun ratio(a: Double, b: Double): Double = if (a <= 0 || b <= 0) Double.MAX_VALUE else max(a, b) / min(a, b)

    /** Easy reference speed: lower quartile of substantial (>= 4 min) laps outside the fast cluster; if there are
     * fewer than two of those, of all substantial laps; otherwise the slow 2-means centre. */
    private fun easyReference(laps: List<Lap>, valid: List<Int>, fast: Set<Int>, slowCenter: Double?): Double {
        fun lowerQuartile(idx: List<Int>): Double? {
            val s = idx.filter { laps[it].movingTime >= SUBSTANTIAL_LAP_SECONDS }.map { laps[it].averageSpeed.toDouble() }.sorted()
            return if (s.size >= 2) s[((s.size - 1) * 0.25).toInt()] else null
        }
        lowerQuartile(valid.filter { it !in fast })?.let { return it }
        lowerQuartile(valid)?.let { return it }
        if (slowCenter != null) return slowCenter
        val all = valid.map { laps[it].averageSpeed.toDouble() }.sorted()
        return all[all.size / 2]
    }

    private data class Split(val low: Double, val high: Double) {
        val midpoint get() = (low + high) / 2
    }

    /** 1-D 2-means; null if the two centres differ by less than [minRatio]. */
    private fun twoMeans(speeds: List<Double>, minRatio: Double = MIN_SPEED_RATIO): Split? {
        if (speeds.size < 2) return null
        var low = speeds.min()
        var high = speeds.max()
        if (low <= 0 || high - low < 1e-6) return null
        repeat(20) {
            val lowGroup = speeds.filter { kotlin.math.abs(it - low) <= kotlin.math.abs(it - high) }
            val highGroup = speeds.filter { kotlin.math.abs(it - low) > kotlin.math.abs(it - high) }
            if (lowGroup.isNotEmpty()) low = lowGroup.average()
            if (highGroup.isNotEmpty()) high = highGroup.average()
        }
        return if (high / low >= minRatio) Split(low, high) else null
    }

    /** True where the lap is a hard interval rep. */
    fun classifyIntervals(laps: List<Lap>, workoutType: Int? = null): List<Boolean> =
        classify(laps, workoutType).map { it == LapKind.REP }

    fun hasIntervals(laps: List<Lap>, workoutType: Int? = null): Boolean =
        SessionClassifier.classify(laps, workoutType) == SessionType.INTERVAL

    /** One interval effort: the lap index range it spans and the (merged) lap. */
    data class RepEffort(val first: Int, val last: Int, val lap: Lap)

    /**
     * Interval efforts: contiguous REP laps (only short NOISE stubs in between, no [isRestBreak]) are ONE rep, e.g. a 2 km rep that the
     * watch auto-lapped into 2x 1 km. Rep counts and rep statistics must use these, not raw laps.
     */
    fun repEfforts(laps: List<Lap>, kinds: List<LapKind>): List<RepEffort> {
        val result = mutableListOf<RepEffort>()
        var start = -1
        var members = mutableListOf<Int>()
        fun flush() {
            if (members.isNotEmpty()) result += RepEffort(start, members.last(), mergeLaps(members.map { laps[it] }))
            members = mutableListOf()
        }
        for (i in laps.indices) {
            when (kinds[i]) {
                LapKind.REP -> { if (members.isEmpty()) start = i; members += i }
                // A standing rest ends the rep; a short stub (double-press, GPS glitch) inside a rep does not.
                LapKind.NOISE -> if (isRestBreak(laps[i])) flush()
                else -> flush()
            }
        }
        flush()
        return result
    }

    /** Rep efforts of at least [MIN_REP_SECONDS] (strides excluded), as merged laps. */
    fun realReps(laps: List<Lap>, workoutType: Int?): List<Lap> =
        repEfforts(laps, classify(laps, workoutType)).map { it.lap }.filter { it.movingTime >= MIN_REP_SECONDS }

    /** Merges consecutive laps into one: sums distance/time, time-weighted HR and cadence, max of maxima. */
    fun mergeLaps(ls: List<Lap>): Lap {
        if (ls.size == 1) return ls.first()
        val t = ls.sumOf { it.movingTime }
        val d = ls.sumOf { it.distance.toDouble() }
        fun weighted(v: (Lap) -> Float?): Float? {
            val w = ls.filter { v(it) != null && it.movingTime > 0 }
            val wt = w.sumOf { it.movingTime }
            return if (wt > 0) (w.sumOf { v(it)!!.toDouble() * it.movingTime } / wt).toFloat() else null
        }
        return ls.first().copy(
            elapsedTime = ls.sumOf { it.elapsedTime },
            movingTime = t,
            endIndex = ls.last().endIndex,
            distance = d.toFloat(),
            averageSpeed = if (t > 0) (d / t).toFloat() else 0f,
            maxSpeed = ls.maxOf { it.maxSpeed },
            averageHeartrate = weighted { it.averageHeartrate },
            maxHeartrate = ls.mapNotNull { it.maxHeartrate }.maxOrNull(),
            averageCadence = weighted { it.averageCadence },
        )
    }
}

object SessionClassifier {
    fun classify(laps: List<Lap>, workoutType: Int?): SessionType {
        if (workoutType == 1) return SessionType.RACE
        if (laps.size < 2) return SessionType.STEADY
        val kinds = LapClassifier.classify(laps, workoutType)
        val reps = LapClassifier.repEfforts(laps, kinds)
        val realReps = reps.count { it.lap.movingTime >= LapClassifier.MIN_REP_SECONDS }
        val hasTempo = kinds.any { it == LapKind.TEMPO }
        return when {
            realReps >= 2 -> SessionType.INTERVAL
            hasTempo -> SessionType.TEMPO
            reps.isNotEmpty() -> SessionType.STRIDES
            else -> SessionType.STEADY
        }
    }
}
