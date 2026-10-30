package com.running.analysis

import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.*
import com.running.strava.domain.SessionType
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

/** Visual tone of a value. Only used where a direction is stated as favourable or not; never a medical verdict. */
enum class Tone(val css: String) {
    GOOD("tone-good"),
    BAD("tone-bad"),
    NEUTRAL("tone-neutral"),
    UNCERTAIN("tone-uncertain"),
    NONE("tone-none"),
}

/** Badge for the kind of number: gemeten / berekend / app-schatting / onbeschikbaar. */
enum class Kind(val text: String, val css: String, val help: String) {
    MEASURED("gemeten", "badge-calc", "Rechtstreeks gemeten door je horloge of Garmin."),
    CALCULATED("berekend", "badge-calc", "Met een vaste formule berekend uit je gemeten data."),
    ESTIMATE("app-schatting", "badge-est", "Een model of herkenning van de app, met meer onzekerheid."),
    UNAVAILABLE("onbeschikbaar", "badge-calc", "Niet te berekenen met de data die er is."),
}

data class Tile(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tip: String? = null,
    val tone: Tone = Tone.NEUTRAL,
    val estimate: Boolean = false,
)

/** Dutch date formats without dots or ISO notation: "26 okt 2026", "okt 2026", "26 okt". */
object PerfDates {
    private val MONTHS = listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
    private val LONG_MONTHS = listOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")
    private val DAYS = listOf("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag")

    fun day(d: LocalDate) = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
    fun dayShort(d: LocalDate) = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    fun dayLong(d: LocalDate) = "${DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${LONG_MONTHS[d.monthValue - 1]} ${d.year}"
    fun month(ym: YearMonth) = "${MONTHS[ym.monthValue - 1]} ${ym.year}"
    fun month(iso: String) = runCatching { month(YearMonth.parse(iso)) }.getOrDefault(iso)
    fun weekOf(iso: String) = runCatching { "week van " + day(LocalDate.parse(iso)) }.getOrDefault(iso)
    fun shortIso(iso: String) = runCatching { dayShort(LocalDate.parse(iso)) }.getOrDefault(iso)
    fun range(from: LocalDate, to: LocalDate) = if (from.year == to.year) "${dayShort(from)} – ${day(to)}" else "${day(from)} – ${day(to)}"
}

object PerformanceText {
    const val ASSOCIATION = "Verband, geen oorzaak. Temperatuur, terrein, slaap, vermoeidheid en hartslagband beïnvloeden deze waarden."
    const val SAFETY = "Geen medische beoordeling. Pijn of klachten wegen zwaarder dan elke score."
    const val INTRO = "Hoe evolueert je loopconditie? Deze pagina zoekt trends in je Strava-data over weken en maanden, niet in één run. " +
        "Klik op een ? voor uitleg bij een begrip."

    fun f(v: Double?, d: Int = 1) = if (v == null) "-" else "%.${d}f".format(Locale.ROOT, v)
    fun pct(v: Double?) = if (v == null) "-" else "%+.1f %%".format(Locale.ROOT, v)
    fun pace(speed: Double?) = Format.pace(speed)

    /** EF in metres per heartbeat: (m/s) / (beats/min) x 60. Easier to read than 0.0209. */
    fun efMetres(ef: Double?) = if (ef == null) "-" else "%.2f m".format(Locale.ROOT, ef * 60)

    fun statusText(s: EfVerdict.Status) = when (s) {
        EfVerdict.Status.IMPROVED -> "gunstig: hoger"
        EfVerdict.Status.DECLINED -> "ongunstig: lager"
        EfVerdict.Status.STABLE -> "stabiel"
        EfVerdict.Status.UNCERTAIN -> "onzeker"
        EfVerdict.Status.INSUFFICIENT_DATA -> "onvoldoende data"
    }

    fun statusTone(s: EfVerdict.Status) = when (s) {
        EfVerdict.Status.IMPROVED -> Tone.GOOD
        EfVerdict.Status.DECLINED -> Tone.BAD
        EfVerdict.Status.STABLE -> Tone.NEUTRAL
        EfVerdict.Status.UNCERTAIN -> Tone.UNCERTAIN
        EfVerdict.Status.INSUFFICIENT_DATA -> Tone.NONE
    }

    fun arrow(d: Direction) = when (d) {
        Direction.HIGHER -> "↑"
        Direction.LOWER -> "↓"
        Direction.STABLE -> "→"
        Direction.UNCERTAIN -> "?"
        Direction.INSUFFICIENT -> "–"
    }

    fun typeText(t: SessionType) = when (t) {
        SessionType.RACE -> "wedstrijd"
        SessionType.INTERVAL -> "interval"
        SessionType.TEMPO -> "tempo"
        SessionType.STRIDES -> "strides"
        SessionType.STEADY -> "rustig"
    }

    /** Signed value with a tone: [goodWhenHigher] decides which sign is favourable; inside ±[neutral] it is neutral. */
    fun toneOf(v: Double?, goodWhenHigher: Boolean, neutral: Double): Tone = when {
        v == null -> Tone.NONE
        abs(v) <= neutral -> Tone.NEUTRAL
        (v > 0) == goodWhenHigher -> Tone.GOOD
        else -> Tone.BAD
    }
}

// ---------------------------------------------------------------- overview view model

data class FitnessCard(
    val key: String,
    val title: String,
    val anchor: String,
    val arrow: String,
    val direction: String,
    val verdict: String,
    val tone: Tone,
    val meaning: String,
    val detail: String,
)

data class FitnessHero(val headline: String, val sub: String, val tone: Tone, val cards: List<FitnessCard>)

data class EfCompare(
    val stratum: String,
    val windowDays: Int,
    val recentPeriod: String,
    val earlierPeriod: String,
    val nRecent: Int,
    val nEarlier: Int,
    val efRecent: String,
    val efEarlier: String,
    val change: String,
    val paceRecent: String,
    val paceEarlier: String,
    val hrRecent: String,
    val hrEarlier: String,
    val verdict: String,
    val tone: Tone,
    val explanation: String,
)

data class EfSection(
    val compares: List<EfCompare>,
    val missingNote: String?,
    val shortDays: Int,
    val longDays: Int,
    val noZones: Boolean,
)

data class DriftTableRow(val id: Long, val date: String, val name: String, val minutes: String, val drift: String, val driftTone: Tone, val decoupling: String, val decouplingTone: Tone, val gapDecoupling: String)

data class DriftSection(val tiles: List<Tile>, val summary: String, val rows: List<DriftTableRow>, val hasUnavailableDrift: Boolean)

data class ZoneShare(val zone: Int, val pct: Double, val minutes: String, val range: String)

data class ZoneSection(
    val available: Boolean,
    val note: String,
    val sourceText: String,
    val recentShares: List<ZoneShare>,
    val recentLabel: String,
    val rows: List<List<String>>,
)

data class CsSection(val available: Boolean, val reason: String?, val tiles: List<Tile>, val anchors: List<List<String>>, val validation: String)

data class ConsistencyRow(val label: String, val tip: String?, val last12: String, val last52: String)

data class ConsistencySection(val available: Boolean, val message: String?, val rows: List<ConsistencyRow>, val gaps: List<String>, val weeks52: Int)

data class DurabilityTableRow(val id: Long, val date: String, val name: String, val minutes: String, val at60: String, val tone: Tone, val end: String)

data class DurabilitySection(val tiles: List<Tile>, val summary: String, val rows: List<DurabilityTableRow>)

data class LongRunTableRow(
    val id: Long, val date: String, val name: String, val minutes: String,
    val pace: String, val paceTone: Tone, val hr: String, val ef: String, val efTone: Tone,
    val cadence: String, val slower: String, val terrainOk: Boolean,
)

data class LongRunSection(val tiles: List<Tile>, val summary: String, val rows: List<LongRunTableRow>)

data class RepBar(val height: Int, val pace: String)

data class IntervalTableRow(
    val id: Long, val date: String, val name: String, val signature: String, val reps: Int, val avgPace: String,
    val bars: List<RepBar>, val fade: String, val fadeTone: Tone, val spread: String, val hrRise: String,
)

data class IntervalGroup(val signature: String, val sessions: Int, val first: String, val last: String, val trend: String, val tone: Tone)

data class IntervalSection(val groups: List<IntervalGroup>, val rows: List<IntervalTableRow>)

data class FunnelStep(val label: String, val count: Int, val pct: Double, val tip: String?, val note: String)

data class CoverageSection(val steps: List<FunnelStep>, val reasons: List<FunnelStep>, val zoneNote: String, val maxHr: String, val version: String)

data class RecentRow(val id: Long, val date: String, val name: String, val type: String, val steady: Boolean, val reason: String?, val coverage: String)

data class PerformancePage(
    val fitness: FitnessHero,
    val ef: EfSection,
    val zones: ZoneSection,
    val drift: DriftSection,
    val cs: CsSection,
    val consistency: ConsistencySection,
    val durability: DurabilitySection,
    val longRuns: LongRunSection,
    val intervals: IntervalSection,
    val hrr: List<Tile>,
    val hrrNote: String,
    val power: List<Tile>,
    val powerNote: String,
    val coverage: CoverageSection,
    val recent: List<RecentRow>,
    val charts: Map<String, Any?>,
)

/** Builds the overview page model: plain-language texts, tones and chart data from a [PerformanceReport]. */
object PerformancePageBuilder {
    private val ROOT = Locale.ROOT
    private const val NO_DATA = "Onvoldoende data voor een samenvatting"

    fun build(r: PerformanceReport): PerformancePage {
        val charts = linkedMapOf<String, Any?>()
        val ef = ef(r, charts)
        val zones = zones(r, charts)
        val drift = drift(r, charts)
        val consistency = consistency(r, charts)
        val durability = durability(r, charts)
        val longRuns = longRuns(r, charts)
        val intervals = intervals(r, charts)
        intensity(r, charts)
        val (hrr, hrrNote) = hrr(r)
        val (power, powerNote) = power(r)
        return PerformancePage(
            fitness = fitness(r), ef = ef, zones = zones, drift = drift, cs = cs(r), consistency = consistency,
            durability = durability, longRuns = longRuns, intervals = intervals, hrr = hrr, hrrNote = hrrNote,
            power = power, powerNote = powerNote, coverage = coverage(r),
            recent = r.recent.take(20).map { a ->
                RecentRow(
                    a.a.id, PerfDates.day(a.a.date), a.a.name, PerformanceText.typeText(a.a.sessionType), a.a.steady, a.a.steadyFailure?.text,
                    if (a.a.hasStream) "%.0f %%".format(ROOT, a.a.coverage * 100) else "geen detaildata",
                )
            },
            charts = charts,
        )
    }

    // ------------------------------------------------------------ 1. fitness

    private val anchors = mapOf("ef" to "ef", "cs" to "cs", "drift" to "drift", "durability" to "durability")
    private val tips = mapOf("ef" to "ef", "cs" to "cs", "drift" to "drift", "durability" to "duurzaamheid")

    private fun fitness(r: PerformanceReport): FitnessHero {
        val s = r.fitness
        val cards = s.indicators.map { i ->
            val tone = when (i.isFavourable) {
                true -> Tone.GOOD
                false -> Tone.BAD
                null -> when (i.direction) {
                    Direction.STABLE -> Tone.NEUTRAL
                    Direction.UNCERTAIN -> Tone.UNCERTAIN
                    else -> Tone.NONE
                }
            }
            val verdict = when (i.isFavourable) {
                true -> "gunstige richting"
                false -> "ongunstige richting"
                null -> when (i.direction) {
                    Direction.STABLE -> "geen duidelijk verschil"
                    Direction.UNCERTAIN -> "verschil, maar te veel schommeling"
                    else -> "nog te weinig runs"
                }
            }
            FitnessCard(tips[i.key] ?: i.key, i.title, anchors[i.key] ?: i.key, PerformanceText.arrow(i.direction), i.direction.text, verdict, tone, i.meaning, i.detail)
        }
        val n = s.indicators.size
        val headline = when {
            !s.hasSummary -> "Nog te weinig data voor een conditietrend"
            s.favourable > 0 && s.unfavourable == 0 -> "Gunstige richting: ${s.favourable} van $n indicatoren, geen enkele ongunstig"
            s.unfavourable > 0 && s.favourable == 0 -> "Ongunstige richting: ${s.unfavourable} van $n indicatoren, geen enkele gunstig"
            s.favourable > 0 -> "Gemengd beeld: ${s.favourable} gunstig, ${s.unfavourable} ongunstig"
            else -> "Stabiel: geen indicator wijkt duidelijk af"
        }
        val tone = when {
            !s.hasSummary -> Tone.NONE
            s.favourable > 0 && s.unfavourable == 0 -> Tone.GOOD
            s.unfavourable > 0 && s.favourable == 0 -> Tone.BAD
            else -> Tone.NEUTRAL
        }
        val sub = "Laatste 90 dagen tegenover de 90 dagen daarvoor. " +
            "${s.stable} stabiel, ${s.uncertain} onzeker, ${s.insufficient} met te weinig data." +
            if (s.hasSummary) "" else " ${s.text}"
        return FitnessHero(headline, sub, tone, cards)
    }

    // ------------------------------------------------------------ 2. aerobic efficiency

    private fun ef(r: PerformanceReport, charts: MutableMap<String, Any?>): EfSection {
        val today = r.today
        val shortDays = r.efWindows.first()
        val longDays = r.efWindows.last()
        val withData = r.efTrends.filter { it.nA + it.nB > 0 }
        val compares = withData.sortedWith(compareBy({ it.stratum.ordinal }, { it.windowDays })).map { t ->
            val recentFrom = today.minusDays(t.windowDays.toLong() - 1)
            val earlierTo = today.minusDays(t.windowDays.toLong())
            val earlierFrom = today.minusDays(2L * t.windowDays - 1)
            val change = t.changePct
            val explanation = when (t.status) {
                EfVerdict.Status.INSUFFICIENT_DATA -> "Te weinig runs voor een oordeel: minstens ${EfVerdict.MIN_EASY_RUNS} stabiele runs per periode nodig " +
                    "(nu ${t.nEarlierText()} ervoor en ${t.nB} recent)."
                EfVerdict.Status.STABLE -> "Geen duidelijk verschil: je efficiëntie veranderde minder dan ${EfVerdict.THRESHOLD_PCT.toInt()} %."
                EfVerdict.Status.UNCERTAIN -> "Er is een verschil van ${PerformanceText.pct(change)}, maar je runs schommelen onderling te veel om er zeker van te zijn." +
                    if (t.tempCapped) " Bovendien verschilde de temperatuur meer dan 8 °C tussen beide periodes." else ""
                EfVerdict.Status.IMPROVED -> "Je legde recent ${PerformanceText.f(change)} % meer afstand af per hartslag dan ervoor: gunstig."
                EfVerdict.Status.DECLINED -> "Je legde recent ${PerformanceText.f(change?.let { -it })} % minder afstand af per hartslag dan ervoor: ongunstig."
            }
            EfCompare(
                stratum = t.stratum.text, windowDays = t.windowDays,
                recentPeriod = PerfDates.range(recentFrom, today), earlierPeriod = PerfDates.range(earlierFrom, earlierTo),
                nRecent = t.nB, nEarlier = t.nA,
                efRecent = PerformanceText.efMetres(t.medianEfB), efEarlier = PerformanceText.efMetres(t.medianEfA),
                change = PerformanceText.pct(change),
                paceRecent = PerformanceText.pace(t.medianSpeedB), paceEarlier = PerformanceText.pace(t.medianSpeedA),
                hrRecent = Format.hr(t.medianHrB), hrEarlier = Format.hr(t.medianHrA),
                verdict = PerformanceText.statusText(t.status), tone = PerformanceText.statusTone(t.status), explanation = explanation,
            )
        }
        val missing = Stratum.entries.filter { s -> withData.none { it.stratum == s } }
        val missingNote = if (r.zoneSet == null || missing.isEmpty()) null else {
            val counts = missing.joinToString(" en ") { "${it.text}: ${r.steadyPerStratum[it] ?: 0}" }
            "Waarom geen vergelijking voor ${missing.joinToString(" en ") { it.text }}? Een stabiele run is per definitie een rustige duurloop " +
                "(geen interval, tempo of wedstrijd en een gemiddelde hartslag onder 85 % van je max). Bij zulke runs ligt je gemiddelde hartslag " +
                "bijna altijd in zone 1-2, dus er zijn (bijna) geen stabiele runs in de hogere zonegroepen. Aantal stabiele runs ooit: $counts. " +
                "Je harde trainingen worden opgevolgd bij intervallen en kritische snelheid."
        }

        // Chart: every steady run of the zone groups with data as a dot, plus the monthly median as a line.
        val strata = Stratum.entries.filter { s -> r.efPoints.any { it.stratum == s } }
        charts["efPoints"] = strata.map { s ->
            mapOf(
                "label" to s.text,
                "points" to r.efPoints.filter { it.stratum == s }.sortedBy { it.date }.map {
                    mapOf("x" to it.date.toString(), "y" to round2(it.ef * 60), "id" to it.id, "t" to PerfDates.day(it.date))
                },
                "monthly" to r.efMonthly.filter { it.stratum == s && it.medianEf != null }.map {
                    mapOf("x" to "${it.month}-15", "y" to round2(it.medianEf!! * 60), "n" to it.runs, "t" to PerfDates.month(it.month))
                },
            )
        }
        return EfSection(compares, missingNote, shortDays, longDays, r.zoneSet == null)
    }

    private fun EfTrend.nEarlierText() = nA.toString()

    // ------------------------------------------------------------ 3. zones per week

    private fun zones(r: PerformanceReport, charts: MutableMap<String, Any?>): ZoneSection {
        val set = r.zoneSet ?: return ZoneSection(false, r.zoneNote, "", emptyList(), "", emptyList())
        val ranges = (0 until 5).map { i -> if (i == 4) "vanaf ${set.floors[4]} bpm" else if (i == 0) "tot ${set.floors[1] - 1} bpm" else "${set.floors[i]}-${set.floors[i + 1] - 1} bpm" }
        val last4 = r.weeks.takeLast(4)
        val totals = (0 until 5).map { z -> last4.sumOf { it.minutes[z] } }
        val sum = totals.sum()
        val shares = (0 until 5).map { z -> ZoneShare(z + 1, if (sum > 0) totals[z] / sum * 100 else 0.0, "%.0f min".format(ROOT, totals[z]), ranges[z]) }
        charts["zoneLabels"] = r.weeks.map { PerfDates.shortIso(it.label) }
        charts["zoneTitles"] = r.weeks.map { PerfDates.weekOf(it.label) + " (${it.runs} runs)" }
        charts["zoneSeries"] = (0 until 5).map { z -> r.weeks.map { round1(it.minutes[z]) } }
        charts["zoneRanges"] = ranges
        return ZoneSection(
            available = true,
            note = r.zoneNote,
            sourceText = if (set.source == ZoneSource.GARMIN) "Garmin-zones" else "app-zones (schatting)",
            recentShares = shares,
            recentLabel = if (last4.isEmpty()) "" else "Laatste 4 weken met data (${PerfDates.dayShort(LocalDate.parse(last4.first().label))} – ${PerfDates.day(LocalDate.parse(last4.last().label).plusDays(6))}), ${"%.0f".format(ROOT, sum)} minuten",
            rows = r.weeks.reversed().map { w ->
                listOf(PerfDates.weekOf(w.label), w.runs.toString(), "%.0f".format(ROOT, w.totalMinutes)) + w.minutes.map { "%.0f".format(ROOT, it) }
            },
        )
    }

    // ------------------------------------------------------------ 4. drift

    private fun drift(r: PerformanceReport, charts: MutableMap<String, Any?>): DriftSection {
        val ok = r.driftRows.filter { it.drift.driftAvailable }
        val medDrift = Stats.median(ok.map { it.drift.driftPct })
        val medDec = Stats.median(r.driftRows.map { it.drift.decouplingPct })
        val tiles = listOf(
            Tile("Mediaan hartslagdrift", medDrift?.let { "%.1f %%".format(ROOT, it) } ?: "-", "over ${ok.size} runs met gelijk tempo", "drift"),
            Tile("Mediaan ontkoppeling", medDec?.let { "%.1f %%".format(ROOT, it) } ?: "-", "over ${r.driftRows.size} stabiele runs", "ontkoppeling"),
            Tile("Runs onder 5 % ontkoppeling", if (r.driftRows.isEmpty()) "-" else "${r.driftRows.count { it.drift.decouplingPct < 5.0 }} van ${r.driftRows.size}", "vuistregel, niet gevalideerd", "ontkoppeling"),
        )
        val chrono = r.driftRows.sortedBy { it.date }
        charts["driftLabels"] = chrono.map { PerfDates.dayShort(it.date) }
        charts["driftTitles"] = chrono.map { PerfDates.day(it.date) + " · " + it.name }
        charts["driftValues"] = chrono.map { if (it.drift.driftAvailable) round1(it.drift.driftPct) else null }
        charts["decouplingValues"] = chrono.map { round1(it.drift.decouplingPct) }
        val summary = if (r.driftRows.isEmpty()) "$NO_DATA: nog geen stabiele runs in de laatste 90 dagen." else r.driftSummary
        return DriftSection(
            tiles, summary,
            r.driftRows.map { d ->
                DriftTableRow(
                    d.id, PerfDates.day(d.date), d.name, "%.0f min".format(ROOT, d.minutes),
                    if (d.drift.driftAvailable) "${PerformanceText.pct(d.drift.driftPct)} (${"%+.0f".format(ROOT, d.drift.driftBpm)} bpm)" else "– *",
                    if (d.drift.driftAvailable) Tone.NEUTRAL else Tone.NONE,
                    PerformanceText.pct(d.drift.decouplingPct), if (d.drift.decouplingPct > DECOUPLING_RULE) Tone.UNCERTAIN else Tone.NEUTRAL,
                    PerformanceText.pct(d.drift.gapDecouplingPct),
                )
            },
            r.driftRows.any { !it.drift.driftAvailable },
        )
    }

    // ------------------------------------------------------------ 5. critical speed

    private fun cs(r: PerformanceReport): CsSection {
        val c = r.cs
        val anchorsList = when (val o = c.outcome) { is CriticalSpeed.Outcome.Ok -> o.fit.anchors; is CriticalSpeed.Outcome.Abstain -> o.anchors }
        val anchors = anchorsList.sortedBy { it.distanceMeters }.map {
            listOf(Format.km(it.distanceMeters, 0), Format.duration(it.seconds), Format.pace(it.distanceMeters / it.seconds), PerfDates.day(it.date) + if (it.isRace) " (wedstrijd)" else "", it.activityId.toString())
        }
        val validation = c.validation.meanAbsErrorPct?.let {
            "Controle achteraf: met de data van toen voorspelde dit model je latere snelste tijden gemiddeld ${"%.1f".format(ROOT, it)} % naast de werkelijke tijd (${c.validation.n} voorspellingen)."
        } ?: "Nog te weinig eerdere snelste stukken om het model achteraf te controleren (n = ${c.validation.n})."
        val garmin = Tile("Garmin lactaatdrempel", c.garminLtHr?.let { "$it bpm" } ?: "-", "Garmin-schatting, los van de app-schatting", null)
        return when (val o = c.outcome) {
            is CriticalSpeed.Outcome.Ok -> {
                val fit = o.fit
                CsSection(
                    true, null,
                    listOf(
                        Tile("Kritische snelheid", Format.paceFromSeconds(fit.csSecondsPerKm), "marge ${Format.paceFromSeconds(1000 / fit.csRangeMs.second, false)} – ${Format.paceFromSeconds(1000 / fit.csRangeMs.first, false)} /km", "cs", estimate = true),
                        Tile("Reserve boven dat tempo (D′)", "%.0f m".format(ROOT, fit.dPrimeMeters), "grootteorde", "d-prime", estimate = true),
                        Tile("Hartslag bij dat tempo", c.hrAtCs?.let { Format.hr(it) } ?: "-", if (c.hrAtCs != null) "mediaan over ${c.hrAnchors} snelste stukken van 10-30 min" else "geen snelste stukken van 10-30 min", null),
                        garmin,
                        Tile("Pasvorm (R²)", "%.3f".format(ROOT, fit.r2), "${fit.anchors.size} ankers; 1 = perfect", "anker"),
                    ),
                    anchors, validation,
                )
            }
            is CriticalSpeed.Outcome.Abstain -> CsSection(false, "Geen schatting mogelijk: ${o.reason}. Er zijn minstens 3 snelste stukken (bv. 1, 3 en 5 km) uit de laatste 90 dagen nodig, waarvan het langste minstens twee keer zo lang duurt als het kortste.", listOf(garmin), anchors, validation)
        }
    }

    // ------------------------------------------------------------ 6. intensity (charts only)

    private fun intensity(r: PerformanceReport, charts: MutableMap<String, Any?>) {
        val months = r.months.takeLast(18)
        charts["intLabels"] = months.map { PerfDates.month(it.label) }
        charts["intRuns"] = months.map { it.runs }
        charts["intEasy"] = months.map { round1(it.easyPct) }
        charts["intModerate"] = months.map { round1(it.moderatePct) }
        charts["intHard"] = months.map { round1(it.hardPct) }
        charts["mixLabels"] = r.mix4Weeks.map { PerfDates.shortIso(it.label) }
        charts["mixTitles"] = r.mix4Weeks.map { m -> runCatching { val d = LocalDate.parse(m.label); PerfDates.range(d, d.plusDays(27)) }.getOrDefault(m.label) }
        charts["mixSeries"] = SessionType.entries.reversed().map { t -> mapOf("label" to PerformanceText.typeText(t), "type" to t.name, "data" to r.mix4Weeks.map { it.counts[t] ?: 0 }) }
    }

    // ------------------------------------------------------------ 7. consistency

    private fun consistency(r: PerformanceReport, charts: MutableMap<String, Any?>): ConsistencySection {
        val c = r.consistency
        if (!c.enough) return ConsistencySection(false, "Nog te weinig geschiedenis: ${c.message}.", emptyList(), emptyList(), 0)
        val a = c.last12!!
        val b = c.last52!!
        fun pctOf(v: Double) = "%.0f %%".format(ROOT, v * 100)
        fun weeksWithRun(w: Consistency.Window) = "${Math.round(w.shareWeeksWithRun * w.weeks)} van ${w.weeks} (${pctOf(w.shareWeeksWithRun)})"
        val rows = listOf(
            ConsistencyRow("Runs in totaal", null, "${a.runs}", "${b.runs}"),
            ConsistencyRow("Runs per week (mediaan)", "mediaan", PerformanceText.f(a.medianRunsPerWeek), PerformanceText.f(b.medianRunsPerWeek)),
            ConsistencyRow("Kilometer per week (mediaan)", "mediaan", PerformanceText.f(a.medianWeeklyKm) + " km", PerformanceText.f(b.medianWeeklyKm) + " km"),
            ConsistencyRow("Weken waarin je liep", null, weeksWithRun(a), weeksWithRun(b)),
            ConsistencyRow("Langste reeks weken op rij met een run", null, "${a.longestStreakWeeks} weken", "${b.longestStreakWeeks} weken"),
            ConsistencyRow("Langste pauze tussen twee runs", null, a.longestGapDays?.let { "$it dagen" } ?: "-", b.longestGapDays?.let { "$it dagen" } ?: "-"),
            ConsistencyRow("Pauzes van een week of langer", null, "${a.gaps7Plus.size}", "${b.gaps7Plus.size}"),
            ConsistencyRow("Schommeling weekafstand", "schommeling", a.weeklyDistanceCv?.let { pctOf(it) } ?: "-", b.weeklyDistanceCv?.let { pctOf(it) } ?: "-"),
        )
        charts["weekLabels"] = c.weeks.map { PerfDates.dayShort(it.start) }
        charts["weekTitles"] = c.weeks.map { "week van " + PerfDates.day(it.start) }
        charts["weekKm"] = c.weeks.map { round1(it.km) }
        charts["weekRuns"] = c.weeks.map { it.runs }
        return ConsistencySection(
            true, null, rows,
            b.gaps7Plus.map { "${PerfDates.day(it.from)} tot ${PerfDates.day(it.to)}: ${it.days} dagen zonder run" },
            b.weeks,
        )
    }

    // ------------------------------------------------------------ 8. durability

    private fun durability(r: PerformanceReport, charts: MutableMap<String, Any?>): DurabilitySection {
        val recent = r.durabilityRows.filter { it.date.isAfter(r.today.minusDays(90)) }
        val at60 = recent.mapNotNull { it.run.efChangeAt60Pct }
        val allAt60 = r.durabilityRows.mapNotNull { it.run.efChangeAt60Pct }
        val tiles = listOf(
            Tile("EF na een uur (mediaan, 90 dagen)", Stats.median(at60)?.let { "%.0f %%".format(ROOT, 100 + it) } ?: "-",
                if (at60.isEmpty()) "geen lange stabiele runs" else "${at60.size} runs; 100 % = even efficiënt als in het begin", "duurzaamheid",
                if (at60.size < MIN_DURABILITY_RUNS) Tone.NONE else lossTone(Stats.median(at60))),
            Tile("EF na een uur (mediaan, alle runs)", Stats.median(allAt60)?.let { "%.0f %%".format(ROOT, 100 + it) } ?: "-", "${allAt60.size} runs", "duurzaamheid"),
        )
        val shown = r.durabilityRows.take(6).reversed()
        charts["durSeries"] = shown.map { d ->
            mapOf("label" to PerfDates.day(d.date), "id" to d.id,
                "points" to d.run.blocks.zip(d.run.efRelativePct).map { (b, p) -> mapOf("x" to Math.round(b.startMinute), "y" to round1(p)) })
        }
        return DurabilitySection(
            tiles,
            if (at60.size >= 1) r.durabilitySummary else "$NO_DATA: geen stabiele runs van minstens 75 minuten in de laatste 90 dagen.",
            r.durabilityRows.take(20).map { d ->
                val last = d.run.efRelativePct.lastOrNull()
                DurabilityTableRow(
                    d.id, PerfDates.day(d.date), d.name, "%.0f min".format(ROOT, d.minutes),
                    d.run.efChangeAt60Pct?.let { "%.0f %%".format(ROOT, 100 + it) } ?: "-",
                    lossTone(d.run.efChangeAt60Pct),
                    last?.let { "%.0f %%".format(ROOT, it) } ?: "-",
                )
            },
        )
    }

    // ------------------------------------------------------------ 9. long runs

    private fun longRuns(r: PerformanceReport, charts: MutableMap<String, Any?>): LongRunSection {
        val recent = r.longRuns.filter { it.date.isAfter(r.today.minusDays(90)) }
        fun med(f: (LongRun.Thirds) -> Double) = Stats.median(recent.map { f(it.thirds) })
        val tiles = listOf(
            Tile("Tempo laatste derde", med { it.speedChangePct }?.let { speedText(it) } ?: "-", "mediaan over ${recent.size} lange runs (90 dagen)", "lange-run"),
            Tile("Hartslag laatste derde", med { it.hrChangeBpm }?.let { "%+.0f bpm".format(ROOT, it) } ?: "-", "tegenover het eerste derde", null),
            Tile("Efficiëntie laatste derde", med { it.efChangePct }?.let { PerformanceText.pct(it) } ?: "-", "EF tegenover het eerste derde; dichter bij 0 = beter", "ef",
                if (recent.isEmpty()) Tone.NONE else lossTone(med { it.efChangePct }, 5.0)),
        )
        val chrono = r.longRuns.take(20).sortedBy { it.date }
        charts["lrLabels"] = chrono.map { PerfDates.dayShort(it.date) }
        charts["lrTitles"] = chrono.map { PerfDates.day(it.date) + " · " + it.name + " · " + "%.0f min".format(ROOT, it.minutes) }
        charts["lrEf"] = chrono.map { round1(it.thirds.efChangePct) }
        charts["lrHr"] = chrono.map { round1(it.thirds.hrChangeBpm) }
        return LongRunSection(
            tiles,
            if (recent.isEmpty()) "$NO_DATA: geen lange runs in de laatste 90 dagen." else r.longRunSummary,
            r.longRuns.take(20).map { l ->
                val t = l.thirds
                LongRunTableRow(
                    l.id, PerfDates.day(l.date), l.name, "%.0f min".format(ROOT, l.minutes),
                    speedText(t.speedChangePct), PerformanceText.toneOf(t.speedChangePct, true, 2.0),
                    "%+.0f bpm".format(ROOT, t.hrChangeBpm),
                    PerformanceText.pct(t.efChangePct), if (t.terrainDiffers) Tone.NONE else lossTone(t.efChangePct, 5.0),
                    t.cadenceChangeSpm?.let { "%+.0f spm".format(ROOT, it) } ?: "-",
                    "%.0f %%".format(ROOT, t.slowerShare * 100), !t.terrainDiffers,
                )
            },
        )
    }

    private fun speedText(speedChangePct: Double) = when {
        abs(speedChangePct) < 0.5 -> "even snel"
        speedChangePct > 0 -> "%.1f %% sneller".format(ROOT, speedChangePct)
        else -> "%.1f %% trager".format(ROOT, -speedChangePct)
    }

    // ------------------------------------------------------------ 10. intervals

    private fun avgSpeed(s: IntervalPerformance.Session): Double? {
        val sec = s.reps.sumOf { it.seconds }
        return if (sec > 0) s.reps.sumOf { it.distanceMeters } / sec else null
    }

    private fun intervals(r: PerformanceReport, charts: MutableMap<String, Any?>): IntervalSection {
        val repeated = r.signatureGroups.filterValues { it.size >= 3 }.entries.sortedByDescending { it.value.maxOf { v -> v.date } }
        val groups = repeated.map { (sig, list) ->
            val chrono = list.sortedBy { it.date }
            val first = avgSpeed(chrono.first().session)
            val last = avgSpeed(chrono.last().session)
            val change = if (first != null && last != null) (last - first) / first * 100 else null
            IntervalGroup(
                sig, list.size, PerfDates.day(chrono.first().date), PerfDates.day(chrono.last().date),
                "eerste keer ${PerformanceText.pace(first)}, laatste keer ${PerformanceText.pace(last)}" + (change?.let { " (" + speedText(it) + ")" } ?: ""),
                PerformanceText.toneOf(change, true, 1.0),
            )
        }
        charts["intervalGroups"] = repeated.take(6).map { (sig, list) ->
            mapOf("label" to sig, "points" to list.sortedBy { it.date }.mapNotNull { row ->
                avgSpeed(row.session)?.let { mapOf("x" to row.date.toString(), "y" to Math.round(1000 / it), "t" to PerfDates.day(row.date) + " · " + row.name, "id" to row.id) }
            })
        }
        val rows = r.intervals.take(20).map { i ->
            val s = i.session
            val speeds = s.reps.map { it.speed }
            val lo = speeds.minOrNull() ?: 0.0
            val hi = speeds.maxOrNull() ?: 0.0
            IntervalTableRow(
                i.id, PerfDates.day(i.date), i.name, s.signature ?: "gemengd", s.reps.size, PerformanceText.pace(avgSpeed(s)),
                s.reps.map { rep -> RepBar(if (hi - lo < 1e-9) 100 else (35 + 65 * (rep.speed - lo) / (hi - lo)).toInt(), "rep ${rep.number}: ${Format.pace(rep.speed)}") },
                s.paceFadePct?.let { if (abs(it) < 0.5) "gelijk" else if (it > 0) "%.1f %% trager".format(ROOT, it) else "%.1f %% sneller".format(ROOT, -it) } ?: "-",
                PerformanceText.toneOf(s.paceFadePct?.let { -it }, true, 2.0),
                s.speedCv?.let { "%.1f %%".format(ROOT, it * 100) } ?: "-",
                s.hrRiseBpm?.let { "%+.0f bpm".format(ROOT, it) } ?: "-",
            )
        }
        return IntervalSection(groups, rows)
    }

    // ------------------------------------------------------------ 11. HRR, 12. power

    private fun hrr(r: PerformanceReport): Pair<List<Tile>, String> {
        if (r.hrr.isEmpty()) return emptyList<Tile>() to "Niet beschikbaar: je hartslag wordt te weinig vaak opgeslagen (minder dan om de 5 seconden) om het herstel na 60 seconden te meten."
        val tiles = r.hrr.map {
            Tile("Rust: ${it.protocol.text}", if (it.enough) "%.0f bpm".format(ROOT, it.medianHrr60) else "-",
                if (it.enough) "mediane daling na 60 s, ${it.n} metingen" else "${it.n} metingen, minimum 10", "hrr",
                if (it.enough) Tone.NEUTRAL else Tone.NONE)
        }
        return tiles to "Laatste 90 dagen, ${r.hrrN} bruikbare metingen na een rep."
    }

    private fun power(r: PerformanceReport): Pair<List<Tile>, String> {
        val p = r.power
        val note = "${p.withPower} van ${p.eligible} stabiele runs (laatste 180 dagen) hebben vermogen gemeten door je apparaat."
        if (!p.available) return emptyList<Tile>() to "Niet beschikbaar: $note Er zijn minstens 70 % runs met gemeten vermogen nodig."
        return listOf(
            Tile("Watt per hartslag", "${PerformanceText.f(p.windowA.medianWattsPerBpm, 2)} → ${PerformanceText.f(p.windowB.medianWattsPerBpm, 2)}",
                "${PerformanceText.statusText(p.wattsPerBpmStatus)} (${PerformanceText.pct(p.wattsPerBpmChangePct)}); ${p.windowB.n} runs recent, ${p.windowA.n} ervoor", "vermogen", PerformanceText.statusTone(p.wattsPerBpmStatus)),
            Tile("Snelheid per watt", "${PerformanceText.f(p.windowA.medianSpeedPerWatt?.times(1000), 2)} → ${PerformanceText.f(p.windowB.medianSpeedPerWatt?.times(1000), 2)}",
                "mm/s per watt; ${PerformanceText.statusText(p.speedPerWattStatus)} (${PerformanceText.pct(p.speedPerWattChangePct)})", "vermogen", PerformanceText.statusTone(p.speedPerWattStatus)),
        ) to "$note Laatste 90 dagen tegenover de 90 dagen daarvoor."
    }

    // ------------------------------------------------------------ 13. coverage

    private fun coverage(r: PerformanceReport): CoverageSection {
        val total = r.runCount.coerceAtLeast(1).toDouble()
        val steps = listOf(
            FunnelStep("Al je runs", r.runCount, 100.0, null, "zonder handmatig ingevoerde en gemarkeerde runs"),
            FunnelStep("Met detailmetingen (stroomdata)", r.runsWithStream, r.runsWithStream / total * 100, "stroomdata", "nodig voor alles behalve regelmaat"),
            FunnelStep("Met bruikbare hartslag", r.runsWithHr, r.runsWithHr / total * 100, "hartslagdekking", "nodig voor zones, drift en efficiëntie"),
            FunnelStep("Stabiele runs", r.steadyCount, r.steadyCount / total * 100, "stabiele-run", "basis voor efficiëntie, drift en duurzaamheid"),
        )
        val failed = r.failureCounts.values.sum().coerceAtLeast(1).toDouble()
        val reasons = r.failureCounts.entries.sortedByDescending { it.value }.map { (k, v) ->
            FunnelStep(reasonText(k), v, v / failed * 100, null, "")
        }
        return CoverageSection(steps, reasons, r.zoneNote, r.maxHr?.let { "$it bpm" } ?: "-", r.algorithmVersion)
    }

    private fun reasonText(k: String) = when (k) {
        SteadyFailure.NO_STREAM.text -> "Geen detailmetingen (stroomdata)"
        SteadyFailure.NO_HR.text -> "Geen hartslag in de detailmetingen"
        else -> k.replaceFirstChar { it.uppercase() }
    }

    /** Neutral while the EF loss stays within [limit] %, amber beyond it. No green/red: a loss is not a diagnosis. */
    private fun lossTone(changePct: Double?, limit: Double = EfVerdict.THRESHOLD_PCT) = when {
        changePct == null -> Tone.NONE
        changePct < -limit -> Tone.UNCERTAIN
        else -> Tone.NEUTRAL
    }

    private const val DECOUPLING_RULE = 5.0
    private const val MIN_DURABILITY_RUNS = 5

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
