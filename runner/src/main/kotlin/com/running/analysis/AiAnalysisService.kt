package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.WorkoutStructure
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.ZonedDateTime

data class NextTrainingParams(
    val distanceKm: Double? = null,
    val trainingType: String? = null,
    val goalRace: String? = null,
    val raceDate: String? = null,
    val goalTime: String? = null,
    val trainingDate: String? = null,
    val notes: String? = null,
)

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
            val lapsByActivity = activityRepository.findLapsForActivities(recentActivities.map { it.id })
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
                val laps = lapsByActivity[activity.id] ?: emptyList()
                val intervalSummary = formatIntervalSummary(laps)
                if (intervalSummary != null) appendLine("   Intervallen: $intervalSummary")
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

    /** Workout structure (warm-up, sets per rep distance, recovery/floats, cool-down), or null if no intervals. */
    private fun formatIntervalSummary(laps: List<Lap>): String? = WorkoutStructure.describe(laps)


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

    fun buildNextTrainingPrompt(params: NextTrainingParams): String {
        val context = buildTrainingContext()
        if (context == "No training data available.") return context

        val details = buildString {
            appendLine("Geplande training: ${params.distanceKm?.let { "%.1f km".format(it) } ?: "afstand niet opgegeven"}")
            params.trainingType?.takeIf { it.isNotBlank() }?.let { appendLine("Type training (uit schema): $it") }
            params.goalRace?.takeIf { it.isNotBlank() }?.let { appendLine("Doelwedstrijd: $it") }
            params.raceDate?.takeIf { it.isNotBlank() }?.let { appendLine("Datum doelwedstrijd: $it") }
            params.goalTime?.takeIf { it.isNotBlank() }?.let { appendLine("Doeltijd: $it") }
            params.trainingDate?.takeIf { it.isNotBlank() }?.let { appendLine("Datum van deze training: $it") }
            params.notes?.takeIf { it.isNotBlank() }?.let { appendLine("Extra context/opmerkingen: $it") }
        }

        return """
            |Je bent een ervaren hardloopcoach. Ik moet binnenkort een specifieke training uitvoeren
            |(bv. uit een trainingsschema zoals Runna) en wil weten hoe ik die optimaal aanpak, rekening
            |houdend met mijn recente trainingshistoriek.
            |
            |<geplande_training>
            |$details
            |</geplande_training>
            |
            |Geef concreet en gestructureerd advies in het Nederlands, met minstens:
            |1. Aanbevolen tempo/pace-zones voor deze training (per onderdeel als het een gestructureerde
            |   training is, bv. warming-up, kern, blokken/intervallen, cooling-down).
            |2. Aanbevolen hartslagzones voor deze training, gebaseerd op mijn recente HR-data.
            |3. Hoe ik deze training moet indelen (bv. negative split, even tempo, blokken) om zowel goed te
            |   presteren als te herstellen richting de volgende trainingen.
            |4. Waar ik op moet letten gezien mijn recente trainingsbelasting (vermoeidheid, herstel,
            |   blessurerisico) - pas het advies eventueel aan als de belasting te hoog lijkt.
            |5. Hoe deze training bijdraagt aan mijn doel (doelwedstrijd/doeltijd indien opgegeven) en wat
            |   een realistisch verwacht resultaat is.
            |6. Voeding/hydratatie en warming-up tips specifiek voor deze afstand/type training.
            |
            |Geef concrete cijfers (tempo's in min/km, hartslag in bpm) op basis van mijn data hierboven,
            |geen vage algemene richtlijnen.
            |
            |<trainingshistoriek>
            |$context
            |</trainingshistoriek>
        """.trimMargin()
    }

    fun buildActivityRatingPrompt(activityId: Long): String? {
        val activity = activityRepository.findById(activityId) ?: return null
        val laps = activityRepository.findLaps(activityId)
        val context = buildTrainingContext()

        val detail = buildString {
            appendLine("Datum: ${activity.startDate.toLocalDate()}")
            appendLine("Naam: ${activity.name}")
            appendLine("Type: ${activity.type}")
            appendLine("Afstand: ${"%.2f".format(activity.distance / 1000)} km")
            appendLine("Tijd: ${activity.movingTime / 60} min")
            appendLine("Gem. tempo: ${calculatePace(activity.averageSpeed?.toDouble() ?: 0.0)}")
            activity.averageHeartrate?.let { appendLine("Gem. HR: ${"%.0f".format(it)} bpm") }
            activity.maxHeartrate?.let { appendLine("Max HR: ${"%.0f".format(it)} bpm") }
            activity.averageCadence?.let { appendLine("Gem. cadans: ${"%.0f".format(it)} rpm") }
            activity.averageWatts?.let { appendLine("Gem. vermogen: ${"%.0f".format(it)} W") }
            appendLine("Hoogtemeters: ${"%.0f".format(activity.totalElevationGain)} m")
            activity.sufferScore?.let { appendLine("Suffer score (Strava): $it") }

            if (laps.size > 1) {
                val intervalSummary = formatIntervalSummary(laps)
                if (intervalSummary != null) {
                    appendLine()
                    appendLine("Opbouw training (automatisch herkend): $intervalSummary")
                }
                val labels = lapLabels(laps)
                appendLine()
                appendLine("Rondes/laps (${laps.size}):")
                laps.forEachIndexed { i, lap ->
                    appendLine(
                        "  Lap ${lap.lapIndex}: ${"%.0f".format(lap.distance)} m in ${formatDuration(lap.movingTime)}, " +
                            "${calculatePace(lap.averageSpeed.toDouble())}, " +
                            "${lap.averageHeartrate?.let { "gem. HR ${"%.0f".format(it)}" } ?: "HR onbekend"}" +
                            (lap.maxHeartrate?.let { " / max ${"%.0f".format(it)}" } ?: "") +
                            " - ${labels[i]}"
                    )
                }
            } else {
                appendLine()
                appendLine("Geen rondes/laps geregistreerd voor deze activiteit (geen intervaldetail beschikbaar).")
            }
        }

        return """
            |Je bent een ervaren hardloopcoach. Beoordeel onderstaande specifieke training grondig.
            |Behandel minimaal deze punten:
            |1. Was dit een nuttige/optimale training gezien type, afstand, tempo en hartslag - en waarom (niet)?
            |2. Als er intervallen/reps waren: was de uitvoering consistent (tempo/HR per rep), was de
            |   herstelduur/afstand tussen reps gepast, en zaten de reps in de juiste intensiteitszone voor het
            |   beoogde doel (bv. VO2max, drempel, snelheid)?
            |3. Hoe verhoudt deze training zich tot mijn recente trainingshistoriek (te licht, precies goed, te
            |   zwaar, past ze goed binnen de opbouw)?
            |4. Concreet cijfermatig advies: wat had beter gekund (tempo's, hartslagzones, aantal/duur reps,
            |   hersteltijd) om meer trainingseffect te halen richting een sneller/beter looptempo?
            |5. Een score op 10 voor hoe nuttig/optimaal deze training was voor mijn ontwikkeling, met korte
            |   motivatie.
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers per punt. Gebruik de automatisch herkende
            |opbouw (sets per rep-afstand, herstel, floats) als uitgangspunt en controleer die zelf aan de hand van
            |de individuele laps. Laps gemarkeerd als "ruis" zijn per ongeluk ingedrukte lap-knoppen: negeer die.
            |
            |<training_om_te_beoordelen>
            |$detail
            |</training_om_te_beoordelen>
            |
            |<trainingshistoriek>
            |$context
            |</trainingshistoriek>
        """.trimMargin()
    }

    /** Per-lap role label, consistent with the [WorkoutStructure] summary. */
    private fun lapLabels(laps: List<Lap>): List<String> {
        val kinds = LapClassifier.classify(laps)
        val structure = WorkoutStructure.analyze(laps)
        val roles = mutableMapOf<Lap, String>()
        structure?.let { s ->
            s.warmup.laps.forEach { roles[it] = "warming-up" }
            s.sets.forEachIndexed { n, set ->
                set.reps.laps.forEach { roles[it] = "INTERVAL (set ${n + 1})" }
                set.floats.laps.forEach { roles[it] = "FLOAT/matig tempo (set ${n + 1})" }
                set.recovery.laps.forEach { roles[it] = "herstel (set ${n + 1})" }
            }
            s.setPauses.forEach { p -> p.laps.forEach { roles[it] = "setpauze" } }
            s.cooldown.laps.forEach { roles[it] = "cooling-down" }
        }
        return laps.mapIndexed { i, lap ->
            if (kinds[i] == LapKind.NOISE) "ruis (per ongeluk lap-knop, negeren)" else roles[lap] ?: "rustig"
        }
    }

    private fun formatDuration(seconds: Int): String =
        "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"

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
