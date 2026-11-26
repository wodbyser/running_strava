package com.running.analysis

import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.Agreement
import com.running.strava.analysis.performance.ComparableRuns
import com.running.strava.analysis.performance.Criterion
import com.running.strava.analysis.performance.RunProfile
import java.util.Locale

/** One measurement of a run with what it means and how it compares to your own recent runs. */
data class Metric(
    val label: String,
    val tip: String?,
    val value: String,
    val available: Boolean,
    val explanation: String,
    val context: String?,
)

data class CriterionHead(val short: String, val long: String)
data class CriterionCell(val symbol: String, val css: String, val title: String)

data class CmpRow(
    val id: Long,
    val date: String,
    /** ISO date, used by the list filter (never shown). */
    val iso: String,
    val distanceMeters: Double,
    val sport: String,
    val name: String,
    val km: String,
    val pace: String,
    val gap: String,
    val hr: String,
    val ef: String,
    val drift: String,
    val cells: List<CriterionCell>,
    val agree: String,
    val self: Boolean,
)

data class RepRow(val number: Int, val distance: String, val time: String, val pace: String, val hr: String, val rest: String, val hrr: String)

data class RunPage(
    val date: String,
    val facts: List<Tile>,
    val metrics: List<Metric>,
    val zoneShares: List<ZoneShare>?,
    val zoneNote: String,
    val intervalSignature: String?,
    val reps: List<RepRow>,
    val cmpHeads: List<CriterionHead>,
    val cmpRows: List<CmpRow>,
    val cmpSort: CmpSort,
    /** Sport options present in the comparable list, for the same filter as the compare-runs picker. */
    val cmpSports: List<CriterionHead>,
    val cmpFrom: String,
    val cmpTill: String,
    val cmpMessage: String?,
    val hiddenCriteria: List<String>,
)

/** Ordering of the comparable runs on the run analysis page. */
enum class CmpSort { SIMILARITY, DATE }

object RunPageBuilder {
    private val ROOT = Locale.ROOT
    private const val DECOUPLING_RULE = 5.0

    private val SHORT = mapOf(
        Criterion.HR to CriterionHead("HR ±5", "Gemiddelde hartslag binnen 5 bpm van deze run"),
        Criterion.ZONE to CriterionHead("Zone", "Gemiddelde hartslag in dezelfde hartslagzone"),
        Criterion.TEMPERATURE to CriterionHead("Temp.", "Temperatuur binnen 5 °C"),
        Criterion.START to CriterionHead("Start", "Startpunt binnen 1 km"),
        Criterion.ELEVATION to CriterionHead("Hoogte", "Hoogtemeters per km binnen 5 m van deze run"),
    )

    fun build(report: RunReport, sort: CmpSort = CmpSort.SIMILARITY, sportOf: (Long) -> String = { "weg" }): RunPage {
        val a = report.run
        val t = report.typical
        val pace = Format.pace(a.distanceMeters / a.movingSeconds)
        val notSteady = a.steadyFailure?.let { "Alleen voor stabiele runs; deze run telt niet mee: ${it.text}." }

        val facts = listOf(
            Tile("Afstand", Format.km(a.distanceMeters)),
            Tile("Gemiddeld tempo", pace, "bewegingstijd ${Format.duration(a.movingSeconds)}"),
            Tile("Gemiddelde hartslag", Format.hr(a.avgHr)),
            Tile("Sessietype", PerformanceText.typeText(a.sessionType), "automatisch herkend", "sessietype", estimate = true),
            Tile("Stabiele run?", if (a.steady) "ja" else "nee", a.steadyFailure?.text ?: "telt mee voor de trends", "stabiele-run", if (a.steady) Tone.GOOD else Tone.NONE),
            Tile("Hartslagdekking", if (a.hasStream) "%.0f %%".format(ROOT, a.coverage * 100) else "-", if (a.hasStream) "minimum 90 % voor hartslaganalyses" else "geen detailmetingen", "hartslagdekking",
                if (!a.hasStream) Tone.NONE else if (a.coverage >= 0.9) Tone.GOOD else Tone.UNCERTAIN),
            Tile("Terrein", a.terrain.text, "%.1f hoogtemeter per km".format(ROOT, a.elevationPerKm), "terrein"),
        )

        val ef = a.ef
        val metrics = buildList {
            add(Metric(
                "Efficiëntie (EF) van het stabiele deel", "ef",
                ef?.let { PerformanceText.efMetres(it.ef) + " per hartslag" } ?: "-", ef != null,
                "Hoeveel meter je per hartslag aflegde, gemeten na de eerste 10 minuten (${ef?.let { "%.0f minuten".format(ROOT, it.windowMinutes) } ?: "het stabiele deel"}). Hoger = efficiënter.",
                if (ef == null) notSteady else t?.efSameStratum?.let { "Jouw mediaan bij vergelijkbare hartslag (laatste ${t.days} dagen): ${PerformanceText.efMetres(it)} over ${t.nEf} runs." },
            ))
            add(Metric(
                "Efficiëntie op hellingsgecorrigeerd tempo", "gap",
                ef?.efGap?.let { PerformanceText.efMetres(it) + " per hartslag" } ?: "-", ef?.efGap != null,
                "Dezelfde efficiëntie, maar met het tempo omgerekend naar vlak terrein (GAP). Haalt het effect van heuvels weg.",
                if (ef == null) notSteady else if (ef.efGap == null) "Niet beschikbaar: geen bruikbare hellingsdata." else null,
            ))
            add(Metric(
                "GAP (hellingsgecorrigeerd tempo)", "gap",
                a.gap?.gapSpeed?.let { Format.pace(it) } ?: "-", a.gap?.gapSpeed != null,
                "Het tempo dat je met dezelfde inspanning op vlak terrein zou lopen. Bergop telt sneller, bergaf trager. Je echte gemiddelde tempo was $pace.",
                if (a.gap?.gapSpeed == null) "Niet beschikbaar: ${a.gap?.reason ?: if (a.hasStream) "te weinig bruikbare hartslag- of hellingsdata" else "geen detailmetingen"}." else null,
            ))
            val d = a.drift
            add(Metric(
                "Hartslagdrift", "drift",
                if (d != null && d.driftAvailable) "${PerformanceText.pct(d.driftPct)} (${"%+.0f".format(ROOT, d.driftBpm)} bpm)" else "-", d != null && d.driftAvailable,
                "Hoeveel hoger je hartslag in de tweede helft lag dan in de eerste helft, bij hetzelfde tempo. Lager = gunstiger.",
                when {
                    d == null -> notSteady
                    !d.driftAvailable -> "Niet berekend: je tempo verschilde ${"%.1f".format(ROOT, abs1(d.speedRatio))} % tussen beide helften (maximum 5 %). Kijk naar de ontkoppeling."
                    else -> t?.driftPct?.let { "Jouw mediaan (stabiele runs, laatste ${t.days} dagen): ${"%.1f".format(ROOT, it)} %." }
                },
            ))
            add(Metric(
                "Ontkoppeling", "ontkoppeling",
                d?.let { PerformanceText.pct(it.decouplingPct) } ?: "-", d != null,
                "Hoeveel minder meter per hartslag je in de tweede helft had. 0 % = even efficiënt tot het einde. Vuistregel bij coaches: onder 5 % (niet gevalideerd).",
                if (d == null) notSteady else listOfNotNull(
                    if (d.decouplingPct > DECOUPLING_RULE) "Boven de vuistregel van 5 %." else "Onder de vuistregel van 5 %.",
                    t?.decouplingPct?.let { "Jouw mediaan: ${"%.1f".format(ROOT, it)} %." },
                ).joinToString(" "),
            ))
            val dur = a.durability?.efChangeAt60Pct
            add(Metric(
                "Efficiëntie na een uur", "duurzaamheid",
                dur?.let { "%.0f %% van het begin".format(ROOT, 100 + it) } ?: "-", dur != null,
                "Je EF in het blok vanaf minuut 60, als percentage van het eerste blok na de opwarming. 100 % = je bleef even efficiënt.",
                if (dur == null) "Alleen voor stabiele runs van minstens 75 minuten." else t?.efChangeAt60Pct?.let { "Jouw mediaan: ${"%.0f".format(ROOT, 100 + it)} % (${t.nDurability} runs)." },
            ))
            add(Metric(
                "Temposchommeling", "stabiele-run",
                a.speedCv?.let { "%.1f %%".format(ROOT, it * 100) } ?: "-", a.speedCv != null,
                "Hoeveel je tempo verschilde tussen blokken van 5 minuten (variatiecoëfficiënt). Boven 10 % telt een run niet als stabiel.",
                if (a.speedCv == null) "Niet beschikbaar: te weinig detailmetingen." else null,
            ))
        }

        val set = report.zoneSet
        val shares = report.zoneSeconds?.let { secs ->
            val total = secs.sum()
            secs.mapIndexed { i, s ->
                val range = if (set == null) "" else if (i == 4) "vanaf ${set.floors[4]} bpm" else if (i == 0) "tot ${set.floors[1] - 1} bpm" else "${set.floors[i]}-${set.floors[i + 1] - 1} bpm"
                ZoneShare(i + 1, if (total > 0) s / total * 100 else 0.0, "%.0f min".format(ROOT, s / 60), range)
            }
        }

        val reps = a.intervals?.reps.orEmpty().map { rep ->
            RepRow(
                rep.number, "%.0f m".format(ROOT, rep.distanceMeters), Format.duration(rep.seconds), Format.pace(rep.speed), Format.hr(rep.avgHr),
                rep.recoverySeconds?.let { Format.duration(it) } ?: "-",
                rep.hrr?.let { "%.0f bpm (%s)".format(ROOT, it.hrr60, it.protocol.text) } ?: "- (${rep.hrrNote ?: "niet gemeten"})",
            )
        }

        val c = report.comparable
        val shown = Criterion.entries.filter { k ->
            c.matches.any { it.criteria[k] != Agreement.UNKNOWN }
        }
        val hidden = Criterion.entries.filter { it !in shown }.map { it.text }
        val matches = c.matches
        val rows = buildList {
            report.reference?.takeIf { matches.isNotEmpty() }?.let { add(row(it, null, shown, a.name, true, sportOf(it.activityId))) }
            matches.forEach { m -> add(row(m.run, m, shown, report.names[m.run.activityId] ?: "", false, sportOf(m.run.activityId))) }
        }
        val ordered = when (sort) {
            CmpSort.SIMILARITY -> rows
            // Same selection, newest first: the current run takes its own place in the chronology.
            CmpSort.DATE -> rows.sortedWith(compareByDescending<CmpRow> { it.iso }.thenByDescending { it.self })
        }
        val sports = linkedMapOf<String, String>()
        rows.forEach { s ->
            val label = when (s.sport) { "trail" -> "Trail"; "loopband" -> "Loopband of virtueel"; else -> "Weg" }
            sports.putIfAbsent(s.sport, label)
        }
        val dates = rows.map { it.iso }.sorted()
        return RunPage(
            PerfDates.dayLong(a.date), facts, metrics, shares, report.zoneNote,
            a.intervals?.signature, reps, shown.map { SHORT.getValue(it) }, ordered, sort,
            sports.map { (v, label) -> CriterionHead(v, label) }, dates.firstOrNull() ?: "", dates.lastOrNull() ?: "",
            c.message?.replaceFirstChar { it.uppercase() }, hidden,
        )
    }

    private fun abs1(ratio: Double) = kotlin.math.abs(ratio - 1) * 100

    private fun row(p: RunProfile, m: ComparableRuns.Match?, shown: List<Criterion>, name: String, self: Boolean, sport: String) = CmpRow(
        id = p.activityId, date = PerfDates.day(p.date), iso = p.date.toString(),
        distanceMeters = p.distanceMeters, sport = sport, name = name, km = Format.km(p.distanceMeters), pace = Format.pace(p.speed),
        gap = p.gapSpeed?.let { Format.pace(it) } ?: "-", hr = Format.hr(p.avgHr), ef = PerformanceText.efMetres(p.efWindow),
        drift = p.driftPct?.let { "%+.1f %%".format(ROOT, it) } ?: "-",
        cells = shown.map { k ->
            when (m?.criteria?.get(k)) {
                null -> CriterionCell("", "crit-self", "")
                Agreement.AGREE -> CriterionCell("✓", "crit-yes", SHORT.getValue(k).long + ": ja")
                Agreement.DIFFER -> CriterionCell("✗", "crit-no", SHORT.getValue(k).long + ": nee")
                Agreement.UNKNOWN -> CriterionCell("?", "crit-unknown", SHORT.getValue(k).long + ": onbekend")
            }
        },
        agree = m?.let { "${it.agreeing}/${shown.size}" } ?: "",
        self = self,
    )
}
