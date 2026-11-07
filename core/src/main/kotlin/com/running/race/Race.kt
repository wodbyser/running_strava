package com.running.race

import com.fasterxml.jackson.annotation.JsonIgnore
import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.RacePredictor
import com.running.strava.analysis.performance.CriticalSpeed
import com.running.strava.analysis.performance.TerrainClass
import com.running.strava.domain.SessionType
import java.time.LocalDate

object RaceAlgorithm {
    const val VERSION = "race-v3"
}

/** All thresholds are PROPOSALS (design section 2.6), UNVERIFIED, and configurable via `garmin.race.*`. */
data class RaceConfig(
    val minOwnErrorRaces: Int = 5,
    val minTaperRaces: Int = 6,
    val minGroupRaces: Int = 4,
    val minPacingRaces: Int = 3,
    val minFeasibilityWeeks: Int = 26,
    val calmStartPct: Double = 2.0,
    val specificityMinutes: Int = 20,
    val longestRunShare: Double = 0.75,
    val recentAnchorDays: Int = 60,
    val paceTolerancePct: Double = 3.0,
    val goalMatchTolerance: Double = 0.10,
    /** Share of the own errors left out at each side of the range (race-v3). */
    val rangeTrimPct: Double = 10.0,
    /** A logged prediction counts for a race only when it was logged at most this many days before the race. */
    val logMaxAgeDays: Int = 14,
    val resamples: Int = 2000,
    val seed: Long = 42,
) {
    companion object {
        val DEFAULT = RaceConfig()
    }
}

/** Every adjustable period of the race pages (design section 4.3). */
data class RaceWindows(
    val anchorDays: Int = 180,
    val chartWeeks: Int = 52,
    val trendWeeks: Int = 8,
    val specificWeeks: Int = 6,
    val weekCheckDays: Int = 7,
    val taperShortDays: Int = 7,
    val taperLongDays: Int = 14,
    val retroWeeks: Int = 8,
) {
    companion object {
        val DEFAULT = RaceWindows()
    }
}

/** One run reduced to what the race analysis needs. No raw samples and no coordinates. */
data class RaceRun(
    val id: Long,
    val date: LocalDate,
    val startEpochSeconds: Long,
    val name: String,
    val distanceMeters: Double,
    val movingSeconds: Int,
    val elapsedSeconds: Int,
    val avgHr: Double?,
    val workoutType: Int?,
    val isTrainer: Boolean,
    val elevationPerKm: Double,
    val sessionType: SessionType,
    /** Fastest segment per standard distance in metres, in seconds (own stream calculation, Strava best effort as fallback). */
    val efforts: Map<Double, Double> = emptyMap(),
    /** First and second half time from the stream; filled for races only. */
    val halves: Halves? = null,
)

/** A race goal as entered on the page. */
data class RaceGoal(val id: Long, val distanceMeters: Double, val date: LocalDate, val targetSeconds: Int?, val name: String?)

object Races {
    const val RACE = 1

    /** A race is a run with the Strava marking `workout_type = 1` (DECISION 2026-11-06). Nothing else is called a race. */
    fun isRace(r: RaceRun) = r.workoutType == RACE && r.distanceMeters > 0 && r.movingSeconds > 0

    /** Trail: more than 15 m of climbing per km. Road predictions do not apply to it. */
    fun isTrail(r: RaceRun) = TerrainClass.of(r.elevationPerKm) == TerrainClass.HILLY

    fun isTrailRace(r: RaceRun) = isRace(r) && isTrail(r)

    fun list(runs: List<RaceRun>): List<RaceRun> = runs.filter { isRace(it) }.sortedBy { it.startEpochSeconds }

    /** The race on the goal date with a measured distance within the tolerance of the goal distance, if any. */
    fun match(goal: RaceGoal, runs: List<RaceRun>, cfg: RaceConfig = RaceConfig.DEFAULT): RaceRun? =
        list(runs).filter { it.date == goal.date && kotlin.math.abs(it.distanceMeters - goal.distanceMeters) <= goal.distanceMeters * cfg.goalMatchTolerance }
            .minByOrNull { kotlin.math.abs(it.distanceMeters - goal.distanceMeters) }
}

enum class AnchorKind(val text: String) {
    RACE("wedstrijd"),
    BEST_EFFORT("snelste stuk in een run"),
    HARD_RUN("harde run"),
}

data class RaceAnchor(
    val kind: AnchorKind,
    val date: LocalDate,
    val activityId: Long,
    val name: String,
    val distanceMeters: Double,
    val seconds: Double,
    val elevationPerKm: Double,
) {
    @get:JsonIgnore
    val asPredictorAnchor: RacePredictor.Anchor get() = RacePredictor.Anchor(distanceMeters, seconds, kind.text)
}

/**
 * Anchors of Phase A (audit Q6: continuous efforts only) with two additions: trail races never count (design 2.2) and the
 * window is adjustable (default 180 days). Anchors are always dated strictly BEFORE [asOf] (walk-forward rule, design 2.4).
 */
object Anchors {
    fun collect(runs: List<RaceRun>, asOf: LocalDate, windowDays: Int, maxHr: Int?): List<RaceAnchor> {
        val from = asOf.minusDays(windowDays.toLong())
        val inWindow = runs.filter { !it.date.isBefore(from) && it.date.isBefore(asOf) && !Races.isTrailRace(it) }
        val out = ArrayList<RaceAnchor>()

        inWindow.filter { Races.isRace(it) }.sortedBy { it.startEpochSeconds }.forEach {
            out += RaceAnchor(AnchorKind.RACE, it.date, it.id, it.name, it.distanceMeters, it.movingSeconds.toDouble(), it.elevationPerKm)
        }

        BestEffortFinder.STANDARD_DISTANCES.forEach { (_, meters) ->
            val best = inWindow.filter { !it.isTrainer }.mapNotNull { r -> r.efforts[meters]?.let { r to it } }.minByOrNull { it.second }
            if (best != null && best.second >= RacePredictor.MIN_VALID_SECONDS) {
                out += RaceAnchor(AnchorKind.BEST_EFFORT, best.first.date, best.first.id, best.first.name, meters, best.second, best.first.elevationPerKm)
            }
        }

        if (maxHr != null) {
            inWindow.filter {
                !Races.isRace(it) && it.distanceMeters > 0 && it.movingSeconds > 0 &&
                    (it.avgHr ?: 0.0) >= maxHr * com.running.strava.analysis.HrZones.HARD_EFFORT_FRACTION && it.sessionType == SessionType.STEADY
            }.sortedBy { it.startEpochSeconds }.forEach {
                out += RaceAnchor(AnchorKind.HARD_RUN, it.date, it.id, it.name, it.distanceMeters, it.movingSeconds.toDouble(), it.elevationPerKm)
            }
        }
        return out
    }
}

/** A prediction (#49). Times are the Phase A numbers: median of the Riegel predictions of every anchor within 0.4 to 2.5 x. */
data class RacePrediction(
    val targetMeters: Double,
    /** Median, rounded for display (5 s below 20 min, 15 s below 1 h, whole minutes above). */
    val seconds: Double,
    val rawSeconds: Double,
    val lowSeconds: Double,
    val highSeconds: Double,
    val anchors: List<RaceAnchor>,
    val outsideValidRange: Boolean,
    val marathonWarning: Boolean,
    /** The most recent road race at the target distance or longer when its Riegel time capped the median (race-v2), else null. */
    val cappedBy: RaceAnchor? = null,
) {
    val secondsPerKm: Double get() = seconds / (targetMeters / 1000)
}

data class WeekPrediction(val weekStart: LocalDate, val seconds: Map<Double, Double?>)

object Prediction {
    const val MARATHON_WARNING = "Je liep nog geen marathon. Riegel is voor de marathon vaak te optimistisch, zeker zonder lange duurlopen van 30 km of meer."
    const val NOTE = "Schatting uit je eerdere inspanningen, geen belofte. Weer, parcours en de dag zelf maken het verschil."

    /** Distances predicted by default: 5 km, 10 km, half marathon and marathon. */
    val STANDARD: List<Double> = listOf(5000.0, 10000.0, 21097.5, 42195.0)

    /** Null when fewer than [RacePredictor.MIN_ANCHORS] anchors lie within 0.4 to 2.5 x of the target. */
    fun predict(targetMeters: Double, anchors: List<RaceAnchor>): RacePrediction? {
        val p = RacePredictor.predict(targetMeters, anchors.map { it.asPredictorAnchor }) ?: return null
        val used = anchors.filter { a ->
            a.distanceMeters > 0 && a.seconds >= RacePredictor.MIN_VALID_SECONDS && a.seconds <= RacePredictor.MAX_VALID_SECONDS &&
                (targetMeters / a.distanceMeters) in RacePredictor.MIN_RATIO..RacePredictor.MAX_RATIO
        }
        val capAnchor = capAnchor(targetMeters, anchors)
        val cap = capAnchor?.let { RacePredictor.riegel(it.seconds, it.distanceMeters, targetMeters) }
        val capped = cap != null && cap < p.seconds
        val raw = if (capped) cap!! else p.seconds
        val low = minOf(p.lowSeconds, raw)
        return RacePrediction(
            targetMeters, RacePredictor.roundSeconds(raw), raw, RacePredictor.roundSeconds(low), RacePredictor.roundSeconds(p.highSeconds),
            used, raw < RacePredictor.MIN_VALID_SECONDS || raw > RacePredictor.MAX_VALID_SECONDS, targetMeters >= 40_000.0,
            if (capped) capAnchor else null,
        )
    }

    /**
     * race-v2 (design section 12): the most recent road race in the anchor set whose measured distance is at least the target.
     * Trail races and the window are already handled by [Anchors.collect]. One-sided: the caller only lets it lower the median.
     */
    fun capAnchor(targetMeters: Double, anchors: List<RaceAnchor>): RaceAnchor? =
        anchors.filter {
            it.kind == AnchorKind.RACE && it.distanceMeters >= targetMeters &&
                it.seconds >= RacePredictor.MIN_VALID_SECONDS && it.seconds <= RacePredictor.MAX_VALID_SECONDS
        }.maxWithOrNull(compareBy<RaceAnchor> { it.date }.thenBy { it.activityId })

    /**
     * Second method for 5 km and shorter: critical speed of Phase 3, `t = (D - D') / CS`. Only when the fit passed and the result lies
     * in 3 to 30 minutes. Never averaged with the Riegel number.
     */
    fun criticalSpeedSeconds(targetMeters: Double, outcome: CriticalSpeed.Outcome?): Double? {
        if (targetMeters > 5000.0) return null
        val fit = (outcome as? CriticalSpeed.Outcome.Ok)?.fit ?: return null
        if (targetMeters <= fit.dPrimeMeters) return null
        val t = (targetMeters - fit.dPrimeMeters) / fit.csMs
        return if (t in CriticalSpeed.MIN_SECONDS..CriticalSpeed.MAX_SECONDS) t else null
    }

    /** Predictions of every Monday from [firstMonday] to [lastMonday], each from anchors dated before that Monday only. */
    fun weekly(
        runs: List<RaceRun>, firstMonday: LocalDate, lastMonday: LocalDate, distances: List<Double>, windowDays: Int, maxHr: Int?,
    ): List<WeekPrediction> = generateSequence(firstMonday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(lastMonday) }.map { monday ->
        val anchors = Anchors.collect(runs, monday, windowDays, maxHr)
        WeekPrediction(monday, distances.associateWith { d -> predict(d, anchors)?.rawSeconds })
    }.toList()

    /** The pace of a longer distance must not be faster than the pace of a shorter one. Returns the pairs that break the rule. */
    fun paceViolations(predictions: List<RacePrediction>): List<Pair<Double, Double>> {
        val sorted = predictions.sortedBy { it.targetMeters }
        fun pace(p: RacePrediction) = p.rawSeconds / (p.targetMeters / 1000)
        return sorted.zipWithNext().filter { (a, b) -> pace(b) < pace(a) - 1e-9 }.map { it.first.targetMeters to it.second.targetMeters }
    }
}
