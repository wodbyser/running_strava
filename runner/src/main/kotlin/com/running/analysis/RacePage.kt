package com.running.analysis

import com.running.load.ReadinessLevel
import com.running.race.*
import com.running.strava.analysis.Format
import com.running.strava.analysis.WeeklyVolume
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object RaceFmt {
    fun d1(v: Double?) = if (v == null) "-" else "%.1f".format(Locale.ROOT, v).replace('.', ',')
    fun d2(v: Double?) = if (v == null) "-" else "%.2f".format(Locale.ROOT, v).replace('.', ',')
    fun pct(v: Double?) = if (v == null) "-" else "%+.1f %%".format(Locale.ROOT, v).replace('.', ',')
    fun time(s: Double?) = if (s == null) "-" else Format.duration(s)
    fun pace(secondsPerKm: Double?) = Format.paceFromSeconds(secondsPerKm, false)
    fun km(m: Double) = d1(m / 1000) + " km"

    fun distanceLabel(m: Double): String = when {
        abs(m - 5000.0) < 1 -> "5 km"
        abs(m - 10000.0) < 1 -> "10 km"
        abs(m - 21097.5) < 1 -> "Halve marathon"
        abs(m - 42195.0) < 1 -> "Marathon"
        else -> d1(m / 1000).removeSuffix(",0") + " km"
    }

    fun days(n: Long) = if (n == 1L) "1 dag" else "$n dagen"

    fun levelCss(l: ReadinessLevel) = LoadPageBuilder.levelCss(l)

    fun stanceCss(s: RStance) = when (s) {
        RStance.FOR -> "tone-good"
        RStance.AGAINST -> "tone-bad"
        RStance.NEUTRAL -> "tone-none"
        RStance.UNKNOWN -> "tone-none"
    }

    /** Parses "19:00", "1:39:12" or minutes only ("19") into seconds. Null for anything else. */
    fun parseTime(text: String?): Int? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size !in 1..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val n = parts.map { it.toInt() }
        return when (n.size) {
            1 -> n[0] * 60
            2 -> n[0] * 60 + n[1]
            else -> n[0] * 3600 + n[1] * 60 + n[2]
        }.takeIf { it in 60..(12 * 3600) }
    }
}

data class RaceHeroView(val css: String, val headline: String, val sub: String, val lines: List<String>)

data class ForecastTileView(
    val key: String,
    val label: String,
    val has: Boolean,
    val time: String,
    val range: String,
    val rangeBasis: String,
    val pace: String,
    val paceRange: String,
    val criticalSpeed: String?,
    val notes: List<String>,
    val anchorCount: Int,
    val why: String?,
    val goalPace: String?,
)

data class AnchorRowView(val date: String, val id: Long, val name: String, val kind: String, val distance: String, val time: String, val pace: String)

data class GoalRowView(val id: Long, val title: String, val dateText: String, val daysText: String, val target: String, val status: String, val past: Boolean, val matchText: String?)

data class OutcomeRowView(val id: Long, val date: String, val name: String, val distance: String, val predicted: String, val actual: String, val error: String, val css: String, val note: String?)

data class SplitRowView(val label: String, val evenTime: String, val evenPace: String, val calmTime: String, val calmPace: String)

data class PartView(val title: String, val stance: String, val css: String, val detail: String)

data class BlockView(val title: String, val level: String, val css: String, val parts: List<PartView>)

data class WeekDayView(val date: String, val text: String)

data class CandidateView(val id: Long, val date: String, val name: String, val distance: String, val reasons: String, val strong: Boolean)

data class RacePage(
    val hero: RaceHeroView,
    val goals: List<GoalRowView>,
    val tiles: List<ForecastTileView>,
    val violationNote: String?,
    val accuracyLines: List<String>,
    val accuracyRows: List<OutcomeRowView>,
    val anchorRows: List<AnchorRowView>,
    val anchorNote: String,
    val planTitle: String?,
    val planLines: List<String>,
    val splitRows: List<SplitRowView>,
    val pacingLines: List<String>,
    val fitness: BlockView,
    val recovery: BlockView?,
    val recoveryNote: String,
    val weekCheck: WeekCheckView?,
    val weekHidden: String?,
    val candidates: List<CandidateView>,
    val hasData: Boolean,
    val hasGoal: Boolean,
    val charts: Map<String, Any?>,
    val coverage: List<String>,
)

data class WeekCheckView(val title: String, val lines: List<String>, val components: List<PartView>, val days: List<WeekDayView>)

object RacePageBuilder {

    private fun tile(f: DistanceForecast, goal: GoalRow?): ForecastTileView {
        val p = f.prediction
        val label = RaceFmt.distanceLabel(f.distanceMeters)
        val key = "d" + f.distanceMeters.roundToInt()
        if (p == null) {
            return ForecastTileView(key, label, false, "-", "-", "", "-", "-", null, emptyList(), 0, "Onvoldoende ankers: minstens ${com.running.strava.analysis.RacePredictor.MIN_ANCHORS} inspanningen binnen 0,4 tot 2,5 keer deze afstand nodig.", null)
        }
        val low = f.rangeLowSeconds
        val high = f.rangeHighSeconds
        val notes = ArrayList<String>()
        if (p.marathonWarning) notes += Prediction.MARATHON_WARNING
        if (p.outsideValidRange) notes += "Buiten het geldigheidsbereik van Riegel (3,5 tot 230 minuten): extra onzeker."
        p.cappedBy?.let { notes += "Begrensd door je wedstrijd van ${PerfDates.day(it.date)} (${RaceFmt.distanceLabel(it.distanceMeters)}, ${RaceFmt.time(it.seconds)}): een voorspelling die trager is dan die wedstrijd laat zien, tel je niet mee." }
        val km = f.distanceMeters / 1000
        val goalPace = goal?.target?.let { RaceFmt.pace(it.toDouble() / km) }
        return ForecastTileView(
            key, label, true, RaceFmt.time(p.seconds),
            if (low != null && high != null) "${RaceFmt.time(low)} tot ${RaceFmt.time(high)}" else "-",
            f.rangeBasis.text,
            RaceFmt.pace(p.secondsPerKm),
            if (low != null && high != null) "${RaceFmt.pace(low / km)} tot ${RaceFmt.pace(high / km)} per km" else "-",
            f.criticalSpeedSeconds?.let { RaceFmt.time(RacePredictorRound.round(it)) },
            notes, p.anchors.size, null, goalPace,
        )
    }

    private data class GoalRow(val id: Long, val target: Int?)

    private fun goalStatus(g: GoalView): String = when {
        g.past -> g.matchedRace?.let { "Gelopen: ${RaceFmt.time(it.movingSeconds.toDouble())}" } ?: "Voorbij, geen wedstrijd gevonden op deze datum"
        g.feasibility != null -> g.feasibility.band.text
        g.goal.targetSeconds == null -> "Geen doeltijd ingevuld"
        else -> "Nog geen voorspelling om mee te vergelijken"
    }

    fun build(o: RaceOverview): RacePage {
        val focus = o.focus
        val focusRow = focus?.let { GoalRow(it.goal.id, it.goal.targetSeconds) }

        val tiles = o.forecasts.map { f -> tile(f, if (focus != null && focus.goal.distanceMeters == f.distanceMeters) focusRow else null) }
        val anyPrediction = o.forecasts.any { it.prediction != null }

        val hero = hero(o)
        val goals = o.goals.map { g ->
            GoalRowView(
                g.goal.id, g.goal.name ?: RaceFmt.distanceLabel(g.goal.distanceMeters) + if (g.goal.name != null) " (${RaceFmt.distanceLabel(g.goal.distanceMeters)})" else "",
                PerfDates.dayLong(g.goal.date),
                if (g.past) "voorbij" else if (g.daysToGo == 0L) "vandaag" else "nog ${RaceFmt.days(g.daysToGo)}",
                g.goal.targetSeconds?.let { RaceFmt.time(it.toDouble()) } ?: "-", goalStatus(g), g.past,
                if (g.past && g.matchedRace != null) "Gevonden in je Strava-data: ${g.matchedRace.name}" else null,
            )
        }

        val acc = o.accuracy
        val accuracyLines = ArrayList<String>()
        if (acc.enough) {
            accuracyLines += "Op ${acc.n} eigen wedstrijden was de voorspelling gemiddeld ${RaceFmt.pct(acc.medianErrorPct)} af (mediaan; plus = je liep trager dan voorspeld), van ${RaceFmt.pct(acc.minErrorPct)} tot ${RaceFmt.pct(acc.maxErrorPct)}."
            accuracyLines += "De bandbreedte gebruikt de middelste ${(100 - 2 * acc.trimPct).roundToInt()} % van die afwijkingen (${RaceFmt.pct(acc.rangeLowPct)} tot ${RaceFmt.pct(acc.rangeHighPct)}), zodat één uitschieter haar niet bepaalt. De voorspelde tijd valt er altijd binnen."
        } else {
            accuracyLines += "Nog te weinig wedstrijden om te meten hoe goed deze voorspelling voor jou klopt (n = ${acc.n}, minstens ${acc.needed} nodig). De bandbreedte is alleen de spreiding van je ankers."
        }
        if (acc.nCompared > 0 && acc.mainMaePct != null && acc.baselineMaePct != null) {
            accuracyLines += "Naast elkaar gelegd op ${acc.nCompared} wedstrijden: de gewone methode zat gemiddeld ${RaceFmt.d1(acc.mainMaePct)} % ernaast, " +
                "'je laatste wedstrijd omgerekend met Riegel' ${RaceFmt.d1(acc.baselineMaePct)} %. Dichterbij was ${if (acc.closer == "main") "de gewone methode" else "de eenvoudige methode met één wedstrijd"}."
        }
        accuracyLines += RaceText.loggedLine(o.loggedSummary)
        val accuracyRows = o.outcomes.sortedByDescending { it.race.startEpochSeconds }.map { outcomeRow(it) }

        val anchorRows = o.anchors.sortedBy { it.distanceMeters }.map {
            AnchorRowView(PerfDates.day(it.date), it.activityId, it.name, it.kind.text, RaceFmt.distanceLabel(it.distanceMeters).let { l -> if (l.endsWith("km") || l.contains("marathon", true)) l else l }, RaceFmt.time(it.seconds), RaceFmt.pace(it.seconds / (it.distanceMeters / 1000)))
        }

        val plan = o.plan
        val planLines = ArrayList<String>()
        val splitRows = ArrayList<SplitRowView>()
        if (plan != null) {
            planLines += "Gelijkmatig tempo voor ${RaceFmt.distanceLabel(plan.distanceMeters)} in ${RaceFmt.time(plan.targetSeconds)} (${plan.basis}): ${RaceFmt.pace(plan.targetSeconds / (plan.distanceMeters / 1000))} per km."
            planLines += "Variant 'rustige start': de eerste ${RaceFmt.d1(plan.calmMeters / 1000).removeSuffix(",0")} km ${RaceFmt.d1(plan.calmPct).removeSuffix(",0")} % trager, de rest even wat sneller, met dezelfde eindtijd."
            plan.even.forEachIndexed { i, s ->
                val c = plan.calm.getOrNull(i)
                splitRows += SplitRowView(s.label, RaceFmt.time(s.cumulativeSeconds), RaceFmt.pace(s.secondsPerKm), c?.let { RaceFmt.time(it.cumulativeSeconds) } ?: "-", c?.let { RaceFmt.pace(it.secondsPerKm) } ?: "-")
            }
        }

        val ps = o.pacing.summary
        val pacingLines = if (ps.enough) listOf("In ${ps.slowerSecondHalf} van je ${ps.n} wedstrijden liep je de tweede helft trager (mediaan ${RaceFmt.pct(ps.medianSplitPct)}; plus = tweede helft trager).")
        else listOf("Te weinig wedstrijden met detailmetingen om te zien hoe je ze indeelde (n = ${ps.n}, minstens ${ps.needed} nodig).")

        val fitness = BlockView("Fitheid op lange termijn", o.fitness.level.text, RaceFmt.levelCss(o.fitness.level), o.fitness.parts.map { PartView(it.title, it.stance.text, RaceFmt.stanceCss(it.stance), it.detail) })
        val recovery = o.recovery?.let { r ->
            BlockView("Herstel op korte termijn (wedstrijdtempo)", r.level.text, RaceFmt.levelCss(r.level), r.parts.map { PartView(it.title, it.stance.text, if (it.stance == com.running.load.Stance.AGAINST) "tone-bad" else if (it.stance == com.running.load.Stance.FOR) "tone-good" else "tone-none", it.detail) })
        }

        val weekCheck = o.raceWeek?.let { weekCheck(it) }
        val weekHidden = if (focus != null && o.raceWeek == null) {
            if (focus.daysToGo > o.windows.weekCheckDays) "Verschijnt in de week voor je wedstrijd (nog ${RaceFmt.days(focus.daysToGo - o.windows.weekCheckDays)} tot het begin ervan)." else null
        } else null

        val candidates = o.candidates.map {
            CandidateView(it.run.id, PerfDates.day(it.run.date), it.run.name, RaceFmt.km(it.run.distanceMeters), it.reasons.joinToString(", "), it.strong)
        }

        val coverage = listOf(
            "${o.runCount} runs bekeken, waarvan ${o.raceCount} gemarkeerd als wedstrijd op Strava.",
            "${o.anchors.size} ankers in de laatste ${o.windows.anchorDays} dagen.",
            "Alleen wedstrijden die je zelf op Strava als wedstrijd markeert tellen als wedstrijd. Trailwedstrijden tellen niet mee voor wegvoorspellingen.",
            "Afstanden zijn de gemeten (GPS-)afstanden, geen officiële tijden.",
        )

        return RacePage(
            hero, goals, tiles, if (o.paceViolations.isEmpty()) null else "Let op: de voorspellingen spreken elkaar tegen (een langere afstand komt sneller uit dan een kortere). Kijk naar de ankers.",
            accuracyLines, accuracyRows, anchorRows,
            "Een voorspelling is de middelste waarde van de omrekeningen van deze ankers. Een voorspelling met minder dan ${com.running.strava.analysis.RacePredictor.MIN_ANCHORS} ankers wordt niet getoond.",
            plan?.let { "Tempo en schema voor ${RaceFmt.distanceLabel(it.distanceMeters)}" }, planLines, splitRows, pacingLines, fitness, recovery,
            "Fitheid en herstel staan los van elkaar: het eerste verandert in weken, het tweede in dagen. Er is geen score en geen 'klaar of niet klaar'. ${RaceText.SAFETY}",
            weekCheck, weekHidden, candidates, o.runCount > 0, focus != null, charts(o), coverage,
        )
    }

    private fun outcomeRow(it: RaceOutcome): OutcomeRowView {
        val e = it.errorPct
        return OutcomeRowView(
            it.race.id, PerfDates.day(it.race.date), it.race.name, RaceFmt.km(it.race.distanceMeters),
            it.predictedSeconds?.let { p -> RaceFmt.time(RacePredictorRound.round(p)) } ?: "-", RaceFmt.time(it.actualSeconds),
            e?.let { v -> RaceFmt.pct(v) } ?: "-", if (e == null) "" else if (e < 0) "tone-good" else "tone-uncertain",
            if (it.trail) "trail, geen vergelijking met een wegvoorspelling" else if (it.predictedSeconds == null) "geen voorspelling vóór deze wedstrijd" else null,
        )
    }

    private fun hero(o: RaceOverview): RaceHeroView {
        val focus = o.focus
        if (!o.forecasts.any { it.prediction != null } && focus == null) {
            return RaceHeroView(
                "tone-none", "Nog geen voorspelling mogelijk",
                "Er zijn te weinig doorlopende inspanningen (wedstrijden, snelste stukken of harde runs) in de laatste ${o.windows.anchorDays} dagen.",
                listOf("Voeg hieronder een wedstrijddoel toe en kom terug zodra er ankers zijn."),
            )
        }
        if (focus == null) {
            val lines = o.forecasts.filter { it.distanceMeters in Prediction.STANDARD }.map {
                RaceFmt.distanceLabel(it.distanceMeters) + ": " + (it.prediction?.let { p -> RaceFmt.time(p.seconds) + (if (it.rangeLowSeconds != null && it.rangeHighSeconds != null) " (bandbreedte ${RaceFmt.time(it.rangeLowSeconds)} tot ${RaceFmt.time(it.rangeHighSeconds)})" else "") } ?: "onvoldoende data")
            }
            return RaceHeroView("tone-neutral", "Je huidige voorspelde tijden", RaceText.PREDICTION_NOTE, lines + "Voeg een wedstrijddoel toe om te zien hoe dat zich tot je voorspelling verhoudt.")
        }
        val g = focus.goal
        val title = (g.name ?: RaceFmt.distanceLabel(g.distanceMeters)) + " op " + PerfDates.day(g.date)
        val head = if (focus.daysToGo == 0L) "$title, vandaag" else "$title, nog ${RaceFmt.days(focus.daysToGo)}"
        val f = focus.forecast
        val p = f?.prediction
        val sub = if (p == null) "Voor deze afstand zijn er te weinig ankers voor een voorspelling." else
            "Je huidige voorspelling: ${RaceFmt.time(p.seconds)}" + (if (f.rangeLowSeconds != null && f.rangeHighSeconds != null) " (bandbreedte ${RaceFmt.time(f.rangeLowSeconds)} tot ${RaceFmt.time(f.rangeHighSeconds)})" else "") + "."
        val lines = ArrayList<String>()
        val feas = focus.feasibility
        val targetSeconds = g.targetSeconds
        if (targetSeconds != null && feas != null) {
            lines += "Je doel ${RaceFmt.time(targetSeconds.toDouble())} ligt ${feas.band.text}" +
                (feas.gapPct?.let { " (${RaceFmt.pct(it)}, ${RaceFmt.d1(abs(feas.gapSecondsPerKm ?: 0.0))} seconden per km ${if (feas.band == GoalBand.FASTER) "sneller" else "trager"} dan de rand)" } ?: "") + "."
            if (feas.needPct < 0 && feas.weeksToGo >= 1) lines += "Je voorspelling moet nog ${RaceFmt.d1(abs(feas.needPct))} % sneller worden in ${feas.weeksToGo} ${if (feas.weeksToGo == 1) "week" else "weken"}."
            if (feas.historyWindows != null && feas.historyQualifying != null) {
                lines += "In je geschiedenis gebeurde zo'n verbetering in ${feas.historyQualifying} van de ${feas.historyWindows} periodes van ${feas.weeksToGo} ${if (feas.weeksToGo == 1) "week" else "weken"}."
            } else feas.historyNote?.let { lines += it }
        } else if (targetSeconds == null) {
            lines += "Geen doeltijd ingevuld: vul er een in om je doel met je voorspelling te vergelijken."
        } else lines += "Zonder voorspelling kan het doel nergens mee vergeleken worden."
        lines += RaceText.PREDICTION_NOTE
        return RaceHeroView(if (feas?.band == GoalBand.INSIDE) "tone-neutral" else if (feas == null) "tone-none" else "tone-uncertain", head, sub, lines)
    }

    private fun weekCheck(w: RaceWeekView): WeekCheckView {
        val lines = ArrayList<String>()
        lines += "Nog ${RaceFmt.days(w.daysToGo)} tot ${w.goal.name ?: RaceFmt.distanceLabel(w.goal.distanceMeters)} (${PerfDates.dayLong(w.goal.date)})."
        w.loadPct?.let { lines += "Je belasting van de laatste 7 dagen is ${it.roundToInt()} % van je gemiddelde week van de laatste 4 weken." } ?: lines.add("Belasting van de laatste 7 dagen niet te berekenen (te weinig dagen met hartslag).")
        if (w.signalsActive != null) lines += "${w.signalsActive} van 6 vermoeidheidssignalen actief" + (if (w.signalNames.isNotEmpty()) ": ${w.signalNames.joinToString(", ")}" else "") + "."
        w.daysSinceHard?.let { lines += "Laatste harde sessie: ${if (it == 0) "vandaag" else "$it ${if (it == 1) "dag" else "dagen"} geleden"}." }
        w.sleepTargetShortfallMinutes?.let { lines += "Slaap vannacht: ${it.roundToInt()} minuten onder je doel van 7:30." }
        val comps = w.components.map { c ->
            val dev = c.deviation
            val text = if (c.value == null || c.median == null || dev == null) "geen waarde" else
                "${RaceFmt.d1(c.value).removeSuffix(",0")} tegenover norm ${RaceFmt.d1(c.median).removeSuffix(",0")} (${RaceFmt.d1(dev)} SD)"
            PartView(c.label, when (c.band) { "STRONG" -> "sterk afwijkend"; "DEVIATING" -> "afwijkend"; "WITHIN" -> "binnen je norm"; else -> "onbekend" }, when (c.band) { "STRONG" -> "tone-bad"; "DEVIATING" -> "tone-bad"; "WITHIN" -> "tone-good"; else -> "tone-none" }, text)
        }
        val days = w.days.map { WeekDayView(PerfDates.dayShort(it.date), if (it.withData == 0) "geen herstelwaarden" else "${it.deviating} van ${it.withData} buiten je norm" + (if (it.names.isNotEmpty()) " (${it.names.joinToString(", ")})" else "")) }
        return WeekCheckView("Wedstrijdweek", lines, comps, days)
    }

    private fun charts(o: RaceOverview): Map<String, Any?> {
        val weeks = o.weekly.filter { !it.weekStart.isBefore(WeeklyVolume.weekStart(o.today).minusWeeks(o.windows.chartWeeks.toLong())) }
        val labels = weeks.map { it.weekStart.toString() }
        val lines = o.forecasts.filter { it.distanceMeters in Prediction.STANDARD }.map { f ->
            mapOf(
                "label" to RaceFmt.distanceLabel(f.distanceMeters), "km" to f.distanceMeters / 1000,
                "pace" to weeks.map { w -> w.seconds[f.distanceMeters]?.let { s -> s / (f.distanceMeters / 1000) } },
            )
        }
        val index = labels.withIndex().associate { it.value to it.index }
        val races = o.outcomes.filter { !it.trail }.mapNotNull { r ->
            val i = index[WeeklyVolume.weekStart(r.race.date).toString()] ?: return@mapNotNull null
            mapOf("index" to i, "pace" to r.actualSeconds / (r.race.distanceMeters / 1000), "name" to r.race.name, "date" to r.race.date.toString(), "id" to r.race.id, "km" to r.race.distanceMeters / 1000)
        }
        val week = o.raceWeek?.days?.map { mapOf("date" to it.date.toString(), "withData" to it.withData, "deviating" to it.deviating) }.orEmpty()
        val plan = o.plan?.let { p ->
            mapOf(
                "labels" to p.even.map { it.label }, "even" to p.even.map { it.secondsPerKm }, "calm" to p.calm.map { it.secondsPerKm },
            )
        }
        return mapOf("labels" to labels, "lines" to lines, "races" to races, "week" to week, "plan" to plan)
    }
}

data class HistoryRaceView(
    val id: Long,
    val date: String,
    val name: String,
    val distance: String,
    val predicted: String,
    val actual: String,
    val diff: String,
    val diffPace: String,
    val css: String,
    val note: String?,
    val factors: List<PartView>,
)

data class TaperRowView(val id: Long, val date: String, val name: String, val taper7: String, val taper14: String, val result: String)

data class PrepRowView(val id: Long, val date: String, val name: String, val km: String, val load: String, val longRuns: String, val hard: String, val longest: String, val taper: String, val result: String)

data class LoggedRowView(
    val id: Long, val date: String, val name: String, val distance: String, val loggedOn: String, val predicted: String, val actual: String,
    val error: String, val css: String, val range: String, val version: String,
)

data class HistoryPage(
    val headline: String,
    val sub: String,
    val lines: List<String>,
    val races: List<HistoryRaceView>,
    val loggedLine: String,
    val loggedRows: List<LoggedRowView>,
    val taperRows: List<TaperRowView>,
    val taperText: String,
    val prepRows: List<PrepRowView>,
    val prepText: String,
    val pacingLines: List<String>,
    val charts: Map<String, Any?>,
    val hasRaces: Boolean,
)

object HistoryPageBuilder {
    fun build(r: RaceHistoryReport): HistoryPage {
        val acc = r.accuracy
        val n = r.outcomes.size
        val predicted = r.outcomes.filter { it.errorPct != null }
        val headline = when {
            n == 0 -> "Nog geen gemarkeerde wedstrijden"
            else -> "$n ${if (n == 1) "wedstrijd" else "wedstrijden"} teruggekeken"
        }
        val lines = ArrayList<String>()
        if (n == 0) lines += "Markeer een run op Strava als wedstrijd en haal de markeringen opnieuw op (knop op de pagina Wedstrijd)."
        else {
            val close = predicted.count { abs(it.errorPct!!) <= 3.0 }
            if (predicted.isNotEmpty()) lines += "Bij ${predicted.size} van die wedstrijden bestond er een voorspelling van vóór de wedstrijd; $close ervan zat binnen 3 % van je gelopen tijd."
            else lines += "Voor geen enkele wedstrijd bestond er een voorspelling van vóór de wedstrijd (te weinig ankers daarvoor)."
            if (acc.enough) lines += "Mediane afwijking ${RaceFmt.pct(acc.medianErrorPct)} (plus = trager dan voorspeld)."
            lines += patternLine(r)
        }

        val races = r.postRaces.map { p ->
            HistoryRaceView(
                p.race.id, PerfDates.day(p.race.date), p.race.name, RaceFmt.km(p.race.distanceMeters),
                p.predictedSeconds?.let { RaceFmt.time(RacePredictorRound.round(it)) } ?: "-", RaceFmt.time(p.actualSeconds),
                RaceFmt.pct(p.diffPct), p.diffSecondsPerKm?.let { "%+.1f s per km".format(Locale.ROOT, it).replace('.', ',') } ?: "-",
                p.diffPct?.let { if (it < 0) "tone-good" else "tone-uncertain" } ?: "tone-none", p.note,
                p.factors.map { PartView(it.title, "", "", it.detail) },
            )
        }

        val taperRows = r.taper.sortedByDescending { it.outcome.race.startEpochSeconds }.map {
            TaperRowView(it.outcome.race.id, PerfDates.day(it.outcome.race.date), it.outcome.race.name, it.taper.load7Pct?.let { v -> "${v.roundToInt()} %" } ?: "-", it.taper.load14Pct?.let { v -> "${v.roundToInt()} %" } ?: "-", RaceFmt.pct(it.outcome.errorPct))
        }
        val ts = r.taperSummary
        val taperText = if (ts.enough && ts.rho != null) "Rangcorrelatie tussen taper en resultaat: ${RaceFmt.d2(ts.rho)} (n = ${ts.n}, p = ${RaceFmt.d2(ts.pValue)}). " +
            (if ((ts.pValue ?: 1.0) < 0.05) "Dat is een duidelijk verband in je eigen wedstrijden (plus = meer belasting vooraf, trager dan voorspeld). " else "Dat is geen duidelijk verband: bij zo weinig wedstrijden kan dit toeval zijn. ") +
            "${RaceText.ASSOCIATION} Je taperde misschien meer voor belangrijke wedstrijden."
        else "Te weinig wedstrijden om een verband te zoeken (n = ${ts.n}, minstens ${ts.needed} nodig)."

        val prepRows = r.prep.sortedByDescending { it.date }.map {
            PrepRowView(it.raceId, PerfDates.day(it.date), it.name, RaceFmt.d1(it.avgWeeklyKm), it.avgWeeklyLoad?.let { v -> v.roundToInt().toString() } ?: "-", it.longRuns.toString(), it.hardSessions.toString(), RaceFmt.d1(it.longestRunKm), it.taper7Pct?.let { v -> "${v.roundToInt()} %" } ?: "-", RaceFmt.pct(it.errorPct))
        }
        val g = r.groups
        val prepText = if (g != null) "Sneller dan voorspeld (n = ${g.faster.n}) tegenover trager dan voorspeld (n = ${g.slower.n}): mediaan ${RaceFmt.d1(g.faster.medians["km"])} tegen ${RaceFmt.d1(g.slower.medians["km"])} km per week. ${Retrospective.NOTE}"
        else "Te weinig wedstrijden om twee groepen te vergelijken (elke groep minstens 4 nodig). De voorbereiding staat per wedstrijd in de staafjes hierboven. ${Retrospective.NOTE}"

        val ps = r.pacing.summary
        val pacingLines = if (ps.enough) listOf("In ${ps.slowerSecondHalf} van je ${ps.n} wedstrijden liep je de tweede helft trager (mediaan ${RaceFmt.pct(ps.medianSplitPct)}).")
        else listOf("Te weinig wedstrijden met detailmetingen (n = ${ps.n}, minstens ${ps.needed} nodig).")

        val points = r.outcomes.filter { it.predictedSeconds != null }.map {
            val km = it.race.distanceMeters / 1000
            mapOf("x" to it.predictedSeconds!! / km, "y" to it.actualSeconds / km, "name" to it.race.name, "date" to it.race.date.toString(), "id" to it.race.id, "fast" to (it.errorPct!! < 0))
        }
        val taperPoints = r.taper.filter { it.taper.load7Pct != null && it.outcome.errorPct != null }.map {
            mapOf("x" to it.taper.load7Pct, "y" to it.outcome.errorPct, "name" to it.outcome.race.name, "date" to it.outcome.race.date.toString(), "id" to it.outcome.race.id)
        }
        val prep = r.prep.sortedBy { it.date }
        val charts = mapOf(
            "points" to points, "taper" to taperPoints,
            "prep" to mapOf(
                "labels" to prep.map { it.date.toString() }, "names" to prep.map { it.name }, "fast" to prep.map { it.errorPct?.let { e -> e < 0 } },
                "km" to prep.map { it.avgWeeklyKm }, "load" to prep.map { it.avgWeeklyLoad }, "long" to prep.map { it.longRuns }, "hard" to prep.map { it.hardSessions },
                "longest" to prep.map { it.longestRunKm }, "taper" to prep.map { it.taper7Pct },
            ),
        )
        val loggedRows = r.logged.sortedByDescending { it.race.startEpochSeconds }.map { o ->
            LoggedRowView(
                o.race.id, PerfDates.day(o.race.date), o.race.name, RaceFmt.km(o.race.distanceMeters),
                PerfDates.day(o.entry.date) + if (o.daysBefore == 1L) " (de dag ervoor)" else " (${o.daysBefore} dagen ervoor)",
                RaceFmt.time(RacePredictorRound.round(o.predictedSeconds)), RaceFmt.time(o.actualSeconds), RaceFmt.pct(o.errorPct),
                if (o.errorPct < 0) "tone-good" else "tone-uncertain",
                when (o.withinRange) { true -> "binnen"; false -> "buiten"; null -> "-" }, o.entry.algorithmVersion,
            )
        }
        return HistoryPage(headline, if (n == 0) "" else RaceText.PREDICTION_NOTE, lines, races, RaceText.loggedLine(r.loggedSummary), loggedRows, taperRows, taperText, prepRows, prepText, pacingLines, charts, n > 0)
    }

    private fun patternLine(r: RaceHistoryReport): String {
        val ts = r.taperSummary
        val rho = ts.rho
        val p = ts.pValue
        return when {
            ts.enough && rho != null && p != null && p < 0.05 -> "Duidelijkste verband: taper en resultaat (rangcorrelatie ${RaceFmt.d2(rho)}). ${RaceText.ASSOCIATION}"
            r.groups != null -> "Een vergelijking van de voorbereiding vóór snelle en trage wedstrijden staat onder Voorbereiding."
            else -> "Nog geen duidelijke patronen: daarvoor zijn er te weinig wedstrijden."
        }
    }
}
