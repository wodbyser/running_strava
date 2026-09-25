package com.running.strava.domain

/** Role of a single lap inside a session. */
enum class LapKind {
    /** Hard interval rep. */
    REP,

    /** Moderate "float"/steady lap between reps: clearly faster than easy jogging, clearly slower than the reps
     * (e.g. 8x 400m fast alternating with 400m moderate). */
    FLOAT,

    /** Easy running: warm-up, cool-down or jog/walk recovery. */
    EASY,

    /** Accidental lap-button press (near-zero distance/duration). Ignored for all statistics. */
    NOISE,
}

/**
 * Heuristics to detect which laps of an activity correspond to hard interval reps, moderate floats, or
 * easy/recovery running, purely from lap-level average speed (no external config needed).
 *
 * Step 1: a 1-D 2-means clustering on lap speed separates "fast" from "slow" laps regardless of how many of
 * each there are. If the two groups aren't meaningfully different in pace (steady run), nothing is an interval.
 *
 * Step 2: the fast group is clustered again. A fast/moderate alternating session (e.g. 400m @ 3:50 / 400m
 * @ 4:45 after a 6:00 warm-up) ends up entirely in the fast group of step 1; if that group itself splits
 * clearly in two, the slower sub-group are floats rather than reps.
 *
 * Accidental double lap-presses are filtered out before clustering and reported as [LapKind.NOISE].
 */
object LapClassifier {

    private const val MIN_SPEED_RATIO = 1.15

    /** A lap this short/near-stationary is almost certainly an accidental double lap-press, not a
     * real rep or recovery segment, and would otherwise poison the speed clustering. */
    private const val MIN_VALID_DURATION_SECONDS = 10
    private const val MIN_VALID_DISTANCE_METERS = 45.0

    fun isDegenerate(lap: Lap): Boolean =
        lap.averageSpeed <= 0f ||
            lap.movingTime < MIN_VALID_DURATION_SECONDS ||
            lap.distance < MIN_VALID_DISTANCE_METERS

    /** Classifies every lap into a [LapKind], parallel to the input list. */
    fun classify(laps: List<Lap>): List<LapKind> {
        val result = Array(laps.size) { i -> if (isDegenerate(laps[i])) LapKind.NOISE else LapKind.EASY }
        val validIndices = laps.indices.filter { result[it] != LapKind.NOISE }
        if (validIndices.size < 2) return result.toList()

        val fastIndices = splitFast(validIndices, laps) ?: return result.toList()
        fastIndices.forEach { result[it] = LapKind.REP }

        // A second clear split inside the fast group means reps alternate with moderate floats.
        if (fastIndices.size >= 2) {
            val fastest = splitFast(fastIndices, laps)
            if (fastest != null) {
                val floats = fastIndices - fastest.toSet()
                val slowAvg = (validIndices - fastIndices.toSet()).map { laps[it].averageSpeed.toDouble() }.average()
                val floatAvg = floats.map { laps[it].averageSpeed.toDouble() }.average()
                // Floats must still be clearly faster than the easy running, otherwise it's not a 3-level session.
                if (slowAvg.isNaN() || floatAvg / slowAvg >= MIN_SPEED_RATIO) {
                    floats.forEach { result[it] = LapKind.FLOAT }
                }
            }
        }
        return result.toList()
    }

    /** 2-means on speed; returns the indices of the fast cluster, or null if there's no clear split. */
    private fun splitFast(indices: List<Int>, laps: List<Lap>): List<Int>? {
        val speeds = indices.map { laps[it].averageSpeed.toDouble() }
        var lowCenter = speeds.min()
        var highCenter = speeds.max()
        if (lowCenter <= 0 || highCenter - lowCenter < 1e-6) return null

        repeat(10) {
            val lowGroup = speeds.filter { kotlin.math.abs(it - lowCenter) <= kotlin.math.abs(it - highCenter) }
            val highGroup = speeds.filter { kotlin.math.abs(it - lowCenter) > kotlin.math.abs(it - highCenter) }
            if (lowGroup.isNotEmpty()) lowCenter = lowGroup.average()
            if (highGroup.isNotEmpty()) highCenter = highGroup.average()
        }
        if (highCenter / lowCenter < MIN_SPEED_RATIO) return null

        val midpoint = (lowCenter + highCenter) / 2
        return indices.filterIndexed { i, _ -> speeds[i] >= midpoint }
    }

    /** Returns a parallel list of booleans: true where the corresponding lap is a hard interval rep. */
    fun classifyIntervals(laps: List<Lap>): List<Boolean> = classify(laps).map { it == LapKind.REP }

    fun hasIntervals(laps: List<Lap>): Boolean = classifyIntervals(laps).any { it }
}
