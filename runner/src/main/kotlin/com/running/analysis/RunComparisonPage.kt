package com.running.analysis

import com.running.analysis.RunComparisonService.Comparison
import com.running.analysis.RunComparisonService.Entry
import com.running.strava.analysis.Format
import com.running.strava.analysis.TrainingAssessment.RatingLevel
import com.running.strava.analysis.performance.RunComparison
import com.running.strava.analysis.performance.RunComparison.AspectResult
import com.running.strava.analysis.performance.RunComparison.Better
import com.running.strava.analysis.performance.RunComparison.Caution
import com.running.strava.analysis.performance.RunComparison.Check
import com.running.strava.analysis.performance.RunComparison.CompareAspect
import com.running.strava.analysis.performance.RunComparison.Level
import com.running.strava.analysis.performance.RunComparison.Status
import com.running.strava.analysis.performance.SteadyFailure
import com.running.strava.domain.SessionType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One chosen run with its letter and colour, used on every part of the page. */
data class CmpRun(
    val letter: String,
    val index: Int,
    val color: String,
    val id: Long,
    val name: String,
    val date: String,
    val dateShort: String,
    val meta: String,
    val type: String,
    val removeUrl: String,
)

data class CmpCheck(val ok: Boolean?, val text: String)

/** "Snelste", "Beste conditie", "Best uitgevoerd": which run, with the number that decided it. */
data class CmpHighlight(
    val label: String,
    val tip: String?,
    val runs: List<CmpRun>,
    val value: String,
    val sub: String,
    val caution: Boolean,
)

data class CmpTally(val run: CmpRun, val wins: Int, val shared: Int, val pips: List<String>)

data class CmpHero(
    val css: String,
    val headline: String,
    val sub: String,
    val levelLabel: String,
    val levelTone: Tone,
    val highlights: List<CmpHighlight>,
    val tally: List<CmpTally>,
    val compared: Int,
    val checks: List<CmpCheck>,
    val conclusion: List<String>,
)

/** One run on the shared axis of an aspect. [pos] is 0 to 100 (better to the right), null without a value. */
data class CmpAspectRow(
    val run: CmpRun,
    val value: String,
    val pos: Double?,
    val best: Boolean,
    val note: String?,
    val tone: Tone = Tone.NEUTRAL,
)

data class CmpAspect(
    val key: String,
    val title: String,
    val tip: String?,
    val lead: String,
    val statusLabel: String,
    val statusTone: Tone,
    val verdict: String,
    val rows: List<CmpAspectRow>,
    val cautions: List<String>,
    /** Left and right end of the axis, and the noise band next to the best value (percent of the track). */
    val axisLeft: String,
    val axisRight: String,
    val axisCaption: String,
    val bandFrom: Double?,
    val bandTo: Double?,
    /** Execution: a verdict per run instead of a position on an axis. */
    val discrete: Boolean = false,
) {
    val caution: Boolean get() = cautions.isNotEmpty()
}

data class CmpZoneRow(val run: CmpRun, val shares: List<ZoneShare>?, val note: String?) {
    val aria: String get() = run.letter + ": " + shares.orEmpty().filter { it.pct > 0 }.joinToString(", ") { "zone ${it.zone} ${it.pct.roundToInt()} %" }
}

data class CmpTableRow(val label: String, val tip: String?, val values: List<String>, val best: List<Boolean>, val groupStart: Boolean = false)

data class RunComparisonView(
    val runs: List<CmpRun>,
    val hero: CmpHero,
    val aspects: List<CmpAspect>,
    val skipped: List<String>,
    val zoneRows: List<CmpZoneRow>,
    val zoneNote: String,
    val zoneLegend: List<Pair<Int, String>>,
    val hasCurves: Boolean,
    val hasHrCurves: Boolean,
    val hasReps: Boolean,
    val blockText: String,
    val chart: Map<String, Any?>,
    val table: List<CmpTableRow>,
    val hiddenRows: List<String>,
)

/**
 * Dutch wording and visual scales of the "Trainingen vergelijken" page. The decisions (who is best, what is a tie,
 * what needs a caution) come from [RunComparison]; this object only names them, formats the numbers and places the
 * runs on an axis. Every axis is labelled with real values and shows the noise band, so a small difference never
 * looks bigger than it is.
 */
object RunComparisonPage {
    private val ROOT = Locale.ROOT
    val LETTERS = listOf("A", "B", "C", "D", "E", "F")
    /** Run colours, in sync with `.run-1` to `.run-6` in app.css. */
    val COLORS = listOf("#0e6655", "#c2410c", "#2f6db0", "#8a5cc2", "#8a6500", "#15202b")

    private fun f(v: Double, d: Int = 1) = "%.${d}f".format(ROOT, v)

    fun build(c: Comparison, ids: List<Long>): RunComparisonView {
        val runs = c.entries.mapIndexed { i, e -> runChip(e, i, ids) }
        val byId = runs.associateBy { it.id }
        val entries = c.entries.associateBy { it.analysis.id }
        val r = c.result

        val aspects = r.aspects.filter { it.status != Status.TOO_FEW }.map { aspect(it, runs, entries) }
        val skipped = r.aspects.filter { it.status == Status.TOO_FEW }.map { a ->
            val name = title(a).replaceFirstChar { it.lowercase() }
            if (a.values.isEmpty()) "$name (bij geen enkele run beschikbaar)" else "$name (alleen bij ${byId.getValue(a.values.keys.first()).letter})"
        }

        val zoneRows = if (c.zoneSet == null) emptyList() else runs.map { run ->
            val secs = c.zoneSeconds(run.id)
            CmpZoneRow(run, secs?.let { shares(it, c) }, if (secs == null) "Geen tijd in zones: te weinig hartslagdata of geen detailmetingen." else null)
        }
        val zoneLegend = c.zoneSet?.let { set ->
            (1..5).map { z -> z to if (z == 5) "vanaf ${set.floors[4]} bpm" else if (z == 1) "tot ${set.floors[1] - 1} bpm" else "${set.floors[z - 1]}-${set.floors[z] - 1} bpm" }
        }.orEmpty()

        val curves = c.entries.filter { it.curve.size >= 2 }
        val repRuns = c.entries.filter { it.analysis.intervals?.reps.orEmpty().size >= 2 }
        val chart = mapOf(
            "runs" to c.entries.mapIndexed { i, e ->
                mapOf(
                    "letter" to LETTERS[i], "color" to COLORS[i], "name" to e.activity.name, "date" to runs[i].date, "id" to e.analysis.id,
                    "curve" to e.curve.map { p -> listOf(p.km, p.paceSecondsPerKm?.let { (it * 10).roundToInt() / 10.0 }, p.hr?.let { (it * 10).roundToInt() / 10.0 }) },
                    "reps" to e.analysis.intervals?.reps.orEmpty().map { rep -> listOf(rep.number, if (rep.speed > 0) (10000 / rep.speed).roundToInt() / 10.0 else null, rep.avgHr) },
                    "signature" to e.analysis.intervals?.signature,
                )
            },
        )
        val (table, hidden) = table(c, runs)
        return RunComparisonView(
            runs = runs,
            hero = hero(c, runs, byId),
            aspects = aspects,
            skipped = skipped,
            zoneRows = zoneRows,
            zoneNote = c.zoneNote,
            zoneLegend = zoneLegend,
            hasCurves = curves.size >= RunComparison.MIN_RUNS,
            hasHrCurves = curves.count { e -> e.curve.any { it.hr != null } } >= RunComparison.MIN_RUNS,
            hasReps = repRuns.size >= RunComparison.MIN_RUNS,
            blockText = "%.0f m".format(ROOT, c.blockMeters),
            chart = chart,
            table = table,
            hiddenRows = hidden,
        )
    }

    private fun Comparison.zoneSeconds(id: Long) = entries.firstOrNull { it.analysis.id == id }?.zoneSeconds

    // ---------------------------------------------------------------- runs

    private fun runChip(e: Entry, i: Int, ids: List<Long>): CmpRun {
        val a = e.analysis
        val rest = ids.filter { it != a.id }
        return CmpRun(
            letter = LETTERS[i], index = i + 1, color = COLORS[i], id = a.id, name = e.activity.name,
            date = PerfDates.day(a.date), dateShort = PerfDates.dayShort(a.date),
            meta = listOf(Format.km(a.distanceMeters), Format.pace(a.distanceMeters / a.movingSeconds.coerceAtLeast(1)), Format.hr(a.avgHr))
                .filter { it != "-" }.joinToString(" · "),
            type = typeLabel(a.sessionType) + if (a.isTrainer) " · loopband" else "",
            removeUrl = "/compare-runs" + if (rest.isEmpty()) "" else "?ids=" + rest.joinToString(","),
        )
    }

    fun typeLabel(t: SessionType) = when (t) {
        SessionType.RACE -> "wedstrijd"
        SessionType.INTERVAL -> "interval"
        SessionType.TEMPO -> "tempo"
        SessionType.STRIDES -> "rustig met versnellingen"
        SessionType.STEADY -> "rustig"
    }

    private fun letters(ids: Collection<Long>, byId: Map<Long, CmpRun>): String =
        joinAnd(ids.mapNotNull { byId[it] }.sortedBy { it.index }.map { it.letter })

    private fun joinAnd(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " en " + items.last()
    }

    // ---------------------------------------------------------------- hero

    private fun hero(c: Comparison, runs: List<CmpRun>, byId: Map<Long, CmpRun>): CmpHero {
        val r = c.result
        val tallied = r.aspects.filter { it.aspect.inTally && it.status != Status.TOO_FEW && it.status != Status.NOT_COMPARABLE }
        val noDifference = tallied.isNotEmpty() && tallied.all { it.status == Status.NO_DIFFERENCE }
        val leader = r.leader?.let { byId[it] }
        val leaderFirsts = leader?.let { r.firsts(it.id) } ?: 0
        val leaderShared = leader?.let { r.shared[it.id] ?: 0 } ?: 0
        val topFirsts = runs.maxOfOrNull { r.firsts(it.id) } ?: 0
        val headline = when {
            r.compared == 0 -> "Te weinig gegevens om te vergelijken"
            leader != null && leaderShared == 0 -> "${leader.letter} was het best op $leaderFirsts van de ${r.compared} onderdelen"
            leader != null -> "${leader.letter} was het vaakst bij de besten: $leaderFirsts van de ${r.compared} onderdelen"
            noDifference -> "Geen duidelijk verschil tussen deze runs"
            topFirsts == 0 -> "Geen run springt eruit"
            else -> "Geen run springt eruit: ${letters(runs.filter { r.firsts(it.id) == topFirsts }.map { it.id }, byId)} waren elk op $topFirsts " +
                (if (topFirsts == 1) "onderdeel" else "onderdelen") + " bij de besten"
        }
        val sub = when {
            r.compared == 0 -> "Voor geen enkel onderdeel hebben minstens twee runs een waarde."
            leader != null -> "${leader.letter} = ${leader.name}, ${leader.date}." +
                (when {
                    leaderShared == 0 -> ""
                    (r.wins[leader.id] ?: 0) == 0 -> " Telkens gedeeld met een run die even goed was."
                    else -> " Daarvan ${r.wins[leader.id]} alleen en $leaderShared gedeeld met een run die even goed was."
                }) +
                " Verschillen binnen de meetruis tellen niet."
            noDifference -> "Op elk onderdeel liggen de runs binnen de meetruis van elkaar. Dat is een uitkomst, geen gebrek aan data."
            else -> "Verschillen binnen de meetruis tellen niet. Hieronder zie je per onderdeel wie voorop lag."
        }
        val (levelLabel, levelTone, css) = when (r.comparability.level) {
            Level.GOOD -> Triple("Goed vergelijkbaar", Tone.GOOD, "hero-ok")
            Level.PARTLY -> Triple("Deels vergelijkbaar", Tone.UNCERTAIN, "hero-warn")
            Level.HARD -> Triple("Moeilijk vergelijkbaar", Tone.BAD, "hero-alert")
        }
        val checks = checks(c)
        val highlights = listOf(
            highlight("Snelste", null, r.aspect(CompareAspect.SPEED), byId, c),
            highlight("Beste conditie", "ef", r.aspect(CompareAspect.EFFICIENCY), byId, c),
            highlight("Best uitgevoerd", "uitvoering", r.aspect(CompareAspect.EXECUTION), byId, c),
        )
        val conclusion = buildList {
            val differs = checks.filter { it.ok == false }.map { it.text }
            add(
                when (r.comparability.level) {
                    Level.GOOD -> "Deze runs zijn goed vergelijkbaar: zelfde soort training, gelijkaardige afstand, zelfde terrein en ondergrond."
                    Level.PARTLY -> "Deze runs zijn deels vergelijkbaar: ${differs.joinToString("; ")}. Onderdelen waar dat meetelt, staan hieronder onder voorbehoud."
                    Level.HARD -> "Let op, deze runs zijn moeilijk te vergelijken: ${differs.joinToString("; ")}. Lees de uitkomst als een beschrijving, niet als een oordeel over je vorm."
                },
            )
            val ties = r.aspects.filter { it.status == Status.NO_DIFFERENCE }.map { title(it).replaceFirstChar { ch -> ch.lowercase() } }
            if (ties.isNotEmpty()) add("Geen duidelijk verschil (binnen de meetruis) bij: ${joinAnd(ties)}.")
            val notComparable = r.aspects.filter { it.status == Status.NOT_COMPARABLE }.map { title(it).replaceFirstChar { ch -> ch.lowercase() } }
            if (notComparable.isNotEmpty()) add("Niet eerlijk te vergelijken: ${joinAnd(notComparable)}.")
            add("Eén run is één dag: slaap, warmte, wind, vermoeidheid en je hartslagmeting spelen mee. Verband, geen oorzaak.")
        }
        return CmpHero(
            css = if (r.compared == 0) "hero-na" else css,
            headline = headline,
            sub = sub,
            levelLabel = levelLabel,
            levelTone = levelTone,
            highlights = highlights,
            tally = runs.map {
                val w = r.wins[it.id] ?: 0
                val sh = r.shared[it.id] ?: 0
                CmpTally(it, w, sh, List(w) { "is-win" } + List(sh) { "is-shared" } + List((r.compared - w - sh).coerceAtLeast(0)) { "" })
            },
            compared = r.compared,
            checks = checks,
            conclusion = conclusion,
        )
    }

    private fun checks(c: Comparison): List<CmpCheck> {
        val checks = c.result.comparability.checks
        val e = c.entries.map { it.analysis }
        val types = e.map { typeLabel(it.sessionType) }.distinct()
        val km = e.map { it.distanceMeters }
        val temps = e.mapNotNull { it.tempC }
        return Check.entries.map { k ->
            val ok = checks[k]
            CmpCheck(
                ok,
                when (k) {
                    Check.SAME_TYPE -> if (ok == true) "zelfde soort training (${types.first()})" else "verschillende soort training (${types.joinToString(", ")})"
                    Check.SIMILAR_DISTANCE -> if (ok == true) "gelijkaardige afstand (${kmRange(km)})"
                    else "afstand verschilt meer dan 25 % (${kmRange(km)})"
                    Check.SAME_TERRAIN -> if (ok == true) "zelfde terrein (${e.first().terrain.text})" else "ander terrein (${e.map { it.terrain.text }.distinct().joinToString(", ")})"
                    Check.SAME_SURFACE -> if (ok == true) (if (e.first().isTrainer) "allemaal op de loopband" else "allemaal buiten") else "loopband en buiten door elkaar"
                    Check.SIMILAR_TEMPERATURE -> when (ok) {
                        null -> "temperatuur onbekend"
                        true -> "gelijkaardige temperatuur (${tempRange(temps)})"
                        false -> "temperatuur verschilt meer dan 5 °C (${tempRange(temps)})"
                    }
                    Check.ALL_HR -> if (ok == true) "allemaal met hartslag" else "niet elke run heeft hartslag"
                },
            )
        }
    }

    private fun kmRange(m: List<Double>) = if (abs(m.max() - m.min()) < 50) Format.km(m.min()) else "${f(m.min() / 1000)} tot ${f(m.max() / 1000)} km"
    private fun tempRange(t: List<Double>) = if (t.min().roundToInt() == t.max().roundToInt()) "${t.min().roundToInt()} °C" else "${t.min().roundToInt()} tot ${t.max().roundToInt()} °C"

    private fun highlight(label: String, tip: String?, a: AspectResult, byId: Map<Long, CmpRun>, c: Comparison): CmpHighlight {
        val winners = a.winners.mapNotNull { byId[it] }.sortedBy { it.index }
        val sub = when (a.status) {
            Status.WINNER, Status.SHARED -> {
                val margin = margin(a, byId)
                buildString {
                    if (a.status == Status.SHARED) append("Gedeeld, verschil binnen de meetruis. ")
                    if (margin != null) append(margin.replaceFirstChar { it.uppercase() }).append(". ")
                    if (a.aspect == CompareAspect.EFFICIENCY) append(if (a.wholeRun) "Over de hele run, onder voorbehoud." else "Stabiel deel van de run.")
                    else if (a.cautions.isNotEmpty()) append("Onder voorbehoud: ${cautionShort(a.cautions.first())}.")
                }.trim()
            }
            Status.NO_DIFFERENCE -> "Alle runs liggen binnen ${noise(a.aspect)} van elkaar."
            Status.NOT_COMPARABLE -> "Niet eerlijk te vergelijken."
            Status.TOO_FEW -> when (a.aspect) {
                CompareAspect.EFFICIENCY -> "Minstens twee runs met hartslag nodig."
                CompareAspect.EXECUTION -> "Minstens twee runs met een oordeel van Training beoordelen nodig."
                else -> "Te weinig gegevens."
            }
        }
        val value = when (a.status) {
            Status.WINNER, Status.SHARED -> value(a.aspect, a.best!!)
            Status.NO_DIFFERENCE -> if (a.aspect == CompareAspect.EXECUTION) "Zelfde oordeel" else "Even goed"
            Status.NOT_COMPARABLE, Status.TOO_FEW -> "Niet te bepalen"
        }
        return CmpHighlight(label, tip, winners, value, sub, a.cautions.isNotEmpty() && a.decided)
    }

    // ---------------------------------------------------------------- aspects

    fun title(a: AspectResult): String = when (a.aspect) {
        CompareAspect.SPEED -> "Snelheid"
        CompareAspect.GAP -> "Tempo zonder hellingen (GAP)"
        CompareAspect.EFFICIENCY -> if (a.wholeRun) "Conditie: meter per hartslag (hele run)" else "Conditie: meter per hartslag"
        CompareAspect.DECOUPLING -> "Efficiëntie vasthouden (ontkoppeling)"
        CompareAspect.EVENNESS -> "Gelijkmatig tempo"
        CompareAspect.REP_SPEED -> "Tempo van de reps"
        CompareAspect.REP_EVENNESS -> "Gelijkmatige reps"
        CompareAspect.REP_FADE -> "Verval over de reps"
        CompareAspect.HRR -> "Hartslagherstel na de reps"
        CompareAspect.EXECUTION -> "Uitvoering"
    }

    private fun tip(a: CompareAspect) = when (a) {
        CompareAspect.SPEED -> null
        CompareAspect.GAP -> "gap"
        CompareAspect.EFFICIENCY -> "ef"
        CompareAspect.DECOUPLING -> "ontkoppeling"
        CompareAspect.EVENNESS -> "temposchommeling"
        CompareAspect.REP_SPEED -> "opbouw"
        CompareAspect.REP_EVENNESS -> "spreiding"
        CompareAspect.REP_FADE -> "verval"
        CompareAspect.HRR -> "hrr"
        CompareAspect.EXECUTION -> "uitvoering"
    }

    private fun lead(a: AspectResult): String = when (a.aspect) {
        CompareAspect.SPEED -> "Gemiddeld tempo over je bewegingstijd (pauzes tellen niet mee). Sneller is beter, zolang de runs vergelijkbaar zijn."
        CompareAspect.GAP -> "Het tempo omgerekend naar vlak terrein, zodat heuvels niet meetellen. Telt niet apart mee in de telling: het is dezelfde snelheid."
        CompareAspect.EFFICIENCY -> if (a.wholeRun) {
            "Minder dan twee stabiele duurlopen, dus de app rekent met de hele run: gemiddeld tempo gedeeld door gemiddelde hartslag. " +
                "Dat hangt sterk af van hoe hard je liep, dus lees het onder voorbehoud. Meer meter per hartslag = zuiniger gelopen."
        } else {
            "Hoeveel meter je per hartslag aflegde in het stabiele deel (na de eerste 10 minuten). Meer meter per hartslag = betere aerobe conditie. " +
                "Alleen voor stabiele duurlopen."
        }
        CompareAspect.DECOUPLING -> "Hoeveel meter per hartslag je in de tweede helft verloor tegenover de eerste. Lager = je hield je efficiëntie beter vast. " +
            "Vuistregel bij coaches: onder 5 %. Onder 0 % verloor je niets; dat telt als even goed, want een sterk negatief cijfer wijst eerder op een trage start dan op een betere run."
        CompareAspect.EVENNESS -> "Hoeveel je tempo verschilde tussen blokken van 5 minuten na de opwarming. Lager = gelijkmatiger gelopen. Niet voor intervaltrainingen."
        CompareAspect.REP_SPEED -> "Gemiddeld tempo over alle herkende reps (pauzes niet meegeteld). Alleen eerlijk bij dezelfde opbouw, want kortere reps loop je sneller."
        CompareAspect.REP_EVENNESS -> "Hoeveel je reps in tempo van elkaar verschilden. Lager = gelijkmatiger."
        CompareAspect.REP_FADE -> "Hoeveel trager je laatste rep was dan je eerste. Lager = je hield beter vol. Onder 0 % versnelde je; dat telt als even goed als gelijk blijven."
        CompareAspect.HRR -> "Hoeveel je hartslag zakte in de 60 seconden na een rep (mediaan). Hoger = sneller herstel. Alleen vergeleken bij dezelfde manier van herstellen."
        CompareAspect.EXECUTION -> "Het oordeel van Training beoordelen: hoe je de training uitvoerde, gemeten tegen haar eigen soort. Uitvoering klopt > aandachtspunt > wijkt af."
    }

    private fun value(a: CompareAspect, v: Double): String = when (a) {
        CompareAspect.SPEED, CompareAspect.GAP, CompareAspect.REP_SPEED -> Format.pace(v)
        CompareAspect.EFFICIENCY -> PerformanceText.efMetres(v) + " per hartslag"
        CompareAspect.DECOUPLING, CompareAspect.REP_FADE -> "%+.1f %%".format(ROOT, v)
        CompareAspect.EVENNESS, CompareAspect.REP_EVENNESS -> "${f(v)} %"
        CompareAspect.HRR -> "${v.roundToInt()} bpm"
        CompareAspect.EXECUTION -> executionText(v)
    }

    private fun shortValue(a: CompareAspect, v: Double): String = when (a) {
        CompareAspect.EFFICIENCY -> PerformanceText.efMetres(v)
        else -> value(a, v)
    }

    private fun executionText(v: Double) = when (v.roundToInt()) {
        3 -> "Uitvoering klopt"
        2 -> "Aandachtspunt"
        else -> "Wijkt af"
    }

    private fun executionTone(v: Double) = when (v.roundToInt()) {
        3 -> Tone.GOOD
        2 -> Tone.UNCERTAIN
        else -> Tone.BAD
    }

    /** How large a difference must be to count, in words. */
    private fun noise(a: CompareAspect) = when {
        a == CompareAspect.EXECUTION -> "hetzelfde oordeel"
        a.floor != null -> "${f(a.tieAbs!!, 0)} procentpunt, en alles onder ${f(a.floor!!, 0)} % telt als even goed"
        a.tieRelPct != null -> "${f(a.tieRelPct!!, 0)} %"
        a == CompareAspect.HRR -> "${f(a.tieAbs!!, 0)} bpm"
        else -> "${f(a.tieAbs!!, 0)} procentpunt"
    }

    /**
     * Difference of [value] against [reference] in words, e.g. "12 s/km trager", "0.05 m minder per hartslag (4 %)".
     * Aspects with a floor compare the effective values: -5 % and 0 % decoupling are both "no loss".
     */
    private fun diff(a: CompareAspect, value: Double, reference: Double): String =
        rawDiff(a, RunComparison.effective(a, value), RunComparison.effective(a, reference))

    private fun rawDiff(a: CompareAspect, v: Double, ref: Double): String = when (a) {
        CompareAspect.SPEED, CompareAspect.GAP, CompareAspect.REP_SPEED -> {
            val s = (1000 / v - 1000 / ref).roundToInt()
            when {
                s == 0 -> "even snel"
                abs(s) < 60 -> "${abs(s)} s/km ${if (s > 0) "trager" else "sneller"}"
                else -> "${Format.paceFromSeconds(abs(s).toDouble(), unit = false)} /km ${if (s > 0) "trager" else "sneller"}"
            }
        }
        CompareAspect.EFFICIENCY -> {
            val m = (v - ref) * 60
            val pct = (v - ref) / ref * 100
            "${"%.2f".format(ROOT, abs(m))} m ${if (m < 0) "minder" else "meer"} per hartslag (${f(abs(pct), 0)} %)"
        }
        CompareAspect.DECOUPLING, CompareAspect.EVENNESS, CompareAspect.REP_EVENNESS, CompareAspect.REP_FADE ->
            "${f(abs(v - ref))} procentpunt ${if (v > ref) "hoger" else "lager"}"
        CompareAspect.HRR -> "%.0f bpm %s".format(ROOT, abs(v - ref), if (v < ref) "minder" else "meer")
        CompareAspect.EXECUTION -> ""
    }

    /** The margin of the best run over the best of the rest: "8 s/km sneller dan C". */
    private fun margin(a: AspectResult, byId: Map<Long, CmpRun>): String? {
        if (!a.decided || a.aspect == CompareAspect.EXECUTION) return null
        val best = a.best ?: return null
        val rest = a.values.filterKeys { it !in a.winners }
        if (rest.isEmpty()) return null
        val next = if (a.aspect.better == Better.HIGHER) rest.maxBy { it.value } else rest.minBy { it.value }
        return "${diff(a.aspect, best, next.value)} dan ${byId.getValue(next.key).letter}"
    }

    private fun cautionText(k: Caution, a: AspectResult, entries: Map<Long, Entry>): String {
        val runs = a.values.keys.mapNotNull { entries[it]?.analysis }.ifEmpty { entries.values.map { it.analysis } }
        return when (k) {
            Caution.SESSION_TYPE -> "verschillende soort training (${runs.map { typeLabel(it.sessionType) }.distinct().joinToString(", ")})"
            Caution.DISTANCE -> "afstand verschilt meer dan 25 % (${kmRange(runs.map { it.distanceMeters })})"
            Caution.TERRAIN -> "ander terrein (${runs.map { it.terrain.text }.distinct().joinToString(", ")}); heuvels maken je trager en je hartslag hoger"
            Caution.TREADMILL -> "loopband en buiten door elkaar; op de loopband meet je horloge de afstand minder nauwkeurig"
            Caution.HEAT -> "temperatuur verschilt meer dan 5 °C (${tempRange(runs.mapNotNull { it.tempC })}); warmte verhoogt je hartslag"
            Caution.HR_LEVEL -> {
                val hr = if (a.wholeRun) runs.mapNotNull { it.avgHr } else runs.mapNotNull { it.ef?.avgHr }
                "gemiddelde hartslag verschilt (${hr.min().roundToInt()} tot ${hr.max().roundToInt()} bpm); meter per hartslag hangt af van hoe hard je liep"
            }
            Caution.DURATION -> {
                val min = runs.mapNotNull { it.ef?.windowMinutes }
                "het stabiele deel verschilt in lengte (${min.min().roundToInt()} tot ${min.max().roundToInt()} min); in een langer stuk loopt je hartslag meer op"
            }
            Caution.REP_STRUCTURE -> "andere opbouw van de reps (${runs.map { it.intervals?.signature ?: "gemengd" }.distinct().joinToString(", ")})"
            Caution.WHOLE_RUN -> "hele run in plaats van een stabiel deel: opwarming, pauzes en tempowissels tellen mee"
        }
    }

    private fun cautionShort(k: Caution) = when (k) {
        Caution.SESSION_TYPE -> "andere soort training"
        Caution.DISTANCE -> "andere afstand"
        Caution.TERRAIN -> "ander terrein"
        Caution.TREADMILL -> "loopband en buiten"
        Caution.HEAT -> "andere temperatuur"
        Caution.HR_LEVEL -> "andere hartslag"
        Caution.DURATION -> "ander stabiel deel"
        Caution.REP_STRUCTURE -> "andere opbouw"
        Caution.WHOLE_RUN -> "hele run"
    }

    private fun aspect(a: AspectResult, runs: List<CmpRun>, entries: Map<Long, Entry>): CmpAspect {
        val byId = runs.associateBy { it.id }
        val better = a.aspect.better
        val discrete = a.aspect == CompareAspect.EXECUTION
        // Goodness: higher is always better on the axis, so the best run is always on the right.
        fun g(v: Double) = if (better == Better.HIGHER) v else -v
        val gs = a.values.values.map { g(it) }
        val noiseG = when {
            a.aspect.tieAbs != null -> a.aspect.tieAbs!!
            a.best != null -> abs(a.best!!) * (a.aspect.tieRelPct ?: 0.0) / 100
            else -> 0.0
        }
        val span = if (gs.isEmpty()) 0.0 else gs.max() - gs.min()
        val pad = maxOf(span * 0.15, noiseG * 0.75, if (gs.isEmpty()) 1.0 else abs(gs.max()) * 0.005, 1e-6)
        val lo = (gs.minOrNull() ?: 0.0) - pad
        val hi = (gs.maxOrNull() ?: 1.0) + pad
        fun pos(v: Double) = ((g(v) - lo) / (hi - lo) * 100).coerceIn(0.0, 100.0)
        fun ungood(x: Double) = if (better == Better.HIGHER) x else -x

        val rows = runs.map { run ->
            val v = a.values[run.id]
            if (v == null) {
                CmpAspectRow(run, if (discrete) "Geen oordeel" else "-", null, false, missing(a.aspect, a.wholeRun, entries.getValue(run.id)), Tone.NONE)
            } else {
                val best = run.id in a.winners
                val note = when {
                    discrete -> null
                    best && a.status == Status.WINNER -> "beste"
                    best -> "gedeeld beste"
                    a.best != null && RunComparison.tied(a.aspect, v, a.best!!) -> "gelijk binnen de meetruis"
                    a.best != null && a.values.size > 1 -> diff(a.aspect, v, a.best!!) + " dan de beste"
                    else -> null
                }
                CmpAspectRow(run, if (discrete) executionText(v) else value(a.aspect, v), if (discrete) null else pos(v), best, note,
                    if (discrete) executionTone(v) else Tone.NEUTRAL)
            }
        }
        val winnersText = letters(a.winners, byId)
        val verdict = when (a.status) {
            Status.WINNER -> {
                val m = margin(a, byId)
                if (discrete) "$winnersText kreeg het beste oordeel." else "$winnersText was het best" + (m?.let { ": $it." } ?: ".")
            }
            Status.SHARED -> {
                val m = margin(a, byId)
                "$winnersText delen de eerste plaats: hun verschil valt binnen de meetruis (${noise(a.aspect)})" + (m?.let { ". Daarna: $it." } ?: ".")
            }
            Status.NO_DIFFERENCE -> if (discrete) "Elke run kreeg hetzelfde oordeel." else "Geen duidelijk verschil: alle runs liggen binnen ${noise(a.aspect)} van elkaar."
            Status.NOT_COMPARABLE -> when (a.aspect) {
                CompareAspect.HRR -> "Niet te vergelijken: je herstelde op een andere manier (${a.values.keys.mapNotNull { entries[it]?.compared?.interval?.hrrProtocol?.text }.distinct().joinToString(", ")}). Staan, wandelen en joggen geven een ander hartslagherstel."
                else -> "Niet eerlijk te vergelijken: de reps hebben een andere opbouw. Kortere reps loop je sneller, dus een sneller reptempo zegt hier niets."
            }
            Status.TOO_FEW -> "Te weinig gegevens."
        }
        val (statusLabel, statusTone) = when (a.status) {
            Status.WINNER -> (if (a.cautions.isEmpty()) "Best: $winnersText" else "Best: $winnersText, onder voorbehoud") to (if (a.cautions.isEmpty()) Tone.GOOD else Tone.UNCERTAIN)
            Status.SHARED -> "Gedeeld: $winnersText" to (if (a.cautions.isEmpty()) Tone.GOOD else Tone.UNCERTAIN)
            Status.NO_DIFFERENCE -> "Geen duidelijk verschil" to Tone.NEUTRAL
            Status.NOT_COMPARABLE -> "Niet vergelijkbaar" to Tone.NONE
            Status.TOO_FEW -> "Te weinig gegevens" to Tone.NONE
        }
        // The noise band: every value between the best and this edge counts as equally good.
        val bandEdge = a.best?.let { b ->
            val tieAbs = a.aspect.tieAbs
            val rel = a.aspect.tieRelPct
            when {
                tieAbs != null && better == Better.HIGHER -> b - tieAbs
                tieAbs != null -> RunComparison.effective(a.aspect, b) + tieAbs
                rel != null && better == Better.HIGHER -> b * (1 - rel / 100)
                rel != null -> b * (1 + rel / 100)
                else -> b
            }
        }
        val showBand = !discrete && a.best != null && bandEdge != null && a.status != Status.NOT_COMPARABLE
        val bandFrom = if (showBand) pos(bandEdge!!) else null
        val bandTo = if (showBand) pos(a.best!!) else null
        val speedLike = a.aspect in setOf(CompareAspect.SPEED, CompareAspect.GAP, CompareAspect.REP_SPEED)
        return CmpAspect(
            key = a.aspect.name.lowercase(),
            title = title(a),
            tip = tip(a.aspect),
            lead = lead(a),
            statusLabel = statusLabel,
            statusTone = statusTone,
            verdict = verdict,
            rows = rows,
            cautions = a.cautions.map { cautionText(it, a, entries) },
            axisLeft = if (discrete) "" else shortValue(a.aspect, ungood(lo)),
            axisRight = if (discrete) "" else shortValue(a.aspect, ungood(hi)),
            axisCaption = when {
                discrete -> ""
                speedLike -> "sneller →"
                a.aspect == CompareAspect.EFFICIENCY -> "meer meter per hartslag →"
                a.aspect == CompareAspect.HRR -> "sneller herstel →"
                else -> "lager is beter →"
            },
            bandFrom = bandFrom,
            bandTo = bandTo,
            discrete = discrete,
        )
    }

    private fun steadyReason(f: SteadyFailure?) = when (f) {
        null -> "geen stabiel deel"
        SteadyFailure.NO_STREAM -> "geen detailmetingen"
        else -> "geen stabiele run: ${f.text}"
    }

    /** Why a run has no value for an aspect, in plain Dutch. */
    private fun missing(a: CompareAspect, wholeRun: Boolean, e: Entry): String {
        val r = e.analysis
        return when (a) {
            CompareAspect.SPEED -> "geen afstand of tijd"
            CompareAspect.GAP -> when {
                r.isTrainer -> "loopband: geen hellingsdata"
                !r.hasStream -> "geen detailmetingen"
                else -> r.gap?.reason ?: "te weinig bruikbare hartslag- of hellingsdata"
            }
            CompareAspect.EFFICIENCY -> if (wholeRun) "geen hartslag" else steadyReason(r.steadyFailure)
            CompareAspect.DECOUPLING -> steadyReason(r.steadyFailure)
            CompareAspect.EVENNESS -> when {
                r.sessionType == SessionType.INTERVAL -> "intervaltraining: tempowissels horen erbij"
                !r.hasStream -> "geen detailmetingen"
                else -> "te kort na de opwarming"
            }
            CompareAspect.REP_SPEED, CompareAspect.REP_EVENNESS, CompareAspect.REP_FADE -> when {
                r.sessionType != SessionType.INTERVAL -> "geen intervaltraining"
                r.intervals == null -> "geen reps herkend"
                else -> "minder dan 3 reps"
            }
            CompareAspect.HRR -> when {
                r.sessionType != SessionType.INTERVAL -> "geen intervaltraining"
                else -> r.intervals?.reps?.firstNotNullOfOrNull { it.hrrNote }?.let { "niet gemeten: $it" } ?: "niet gemeten"
            }
            CompareAspect.EXECUTION -> if (e.assessment?.level == RatingLevel.INSUFFICIENT) "geen oordeel mogelijk (te weinig rondes of hartslag)" else "geen oordeel"
        }
    }

    // ---------------------------------------------------------------- zones and table

    private fun shares(secs: List<Double>, c: Comparison): List<ZoneShare> {
        val set = c.zoneSet!!
        val total = secs.sum()
        return secs.mapIndexed { i, s ->
            val range = if (i == 4) "vanaf ${set.floors[4]} bpm" else if (i == 0) "tot ${set.floors[1] - 1} bpm" else "${set.floors[i]}-${set.floors[i + 1] - 1} bpm"
            ZoneShare(i + 1, if (total > 0) s / total * 100 else 0.0, "%.0f min".format(ROOT, s / 60), range)
        }
    }

    private fun table(c: Comparison, runs: List<CmpRun>): Pair<List<CmpTableRow>, List<String>> {
        val e = c.entries
        val res = c.result
        fun bests(a: CompareAspect) = runs.map { it.id in res.aspect(a).winners }
        val none = runs.map { false }
        val rows = listOf(
            CmpTableRow("Datum", null, runs.map { it.date }, none),
            CmpTableRow("Soort training", "sessietype", e.map { typeLabel(it.analysis.sessionType) + if (it.analysis.isTrainer) " (loopband)" else "" }, none),
            CmpTableRow("Afstand", null, e.map { Format.km(it.analysis.distanceMeters) }, none, groupStart = true),
            CmpTableRow("Bewegingstijd", null, e.map { Format.duration(it.analysis.movingSeconds) }, none),
            CmpTableRow("Gemiddeld tempo", null, e.map { Format.pace(it.analysis.distanceMeters / it.analysis.movingSeconds.coerceAtLeast(1)) }, bests(CompareAspect.SPEED)),
            CmpTableRow("GAP", "gap", e.map { Format.pace(it.analysis.gap?.gapSpeed) }, bests(CompareAspect.GAP)),
            CmpTableRow("Gemiddelde hartslag", null, e.map { Format.hr(it.analysis.avgHr) }, none, groupStart = true),
            CmpTableRow("Max. hartslag", null, e.map { Format.hr(it.activity.maxHeartrate) }, none),
            CmpTableRow("Hartslagdekking", null, e.map { if (it.analysis.hasStream) "%.0f %%".format(ROOT, it.analysis.coverage * 100) else "-" }, none),
            CmpTableRow("Terrein", "terrein", e.map { "${it.analysis.terrain.text} (${f(it.analysis.elevationPerKm)} m/km)" }, none),
            CmpTableRow("Temperatuur", null, e.map { it.analysis.tempC?.let { t -> "${t.roundToInt()} °C" } ?: "-" }, none),
            CmpTableRow("Meter per hartslag, stabiel deel", "ef", e.map { PerformanceText.efMetres(it.analysis.ef?.ef) },
                if (res.aspect(CompareAspect.EFFICIENCY).wholeRun) none else bests(CompareAspect.EFFICIENCY), groupStart = true),
            CmpTableRow("Meter per hartslag, hele run", "ef", e.map { PerformanceText.efMetres(it.compared.wholeEf) },
                if (res.aspect(CompareAspect.EFFICIENCY).wholeRun) bests(CompareAspect.EFFICIENCY) else none),
            CmpTableRow("Ontkoppeling", "ontkoppeling", e.map { it.analysis.drift?.let { d -> "%+.1f %%".format(ROOT, d.decouplingPct) } ?: "-" }, bests(CompareAspect.DECOUPLING)),
            CmpTableRow("Hartslagdrift", "drift", e.map { it.analysis.drift?.takeIf { d -> d.driftAvailable }?.let { d -> "%+.1f %%".format(ROOT, d.driftPct) } ?: "-" }, none),
            CmpTableRow("Temposchommeling", "temposchommeling", e.map { if (it.compared.sessionType == SessionType.INTERVAL) "-" else it.analysis.speedCv?.let { cv -> "${f(cv * 100)} %" } ?: "-" }, bests(CompareAspect.EVENNESS)),
            CmpTableRow("Opbouw reps", "opbouw", e.map { it.analysis.intervals?.let { s -> s.signature ?: "gemengd (${s.reps.size} reps)" } ?: "-" }, none, groupStart = true),
            CmpTableRow("Tempo van de reps", null, e.map { Format.pace(it.compared.interval?.repSpeed) }, bests(CompareAspect.REP_SPEED)),
            CmpTableRow("Spreiding reps", "spreiding", e.map { it.compared.interval?.repSpeedCv?.let { cv -> "${f(cv * 100)} %" } ?: "-" }, bests(CompareAspect.REP_EVENNESS)),
            CmpTableRow("Verval", "verval", e.map { it.compared.interval?.fadePct?.let { p -> "%+.1f %%".format(ROOT, p) } ?: "-" }, bests(CompareAspect.REP_FADE)),
            CmpTableRow("Hartslagherstel na 60 s", "hrr", e.map { it.compared.interval?.let { i -> i.hrr60?.let { h -> "${h.roundToInt()} bpm (${i.hrrProtocol?.text ?: ""})" } } ?: "-" }, bests(CompareAspect.HRR)),
            CmpTableRow("Uitvoering", "uitvoering", e.map { it.assessment?.level?.let { l -> when (l) {
                RatingLevel.SOUND -> "uitvoering klopt"
                RatingLevel.MIXED -> "aandachtspunt"
                RatingLevel.OFF -> "wijkt af"
                RatingLevel.INSUFFICIENT -> "geen oordeel"
            } } ?: "-" }, bests(CompareAspect.EXECUTION), groupStart = true),
        )
        val (shown, hidden) = rows.partition { row -> row.values.any { it != "-" } }
        // A hidden row must not take the stronger group line with it: move it to the next visible row.
        val fixed = shown.mapIndexed { i, row ->
            val originalIndex = rows.indexOf(row)
            val previousShown = shown.getOrNull(i - 1)?.let { rows.indexOf(it) } ?: -1
            val groupInGap = rows.subList(previousShown + 1, originalIndex + 1).any { it.groupStart }
            row.copy(groupStart = i > 0 && groupInGap)
        }
        return fixed to hidden.map { it.label.replaceFirstChar { ch -> ch.lowercase() } }
    }
}
