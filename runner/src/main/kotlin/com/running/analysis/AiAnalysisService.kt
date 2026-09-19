package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.ZonedDateTime

@Service
class AiAnalysisService(
    private val activityRepository: ActivityRepository,
    private val periodComparisonService: PeriodComparisonService,
) {

    fun buildTrainingContext(): String {
        val activities = activityRepository.findAll()
            .filter { it.type in listOf("Run", "TrailRun", "VirtualRun") }
            .sortedBy { it.startDate }

        if (activities.isEmpty()) return "No training data available."

        return buildString {
            appendLine("TRAININGSHISTORIEK")
            appendLine("=" .repeat(50))

            val recentActivities = activities.takeLast(30)
            appendLine("\n## Laatste ${recentActivities.size} activiteiten\n")
            recentActivities.forEachIndexed { index, activity ->
                appendLine("${index + 1}. ${activity.startDate.toLocalDate()}")
                appendLine("   Type: ${activity.type}")
                appendLine("   Naam: ${activity.name}")
                appendLine("   Afstand: ${"%.2f".format(activity.distance / 1000)} km")
                appendLine("   Tijd: ${activity.movingTime / 60} min")
                appendLine("   Pace: ${calculatePace(activity.averageSpeed?.toDouble() ?: 0.0)}")
                activity.averageHeartrate?.let { appendLine("   Gem. HR: $it bpm") }
                activity.maxHeartrate?.let { appendLine("   Max HR: $it bpm") }
                activity.averageCadence?.let { appendLine("   Cadence: $it rpm") }
                activity.averageWatts?.let { appendLine("   Vermogen: $it W") }
                appendLine("   Elevation: ${activity.totalElevationGain} m")
                appendLine()
            }

            appendLine("\n## Totaaloverzicht\n")
            val totalDistance = activities.sumOf { it.distance.toDouble() }
            val totalTime = activities.sumOf { it.movingTime.toLong() }
            val avgHeartrate = activities.mapNotNull { it.averageHeartrate }.average()
            appendLine("Totale afstand: ${"%.1f".format(totalDistance / 1000)} km")
            appendLine("Totale tijd: ${totalTime / 3600} uur")
            appendLine("Gemiddelde HR: ${"%.0f".format(avgHeartrate)} bpm")
            appendLine("Aantal runs: ${activities.size}")

            val weeklyMileage = activities
                .groupBy { it.startDate.toLocalDate().with(DayOfWeek.MONDAY) }
                .mapValues { it.value.sumOf { a -> a.distance.toDouble() } }
            appendLine("\n## Wekelijkse kilometers\n")
            weeklyMileage.toSortedMap().forEach { (week, distance) ->
                appendLine("  $week: ${"%.1f".format(distance / 1000)} km")
            }
        }
    }

    fun buildAiPrompt(): String {
        val context = buildTrainingContext()
        if (context == "No training data available.") return context

        return """
            |Je bent een ervaren hardloopcoach. Analyseer onderstaande trainingshistoriek en geef concreet advies.
            |Behandel minimaal deze punten:
            |1. Ben ik aan het overtrainen?
            |2. Hoe evolueert mijn lactaatdrempel?
            |3. Wat is mijn ideale trainingsweek?
            |4. Voorspelling voor halve marathon / marathon
            |5. Blessurerisico op basis van mijn trainingsbelasting
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers en per punt een duidelijk actieplan.
            |
            |<trainingshistoriek>
            |$context
            |</trainingshistoriek>
        """.trimMargin()
    }

    private fun calculatePace(speedMs: Double): String {
        if (speedMs <= 0) return "-"
        val paceSeconds = (1000 / speedMs).toInt()
        return "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')} /km"
    }

    fun buildComparisonPrompt(
        fromA: ZonedDateTime,
        tillA: ZonedDateTime,
        fromB: ZonedDateTime,
        tillB: ZonedDateTime,
        labelA: String,
        labelB: String,
    ): String {
        val result = periodComparisonService.compare(fromA, tillA, fromB, tillB, labelA, labelB)

        val context = buildString {
            appendLine("VERGELIJKING TRAININGSPERIODES")
            appendLine("=".repeat(50))
            appendLine()
            listOf(result.periodA, result.periodB).forEach { p ->
                appendLine("## ${p.label}")
                appendLine("Aantal trainingen: ${p.activityCount}")
                appendLine("Totale afstand: ${"%.1f".format(p.totalDistanceKm)} km")
                appendLine("Totale tijd: ${"%.1f".format(p.totalTimeHours)} uur")
                appendLine()
                appendLine("Rustige/duurlopen (${p.easyRunCount}x):")
                appendLine("  Gem. tempo: ${p.easyAvgPace}")
                appendLine("  Gem. HR: ${p.easyAvgHr?.let { "%.0f bpm".format(it) } ?: "-"}")
                appendLine("  Efficiëntiefactor (snelheid/HR): ${p.easyEf?.let { "%.4f".format(it) } ?: "-"}")
                appendLine()
                appendLine("Intervaltrainingen (${p.intervalSessionCount} sessies, ${p.intervalRepCount} reps):")
                appendLine("  Gem. tempo per rep: ${p.intervalAvgPace}")
                appendLine("  Gem. HR per rep: ${p.intervalAvgHr?.let { "%.0f bpm".format(it) } ?: "-"}")
                appendLine("  Efficiëntiefactor (snelheid/HR): ${p.intervalEf?.let { "%.4f".format(it) } ?: "-"}")
                appendLine()
            }
            appendLine("## Berekende verandering (${result.periodA.label} -> ${result.periodB.label})")
            appendLine("Verandering efficiëntie rustige lopen: ${result.easyEfChangePct?.let { "%.1f%%".format(it) } ?: "onbekend"}")
            appendLine("Verandering efficiëntie intervallen: ${result.intervalEfChangePct?.let { "%.1f%%".format(it) } ?: "onbekend"}")
            result.easyPaceAtRefHr?.let { (a, b) ->
                appendLine("Geschat tempo bij gelijke inspanning (150 bpm) rustige lopen: $a -> $b")
            }
            result.intervalPaceAtRefHr?.let { (a, b) ->
                appendLine("Geschat tempo bij gelijke inspanning (150 bpm) intervallen: $a -> $b")
            }
            appendLine()
            appendLine("Automatische conclusie van de app: ${result.verdict}")
        }

        return """
            |Je bent een ervaren hardloopcoach. Vergelijk onderstaande twee trainingsperiodes en analyseer
            |of het loopniveau van de gebruiker vooruit- of achteruitgaat.
            |Behandel minimaal deze punten:
            |1. Is de aerobe efficiëntie (tempo per hartslag) bij rustige lopen verbeterd of verslechterd?
            |2. Is de kwaliteit van de intervaltrainingen (tempo & hartslag per herhaling) verbeterd?
            |3. Welke concrete verklaring kan er zijn voor de verandering (trainingsvolume, intensiteit, rust)?
            |4. Wat zou de gebruiker moeten aanpassen om verder te verbeteren richting de volgende vergelijkingsperiode?
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers en een duidelijk eindoordeel.
            |
            |<trainingsvergelijking>
            |$context
            |</trainingsvergelijking>
        """.trimMargin()
    }
}
