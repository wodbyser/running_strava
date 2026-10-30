package com.running.strava.analysis.performance

/** #25 durability and #26 long run fatigue. Both cut a run into 10 minute blocks / thirds after the warm-up. */
object DurabilityProfile {
    const val BLOCK_SECONDS = 600.0
    const val MIN_BLOCK_VALID_SHARE = 0.80
    const val MINUTE_60_BLOCK = 5

    data class Block(
        val index: Int,
        val startMinute: Double,
        val speed: Double,
        val hr: Double,
        val ef: Double,
        val cadenceSpm: Double?,
        val meanGradePct: Double?,
    )

    data class Run(
        /** False when the run is shorter than the minimum (default 75 minutes of valid time): nothing is computed. */
        val eligible: Boolean,
        val blocks: List<Block>,
        /** EF per counted block as % of the first block. */
        val efRelativePct: List<Double>,
        /** EF of the block starting at minute 60 against the first block, in %. Null when either block is missing. */
        val efChangeAt60Pct: Double?,
    )

    /** A block counts when its valid time is at least 80 % of the elapsed time it spans. */
    fun blocks(cleanSamples: List<Sample>, warmupSeconds: Double): List<Block> =
        Series.blocks(Series.afterWarmup(cleanSamples, warmupSeconds), warmupSeconds, BLOCK_SECONDS).mapIndexedNotNull { _, s ->
            val valid = Series.seconds(s)
            if (valid < BLOCK_SECONDS * 0.95) return@mapIndexedNotNull null
            val elapsed = s.last().t - (s.first().t - s.first().dt)
            if (elapsed <= 0 || valid / elapsed < MIN_BLOCK_VALID_SHARE) return@mapIndexedNotNull null
            val idx = ((s.first().clock - warmupSeconds - 1e-9) / BLOCK_SECONDS).toInt()
            Block(
                index = idx,
                startMinute = (s.first().clock - s.first().dt) / 60,
                speed = Series.avgSpeed(s) ?: return@mapIndexedNotNull null,
                hr = Series.avgHr(s) ?: return@mapIndexedNotNull null,
                ef = Series.ef(s) ?: return@mapIndexedNotNull null,
                cadenceSpm = Series.avgCadence(s),
                meanGradePct = Series.avgGrade(s),
            )
        }

    fun analyze(cleanSamples: List<Sample>, warmupSeconds: Double, minValidSeconds: Double = 75 * 60.0): Run {
        if (Series.seconds(cleanSamples) < minValidSeconds) return Run(false, emptyList(), emptyList(), null)
        val blocks = blocks(cleanSamples, warmupSeconds)
        val first = blocks.firstOrNull { it.index == 0 }
        val rel = if (first != null) blocks.map { it.ef / first.ef * 100 } else emptyList()
        val at60 = blocks.firstOrNull { it.index == MINUTE_60_BLOCK }
        val change = if (first != null && at60 != null) (at60.ef - first.ef) / first.ef * 100 else null
        return Run(true, blocks, rel, change)
    }
}

object LongRun {
    const val MIN_MINUTES = 75
    const val MEDIAN_FACTOR = 1.5
    const val SLOWER_SHARE_LIMIT = 0.05
    const val GRADE_DIFF_LIMIT_PCT = 2.0

    /** Strava `workout_type` 2, or at least 75 minutes and 1.5x the median run duration of the last 90 days. PROPOSAL. */
    fun isLongRun(workoutType: Int?, movingSeconds: Int, medianMovingSeconds: Double?): Boolean =
        workoutType == 2 ||
            (movingSeconds >= MIN_MINUTES * 60 && medianMovingSeconds != null && movingSeconds >= MEDIAN_FACTOR * medianMovingSeconds)

    data class Thirds(
        val speedChangePct: Double,
        val hrChangeBpm: Double,
        val hrChangePct: Double,
        val efChangePct: Double,
        val cadenceChangeSpm: Double?,
        val slowerShare: Double,
        val gradeAbs1: Double?,
        val gradeAbs3: Double?,
        /** EF comparison is not trustworthy because the terrain of the first and last third differs. */
        val terrainDiffers: Boolean,
        val validMinutes: Double,
    )

    fun thirds(cleanSamples: List<Sample>, warmupSeconds: Double): Thirds? {
        val w = Series.afterWarmup(cleanSamples, warmupSeconds)
        if (w.isEmpty()) return null
        val parts = Series.parts(w, 3)
        val t1 = parts[0]
        val t3 = parts[2]
        val s1 = Series.avgSpeed(t1) ?: return null
        val s3 = Series.avgSpeed(t3) ?: return null
        val hr1 = Series.avgHr(t1) ?: return null
        val hr3 = Series.avgHr(t3) ?: return null
        val ef1 = Series.ef(t1) ?: return null
        val ef3 = Series.ef(t3) ?: return null
        val c1 = Series.avgCadence(t1)
        val c3 = Series.avgCadence(t3)
        val slower = w.filter { it.speed < s1 * (1 - 0.05) }.sumOf { it.dt } / Series.seconds(w)
        val g1 = Series.avgAbsGrade(t1)
        val g3 = Series.avgAbsGrade(t3)
        return Thirds(
            speedChangePct = (s3 - s1) / s1 * 100,
            hrChangeBpm = hr3 - hr1,
            hrChangePct = (hr3 - hr1) / hr1 * 100,
            efChangePct = (ef3 - ef1) / ef1 * 100,
            cadenceChangeSpm = if (c1 != null && c3 != null) c3 - c1 else null,
            slowerShare = slower,
            gradeAbs1 = g1,
            gradeAbs3 = g3,
            terrainDiffers = g1 != null && g3 != null && kotlin.math.abs(g3 - g1) > GRADE_DIFF_LIMIT_PCT,
            validMinutes = Series.seconds(cleanSamples) / 60,
        )
    }
}
