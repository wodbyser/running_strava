package com.running.race

import com.running.strava.analysis.RacePredictor
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * One frozen prediction: what the page said on [date] for [distanceMeters], with the default periods. Written at most once per day and
 * distance (a later write that day replaces it), never changed afterwards, so it is not affected by later rule or data changes.
 */
data class LoggedPrediction(
    val date: LocalDate,
    val distanceMeters: Double,
    val seconds: Double,
    val lowSeconds: Double?,
    val highSeconds: Double?,
    val algorithmVersion: String,
    val anchorCount: Int,
    val cappedByActivityId: Long?,
)

/** Storage of the frozen predictions. The adapter lives in the runner module. */
interface PredictionLogRepository {
    /** Inserts or replaces the rows of the same date and distance. */
    fun record(entries: List<LoggedPrediction>)
    fun findAll(): List<LoggedPrediction>
}

/** A race compared with the last prediction logged before the race day. */
data class LoggedOutcome(
    val race: RaceRun,
    val entry: LoggedPrediction,
    /** The logged prediction converted with Riegel to the measured race distance. */
    val predictedSeconds: Double,
    /** (actual - predicted) / predicted in %: positive = slower than predicted. */
    val errorPct: Double,
    val withinRange: Boolean?,
) {
    val actualSeconds: Double get() = race.movingSeconds.toDouble()
    val daysBefore: Long get() = ChronoUnit.DAYS.between(entry.date, race.date)
}

data class LoggedSummary(val n: Int, val medianErrorPct: Double?, val maePct: Double?, val withinRange: Int, val firstLogDate: LocalDate?)

object PredictionLog {
    /** The rows to freeze today: one per distance that has a prediction. */
    fun entries(date: LocalDate, predictions: List<Triple<RacePrediction, Double?, Double?>>): List<LoggedPrediction> =
        predictions.map { (p, low, high) ->
            LoggedPrediction(date, p.targetMeters, p.rawSeconds, low, high, RaceAlgorithm.VERSION, p.anchors.size, p.cappedBy?.activityId)
        }

    /**
     * Every road race that has a logged prediction dated strictly before the race day, at most [RaceConfig.logMaxAgeDays] old, for a distance
     * within [RaceConfig.goalMatchTolerance] of the measured race distance. The latest day wins, then the closest distance. Newest race last.
     */
    fun outcomes(runs: List<RaceRun>, log: List<LoggedPrediction>, cfg: RaceConfig = RaceConfig.DEFAULT): List<LoggedOutcome> =
        Races.list(runs).filter { !Races.isTrail(it) }.mapNotNull { race ->
            val from = race.date.minusDays(cfg.logMaxAgeDays.toLong())
            val entry = log.filter {
                it.date.isBefore(race.date) && !it.date.isBefore(from) &&
                    abs(it.distanceMeters - race.distanceMeters) <= it.distanceMeters * cfg.goalMatchTolerance
            }.sortedWith(compareByDescending<LoggedPrediction> { it.date }.thenBy { abs(it.distanceMeters - race.distanceMeters) }).firstOrNull()
                ?: return@mapNotNull null
            val scale = RacePredictor.riegel(1.0, entry.distanceMeters, race.distanceMeters)
            val predicted = entry.seconds * scale
            val actual = race.movingSeconds.toDouble()
            val inRange = if (entry.lowSeconds != null && entry.highSeconds != null) actual in entry.lowSeconds * scale..entry.highSeconds * scale else null
            LoggedOutcome(race, entry, predicted, (actual - predicted) / predicted * 100, inRange)
        }

    fun summary(outcomes: List<LoggedOutcome>, log: List<LoggedPrediction>): LoggedSummary {
        val errors = outcomes.map { it.errorPct }
        return LoggedSummary(
            outcomes.size, com.running.load.LoadStats.median(errors), errors.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(),
            outcomes.count { it.withinRange == true }, log.minOfOrNull { it.date },
        )
    }
}
