package com.running.analysis

import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.SustainedDirection
import com.running.load.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class LoadHero(
    val css: String,
    val headline: String,
    val sub: String,
    val lines: List<String>,
    val facts: List<Pair<String, String>>,
    val method: List<String>,
)

data class LoadTile(val label: String, val value: String, val sub: String, val tip: String?, val tone: String)
data class PartRow(val title: String, val stance: String, val css: String, val detail: String)
data class TypeRow(val label: String, val level: String, val css: String, val parts: List<PartRow>)

data class WeekTableRow(
    val label: String,
    val trimp: String,
    val km: String,
    val hours: String,
    val hard: String,
    val change: String,
    val rank: String,
    val level: String,
    val css: String,
    val flags: String,
    val kj: String,
)

data class SignalRow(val title: String, val rule: String, val state: String, val css: String, val value: String)

data class ModelView(val passed: Boolean, val headline: String, val lines: List<String>, val rows: List<Pair<String, String>>)
data class ForecastRow(val label: String, val passed: Boolean, val text: String, val detail: String)

data class LoadPage(
    val hero: LoadHero,
    val tiles: List<LoadTile>,
    val types: List<TypeRow>,
    val readinessNote: String?,
    val weekRows: List<WeekTableRow>,
    val showKj: Boolean,
    val weekTiles: List<LoadTile>,
    val signalSummary: String,
    val signalMeaning: String,
    val signalRows: List<SignalRow>,
    val baseRate: List<String>,
    val monotonyText: String,
    val model: ModelView,
    val forecasts: List<ForecastRow>,
    val coverage: List<String>,
    val charts: Map<String, Any?>,
)

object LoadPageBuilder {
    private fun f(v: Double?, d: Int = 1) = if (v == null) "-" else "%.${d}f".format(Locale.ROOT, v)
    private fun n0(v: Double?) = if (v == null) "-" else v.roundToInt().toString()
    private fun pct(v: Double?) = if (v == null) "-" else "%+.0f %%".format(Locale.ROOT, v)
    private fun hours(sec: Int) = "%.1f u".format(Locale.ROOT, sec / 3600.0)

    private fun kindName(k: String) = when (k) {
        "HRV" -> "HRV"
        "RESTING_HR" -> "rusthartslag"
        "SLEEP" -> "slaap"
        "BODY_BATTERY" -> "Body Battery"
        else -> k.lowercase()
    }

    fun levelCss(l: ReadinessLevel) = when (l) {
        ReadinessLevel.NONE -> "tone-neutral"
        ReadinessLevel.ONE -> "tone-uncertain"
        ReadinessLevel.MANY -> "tone-bad"
        ReadinessLevel.INSUFFICIENT -> "tone-none"
    }

    private fun ownCss(l: OwnLevel) = when (l) {
        OwnLevel.HIGH -> "tone-bad"
        OwnLevel.LOW -> "tone-uncertain"
        OwnLevel.NORMAL -> "tone-neutral"
        OwnLevel.UNKNOWN -> "tone-none"
    }

    private fun stanceCss(s: Stance) = when (s) {
        Stance.AGAINST -> "tone-bad"
        Stance.FOR -> "tone-good"
        Stance.UNKNOWN -> "tone-none"
    }

    fun hero(o: LoadOverview): LoadHero {
        val w = o.windows
        val facts = mutableListOf<Pair<String, String>>()
        o.reference?.let { facts += "Hartslagreferentie" to "max ${it.maxHr} bpm, rust ${it.restHr} bpm (${it.source})" }
        o.readiness?.let { facts += "Herstel gemeten op" to PerfDates.dayLong(it.date) }
        val method = listOf(
            "Belasting: elke training wordt uit de hartslag van elke seconde omgerekend naar punten (Banister). Rustdagen tellen als 0 punten.",
            "De belasting van de laatste ${w.acuteDays} dagen wordt vergeleken met je gemiddelde week van de laatste ${w.chronicDays} dagen en met je eigen weken van de laatste ${w.bandWeeks} weken.",
            "Herstel komt uit de Herstel-pagina (eigen norm van ${w.normDays} dagen). De vermoeidheidssignalen zijn zes losse controles zonder totaalscore.",
            "Alles is een schatting van de app op basis van je eigen cijfers: geen meting en geen diagnose. Er is geen risicozone en geen advies over hoeveel je moet trainen.",
        )
        if (o.reference == null || o.coverage.firstHrDate == null) {
            return LoadHero(
                "hero-na", "Nog geen belasting te berekenen", "Er is geen hartslagreferentie of geen activiteit met hartslag gevonden.",
                listOf("Zonder hartslag kan de app de belasting niet berekenen. Synchroniseer eerst je activiteiten (en Garmin) en kom terug."), facts, method,
            )
        }
        val lines = mutableListOf<String>()
        val level = o.acuteBand.level
        val load = o.acute.sum
        var flags = 0
        if (load != null) {
            val cmp = o.differencePct?.let { d -> "${abs(d).roundToInt()} % ${if (d >= 0) "boven" else "onder"} je gemiddelde week van de laatste ${w.chronicDays} dagen" }
            val head = "Je belasting van de laatste ${w.acuteDays} dagen is ${if (level == OwnLevel.UNKNOWN) "bekend, maar er is te weinig geschiedenis voor je eigen norm" else level.text}"
            lines += "$head (${n0(load)} punten${cmp?.let { ", $it" } ?: ""})."
            if (level == OwnLevel.HIGH || level == OwnLevel.LOW) flags++
        } else lines += "Voor de laatste ${w.acuteDays} dagen is de belasting niet voor alle dagen bekend (minstens 70 % van de dagen nodig)."

        val r = o.readiness
        val dev = r?.deviating
        if (r != null && dev != null) {
            val names = r.deviatingNames.map(::kindName)
            lines += if (dev == 0) "Je herstelwaarden liggen allemaal binnen je eigen norm."
            else "$dev van je herstelwaarden ${if (dev == 1) "wijkt" else "wijken"} af van je eigen norm (${names.joinToString(", ")})."
            if (dev >= 2 || r.anyStrong) flags++
        } else lines += "Voor je herstel is nog te weinig Garmin-data om een samenvatting te geven."

        val s = o.signals
        if (s != null && s.set.withData >= 3) {
            val a = s.set.active
            val base = if (a >= 1) s.baseRate[a]?.let { " $a of meer tegelijk kwam in de laatste 12 maanden op $it van de ${s.baseRateDays} dagen voor." } ?: "" else ""
            val names = s.set.signals.joinToString(", ") { it.key.label }
            val activeNames = s.set.signals.filter { it.active == true }.joinToString(", ") { it.key.label }
            val noData = s.set.signals.filter { it.active == null }.joinToString(", ") { it.key.label }
            lines += "$a van ${s.set.signals.size} vermoeidheidssignalen ${if (a == 1) "is" else "zijn"} actief." +
                (if (a >= 1) " Actief: $activeNames." else " Er is geen enkel signaal dat afwijkt van je eigen norm.") +
                " De zes controles zijn: $names." +
                (if (noData.isNotEmpty()) " Niet te controleren (geen data): $noData." else "") + base
            if (a >= 3) flags++
        }
        val css = when (flags) {
            0 -> "hero-ok"
            1 -> "hero-warn"
            else -> "hero-alert"
        }
        val head = when (flags) {
            0 -> "Niets valt op"
            1 -> "Eén ding wijkt af van je norm"
            else -> "Meerdere dingen wijken af van je norm"
        }
        return LoadHero(css, head, "Een samenvatting van je belasting, je herstel en je vermoeidheidssignalen. ${LoadText.COLOUR_LEGEND}", lines, facts, method)
    }

    fun types(o: LoadOverview): List<TypeRow> = o.readiness?.types.orEmpty().map { t ->
        TypeRow(t.type.label, t.level.text, levelCss(t.level), t.parts.map { PartRow(it.title, it.stance.text, stanceCss(it.stance), it.detail) })
    }

    private fun weekRows(o: LoadOverview): List<WeekTableRow> = o.weeks.reversed().map { r ->
        val w = r.week
        val p = r.progression
        val flags = buildList {
            if (!w.complete) add("week loopt nog")
            if (p?.afterBreak == true) add("na een onderbreking")
            if (w.complete && w.knownDays < 7) add("niet elke dag bekend")
        }.joinToString(", ")
        val rank = p?.rank?.takeIf { p.changePct != null }?.let { "${(it * 100).roundToInt()} % van je eigen weekwijzigingen was kleiner" } ?: "-"
        WeekTableRow(
            PerfDates.weekOf(w.weekStart.toString()), if (w.trimp == null) "geen hartslag" else n0(w.trimp), f(w.km, 1), hours(w.seconds), f(w.hardMinutes, 0),
            pct(p?.changePct), rank, r.level.text, ownCss(r.level), flags, if (w.kilojoules == null) "-" else n0(w.kilojoules),
        )
    }

    private fun sustained(d: SustainedDirection?, lower: String, higher: String) = when (d) {
        null, SustainedDirection.INSUFFICIENT -> "te weinig data"
        SustainedDirection.NONE -> "geen aanhoudende afwijking"
        SustainedDirection.LOWER -> "5 of meer van de laatste 7 dagen $lower je norm"
        SustainedDirection.HIGHER -> "5 of meer van de laatste 7 dagen $higher je norm"
    }

    private fun signalValue(k: SignalKey, i: SignalInput): String = when (k) {
        SignalKey.HRV -> sustained(i.hrvSustained, "onder", "boven")
        SignalKey.RESTING_HR -> sustained(i.restingHrSustained, "onder", "boven")
        SignalKey.SLEEP -> when (i.sleepBand) {
            null -> "geen slaapdata"
            DeviationBand.WITHIN -> "binnen je slaapdoel"
            DeviationBand.DEVIATING -> "onder je slaapdoel"
            DeviationBand.STRONG -> "ver onder je slaapdoel"
        }
        SignalKey.EFFICIENCY ->
            if (i.efMedianPct != null && i.efN >= FatigueSignals.EF_MIN_RUNS) "${pct(i.efMedianPct)} tegenover je eigen niveau (${i.efN} runs)"
            else "${i.efN} stabiele runs, minstens ${FatigueSignals.EF_MIN_RUNS} nodig"
        SignalKey.LOAD -> if (i.load7 != null && i.load7P90 != null) "${n0(i.load7)} punten tegenover ${n0(i.load7P90)} (jouw 90e percentiel)" else "te weinig geschiedenis"
        SignalKey.MONOTONY ->
            if (i.monotony != null && i.monotonyP90 != null) "${f(i.monotony, 2)} tegenover ${f(i.monotonyP90, 2)} (jouw 90e percentiel)"
            else "geen volledige week of te weinig geschiedenis"
    }

    private fun signalRows(o: LoadOverview): List<SignalRow> = o.signals?.let { s ->
        s.set.signals.map { sig ->
            SignalRow(
                sig.key.label, sig.key.rule,
                when (sig.active) { true -> "actief"; false -> "niet actief"; null -> "geen data" },
                when (sig.active) { true -> "tone-bad"; false -> "tone-good"; null -> "tone-none" },
                signalValue(sig.key, s.input),
            )
        }
    }.orEmpty()

    private fun signalMeaning(o: LoadOverview): String {
        val s = o.signals ?: return ""
        val a = s.set.active
        val noData = s.set.signals.size - s.set.withData
        val missing = if (noData > 0) " ${if (noData == 1) "Eén signaal kon niet gecontroleerd worden" else "$noData signalen konden niet gecontroleerd worden"} omdat er te weinig data is (zie 'geen data' in de tabel)." else ""
        return when {
            s.set.withData < 3 -> "Er is te weinig data om hier iets over te zeggen.$missing"
            a == 0 -> "Geen enkele controle springt op rood: je cijfers wijken niet af van wat voor jou gewoon is. Dat is het rustige resultaat; er is niets om op te letten.$missing"
            a <= 2 -> "$a signaal${if (a == 1) "" else "en"} actief. Dat komt regelmatig voor en betekent op zich weinig. Kijk in de tabel welk signaal het is.$missing"
            else -> "$a signalen tegelijk actief. Dat is ongewoner (zie hieronder hoe vaak dat voorkwam). Het wijst op een verband met vermoeidheid, niet op een zekerheid: let op hoe je je voelt.$missing"
        }
    }

    private fun baseRate(o: LoadOverview): List<String> {
        val s = o.signals ?: return emptyList()
        if (s.baseRateDays == 0) return listOf("Nog geen geschiedenis van 12 maanden om mee te vergelijken.")
        return s.baseRate.filter { it.key <= 4 }.map { (k, n) -> "${if (k == 1) "1 of meer" else "$k of meer"} signalen tegelijk: $n van de ${s.baseRateDays} dagen" }
    }

    private fun monotonyText(o: LoadOverview): String {
        val m = o.lastMonotony ?: return "Nog geen volledige week met belasting op elke dag."
        return "Laatste volledige week (${PerfDates.weekOf(m.weekStart.toString())}): monotonie ${f(m.monotony, 2)}, belastingsdruk ${n0(m.strain)}. " +
            "Dat is ${o.monotonyBand.level.text} (vergeleken met je eigen weken)."
    }

    private fun model(o: LoadOverview): ModelView {
        val v = o.model.validation
        val rows = mutableListOf<Pair<String, String>>()
        if (v.evaluated > 0 && v.maeModel != null) {
            val period = v.firstEvaluated?.let { " (${PerfDates.range(it, v.lastEvaluated ?: it)})" } ?: ""
            rows += "Voorspelde runs" to "${v.evaluated}$period"
            rows += "Gemiddelde fout van het model" to "${f(v.maeModel, 2)} procentpunt efficiëntie"
            rows += "Fout bij 'zoals de laatste 4 weken'" to "${f(v.maeLast28, 2)} procentpunt"
            rows += "Fout bij 'zoals de vorige meting'" to "${f(v.maePrevious, 2)} procentpunt"
            rows += "Model dichter bij de werkelijkheid" to "${((v.shareCloser ?: 0.0) * 100).roundToInt()} % van de runs"
            rows += "Gewichten steeds positief" to "${((v.stableShare ?: 0.0) * 100).roundToInt()} % van de aanpassingen"
        }
        val headline: String
        val lines = mutableListOf<String>()
        if (v.passed) {
            headline = "Het model voorspelde je efficiëntie beter dan de simpele gok"
            lines += "Het model hield stand tegen 'zoals de laatste 4 weken' en 'zoals de vorige meting' op runs die het nog niet gezien had. De gestippelde lijn hieronder is wat het model verwachtte."
        } else if (v.evaluated >= FitnessFatigue.MIN_EVALUATED && v.maeModel != null) {
            val baseName = v.betterBaseline ?: "de simpele gok"
            val baseErr = if (v.betterBaseline?.contains("4 weken") == true) v.maeLast28 else v.maePrevious
            headline = "De app toont geen vorm- of fitheidscijfer"
            lines += "Het model voorspelde je efficiëntie niet beter dan '$baseName' (fout ${f(v.maeModel, 1)} tegen ${f(baseErr, 1)} procentpunt), dus de app toont geen vorm- of fitheidscijfer."
            lines += "Hieronder staan alleen je korte- en lange-termijnbelasting als gewone samenvatting."
        } else {
            headline = "Nog niet genoeg data om het model te testen"
            lines += "${v.reason ?: "Te weinig data"}. Het model wordt pas getoond als het zijn voorspellingen heeft waargemaakt. Hieronder staan alleen je korte- en lange-termijnbelasting."
        }
        return ModelView(v.passed, headline, lines, rows)
    }

    private fun forecasts(o: LoadOverview): List<ForecastRow> = o.forecasts.map { c ->
        if (c.passed && c.forecasts.isNotEmpty()) {
            val t = c.forecasts.joinToString("; ") { "${PerfDates.dayLong(it.date)}: ${f(it.value, 0)} (${f(it.low, 0)} tot ${f(it.high, 0)}) ${c.unit}" }
            ForecastRow(c.label, true, t, "Gebaseerd op je eigen patroon van de laatste maanden, zonder rekening te houden met je trainingsplan. Fout in het verleden ${f(c.maeModel, 2)} tegen ${f(c.maePersistence, 2)} ${c.unit} bij 'zoals vandaag'.")
        } else ForecastRow(
            c.label, false,
            "Een voorspelling was in het verleden niet beter dan 'zoals vandaag', dus de app toont er geen.",
            if (c.maeModel != null) "Gemiddelde fout ${f(c.maeModel, 2)} ${c.unit} tegen ${f(c.maePersistence, 2)} ${c.unit} bij 'zoals vandaag' (${c.evaluated} voorspellingen over de laatste 120 dagen)." else (c.reason ?: ""),
        )
    }

    private fun coverage(o: LoadOverview): List<String> {
        val c = o.coverage
        val out = mutableListOf<String>()
        out += "${c.sessions} activiteiten in je geschiedenis: ${c.runs} runs en ${c.otherSports} andere activiteiten" +
            (if (c.otherSportsIncluded) " (die tellen mee voor de belasting als ze hartslag hebben, apart getoond)." else " (tellen niet mee).")
        out += "Belasting uit de hartslagstroom: ${c.fromStream}. Geschat uit de gemiddelde hartslag: ${c.fromAverage} (onderschat intervallen). Zonder hartslag, dus zonder belasting: ${c.withoutHr}."
        c.firstHrDate?.let { out += "Belasting is er vanaf ${PerfDates.day(it)}. Eerdere weken hebben geen hartslag en tellen niet mee (grijs in de grafieken)." }
        o.reference?.let { out += "Hartslagreferentie: max ${it.maxHr} bpm en rust ${it.restHr} bpm (${it.source}), de huidige instelling toegepast op alle runs." }
        val x = c.crossCheck
        out += if (x.spearman != null) "Garmin-belasting (Garmin-schatting, formule onbekend): rangcorrelatie ${f(x.spearman, 2)} met de app-belasting over ${x.n} gekoppelde runs. Dit is een overeenkomstcheck, geen bewijs dat een van beide klopt."
        else "Garmin-belasting kon niet vergeleken worden (${x.n} gekoppelde runs, minstens 8 nodig)."
        return out
    }

    private fun charts(o: LoadOverview): Map<String, Any?> {
        val band = o.acuteBand
        val median = band.median
        val sd = band.sd
        val bandLow = if (median != null && sd != null) maxOf(0.0, median - sd) else null
        val bandHigh = if (median != null && sd != null) median + sd else null
        val load = mapOf(
            "labels" to o.days.map { it.date.toString() },
            "run" to o.days.map { it.runTrimp }, "other" to o.days.map { it.otherTrimp },
            "acute" to o.days.map { it.acute }, "chronic" to o.days.map { it.chronicWeek },
            "names" to o.days.map { it.sessions.joinToString(", ") },
            "unknown" to o.days.map { it.trimp == null },
            "today" to o.today.toString(),
            "bandLow" to bandLow, "bandHigh" to bandHigh, "firstKnown" to o.coverage.firstHrDate?.toString(),
            "acuteDays" to o.windows.acuteDays, "chronicDays" to o.windows.chronicDays,
        )
        val weeks = o.weeks
        val byStart = weeks.associateBy { it.week.weekStart }
        val mean4 = weeks.map { r ->
            val prev = (1..4).mapNotNull { byStart[r.week.weekStart.minusWeeks(it.toLong())]?.week?.trimp }
            if (prev.size == 4) prev.average() else null
        }
        val weekChart = mapOf(
            "labels" to weeks.map { it.week.weekStart.toString() },
            "trimp" to weeks.map { it.week.trimp }, "mean4" to mean4,
            "levels" to weeks.map { if (!it.week.complete) "OPEN" else it.level.name },
            "km" to weeks.map { it.week.km }, "hours" to weeks.map { it.week.seconds / 3600.0 }, "hard" to weeks.map { it.week.hardMinutes },
            "monotony" to weeks.map { it.week.monotony }, "strain" to weeks.map { it.week.strain },
            "complete" to weeks.map { it.week.complete },
        )
        val timeline = mapOf(
            "labels" to o.timeline.map { it.date.toString() },
            "load" to o.days.map { it.trimp },
            "deviating" to o.timeline.map { if (it.withData >= 3) it.deviating else null },
            "strong" to o.timeline.map { it.strong },
            "sleepShort" to o.timeline.map { t -> t.sleepMinutes?.let { maxOf(0.0, o.sleepTargetMinutes - it) } },
        )
        val m = o.model
        val from = o.days.firstOrNull()?.date
        val modelDays = m.days.filter { from == null || !it.date.isBefore(from) }
        val runInIdx = modelDays.indexOfLast { it.runIn }
        val model = mapOf(
            "labels" to modelDays.map { it.date.toString() },
            "fatigue" to modelDays.map { it.fatigue }, "fitness" to modelDays.map { it.fitness },
            "predicted" to modelDays.map { it.predicted },
            "runInUntil" to runInIdx,
            "show" to m.showCurve,
        )
        return mapOf("load" to load, "weeks" to weekChart, "timeline" to timeline, "model" to model)
    }

    fun build(o: LoadOverview): LoadPage {
        val w = o.windows
        val tiles = listOf(
            LoadTile("Laatste ${w.acuteDays} dagen", o.acute.sum?.let { "${n0(it)} punten" } ?: "-", o.acuteBand.level.text, "belasting", ownCss(o.acuteBand.level)),
            LoadTile("Gemiddelde week (laatste ${w.chronicDays} dagen)", o.chronic.perWeek?.let { "${n0(it)} punten" } ?: "-", "${o.chronic.knownDays} van ${o.chronic.days} dagen bekend", "week-vergelijking", "tone-neutral"),
            LoadTile("Verschil", pct(o.differencePct), "de laatste ${w.acuteDays} dagen tegenover je gemiddelde week", "week-vergelijking", "tone-none"),
            LoadTile("Vandaag (loopt nog)", o.todayLoad?.trimp?.let { "${n0(it)} punten" } ?: "-", "telt niet mee in de vergelijkingen", null, "tone-none"),
        )
        val last = o.weeks.lastOrNull { it.week.complete && it.week.trimp != null }
        val prog = last?.progression
        val weekTiles = listOfNotNull(
            last?.let { LoadTile("Laatste volledige week", "${n0(it.week.trimp)} punten", "${f(it.week.km, 1)} km, ${hours(it.week.seconds)}", null, "tone-neutral") },
            prog?.changePct?.let { LoadTile("Verandering", pct(it), "tegenover de week ervoor", null, "tone-none") },
            prog?.vsMean4Pct?.let { LoadTile("Tegenover 4 weken ervoor", pct(it), "gemiddelde van de 4 weken daarvoor", null, "tone-none") },
        )
        return LoadPage(
            hero = hero(o), tiles = tiles, types = types(o),
            readinessNote = o.readiness?.let { r ->
                "Gebaseerd op je herstel van ${PerfDates.dayLong(r.date)}${if (r.fellBack) " (de laatste dag met Garmin-data)" else ""}, je laatste trainingen en je vermoeidheidssignalen. " +
                    "Geen advies: pijn en klachten wegen zwaarder."
            },
            weekRows = weekRows(o), showKj = o.weeks.any { it.week.kilojoules != null }, weekTiles = weekTiles,
            signalSummary = o.signals?.let { s -> "${s.set.active} van ${s.set.signals.size} signalen actief (${s.set.withData} met data)" } ?: "Geen signalen beschikbaar",
            signalMeaning = signalMeaning(o),
            signalRows = signalRows(o), baseRate = baseRate(o), monotonyText = monotonyText(o),
            model = model(o), forecasts = forecasts(o), coverage = coverage(o), charts = charts(o),
        )
    }
}
