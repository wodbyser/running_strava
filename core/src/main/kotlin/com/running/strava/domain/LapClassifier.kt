package com.running.strava.domain

/**
 * Heuristics to detect which laps of an activity correspond to hard interval reps vs. easy/recovery
 * jogging, purely from lap-level average speed (no external config needed).
 *
 * A naive "faster than the median lap" check fails whenever the fast reps outnumber the recovery
 * laps in a session (the median then lands on a fast lap itself, so nothing clears the threshold).
 * Instead we run a simple 1-D 2-means clustering on lap speed: this correctly separates "fast" and
 * "slow" laps regardless of how many of each there are, as long as the two groups are meaningfully
 * different in pace. If all laps have very similar pace (e.g. a steady/easy run with no laps
 * pressed for reps), nothing is classified as an interval.
 *
 * Accidental double lap-presses (a near-instant lap with ~0 distance/duration, e.g. from
 * double-tapping the lap button) are filtered out before clustering so they can't drag the speed
 * centers around or, worse, abort classification for the whole activity. They are always reported
 * as non-interval, but the *rest* of the activity's laps are still classified normally.
 */
object LapClassifier {

    private const val MIN_SPEED_RATIO = 1.15

    /** A lap this short/near-stationary is almost certainly an accidental double lap-press, not a
     * real rep or recovery segment, and would otherwise poison the speed clustering. */
    private const val MIN_VALID_DURATION_SECONDS = 3
    private const val MIN_VALID_DISTANCE_METERS = 20.0

    private fun isDegenerate(lap: Lap): Boolean =
        lap.averageSpeed <= 0f ||
            lap.movingTime < MIN_VALID_DURATION_SECONDS ||
            lap.distance < MIN_VALID_DISTANCE_METERS

    /** Returns a parallel list of booleans: true where the corresponding lap is a fast interval rep. */
    fun classifyIntervals(laps: List<Lap>): List<Boolean> {
        if (laps.size < 2) return laps.map { false }

        val validIndices = laps.indices.filter { !isDegenerate(laps[it]) }
        if (validIndices.size < 2) return laps.map { false }

        val speeds = validIndices.map { laps[it].averageSpeed.toDouble() }

        var lowCenter = speeds.min()
        var highCenter = speeds.max()
        val result = BooleanArray(laps.size)
        if (lowCenter <= 0 || highCenter - lowCenter < 1e-6) return result.toList()

        repeat(10) {
            val lowGroup = speeds.filter { kotlin.math.abs(it - lowCenter) <= kotlin.math.abs(it - highCenter) }
            val highGroup = speeds.filter { kotlin.math.abs(it - lowCenter) > kotlin.math.abs(it - highCenter) }
            if (lowGroup.isNotEmpty()) lowCenter = lowGroup.average()
            if (highGroup.isNotEmpty()) highCenter = highGroup.average()
        }

        if (highCenter / lowCenter < MIN_SPEED_RATIO) return result.toList()

        val midpoint = (lowCenter + highCenter) / 2
        validIndices.forEachIndexed { i, lapIndex -> result[lapIndex] = speeds[i] >= midpoint }
        return result.toList()
    }

    fun hasIntervals(laps: List<Lap>): Boolean = classifyIntervals(laps).any { it }
}
