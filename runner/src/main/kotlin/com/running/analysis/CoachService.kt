package com.running.analysis

import com.running.strava.domain.Activity
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
            runnerProfile = buildRunnerProfile(lastYear, allRuns),
            racePredictions = calculateRacePredictions(lastYear),
            recentFormPredictions = calculateRecentFormPredictions(lastYear),
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

    private fun buildRunnerProfile(recent: List<Activity>, all: List<Activity>): RunnerProfile {
        if (recent.isEmpty()) {
            return RunnerProfile(
                type = "Onbekend", description = "Geen trainingsdata beschikbaar.",
                weeklyVolume = "-", frequency = "-", avgPace = "-", avgHr = "-", avgCadence = "-",
                totalDistance = "-", totalTime = "-", totalRuns = 0, trainingSince = "-",
                longestRun = "-", terrainPreference = "-", classification = "-",
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

        val profileType = when {
            avgElevPerKm >= 15 -> "Berggeit"
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
        )
    }

    private fun calculateRacePredictions(runs: List<Activity>): List<RacePrediction> {
        val distances = listOf(
            "1 km" to 1000f,
            "3 km" to 3000f,
            "5 km" to 5000f,
            "10 km" to 10000f,
            "15 km" to 15000f,
            "21,1 km (HM)" to 21097f,
            "42,2 km (M)" to 42195f,
        )

        val performances = mutableMapOf<Float, Float>()

        for (a in runs) {
            a.bestEfforts?.forEach { e ->
                if (e.distance > 0 && e.movingTime > 0) {
                    performances.merge(e.distance, e.movingTime.toFloat()) { old, new -> minOf(old, new) }
                }
            }
        }

        for ((_, dist) in distances) {
            val best = runs
                .filter { it.distance >= dist * 0.95 && it.distance <= dist * 1.05 && it.averageSpeed > 0 }
                .minByOrNull { it.movingTime.toFloat() / it.distance }
            if (best != null) {
                performances.merge(dist, best.movingTime.toFloat()) { old, new -> minOf(old, new) }
            }
        }

        return distances.map { (label, dist) ->
            val best = performances
                .filter { it.key > 0 && it.value > 0 }
                .mapNotNull { (knownDist, knownTime) ->
                    val predictedSeconds = knownTime * Math.pow((dist / knownDist).toDouble(), 1.06)
                    val paceSeconds = predictedSeconds / (dist / 1000)
                    Triple(predictedSeconds, paceSeconds, formatDistance(knownDist))
                }
                .filter { it.first > 0 }
                .minByOrNull { it.first }

            if (best != null) {
                RacePrediction(
                    distance = label,
                    distanceMeters = dist,
                    predictedTime = formatDuration(best.first.toInt()),
                    predictedPace = formatPace(best.second),
                    basedOn = best.third,
                )
            } else {
                RacePrediction(label, dist, "-", "-", null)
            }
        }
    }

    private fun calculateRecentFormPredictions(runs: List<Activity>): List<RacePrediction> {
        val distances = listOf(
            "1 km" to 1000f,
            "3 km" to 3000f,
            "5 km" to 5000f,
            "10 km" to 10000f,
            "21,1 km (HM)" to 21097f,
            "42,2 km (M)" to 42195f,
        )

        val recent = runs.sortedByDescending { it.startDate }
            .take(5)
            .filter { it.averageSpeed > 0 && it.distance > 0 }

        if (recent.isEmpty()) {
            return distances.map { (label, dist) -> RacePrediction(label, dist, "-", "-", null) }
        }

        val best = recent.maxByOrNull { it.averageSpeed }!!
        val anchorDist = best.distance.coerceAtLeast(1000f)
        val anchorTime = best.movingTime.toFloat()

        return distances.map { (label, dist) ->
            val predictedSeconds = anchorTime * Math.pow((dist / anchorDist).toDouble(), 1.06)
            val paceSeconds = predictedSeconds / (dist / 1000)
            RacePrediction(
                distance = label,
                distanceMeters = dist,
                predictedTime = formatDuration(predictedSeconds.toInt()),
                predictedPace = formatPace(paceSeconds),
                basedOn = "Beste recente run: ${"%.2f".format(best.distance / 1000)} km in ${formatDuration(best.movingTime)}",
            )
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
