package com.running.strava.analysis

/**
 * Finds the fastest continuous segment covering exactly [targetMeters] inside a recorded activity, from the
 * Strava `distance` (m, cumulative) and `time` (s since start, i.e. elapsed time incl. pauses) streams.
 *
 * For every sample j used as segment end, the segment start is the point where cumulative distance equals
 * distance[j] - target; its time is linearly interpolated between the two surrounding samples. The error
 * is therefore bounded by the sampling interval at the segment end (typically 1 s).
 *
 * Elapsed time is used on purpose: a stop inside the segment counts, as it does for a race or a Strava best
 * effort.
 */
object BestEffortFinder {

    /** A segment faster than this over >= 1 km is not humanly possible (1 km WR ≈ 7.6 m/s) → GPS error. */
    const val MAX_PLAUSIBLE_SPEED_MS = 7.0

    /** Peak human sprint speed is ≈ 12.4 m/s; a faster jump between two samples is a GPS error. */
    const val MAX_SAMPLE_SPEED_MS = 12.5

    data class Effort(
        val targetMeters: Double,
        val seconds: Double,
        val startIndex: Int,
        val endIndex: Int,
    ) {
        val speedMs: Double get() = targetMeters / seconds
    }

    val STANDARD_DISTANCES: List<Pair<String, Double>> = listOf(
        "1 km" to 1000.0,
        "5 km" to 5000.0,
        "10 km" to 10000.0,
        "Halve marathon" to 21097.5,
        "Marathon" to 42195.0,
    )

    fun fastest(time: List<Int>?, distance: List<Float>?, targetMeters: Double): Effort? {
        if (time == null || distance == null) return null
        val n = minOf(time.size, distance.size)
        if (n < 2 || targetMeters <= 0) return null
        if (distance[n - 1] - distance[0] < targetMeters) return null

        var best: Effort? = null
        // Prefix count of GPS jumps (sample-to-sample speed above human sprint peak). Any window that
        // contains a jump is rejected, otherwise a teleport would shorten long efforts undetectably.
        val jumps = IntArray(n)
        for (k in 1 until n) {
            val dt = time[k] - time[k - 1]
            val dd = distance[k] - distance[k - 1]
            val bad = dd > 0 && (dt <= 0 || dd / dt > MAX_SAMPLE_SPEED_MS)
            jumps[k] = jumps[k - 1] + if (bad) 1 else 0
        }
        var i = 0 // invariant: distance[i] <= distance[j] - target < distance[i+1] (when found)
        for (j in 1 until n) {
            val startDist = distance[j] - targetMeters
            if (startDist < distance[0]) continue
            while (i + 1 < j && distance[i + 1] <= startDist) i++
            if (jumps[j] - jumps[i] > 0) continue
            val d0 = distance[i].toDouble()
            val d1 = distance[i + 1].toDouble()
            val t0 = time[i].toDouble()
            val t1 = time[i + 1].toDouble()
            val startTime = if (d1 > d0) t0 + (t1 - t0) * ((startDist - d0) / (d1 - d0)) else t0
            val seconds = time[j] - startTime
            if (seconds <= 0) continue
            if (targetMeters / seconds > MAX_PLAUSIBLE_SPEED_MS) continue
            if (best == null || seconds < best.seconds) best = Effort(targetMeters, seconds, i, j)
        }
        return best
    }
}
