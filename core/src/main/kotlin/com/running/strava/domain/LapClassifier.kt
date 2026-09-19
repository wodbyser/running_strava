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
 */
object LapClassifier {

    private const val MIN_SPEED_RATIO = 1.15

    /** Returns a parallel list of booleans: true where the corresponding lap is a fast interval rep. */
    fun classifyIntervals(laps: List<Lap>): List<Boolean> {
        if (laps.size < 2) return laps.map { false }
        val speeds = laps.map { it.averageSpeed.toDouble() }
        if (speeds.any { it <= 0 }) return laps.map { false }

        var lowCenter = speeds.min()
        var highCenter = speeds.max()
        if (lowCenter <= 0 || highCenter - lowCenter < 1e-6) return laps.map { false }

        repeat(10) {
            val lowGroup = speeds.filter { kotlin.math.abs(it - lowCenter) <= kotlin.math.abs(it - highCenter) }
            val highGroup = speeds.filter { kotlin.math.abs(it - lowCenter) > kotlin.math.abs(it - highCenter) }
            if (lowGroup.isNotEmpty()) lowCenter = lowGroup.average()
            if (highGroup.isNotEmpty()) highCenter = highGroup.average()
        }

        if (highCenter / lowCenter < MIN_SPEED_RATIO) return laps.map { false }

        val midpoint = (lowCenter + highCenter) / 2
        return speeds.map { it >= midpoint }
    }

    fun hasIntervals(laps: List<Lap>): Boolean = classifyIntervals(laps).any { it }
}
