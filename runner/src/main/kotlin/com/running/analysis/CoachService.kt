package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.ZonedDateTime

@Service
class CoachService(
    private val activityRepository: ActivityRepository,
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
        val basedOn: String?,
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
        val allRuns = runs

        // Bulk-fetch lap data once so we can use interval reps (fast laps within a session) as extra,
        // more accurate anchors for race predictions and to enrich the runner profile — instead of only
        // relying on whole-activity averages / Strava-detected best efforts.
        val lapsByActivity = activityRepository.findLapsForActivities(allRuns.map { it.id })

        val sortedHr = lastYear.mapNotNull { it.maxHeartrate?.toInt() }.sorted()
        val maxHr = if (sortedHr.size >= 5) {
            sortedHr[(sortedHr.size * 0.95).toInt().coerceAtMost(sortedHr.size - 1)]
        } else {
            sortedHr.lastOrNull()
        }
        val rawMaxHr = sortedHr.lastOrNull()
        val allTimeMaxHr = allRuns.mapNotNull { it.maxHeartrate?.toInt() }.maxOrNull()
        val maxHrSource = when {
            maxHr != null && rawMaxHr != null && rawMaxHr > maxHr ->
                "laatste 12 maanden ($maxHr bpm, uitschieter $rawMaxHr bpm genegeerd)"
            maxHr != null && allTimeMaxHr != null && allTimeMaxHr > maxHr ->
                "laatste 12 maanden ($maxHr bpm)"
            maxHr != null -> "laatste 12 maanden"
            else -> "onbekend"
        }

        val effectiveRhr = restingHr ?: estimateRestingHr(lastYear)

        val hrZoneMethods = mutableListOf<HrZoneMethod>()

        if (maxHr != null) {
            hrZoneMethods.add(HrZoneMethod(
                method = "pctMaxHr",
                label = "% van max. hartslag",
                zones = calculatePctMaxHrZones(maxHr),
            ))
        }

        if (maxHr != null && effectiveRhr != null && effectiveRhr < maxHr) {
            hrZoneMethods.add(HrZoneMethod(
                method = "karvonen",
                label = "Karvonen (HR-reserve —  nauwkeuriger)",
                zones = calculateKarvonenZones(maxHr, effectiveRhr),
            ))
        }

        val rhrLabel = if (restingHr != null) "door jou ingesteld ($restingHr bpm)" else "geschat uit HR-data"

        return CoachData(
            hrZoneMethods = hrZoneMethods,
            maxHr = maxHr,
            maxHrSource = maxHrSource,
            restingHr = effectiveRhr,
            restingHrSource = rhrLabel,
            runnerProfile = buildRunnerProfile(lastYear, allRuns, lapsByActivity),
            racePredictions = calculateRacePredictions(lastYear, lapsByActivity, maxHr),
            recentFormPredictions = calculateRecentFormPredictions(lastYear, lapsByActivity, maxHr),
        )
    }

    private fun calculatePctMaxHrZones(maxHr: Int): List<HrZone> {
        return listOf(
            HrZone(1, "Herstel", "Zeer lichte inspanning. Herstellopen, warming-up & cooling-down.",
                (maxHr * 0.50).toInt(), (maxHr * 0.60).toInt(), "50-60%"),
            HrZone(2, "Duur", "Licht aerobisch. Verbetert vetverbranding & aerobe basis. Moet vloeiend aanvoelen.",
                (maxHr * 0.60).toInt(), (maxHr * 0.70).toInt(), "60-70%"),
            HrZone(3, "Tempo", "Matig. Verbetert aerobe capaciteit & loopefficiëntie. Marathon tot halve marathon tempo.",
                (maxHr * 0.70).toInt(), (maxHr * 0.80).toInt(), "70-80%"),
            HrZone(4, "Drempel", "Hoog. Op of net onder lactaatdrempel. Verhoogt uithouding op hoog tempo. 10km tot 5km tempo.",
                (maxHr * 0.80).toInt(), (maxHr * 0.90).toInt(), "80-90%"),
            HrZone(5, "VO2Max", "Maximaal. Verbetert maximale zuurstofopname & snelheid. 3km tot 1km tempo.",
                (maxHr * 0.90).toInt(), maxHr, "90-100%"),
        )
    }

    private fun calculateKarvonenZones(maxHr: Int, restingHr: Int): List<HrZone> {
        val hrr = maxHr - restingHr
        return listOf(
            HrZone(1, "Herstel", "Zeer lichte inspanning. Herstellopen, warming-up & cooling-down.",
                (hrr * 0.50 + restingHr).toInt(), (hrr * 0.60 + restingHr).toInt(), "50-60% HRR"),
            HrZone(2, "Duur", "Licht aerobisch. Verbetert vetverbranding & aerobe basis.",
                (hrr * 0.60 + restingHr).toInt(), (hrr * 0.70 + restingHr).toInt(), "60-70% HRR"),
            HrZone(3, "Tempo", "Matig. Verbetert aerobe capaciteit & loopefficiëntie.",
                (hrr * 0.70 + restingHr).toInt(), (hrr * 0.80 + restingHr).toInt(), "70-80% HRR"),
            HrZone(4, "Drempel", "Hoog. Op of net onder lactaatdrempel.",
                (hrr * 0.80 + restingHr).toInt(), (hrr * 0.90 + restingHr).toInt(), "80-90% HRR"),
            HrZone(5, "VO2Max", "Maximaal. Verbetert maximale zuurstofopname & snelheid.",
                (hrr * 0.90 + restingHr).toInt(), maxHr, "90-100% HRR"),
        )
    }

    private fun estimateRestingHr(runs: List<Activity>): Int? {
        val candidates = runs
            .filter { it.hasHeartrate }
            .sortedByDescending { it.startDate }
            .take(20)

        val allHrValues = mutableListOf<Int>()

        for (a in candidates) {
            val streams = activityRepository.findStreams(a.id) ?: continue
            val hr = streams.heartrate ?: continue
            allHrValues.addAll(hr.filter { it in 30..200 })
        }

        if (allHrValues.size < 50) return null

        allHrValues.sort()
        val idx = (allHrValues.size * 0.005).toInt().coerceAtLeast(0)
        val resting = allHrValues[idx]
        if (resting > 85) return 85
        return resting.coerceIn(40, 85)
    }

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

        val avgSpeed = recent.mapNotNull { it.averageSpeed }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgHr = recent.mapNotNull { it.averageHeartrate }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgCadence = recent.mapNotNull { it.averageCadence }.average().takeIf { !it.isNaN() } ?: 0.0

        val recentDist = recent.sumOf { it.distance.toDouble() }
        val recentTime = recent.sumOf { it.movingTime.toLong() }
        val recentFirst = recent.minOf { it.startDate }
        val recentWeeks = Duration.between(recentFirst, now).toDays() / 7.0
        val weeklyKm = if (recentWeeks >= 1) recentDist / 1000 / recentWeeks else recentDist / 1000
        val runsPerWeek = recent.size.toDouble() / recentWeeks.coerceAtLeast(1.0)

        val avgElevPerKm = if (recentDist > 0)
            recent.sumOf { it.totalElevationGain.toDouble() } / (recentDist / 1000)
        else 0.0
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
        val trainingSince = when {
            monthsTraining >= 12 -> "${monthsTraining / 12}j ${monthsTraining % 12}mnd"
            else -> "${monthsTraining}mnd"
        }

        val classification = when {
            weeklyKm < 15 -> "Beginner"
            weeklyKm < 30 -> "Recreatief"
            weeklyKm < 50 -> "Gevorderd"
            weeklyKm < 70 -> "Gevorderd+"
            else -> "Elite"
        }

        val paceStr = if (avgSpeed > 0) {
            val p = (1000 / avgSpeed).toInt()
            "${p / 60}:${(p % 60).toString().padStart(2, '0')} /km"
        } else "-"

        // Structured interval training is a strong signal of how a runner trains, independent of raw
        // volume or average pace — someone doing regular quality sessions trains very differently from
        // someone who just accumulates easy mileage, even at similar weekly volume.
        val intervalSessionCount = recent.count { LapClassifier.hasIntervals(lapsByActivity[it.id].orEmpty()) }
        val intervalSessionsPerWeek = intervalSessionCount / recentWeeks.coerceAtLeast(1.0)
        val allIntervalReps = recent.flatMap { intervalReps(it.id, lapsByActivity) }
        val avgIntervalSpeed = allIntervalReps.map { it.averageSpeed.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val intervalPaceStr = if (avgIntervalSpeed > 0) {
            val p = (1000 / avgIntervalSpeed).toInt()
            "${p / 60}:${(p % 60).toString().padStart(2, '0')} /km"
        } else "-"
        val intervalFrequencyStr = if (intervalSessionCount > 0) {
            "%.1fx/week".format(intervalSessionsPerWeek)
        } else "Geen"

        val profileType = when {
            avgElevPerKm >= 15 -> "Berggeit"
            intervalSessionsPerWeek >= 0.4 && avgIntervalSpeed > 0 -> "Intervaltrainer"
            avgSpeed > 4.5 -> "Snelheidsduivel"
            avgSpeed > 3.5 -> "Tempoloper"
            weeklyKm >= 40 -> "Uithoudingsatleet"
            runsPerWeek >= 5 -> "Frequente loper"
            else -> "Allround loper"
        }

        val description = buildString {
            append("<p><strong>$profileType</strong> &mdash; ")
            append("Je loopt gemiddeld <strong>${"%.1f".format(weeklyKm)} km</strong> per week ")
            append("over <strong>${"%.1f".format(runsPerWeek)}x</strong> per week. ")
            append("Je gemiddelde tempo is <strong>$paceStr</strong>")
            if (avgHr > 0) append(" bij <strong>${"%.0f".format(avgHr)} bpm</strong>")
            append(".</p>")
            append("<p>Je trainingsgebied is <strong>${terrainPref.lowercase()}</strong>")
            if (avgCadence > 0) append(" met een cadence van <strong>${"%.0f".format(avgCadence)} spm</strong>")
            append(". Je langste run ooit is <strong>$longestRunStr</strong>. ")
            append("Je bent actief sinds <strong>$trainingSince</strong> ")
            append("met <strong>${all.size} runs</strong>, ")
            append("<strong>${"%.0f".format(totalDistance / 1000)} km</strong> ")
            append("en <strong>${totalTime / 3600}u ${(totalTime % 3600) / 60}m</strong> totaal.</p>")
            if (intervalSessionCount > 0) {
                append("<p>Je doet gemiddeld <strong>${"%.1f".format(intervalSessionsPerWeek)}x</strong> per week ")
                append("een training met intervallen, aan een gemiddeld intervaltempo van <strong>$intervalPaceStr</strong>. ")
                append("Deze data wordt gebruikt om je wedstrijd- en vormvoorspellingen nauwkeuriger te maken.</p>")
            } else {
                append("<p class=\"text-muted\">Geen intervaltrainingen gedetecteerd in de laatste 12 maanden — ")
                append("voorspellingen zijn daardoor gebaseerd op hele runs en PR-segmenten.</p>")
            }
            append("<p class=\"text-muted\">Classificatie: <strong>$classification</strong> &mdash; ")
            append("profiel gebaseerd op laatste 12 maanden.</p>")
        }

        return RunnerProfile(
            type = profileType,
            description = description,
            weeklyVolume = "%.1f km".format(weeklyKm),
            frequency = "%.1fx/week".format(runsPerWeek),
            avgPace = paceStr,
            avgHr = if (avgHr > 0) "%.0f bpm".format(avgHr) else "-",
            avgCadence = if (avgCadence > 0) "%.0f spm".format(avgCadence) else "-",
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

    /** Minimum lap distance/duration for an interval rep to be trusted as a prediction anchor — short
     * or very brief "reps" (e.g. strides, GPS glitches) are too noisy to extrapolate from reliably. */
    private val MIN_REP_DISTANCE_METERS = 150f
    private val MIN_REP_DURATION_SECONDS = 30

    /** Returns the laps of an activity that [LapClassifier] flagged as fast interval reps (as opposed
     * to recovery/jog laps), filtered to those substantial enough to be a trustworthy pace anchor. */
    private fun intervalReps(activityId: Long, lapsByActivity: Map<Long, List<Lap>>): List<Lap> {
        val laps = lapsByActivity[activityId].orEmpty()
        if (laps.size < 2) return emptyList()
        val flags = LapClassifier.classifyIntervals(laps)
        return laps.filterIndexed { i, lap ->
            flags[i] && lap.distance >= MIN_REP_DISTANCE_METERS && lap.movingTime >= MIN_REP_DURATION_SECONDS
        }
    }

    /** A known performance (distance + time) that can be used as a starting point for a Riegel
     * extrapolation to another distance. */
    private data class Anchor(
        val distanceMeters: Float,
        val timeSeconds: Float,
        val source: String,
    )

    /** Riegel's exponent for translating a known performance at one distance to a predicted time at
     * another distance: predictedTime = knownTime * (targetDistance / knownDistance)^1.06. The formula
     * is most reliable when the two distances aren't too far apart, so callers should prefer anchors
     * whose distance is reasonably close to the target. */
    private val RIEGEL_EXPONENT = 1.06

    /** Builds a prediction for [dist] from the given pool of [anchors]. Anchors whose distance is
     * within a 0.4x-2.5x window of the target are strongly preferred (Riegel's exponent becomes
     * unreliable over larger extrapolations — e.g. projecting a 400m interval rep all the way up to
     * marathon distance). If no anchor falls in that window we still fall back to the full pool so a
     * prediction is always returned when *some* data exists. */
    private fun predictFromAnchors(dist: Float, anchors: List<Anchor>): RacePrediction? {
        if (anchors.isEmpty()) return null

        data class Scored(val anchor: Anchor, val ratio: Double, val predictedSeconds: Double)

        val scored = anchors.mapNotNull { anchor ->
            if (anchor.distanceMeters <= 0 || anchor.timeSeconds <= 0) return@mapNotNull null
            val ratio = dist / anchor.distanceMeters.toDouble()
            val predictedSeconds = anchor.timeSeconds * Math.pow(ratio, RIEGEL_EXPONENT)
            Scored(anchor, ratio, predictedSeconds)
        }
        if (scored.isEmpty()) return null

        val reliable = scored.filter { it.ratio in 0.4..2.5 }
        val pool = reliable.ifEmpty { scored }
        val best = pool.minByOrNull { it.predictedSeconds } ?: return null

        val paceSeconds = best.predictedSeconds / (dist / 1000)
        val extrapolationNote = if (reliable.isEmpty()) " (verre extrapolatie)" else ""
        return RacePrediction(
            distance = formatDistanceLabel(dist),
            distanceMeters = dist,
            predictedTime = formatDuration(best.predictedSeconds.toInt()),
            predictedPace = formatPace(paceSeconds),
            basedOn = "${best.anchor.source}$extrapolationNote",
        )
    }

    private fun formatDistanceLabel(dist: Float): String = when (dist) {
        1000f -> "1 km"
        3000f -> "3 km"
        5000f -> "5 km"
        10000f -> "10 km"
        15000f -> "15 km"
        21097f -> "21,1 km (HM)"
        42195f -> "42,2 km (M)"
        else -> formatDistance(dist)
    }


    /** A whole training run is normally *not* a reliable race-pace anchor: most runs (including the
     * "recent run" that used to drive Vormvoorspelling) are easy/moderate efforts, not all-out
     * attempts, so extrapolating from their pace with Riegel just predicts "you can race at your jogging
     * pace" — which is what made predictions like "3 km in 15:53, based on a recent 6 km run" nonsensical.
     * We only trust a whole-run anchor when there's actual evidence it was a hard effort:
     *   - Strava marked it as a race (workoutType == 1), or
     *   - its average HR was close to the runner's max HR, or
     *   - it was notably faster than the runner's own typical pace (top ~15% of their runs).
     * Best-efforts segments and classified interval reps are always near-maximal by construction, so
     * they don't need this filter. */
    private fun hardEffortWholeRunAnchors(runs: List<Activity>, maxHr: Int?): List<Anchor> {
        val speeds = runs.mapNotNull { if (it.averageSpeed > 0) it.averageSpeed.toDouble() else null }.sorted()
        val fastThreshold = if (speeds.size >= 5) speeds[(speeds.size * 0.85).toInt().coerceAtMost(speeds.size - 1)] else null

        return runs.mapNotNull { a ->
            if (a.distance <= 0 || a.movingTime <= 0) return@mapNotNull null
            val label = when {
                a.workoutType == 1 -> "wedstrijd van ${formatDistance(a.distance)}"
                maxHr != null && a.averageHeartrate != null && a.averageHeartrate!! >= maxHr * 0.85 ->
                    "harde inspanning (HR) van ${formatDistance(a.distance)}"
                fastThreshold != null && a.averageSpeed >= fastThreshold ->
                    "snelle training van ${formatDistance(a.distance)}"
                else -> null
            } ?: return@mapNotNull null
            Anchor(a.distance, a.movingTime.toFloat(), label)
        }
    }

    private fun bestEffortAndIntervalAnchors(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>): List<Anchor> {
        val anchors = mutableListOf<Anchor>()
        for (a in runs) {
            a.bestEfforts?.forEach { e ->
                if (e.distance > 0 && e.movingTime > 0) {
                    anchors += Anchor(e.distance, e.movingTime.toFloat(), "PR-segment van ${formatDistance(e.distance)}")
                }
            }
            intervalReps(a.id, lapsByActivity).forEach { lap ->
                anchors += Anchor(lap.distance, lap.movingTime.toFloat(), "intervalrep van ${formatDistance(lap.distance)}")
            }
        }
        return anchors
    }

    private fun calculateRacePredictions(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>, maxHr: Int?): List<RacePrediction> {
        val distances = listOf(1000f, 3000f, 5000f, 10000f, 15000f, 21097f, 42195f)
        val anchors = hardEffortWholeRunAnchors(runs, maxHr) + bestEffortAndIntervalAnchors(runs, lapsByActivity)

        return distances.map { dist ->
            predictFromAnchors(dist, anchors) ?: RacePrediction(formatDistanceLabel(dist), dist, "-", "-", null)
        }
    }

    /** Recent-form predictions should reflect current fitness, not the whole training history. We look
     * at the last 6 weeks of data and — crucially — also draw on interval reps and hard efforts run in
     * that window, not just any recent whole run. Without this filter, a runner whose last activities
     * were all easy jogs would get a "form" prediction that's really just their jogging pace re-labelled
     * as a race prediction. */
    private fun calculateRecentFormPredictions(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>, maxHr: Int?): List<RacePrediction> {
        val distances = listOf(1000f, 3000f, 5000f, 10000f, 21097f, 42195f)
        val now = ZonedDateTime.now()
        val recentWindow = runs.filter { it.startDate.isAfter(now.minusWeeks(6)) }
        val recent = recentWindow.takeIf { it.isNotEmpty() }
            ?: runs.sortedByDescending { it.startDate }.take(5)

        // The "hard effort" pace threshold is computed against the runner's whole recent history (not
        // just this narrow window), otherwise a window with only easy runs would have no real fast/slow
        // contrast to compare against and everything would look "fast" relative to itself.
        val anchors = hardEffortWholeRunAnchors(recent, maxHr).ifEmpty {
            // No clearly hard whole-run effort in the window — still compare recent pace against the
            // full history's threshold so a genuinely solid recent run isn't discarded.
            val referencePool = runs.takeIf { it.size > recent.size } ?: recent
            val speeds = referencePool.mapNotNull { if (it.averageSpeed > 0) it.averageSpeed.toDouble() else null }.sorted()
            val fastThreshold = if (speeds.size >= 5) speeds[(speeds.size * 0.85).toInt().coerceAtMost(speeds.size - 1)] else null
            recent.mapNotNull { a ->
                if (a.distance <= 0 || a.movingTime <= 0) return@mapNotNull null
                if (fastThreshold == null || a.averageSpeed < fastThreshold) return@mapNotNull null
                Anchor(a.distance, a.movingTime.toFloat(), "snelle recente training van ${formatDistance(a.distance)}")
            }
        } + bestEffortAndIntervalAnchors(recent, lapsByActivity).map {
            it.copy(source = "recente ${it.source}")
        }

        if (anchors.isEmpty()) {
            return distances.map { dist -> RacePrediction(formatDistanceLabel(dist), dist, "-", "-", null) }
        }

        return distances.map { dist ->
            predictFromAnchors(dist, anchors) ?: RacePrediction(formatDistanceLabel(dist), dist, "-", "-", null)
        }
    }


    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "${m}:${s.toString().padStart(2, '0')}"
    }

    private fun formatPace(secondsPerKm: Double): String {
        if (secondsPerKm <= 0 || secondsPerKm.isNaN() || secondsPerKm.isInfinite()) return "-"
        val totalSec = secondsPerKm.toInt()
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')} /km"
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000) "%.1f km".format(meters / 1000) else "%.0f m".format(meters)
    }
}
