package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.Format
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.RacePredictor
import com.running.strava.analysis.RunAggregates
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service
class CoachService(
    private val activityRepository: ActivityRepository,
    private val bestEffortService: BestEffortService,
) {

    data class HrZone(
        val zone: Int,
        val name: String,
        val description: String,
        val minBpm: Int,
        val maxBpm: Int,
        val pct: String,
    )

    data class HrZoneMethod(
        val method: String,
        val label: String,
        val zones: List<HrZone>,
    )

    data class RacePrediction(
        val distance: String,
        val distanceMeters: Float,
        val predictedTime: String,
        val predictedPace: String,
        /** Spread of the individual anchor predictions (min–max), or null. */
        val range: String?,
        val basedOn: String?,
        /** Caveat, e.g. outside Riegel's validity range or not enough data. */
        val note: String?,
    )

    data class RunnerProfile(
        val type: String,
        val description: String,
        val weeklyVolume: String,
        val frequency: String,
        val avgPace: String,
        val avgHr: String,
        val avgCadence: String,
        val totalDistance: String,
        val totalTime: String,
        val totalRuns: Int,
        val trainingSince: String,
        val longestRun: String,
        val terrainPreference: String,
        val classification: String,
        val intervalFrequency: String,
        val avgIntervalPace: String,
    )

    data class CoachData(
        val hrZoneMethods: List<HrZoneMethod>,
        val maxHr: Int?,
        val maxHrSource: String,
        val restingHr: Int?,
        val restingHrSource: String,
        val runnerProfile: RunnerProfile,
        val racePredictions: List<RacePrediction>,
        val recentFormPredictions: List<RacePrediction>,
    )

    fun calculateCoachData(runs: List<Activity>, restingHr: Int? = null): CoachData {
        val now = ZonedDateTime.now()
        val lastYear = runs.filter { it.startDate.isAfter(now.minusYears(1)) }.takeIf { it.isNotEmpty() } ?: runs
        val lapsByActivity = activityRepository.findLapsForActivities(runs.map { it.id })

        val perRunMax = lastYear.mapNotNull { it.maxHeartrate?.toInt() }
        val maxHr = HrZones.estimateMaxHr(perRunMax)
        val rawMax = perRunMax.maxOrNull()
        val maxHrSource = when {
            maxHr == null -> "onbekend (geen hartslagdata)"
            perRunMax.size < 5 -> "schatting: hoogste gemeten max-HR in ${perRunMax.size} run(s) van de laatste 12 maanden (te weinig data voor een robuuste schatting)"
            else -> "schatting: 95e percentiel van de max-HR per run (${perRunMax.size} runs, laatste 12 maanden)" +
                (if (rawMax != null && rawMax > maxHr) "; hoogste gemeten waarde $rawMax bpm genegeerd als mogelijke meetpiek" else "") +
                ". Een echte max-HR-test kan hoger uitkomen"
        }

        val hrZoneMethods = mutableListOf<HrZoneMethod>()
        if (maxHr != null) {
            hrZoneMethods += HrZoneMethod("pctMaxHr", "% van (geschatte) max. hartslag", HrZones.pctMax(maxHr).map { it.toView() })
            // Karvonen needs a real resting HR. The app no longer estimates one from in-run HR: the lowest HR during
            // a run says nothing about resting HR (real data gave 96 bpm, clamped to 85).
            if (restingHr != null && restingHr < maxHr) {
                hrZoneMethods += HrZoneMethod("karvonen", "Karvonen (hartslagreserve, met jouw rusthartslag)", HrZones.karvonen(maxHr, restingHr).map { it.toView() })
            }
        }

        return CoachData(
            hrZoneMethods = hrZoneMethods,
            maxHr = maxHr,
            maxHrSource = maxHrSource,
            restingHr = restingHr,
            restingHrSource = if (restingHr != null) "door jou ingesteld" else "niet ingesteld: Karvonen-zones worden pas getoond als je je rusthartslag invult",
            runnerProfile = buildRunnerProfile(lastYear, runs, lapsByActivity),
            racePredictions = predictions(lastYear, lapsByActivity, maxHr, "laatste 12 maanden", RACE_DISTANCES),
            recentFormPredictions = predictions(
                runs.filter { it.startDate.isAfter(now.minusWeeks(6)) }, lapsByActivity, maxHr, "laatste 6 weken", FORM_DISTANCES,
            ),
        )
    }

    private fun HrZones.Zone.toView() = HrZone(zone, name, description, minBpm, maxBpm, pct)

    private fun buildRunnerProfile(recent: List<Activity>, all: List<Activity>, lapsByActivity: Map<Long, List<Lap>>): RunnerProfile {
        if (recent.isEmpty()) {
            return RunnerProfile(
                type = "Onbekend", description = "Geen trainingsdata beschikbaar.",
                weeklyVolume = "-", frequency = "-", avgPace = "-", avgHr = "-", avgCadence = "-",
                totalDistance = "-", totalTime = "-", totalRuns = 0, trainingSince = "-",
                longestRun = "-", terrainPreference = "-", classification = "-",
                intervalFrequency = "-", avgIntervalPace = "-",
            )
        }

        val now = ZonedDateTime.now()
        val agg = RunAggregates.of(recent)
        val recentDist = agg.totalDistanceMeters
        val recentFirst = recent.minOf { it.startDate }
        val recentWeeks = Duration.between(recentFirst, now).toDays() / 7.0
        val weeklyKm = if (recentWeeks >= 1) recentDist / 1000 / recentWeeks else recentDist / 1000
        val runsPerWeek = recent.size.toDouble() / recentWeeks.coerceAtLeast(1.0)

        val avgElevPerKm = if (recentDist > 0) recent.sumOf { it.totalElevationGain.toDouble() } / (recentDist / 1000) else 0.0
        val terrainPref = when {
            avgElevPerKm < 5 -> "Vlak"
            avgElevPerKm < 15 -> "Heuvelachtig"
            else -> "Bergachtig"
        }

        val longestRun = all.maxByOrNull { it.distance }
        val longestRunStr = longestRun?.let { "%.2f km".format(it.distance / 1000) } ?: "-"
        val totalDistance = all.sumOf { it.distance.toDouble() }
        val totalTime = all.sumOf { it.movingTime.toLong() }

        val firstDate = all.minOf { it.startDate }
        val monthsTraining = Duration.between(firstDate, now).toDays() / 30
        val trainingSince = if (monthsTraining >= 12) "${monthsTraining / 12}j ${monthsTraining % 12}mnd" else "${monthsTraining}mnd"

        val classification = when {
            weeklyKm < 15 -> "< 15 km/week"
            weeklyKm < 30 -> "15-30 km/week"
            weeklyKm < 50 -> "30-50 km/week"
            weeklyKm < 70 -> "50-70 km/week"
            else -> "≥ 70 km/week"
        }

        val paceStr = Format.pace(agg.avgSpeedMs)
        val avgHr = agg.avgHr
        val avgCadence = agg.avgCadenceSpm

        val intervalSessions = recent.filter { SessionClassifier.classify(lapsByActivity[it.id].orEmpty(), it.workoutType) == SessionType.INTERVAL }
        val intervalSessionsPerWeek = intervalSessions.size / recentWeeks.coerceAtLeast(1.0)
        val reps = intervalSessions.flatMap { a ->
            val laps = lapsByActivity[a.id].orEmpty()
            val kinds = LapClassifier.classify(laps, a.workoutType)
            laps.filterIndexed { i, lap -> kinds[i] == LapKind.REP && lap.movingTime >= LapClassifier.MIN_REP_SECONDS }
        }
        val repTime = reps.sumOf { it.movingTime }
        val avgIntervalSpeed = if (repTime > 0) reps.sumOf { it.distance.toDouble() } / repTime else null
        val intervalPaceStr = Format.pace(avgIntervalSpeed)
        val intervalFrequencyStr = if (intervalSessions.isNotEmpty()) "%.1fx/week".format(intervalSessionsPerWeek) else "Geen"

        val profileType = when {
            avgElevPerKm >= 15 -> "Berggeit"
            intervalSessionsPerWeek >= 0.4 && avgIntervalSpeed != null -> "Intervaltrainer"
            (agg.avgSpeedMs ?: 0.0) > 4.5 -> "Snelheidsduivel"
            (agg.avgSpeedMs ?: 0.0) > 3.5 -> "Tempoloper"
            weeklyKm >= 40 -> "Uithoudingsatleet"
            runsPerWeek >= 5 -> "Frequente loper"
            else -> "Allround loper"
        }

        val description = buildString {
            append("<p><strong>$profileType</strong> <span class=\"text-muted\">(app-label op basis van vaste vuistregels)</span> &mdash; ")
            append("Je loopt gemiddeld <strong>${"%.1f".format(weeklyKm)} km</strong> per week ")
            append("over <strong>${"%.1f".format(runsPerWeek)}x</strong> per week. ")
            append("Je gemiddelde tempo (totale afstand / totale tijd) is <strong>$paceStr</strong>")
            if (avgHr != null) append(" bij gemiddeld <strong>${Format.hr(avgHr)}</strong> (${agg.hrCount} van ${recent.size} runs met HR)")
            append(".</p>")
            append("<p>Je trainingsgebied is <strong>${terrainPref.lowercase()}</strong>")
            if (avgCadence != null) append(" met een cadans van <strong>${"%.0f".format(avgCadence)} spm</strong> (stappen per minuut, beide voeten)")
            append(". Je langste run ooit is <strong>$longestRunStr</strong>. ")
            append("Je bent actief sinds <strong>$trainingSince</strong> ")
            append("met <strong>${all.size} runs</strong>, ")
            append("<strong>${"%.0f".format(totalDistance / 1000)} km</strong> ")
            append("en <strong>${totalTime / 3600}u ${(totalTime % 3600) / 60}m</strong> totaal.</p>")
            if (intervalSessions.isNotEmpty()) {
                append("<p>De app herkende gemiddeld <strong>${"%.1f".format(intervalSessionsPerWeek)}x</strong> per week ")
                append("een intervaltraining (automatisch herkend uit rondes), met een gemiddeld reptempo van <strong>$intervalPaceStr</strong>.</p>")
            } else {
                append("<p class=\"text-muted\">Geen intervaltrainingen herkend in de laatste 12 maanden (herkenning vereist rondes/laps).</p>")
            }
            append("<p class=\"text-muted\">Weekvolume-categorie: <strong>$classification</strong> &mdash; ")
            append("profiel gebaseerd op laatste 12 maanden. Het profieltype is een app-label, geen wetenschappelijke classificatie.</p>")
        }

        return RunnerProfile(
            type = profileType,
            description = description,
            weeklyVolume = "%.1f km".format(weeklyKm),
            frequency = "%.1fx/week".format(runsPerWeek),
            avgPace = paceStr,
            avgHr = Format.hr(avgHr),
            avgCadence = avgCadence?.let { "%.0f spm".format(it) } ?: "-",
            totalDistance = "%.1f km".format(totalDistance / 1000),
            totalTime = "${totalTime / 3600}u ${(totalTime % 3600) / 60}m",
            totalRuns = all.size,
            trainingSince = trainingSince,
            longestRun = longestRunStr,
            terrainPreference = terrainPref,
            classification = classification,
            intervalFrequency = intervalFrequencyStr,
            avgIntervalPace = intervalPaceStr,
        )
    }

    /**
     * Continuous efforts only (Riegel assumes a continuous maximal effort of 3.5-230 min):
     * - races (Strava `workout_type` = 1), whole activity, moving time;
     * - per standard distance, the fastest best effort in the window (from GPS streams / Strava), >= 3.5 min;
     * - whole runs with average HR >= 85 % of the estimated max HR that are not interval/tempo sessions.
     * Interval reps are never anchors: they are run with rest in between.
     */
    private fun anchors(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>, maxHr: Int?): List<RacePredictor.Anchor> {
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yy")
        val date = { a: Activity -> ActivityTime.local(a).format(fmt) }
        val result = mutableListOf<RacePredictor.Anchor>()

        runs.filter { it.workoutType == 1 && it.distance > 0 && it.movingTime > 0 }.forEach { a ->
            result += RacePredictor.Anchor(a.distance.toDouble(), a.movingTime.toDouble(), "wedstrijd ${formatDistance(a.distance)} (${date(a)})")
        }

        BestEffortFinder.STANDARD_DISTANCES.forEach { (label, meters) ->
            val best = runs.filter { !it.isTrainer }.mapNotNull { bestEffortService.effort(it, meters) }.minByOrNull { it.seconds }
            if (best != null && best.seconds >= RacePredictor.MIN_VALID_SECONDS) {
                result += RacePredictor.Anchor(meters, best.seconds, "snelste $label binnen een run (${date(best.activity)})")
            }
        }

        if (maxHr != null) {
            runs.filter { a ->
                a.workoutType != 1 && a.distance > 0 && a.movingTime > 0 &&
                    (a.averageHeartrate ?: 0f) >= maxHr * 0.85 &&
                    SessionClassifier.classify(lapsByActivity[a.id].orEmpty(), a.workoutType) == SessionType.STEADY
            }.forEach { a ->
                result += RacePredictor.Anchor(a.distance.toDouble(), a.movingTime.toDouble(), "harde run ${formatDistance(a.distance)}, gem. HR ≥ 85% max (${date(a)})")
            }
        }
        return result
    }

    private fun predictions(
        runs: List<Activity>,
        lapsByActivity: Map<Long, List<Lap>>,
        maxHr: Int?,
        windowLabel: String,
        distances: List<Float>,
    ): List<RacePrediction> {
        val anchors = anchors(runs, lapsByActivity, maxHr)
        return distances.map { dist ->
            val p = RacePredictor.predict(dist.toDouble(), anchors)
            if (p == null) {
                RacePrediction(formatDistanceLabel(dist), dist, "-", "-", null, null,
                    "onvoldoende data: minder dan ${RacePredictor.MIN_ANCHORS} doorlopende inspanningen ($windowLabel) binnen 0,4-2,5x deze afstand")
            } else {
                val rounded = RacePredictor.roundSeconds(p.seconds)
                val low = RacePredictor.roundSeconds(p.lowSeconds)
                val high = RacePredictor.roundSeconds(p.highSeconds)
                val sources = p.anchors.map { it.source }
                RacePrediction(
                    distance = formatDistanceLabel(dist),
                    distanceMeters = dist,
                    predictedTime = "≈ " + Format.duration(rounded),
                    predictedPace = Format.paceFromSeconds(rounded / (dist / 1000)),
                    range = if (high > low) "${Format.duration(low)} – ${Format.duration(high)}" else null,
                    basedOn = "mediaan van ${p.anchors.size}: " + sources.take(3).joinToString("; ") + if (sources.size > 3) "; …" else "",
                    note = if (p.outsideValidRange) "buiten het geldigheidsbereik van Riegel (3,5-230 min): extra onzeker" else null,
                )
            }
        }
    }

    private fun formatDistanceLabel(dist: Float): String = when (dist) {
        1000f -> "1 km"
        3000f -> "3 km"
        5000f -> "5 km"
        10000f -> "10 km"
        15000f -> "15 km"
        21097.5f -> "21,1 km (HM)"
        42195f -> "42,2 km (M)"
        else -> formatDistance(dist)
    }

    private fun formatDistance(meters: Float): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000) else "%.0f m".format(meters)

    companion object {
        private val RACE_DISTANCES = listOf(1000f, 3000f, 5000f, 10000f, 15000f, 21097.5f, 42195f)
        private val FORM_DISTANCES = listOf(1000f, 3000f, 5000f, 10000f, 21097.5f, 42195f)
    }
}
