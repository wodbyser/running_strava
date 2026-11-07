package com.running.race

import com.running.strava.analysis.RacePredictor
import kotlin.math.abs

/** What the app predicted for one past race, using only anchors dated before the race day (walk-forward, design 2.4). */
data class RaceOutcome(
    val race: RaceRun,
    val trail: Boolean,
    val predictedSeconds: Double?,
    val anchorCount: Int,
    /** (actual - predicted) / predicted in %: positive = slower than predicted. */
    val errorPct: Double?,
    val baselineSeconds: Double?,
    val baselineErrorPct: Double?,
) {
    val actualSeconds: Double get() = race.movingSeconds.toDouble()
}

data class AccuracySummary(
    val n: Int,
    val enough: Boolean,
    val needed: Int,
    val medianErrorPct: Double?,
    val minErrorPct: Double?,
    val maxErrorPct: Double?,
    /** Trimmed edges of the own errors used for the range: the [trimPct] percentile and its mirror (race-v3). */
    val rangeLowPct: Double?,
    val rangeHighPct: Double?,
    val trimPct: Double,
    /** Mean absolute errors on the races where the one-anchor baseline exists as well. */
    val nCompared: Int,
    val mainMaePct: Double?,
    val baselineMaePct: Double?,
    val closer: String?,
)

object Accuracy {
    /** Every marked race, newest last. Trail races are listed without a prediction. */
    fun outcomes(runs: List<RaceRun>, windowDays: Int, maxHr: Int?): List<RaceOutcome> {
        val races = Races.list(runs)
        return races.map { race ->
            if (Races.isTrail(race)) return@map RaceOutcome(race, true, null, 0, null, null, null)
            val anchors = Anchors.collect(runs, race.date, windowDays, maxHr)
            val p = Prediction.predict(race.distanceMeters, anchors)
            val actual = race.movingSeconds.toDouble()
            val err = p?.let { (actual - it.rawSeconds) / it.rawSeconds * 100 }
            val last = races.lastOrNull { it.date.isBefore(race.date) && !Races.isTrail(it) }
            val ratio = last?.let { race.distanceMeters / it.distanceMeters }
            val base = if (last != null && ratio != null && ratio in RacePredictor.MIN_RATIO..RacePredictor.MAX_RATIO) {
                RacePredictor.riegel(last.movingSeconds.toDouble(), last.distanceMeters, race.distanceMeters)
            } else null
            RaceOutcome(race, false, p?.rawSeconds, p?.anchors?.size ?: 0, err, base, base?.let { (actual - it) / it * 100 })
        }
    }

    fun summary(outcomes: List<RaceOutcome>, cfg: RaceConfig = RaceConfig.DEFAULT): AccuracySummary {
        val errors = outcomes.mapNotNull { it.errorPct }
        val both = outcomes.filter { it.errorPct != null && it.baselineErrorPct != null }
        val main = both.takeIf { it.isNotEmpty() }?.map { abs(it.errorPct!!) }?.average()
        val base = both.takeIf { it.isNotEmpty() }?.map { abs(it.baselineErrorPct!!) }?.average()
        val enough = errors.size >= cfg.minOwnErrorRaces
        val trim = cfg.rangeTrimPct.coerceIn(0.0, 49.0) / 100
        return AccuracySummary(
            errors.size, enough, cfg.minOwnErrorRaces,
            com.running.load.LoadStats.median(errors), errors.minOrNull(), errors.maxOrNull(),
            com.running.load.LoadStats.percentile(errors, trim), com.running.load.LoadStats.percentile(errors, 1 - trim), cfg.rangeTrimPct,
            both.size, main, base,
            if (main == null || base == null) null else if (main <= base) "main" else "baseline",
        )
    }

    /**
     * `prediction x (1 + low error)` to `prediction x (1 + high error)`, with the trimmed percentiles of the own errors (race-v3: one outlier no
     * longer sets an edge). The range always contains the prediction itself. Null below the minimum (design 3.2).
     */
    fun range(predictionSeconds: Double, summary: AccuracySummary): Pair<Double, Double>? {
        if (!summary.enough || summary.rangeLowPct == null || summary.rangeHighPct == null) return null
        val low = predictionSeconds * (1 + summary.rangeLowPct / 100)
        val high = predictionSeconds * (1 + summary.rangeHighPct / 100)
        return minOf(low, predictionSeconds) to maxOf(high, predictionSeconds)
    }
}
