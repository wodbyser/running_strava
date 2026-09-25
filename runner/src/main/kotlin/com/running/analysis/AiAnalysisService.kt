package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Cadence
import com.running.strava.analysis.DateRange
import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.EfficiencyFactor
import com.running.strava.analysis.Format
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.RUN_TYPES
import com.running.strava.analysis.RunAggregates
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.WorkoutStructure
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class NextTrainingParams(
    val distanceKm: Double? = null,
    val trainingType: String? = null,
    val goalRace: String? = null,
    val raceDate: String? = null,
    val goalTime: String? = null,
    val trainingDate: String? = null,
    val notes: String? = null,
)

/**
 * Builds the copy-paste prompts for external chatbots. The prompt text is a product output: every number is
 * tagged as measured (Strava), computed (exact derivation) or an app estimate (heuristic), and the AI is told
 * what the data cannot show.
 */
@Service
class AiAnalysisService(
    private val activityRepository: ActivityRepository,
    private val periodComparisonService: PeriodComparisonService,
) {

    companion object {
        const val NO_DATA = "Geen trainingsdata beschikbaar. Synchroniseer eerst je Strava-activiteiten."
        const val RECENT_COUNT = 30

        /** Weekly-km and totals window: covers the ~5 weeks of detailed runs plus ~7 weeks before, for build-up context. */
        const val SUMMARY_WEEKS = 12

        /** Tags used in every prompt so the AI can tell measured, computed and estimated values apart. */
        const val TAG_MEASURED = "[gemeten]"
        const val TAG_COMPUTED = "[berekend]"
        const val TAG_ESTIMATE = "[app-schatting]"
    }

    private fun runs(): List<Activity> = activityRepository.findAll()
        .filter { it.type in RUN_TYPES }
        .sortedBy { it.startDate }

    // ---------------------------------------------------------------- shared prompt blocks

    /** "Definities & eenheden": identical in every prompt so the AI reads units and methods the same way. */
    private fun definitionsBlock(withLaps: Boolean, withEf: Boolean): String = buildString {
        appendLine("<definities_en_eenheden>")
        appendLine("Labels bij elke waarde:")
        appendLine("- $TAG_MEASURED = rechtstreeks door Strava/het horloge geregistreerd (kan meetfouten bevatten, bv. optische HR, GPS).")
        appendLine("- $TAG_COMPUTED = exact afgeleid uit gemeten waarden door de app (sommen, gewogen gemiddelden, omrekeningen).")
        appendLine("- $TAG_ESTIMATE = door de app geschat met een eigen heuristiek, NIET gemeten en NIET wetenschappelijk gevalideerd.")
        appendLine("Eenheden:")
        appendLine("- Tempo in min/km (m:ss /km), afgerond op de seconde. Tijden als m:ss of u:mm:ss.")
        appendLine("- Bewegingstijd = tijd in beweging (pauzes niet meegeteld); totale tijd = inclusief pauzes.")
        appendLine("- Hartslag (HR) in bpm, afgerond. Ontbreekt HR, dan staat er 'HR onbekend' of wordt de regel weggelaten.")
        appendLine("- Cadans in spm = stappen per minuut van BEIDE voeten (Strava meet per voet; de app verdubbelt die waarde).")
        appendLine("- Afstand in km (activiteiten) of m (rondes); hoogtemeters = totale stijging in m.")
        appendLine("- Vermogen in W: afkomstig van Strava/het horloge, voor lopen meestal zelf door het toestel geschat (geen powermeter).")
        appendLine("- In de herkende opbouw staan hersteltijden als bv. '1m30s' en snelheden als tempo '@ m:ss /km'.")
        appendLine("- Datums en weken: lokale datum van de activiteit; een week loopt van maandag t/m zondag.")
        appendLine("Automatische herkenning van de opbouw $TAG_ESTIMATE:")
        appendLine("- Werkt enkel op gemiddelde snelheid (en HR) per ronde/lap, niet op GPS- of HR-stromen. Zonder laps: geen herkenning.")
        appendLine("- 'interval/rep' = minstens 2 afzonderlijke, duidelijk snellere rondes (ca. >= 1,15x het rustige tempo) met rustigere rondes ertussen.")
        appendLine("- 'herstel' = rustige jog/wandel-ronde tussen reps; 'float' = matig tempo tussen reps, sneller dan rustig maar trager dan de reps.")
        appendLine("- 'tempo' = aaneengesloten sneller blok zonder herhalingen (tempoloop, progressie, eindversnelling).")
        appendLine("- 'ruis' = ronde < 10 s of < 45 m (per ongeluk lap-knop) of onmogelijke snelheid/GPS-fout; genegeerd in berekeningen.")
        appendLine("- Beperkingen: bergop-herhalingen, fartlek zonder laps, loopband (onbetrouwbaar tempo) en wedstrijden worden vaak fout of niet herkend.")
        if (!withLaps) appendLine("- In deze prompt staan GEEN individuele laps: de herkende opbouw kan je hier dus niet zelf controleren.")
        if (withEf) {
            appendLine("Efficiëntiefactor (EF) $TAG_COMPUTED:")
            appendLine("- EF = snelheid (m/s) / hartslag (bpm) = meter per hartslag, gepoold: totale afstand / totaal aantal hartslagen, enkel over sessies of reps met HR.")
            appendLine("- Hogere EF = sneller bij dezelfde hartslag. EF is gevoelig voor warmte, terrein, vermoeidheid, cardiac drift en HR-meetfouten;")
            appendLine("  interval-EF hangt bovendien af van de replengte (HR loopt achter op korte reps). Het is een trendindicator, geen fitheidsmeting.")
        }
        appendLine("</definities_en_eenheden>")
    }

    /** Instructions that keep the AI within what the data can support. */
    private fun guardrailsBlock(withLaps: Boolean): String = buildString {
        appendLine("<werkwijze>")
        appendLine("- Baseer je enkel op de data hieronder. Deze data bevat GEEN weer, slaap, HRV, rusthartslag, ziekte, pijn/klachten,")
        appendLine("  gevoel (RPE), voeding, schoenen, lactaat- of VO2max-metingen, tenzij de gebruiker die zelf in de opmerkingen vermeldt.")
        appendLine("  Hangt een conclusie daarvan af, zeg dan expliciet 'niet bepaalbaar uit deze data' in plaats van te gokken.")
        appendLine("- Zeg het expliciet als er te weinig data is voor een conclusie (bv. weinig sessies, ontbrekende HR) en hoe zeker je bent.")
        appendLine("- Behandel waarden met $TAG_ESTIMATE als ruwe indicatie van de app, niet als feit of als gevestigde sportwetenschap.")
        if (withLaps) {
            appendLine("- Controleer de automatisch herkende opbouw zelf aan de hand van de individuele laps en zeg het als die niet klopt.")
        } else {
            appendLine("- De automatisch herkende opbouw kan fout zijn; de naam van de activiteit is vaak een extra aanwijzing.")
        }
        appendLine("- Onderscheid in je antwoord wat rechtstreeks uit de data volgt en wat algemene trainingsrichtlijnen of je eigen inschatting zijn.")
        appendLine("- Geef voorspellingen en streeftempo's als bereik, niet tot op de seconde.")
        appendLine("</werkwijze>")
    }

    // ---------------------------------------------------------------- training context

    fun buildTrainingContext(): String {
        val activities = runs()
        if (activities.isEmpty()) return NO_DATA

        return buildString {
            appendLine("TRAININGSHISTORIEK (enkel loopactiviteiten)")
            appendLine("=".repeat(50))

            val recent = activities.takeLast(RECENT_COUNT)
            val lapsByActivity = activityRepository.findLapsForActivities(recent.map { it.id })
            appendLine()
            appendLine("## 1. Laatste ${recent.size} activiteiten (${ActivityTime.localDate(recent.first())} t/m ${ActivityTime.localDate(recent.last())}, oud -> nieuw)")
            appendLine("Alle waarden per activiteit zijn $TAG_MEASURED, behalve waar anders vermeld.")
            appendLine()
            recent.forEachIndexed { index, activity ->
                appendLine("${index + 1}. ${ActivityTime.localDate(activity)}")
                appendLine("   Type: ${activity.type}" + raceSuffix(activity) + if (activity.isTrainer) " (loopband: tempo/afstand onbetrouwbaar)" else "")
                appendLine("   Naam (door gebruiker): ${activity.name}")
                appendLine("   Afstand: ${"%.2f".format(activity.distance / 1000)} km")
                appendLine("   Bewegingstijd: ${Format.duration(activity.movingTime)}")
                appendLine("   Gem. tempo: ${Format.pace(activity.averageSpeed.toDouble())}")
                appendLine("   Gem. HR: ${activity.averageHeartrate?.let { Format.hr(it) } ?: "onbekend (geen HR)"}")
                activity.maxHeartrate?.let { appendLine("   Max HR: ${Format.hr(it)}") }
                Cadence.toSpm(activity.averageCadence, activity.type)?.let { appendLine("   Gem. cadans: ${"%.0f".format(it)} spm") }
                activity.averageWatts?.let { appendLine("   Gem. vermogen: ${"%.0f".format(it)} W") }
                appendLine("   Hoogtemeters: ${"%.0f".format(activity.totalElevationGain)} m")
                val laps = lapsByActivity[activity.id].orEmpty()
                val structure = WorkoutStructure.describe(laps, activity.workoutType)
                when {
                    structure != null -> appendLine("   Opbouw $TAG_ESTIMATE: $structure")
                    laps.size <= 1 -> appendLine("   Opbouw: geen laps, dus niet herkend")
                }
                appendLine()
            }

            val today = LocalDate.now()
            val windowStart = WeeklyVolume.weekStart(today).minusWeeks(SUMMARY_WEEKS - 1L)
            val inWindow = activities.filter { !ActivityTime.localDate(it).isBefore(windowStart) }
            val agg = RunAggregates.of(inWindow)
            val firstRun = ActivityTime.localDate(activities.first())
            appendLine()
            appendLine("## 2. Totalen laatste $SUMMARY_WEEKS weken $TAG_COMPUTED ($windowStart t/m $today; overlapt met de lijst hierboven)")
            appendLine()
            appendLine("Aantal runs: ${inWindow.size}")
            appendLine("Totale afstand: ${"%.1f".format(agg.totalDistanceMeters / 1000)} km")
            appendLine("Totale bewegingstijd: ${"%.1f".format(agg.totalMovingTimeSeconds / 3600.0)} uur")
            appendLine("Gem. tempo (totale afstand / totale bewegingstijd): ${Format.pace(agg.avgSpeedMs)}")
            appendLine("Gem. HR (tijdgewogen, over ${agg.hrCount} van ${inWindow.size} runs met HR): ${Format.hr(agg.avgHr)}")
            if (firstRun.isAfter(windowStart)) appendLine("Let op: de historiek begint pas op $firstRun; eerdere weken hebben geen data.")

            appendLine()
            appendLine("## 3. Wekelijkse kilometers laatste $SUMMARY_WEEKS weken $TAG_COMPUTED (week = maandag-datum; 0.0 = geen runs; huidige week kan onvolledig zijn)")
            appendLine()
            WeeklyVolume.compute(inWindow, windowStart, today).forEach { (week, km) ->
                appendLine("  $week: ${"%.1f".format(km)} km")
            }

            appendLine()
            appendLine("## 4. App-schattingen $TAG_ESTIMATE")
            appendLine()
            appendLine(maxHrLine(activities))
            appendLine("Rusthartslag: niet beschikbaar (niet gemeten in deze data).")
        }
    }

    private fun raceSuffix(a: Activity) = if (a.workoutType == 1) " (door de gebruiker op Strava als wedstrijd gemarkeerd)" else ""

    /** Same estimate as the /coach page ([HrZones.estimateMaxHr] over the last 12 months). */
    private fun maxHrLine(activities: List<Activity>): String {
        val oneYearAgo = ZonedDateTime.now().minusYears(1)
        val perRunMax = activities.filter { it.startDate.isAfter(oneYearAgo) }.mapNotNull { it.maxHeartrate?.toInt() }
        val est = HrZones.estimateMaxHr(perRunMax)
            ?: return "Geschatte max. hartslag: onbekend (geen HR-data in de laatste 12 maanden)."
        val method = if (perRunMax.size < 5) "hoogste max-HR van ${perRunMax.size} run(s), te weinig data voor een robuuste schatting"
        else "95e percentiel van de max-HR per run over ${perRunMax.size} runs van de laatste 12 maanden; hoogste gemeten waarde ${perRunMax.max()} bpm"
        return "Geschatte max. hartslag: $est bpm ($method). Dit is geen max-HR-test; de echte max-HR kan hoger liggen."
    }

    // ---------------------------------------------------------------- prompts

    fun buildAiPrompt(): String {
        val context = buildTrainingContext()
        if (context == NO_DATA) return context

        return """
            |Je bent een ervaren hardloopcoach. Analyseer onderstaande trainingshistoriek en geef concreet advies.
            |Behandel minimaal deze punten:
            |1. Trainingsbelasting: zijn er in volume, frequentie en intensiteit (zie weekkilometers en opbouw) signalen van
            |   een te snelle opbouw of te weinig herstel? Overtraining zelf is niet vast te stellen zonder gegevens over
            |   vermoeidheid, slaap, HRV of prestatieverlies; benoem wat de data wel en niet toont.
            |2. Evolutie van het drempelniveau: wat zeggen tempo en HR van tempo- en intervaltrainingen over de evolutie?
            |   Er is geen lactaatmeting: geef hooguit een onderbouwde indicatie, geen lactaatdrempel.
            |3. Een ideale trainingsweek, afgestemd op het huidige weekvolume en de huidige frequentie.
            |4. Voorspelling voor halve marathon en marathon, als bereik, met de gebruikte basis (welke activiteiten)
            |   en de onzekerheid. Zeg het als er geen geschikte recente inspanning is om op te baseren.
            |5. Belastingspatronen die in de literatuur met een hoger blessurerisico geassocieerd worden (bv. sterke
            |   sprongen in weekvolume). Een individueel blessurerisico is niet te bepalen uit deze data.
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers en per punt een duidelijk actieplan.
            |
            |${definitionsBlock(withLaps = false, withEf = false)}
            |${guardrailsBlock(withLaps = false)}
            |<trainingshistoriek>
            |$context
            |</trainingshistoriek>
        """.trimMargin()
    }

    fun buildNextTrainingPrompt(params: NextTrainingParams): String {
        val context = buildTrainingContext()
        if (context == NO_DATA) return context

        val details = buildString {
            appendLine("(Door de gebruiker ingevuld.)")
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
            |1. Aanbevolen tempo's (als bereik in min/km) voor deze training, per onderdeel als het een gestructureerde
            |   training is (bv. warming-up, kern, blokken/intervallen, cooling-down). Vermeld op welke activiteiten uit
            |   mijn historiek je die tempo's baseert.
            |2. Aanbevolen hartslagbereiken (bpm). De max. hartslag hieronder is een app-schatting, geen test: zeg hoe
            |   betrouwbaar HR-zones daardoor zijn en gebruik bij twijfel liever tempo of gevoel als leidraad.
            |3. Hoe ik deze training moet indelen (bv. negative split, even tempo, blokken) om zowel goed te
            |   presteren als te herstellen richting de volgende trainingen.
            |4. Waar ik op moet letten gezien mijn recente trainingsbelasting (weekkilometers, aantal zware sessies).
            |   Vermoeidheid, slaap en klachten zitten niet in de data: vermeld welke signalen ik zelf moet checken.
            |5. Hoe deze training bijdraagt aan mijn doel (doelwedstrijd/doeltijd indien opgegeven) en of dat doel
            |   realistisch lijkt op basis van de data; zeg het als de data daarvoor onvoldoende is.
            |6. Algemene voedings-/hydratatie- en warming-uptips voor deze afstand/type training (dit is algemene
            |   kennis, niet afgeleid uit mijn data).
            |
            |Geef concrete cijfers (tempo's in min/km, hartslag in bpm) op basis van mijn data, geen vage algemene
            |richtlijnen.
            |
            |${definitionsBlock(withLaps = false, withEf = false)}
            |${guardrailsBlock(withLaps = false)}
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
            val local = ActivityTime.local(activity)
            appendLine("Gemeten data $TAG_MEASURED:")
            appendLine("Datum: ${local.toLocalDate()} (lokale starttijd ${local.toLocalTime().withSecond(0).withNano(0)})")
            appendLine("Naam (door gebruiker): ${activity.name}")
            appendLine("Type: ${activity.type}" + raceSuffix(activity) + if (activity.isTrainer) " (loopband: tempo/afstand onbetrouwbaar)" else "")
            appendLine("Afstand: ${"%.2f".format(activity.distance / 1000)} km")
            appendLine("Bewegingstijd: ${Format.duration(activity.movingTime)} (totale tijd ${Format.duration(activity.elapsedTime)})")
            appendLine("Gem. tempo: ${Format.pace(activity.averageSpeed.toDouble())}")
            appendLine("Gem. HR: ${activity.averageHeartrate?.let { Format.hr(it) } ?: "onbekend (geen HR)"}")
            activity.maxHeartrate?.let { appendLine("Max HR: ${Format.hr(it)}") }
            Cadence.toSpm(activity.averageCadence, activity.type)?.let { appendLine("Gem. cadans: ${"%.0f".format(it)} spm") }
            activity.averageWatts?.let { appendLine("Gem. vermogen: ${"%.0f".format(it)} W") }
            appendLine("Hoogtemeters: ${"%.0f".format(activity.totalElevationGain)} m")
            activity.sufferScore?.let { appendLine("Suffer score: $it (Strava-berekening op basis van HR; geen app-waarde)") }

            if (laps.size > 1) {
                formatIntervalSummary(laps, activity.workoutType)?.let {
                    appendLine()
                    appendLine("Opbouw training $TAG_ESTIMATE (kan fout zijn; controleer met de laps): $it")
                }
                val labels = lapLabels(laps, activity.workoutType)
                appendLine()
                appendLine("Rondes/laps (${laps.size}): afstand, tijd, tempo en HR zijn $TAG_MEASURED; de rol na '->' is een $TAG_ESTIMATE:")
                laps.forEachIndexed { i, lap ->
                    appendLine(
                        "  Lap ${lap.lapIndex}: ${"%.0f".format(lap.distance)} m in ${Format.duration(lap.movingTime)}, " +
                            "${Format.pace(lap.averageSpeed.toDouble())}, " +
                            (lap.averageHeartrate?.let { "gem. HR ${Format.hr(it)}" } ?: "HR onbekend") +
                            (lap.maxHeartrate?.let { ", max HR ${Format.hr(it)}" } ?: "") +
                            " -> ${labels[i]}"
                    )
                }
            } else {
                appendLine()
                appendLine("Geen rondes/laps geregistreerd: de opbouw (intervallen, tempo) is niet te bepalen uit deze data.")
            }
        }

        return """
            |Je bent een ervaren hardloopcoach. Beoordeel onderstaande specifieke training grondig.
            |Behandel minimaal deze punten:
            |1. Was dit een nuttige training gezien type, afstand, tempo en hartslag, en waarom (niet)? Het beoogde
            |   doel van de training is niet gekend; leid het af uit de naam en de opbouw en zeg dat je dat doet.
            |2. Als er intervallen/reps waren: was de uitvoering consistent (tempo/HR per rep), was de
            |   herstelduur/afstand tussen reps gepast, en past de intensiteit bij het vermoedelijke doel (bv. VO2max,
            |   drempel, snelheid)? Houd er rekening mee dat HR bij korte reps achterloopt op de inspanning.
            |3. Hoe verhoudt deze training zich tot mijn recente trainingshistoriek (te licht, precies goed, te
            |   zwaar, past ze binnen de opbouw)?
            |4. Concreet cijfermatig advies: wat had beter gekund (tempo's, aantal/duur reps, hersteltijd)?
            |5. Een score op 10 voor hoe nuttig deze training was voor mijn ontwikkeling, met korte motivatie en
            |   vermelding van wat je niet kon beoordelen (bv. gevoel, weer, vermoeidheid).
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers per punt. Gebruik de automatisch herkende
            |opbouw als uitgangspunt, maar controleer die zelf aan de hand van de individuele laps. Laps met rol
            |"ruis" zijn vermoedelijk per ongeluk ingedrukte lap-knoppen of GPS-fouten.
            |
            |${definitionsBlock(withLaps = laps.size > 1, withEf = false)}
            |${guardrailsBlock(withLaps = laps.size > 1)}
            |<training_om_te_beoordelen>
            |$detail
            |</training_om_te_beoordelen>
            |
            |<trainingshistoriek>
            |$context
            |</trainingshistoriek>
        """.trimMargin()
    }

    /** Workout structure (warm-up, sets per rep distance, recovery/floats, cool-down), or null if no intervals. */
    private fun formatIntervalSummary(laps: List<Lap>, workoutType: Int?): String? = WorkoutStructure.describe(laps, workoutType)

    /** Per-lap role label, consistent with the [WorkoutStructure] summary. */
    private fun lapLabels(laps: List<Lap>, workoutType: Int?): List<String> {
        val kinds = LapClassifier.classify(laps, workoutType)
        val structure = WorkoutStructure.analyze(laps, workoutType)
        val roles = mutableMapOf<Lap, String>()
        structure?.let { s ->
            var setNo = 0
            s.segments.forEach { seg ->
                when (seg) {
                    is WorkoutStructure.Segment.Set -> {
                        setNo++
                        seg.set.reps.laps.forEach { roles[it] = "interval-rep (set $setNo)" }
                        seg.set.floats.laps.forEach { roles[it] = "float/matig tempo (set $setNo)" }
                        seg.set.recovery.laps.forEach { roles[it] = "herstel (set $setNo)" }
                    }
                    is WorkoutStructure.Segment.Steady -> {
                        val label = when (seg.role) {
                            WorkoutStructure.Role.WARMUP -> "warming-up"
                            WorkoutStructure.Role.COOLDOWN -> "cooling-down"
                            WorkoutStructure.Role.TEMPO -> "tempo"
                            WorkoutStructure.Role.SET_PAUSE -> "setpauze"
                            WorkoutStructure.Role.EASY -> "rustig"
                        }
                        seg.block.laps.forEach { roles[it] = label }
                    }
                }
            }
        }
        return laps.mapIndexed { i, lap ->
            when {
                kinds[i] == LapKind.NOISE -> "ruis (negeren)"
                workoutType == 1 -> "wedstrijd"
                else -> roles[lap] ?: "rustig"
            }
        }
    }

    fun buildComparisonPrompt(rangeA: DateRange, rangeB: DateRange, labelA: String, labelB: String): String {
        val result = periodComparisonService.compare(rangeA, rangeB, labelA, labelB)

        val context = buildString {
            appendLine("VERGELIJKING TRAININGSPERIODES")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Alle waarden hieronder zijn $TAG_COMPUTED uit gemeten Strava-data, over sessies die de app automatisch")
            appendLine("als rustig of interval heeft herkend $TAG_ESTIMATE. Tempo en HR zijn tijdgewogen gemiddelden.")
            appendLine()
            listOf(result.periodA, result.periodB).forEach { p ->
                val weeks = weeksIn(p.from, p.till)
                appendLine("## ${p.label}")
                appendLine("Aantal runs: ${p.activityCount}")
                appendLine("Totale afstand: ${"%.1f".format(p.totalDistanceKm)} km" +
                    (weeks?.let { " (gem. ${"%.1f".format(p.totalDistanceKm / it)} km/week over ${"%.1f".format(it)} weken)" } ?: ""))
                appendLine("Totale bewegingstijd: ${"%.1f".format(p.totalTimeHours)} uur")
                appendLine()
                appendLine("Rustige lopen met HR: ${p.easyRunCount} (minimum voor een oordeel: ${EfVerdict.MIN_EASY_RUNS})")
                appendLine("  Niet meegeteld: ${p.easyRunsWithoutHr} zonder HR, ${p.excludedTrailOrTreadmill} trail/loopband, ${p.excludedRaces} wedstrijden, ${p.excludedTempo} tempo, ${p.excludedStrides} met versnellingen")
                appendLine("  Gem. tempo: ${p.easyAvgPace}")
                appendLine("  Gem. HR: ${Format.hr(p.easyAvgHr)}")
                appendLine("  EF: ${EfficiencyFactor.format(p.easyEf)}" + (p.easyEfSd?.let { " (spreiding tussen losse runs: SD ${EfficiencyFactor.format(it)})" } ?: ""))
                appendLine()
                appendLine("Intervaltrainingen met HR: ${p.intervalSessionCount} sessies (minimum voor een oordeel: ${EfVerdict.MIN_INTERVAL_SESSIONS}), ${p.intervalRepCount} reps van >= ${LapClassifier.MIN_REP_SECONDS} s, gem. repduur ${p.intervalAvgRepSeconds?.let { "%.0f s".format(it) } ?: "-"}")
                appendLine("  Gem. tempo per rep: ${p.intervalAvgPace}")
                appendLine("  Gem. HR per rep: ${Format.hr(p.intervalAvgHr)}")
                appendLine("  EF: ${EfficiencyFactor.format(p.intervalEf)}" + (p.intervalEfSd?.let { " (spreiding tussen losse sessies: SD ${EfficiencyFactor.format(it)})" } ?: ""))
                appendLine()
            }
            appendLine("## Verandering ${result.periodA.label} -> ${result.periodB.label} $TAG_COMPUTED")
            appendLine("EF rustige lopen: ${result.easyEfChangePct?.let { "%+.1f%%".format(it) } ?: "onbekend"}")
            appendLine("EF intervallen: ${result.intervalEfChangePct?.let { "%+.1f%%".format(it) } ?: "onbekend"}")
            appendLine()
            appendLine("## Indicatie van de app $TAG_ESTIMATE")
            appendLine("Regel: verschil <= ±${EfVerdict.THRESHOLD_PCT.toInt()}% of binnen de spreiding tussen sessies = geen duidelijke verandering; onder het minimum aantal sessies = onvoldoende data.")
            appendLine(result.verdict)
        }

        return """
            |Je bent een ervaren hardloopcoach. Vergelijk onderstaande twee trainingsperiodes en beoordeel of de
            |data wijst op vooruitgang, achteruitgang of geen duidelijke verandering in loopniveau.
            |Behandel minimaal deze punten:
            |1. Is de aerobe efficiëntie (EF, tempo per hartslag) bij rustige lopen veranderd, en is dat verschil groot
            |   genoeg ten opzichte van de spreiding en het aantal runs om iets te betekenen?
            |2. Is de kwaliteit van de intervaltrainingen (tempo & hartslag per rep) veranderd? Houd rekening met
            |   verschillen in repduur tussen de periodes.
            |3. Welke verklaringen zijn zichtbaar in de data (volume per week, aantal en type sessies)? Factoren zoals
            |   seizoen/temperatuur, slaap, ziekte of schoenen zitten niet in de data: noem die hooguit als mogelijke
            |   verstorende factor, niet als verklaring.
            |4. Wat zou de gebruiker kunnen aanpassen om verder te verbeteren richting de volgende vergelijkingsperiode?
            |
            |Geef je antwoord in het Nederlands, met concrete cijfers en een eindoordeel dat niet sterker is dan de data
            |toelaat ('geen duidelijke verandering' en 'onvoldoende data' zijn geldige eindoordelen).
            |
            |${definitionsBlock(withLaps = false, withEf = true)}
            |${guardrailsBlock(withLaps = false)}
            |<trainingsvergelijking>
            |$context
            |</trainingsvergelijking>
        """.trimMargin()
    }

    private fun weeksIn(from: LocalDate?, till: LocalDate?): Double? {
        if (from == null || till == null || till.isBefore(from)) return null
        return (ChronoUnit.DAYS.between(from, till) + 1) / 7.0
    }
}
