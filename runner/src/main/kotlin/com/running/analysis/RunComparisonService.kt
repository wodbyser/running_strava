package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.TrainingAssessment
import com.running.strava.analysis.isRun
import com.running.strava.analysis.performance.ComparedRun
import com.running.strava.analysis.performance.HrZoneSet
import com.running.strava.analysis.performance.IntervalSummary
import com.running.strava.analysis.performance.RunComparison
import com.running.strava.analysis.performance.RunCurves
import com.running.strava.analysis.performance.Stats
import com.running.strava.analysis.performance.TerrainClass
import com.running.strava.domain.Activity
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service

/**
 * Loads 2 to 6 chosen runs with everything the comparison page shows: the per-run analysis of the Prestaties pages,
 * the execution verdict of Training beoordelen, the time per heart rate zone and the pace/heart rate curves. The
 * comparison itself is the pure [RunComparison].
 */
@Service
class RunComparisonService(
    private val performanceService: PerformanceService,
    private val trainingRatingService: TrainingRatingService,
    private val activityRepository: ActivityRepository,
) {
    data class Entry(
        val activity: Activity,
        val analysis: RunAnalysis,
        val assessment: TrainingAssessment.Result?,
        val zoneSeconds: List<Double>?,
        val curve: List<RunCurves.Point>,
        val compared: ComparedRun,
    )

    data class Comparison(
        val entries: List<Entry>,
        val result: RunComparison.Result,
        val zoneSet: HrZoneSet?,
        val zoneNote: String,
        val blockMeters: Double,
    )

    /**
     * A run that can be added to the comparison. [similar] is set for runs that resemble the first chosen run;
     * [sessionType] is recognised from the laps, the same way as on the performance pages.
     */
    data class Candidate(val activity: Activity, val similar: Boolean, val sessionType: SessionType)

    /** Ids in the order asked, without duplicates and at most [RunComparison.MAX_RUNS]. */
    fun normalize(ids: List<Long>): List<Long> = ids.distinct().take(RunComparison.MAX_RUNS)

    fun compare(ids: List<Long>): Comparison? {
        val wanted = normalize(ids)
        if (wanted.size < RunComparison.MIN_RUNS) return null
        val set = performanceService.analyses(wanted)
        if (set.runs.size < RunComparison.MIN_RUNS) return null
        val activities = set.runs.associate { r -> r.id to (activityRepository.findById(r.id) ?: return null) }
        val rated = trainingRatingService.assessMany(set.runs.map { it.id })
        val longest = set.runs.maxOf { it.distanceMeters }
        val block = RunCurves.blockMeters(longest)
        val entries = set.runs.map { r ->
            val assessment = rated[r.id]?.assessment
            Entry(
                activity = activities.getValue(r.id),
                analysis = r,
                assessment = assessment,
                zoneSeconds = set.zoneSeconds[r.id],
                curve = activityRepository.findStreams(r.id)?.let { RunCurves.byDistance(it, block) }.orEmpty(),
                compared = compared(r, assessment),
            )
        }
        return Comparison(entries, RunComparison.compare(entries.map { it.compared }), set.zoneSet, set.zoneNote, block)
    }

    /**
     * Every run to choose from: the chosen runs first (so they can be unticked), then runs that resemble the first
     * chosen run (same rules as "Vergelijkbare runs"), then all other runs, newest first. Filtering happens in the page.
     */
    fun candidates(selected: List<Long>): List<Candidate> {
        val runs = activityRepository.findAll().filter { it.isRun() }.sortedByDescending { it.startDate }
        val byId = runs.associateBy { it.id }
        val similar = selected.firstOrNull()?.let { first ->
            performanceService.runReport(first)?.comparable?.matches.orEmpty().take(8).mapNotNull { byId[it.run.activityId] }
        }.orEmpty()
        val chosen = selected.mapNotNull { byId[it] ?: activityRepository.findById(it) }
        val laps = activityRepository.findLapsForActivities((runs + chosen).map { it.id }.distinct())
        fun type(a: Activity) = SessionClassifier.classify(laps[a.id].orEmpty(), a.workoutType, TerrainClass.elevationPerKm(a))
        val similarIds = similar.map { it.id }.toSet()
        val ordered = LinkedHashMap<Long, Activity>()
        (chosen + similar + runs).forEach { ordered.putIfAbsent(it.id, it) }
        return ordered.values.map { Candidate(it, it.id in similarIds, type(it)) }
    }

    private fun compared(r: RunAnalysis, assessment: TrainingAssessment.Result?): ComparedRun {
        val reps = r.intervals?.reps.orEmpty()
        val interval = r.intervals?.takeIf { reps.isNotEmpty() }?.let { s ->
            val seconds = reps.sumOf { it.seconds }
            val hrr = reps.mapNotNull { it.hrr }
            // One protocol per run: the most common one, so a single walked recovery does not mix with jogged ones.
            val protocol = hrr.groupingBy { it.protocol }.eachCount().maxByOrNull { it.value }?.key
            IntervalSummary(
                signature = s.signature,
                repCount = reps.size,
                repSpeed = if (seconds > 0) reps.sumOf { it.distanceMeters } / seconds else 0.0,
                repSpeedCv = s.speedCv,
                fadePct = s.paceFadePct,
                hrr60 = Stats.median(hrr.filter { it.protocol == protocol }.map { it.hrr60 }),
                hrrProtocol = protocol,
            )
        }
        return ComparedRun(
            id = r.id,
            sessionType = r.sessionType,
            isTrainer = r.isTrainer,
            distanceMeters = r.distanceMeters,
            movingSeconds = r.movingSeconds,
            avgHr = r.avgHr,
            terrain = r.terrain,
            tempC = r.tempC,
            gapSpeed = r.gap?.gapSpeed,
            steadyEf = r.ef?.ef,
            steadyEfHr = r.ef?.avgHr,
            decouplingPct = r.drift?.decouplingPct,
            steadySeconds = r.ef?.windowMinutes?.let { it * 60 },
            speedCv = r.speedCv,
            interval = interval,
            execution = assessment?.level,
        )
    }

    companion object {
        /** Ids from "1,2,3" and/or repeated parameters; anything that is not a number is ignored. */
        fun parseIds(raw: List<String>?): List<Long> =
            raw.orEmpty().flatMap { it.split(',') }.mapNotNull { it.trim().toLongOrNull() }.distinct()

        fun localDate(a: Activity) = ActivityTime.localDate(a)
    }
}
