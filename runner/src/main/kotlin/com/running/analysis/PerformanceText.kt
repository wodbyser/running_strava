package com.running.analysis

import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.EfficiencyFactor
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.*
import java.util.Locale

data class PSection(
    val id: String,
    val title: String,
    val tag: String,
    val notes: List<String>,
    val headers: List<String>,
    val rows: List<List<String>>,
    val links: Map<Int, Long> = emptyMap(),
)

/** Dutch text for the performance pages. Labels as in Phase 2: [gemeten], [berekend], [app-schatting], [onbeschikbaar]. */
object PerformanceText {
    const val ASSOCIATION = "Verband, geen oorzaak. Temperatuur, terrein, slaap, vermoeidheid en hartslagband beïnvloeden deze waarden."
    const val SAFETY = "Geen medische beoordeling. Pijn of klachten wegen zwaarder dan elke score."
    const val INTRO = "Prestatie-indicatoren uit je Strava-streams. Alle drempels zijn voorstellen van de app (niet extern gevalideerd) en alle waarden zijn trends over de runs die kwalificeren, geen oordeel over één run."

    private fun f(v: Double?, d: Int = 1) = if (v == null) "-" else "%.${d}f".format(Locale.ROOT, v)
    private fun pct(v: Double?) = if (v == null) "-" else "%+.1f %%".format(Locale.ROOT, v)
    private fun pace(speed: Double?) = Format.pace(speed)
    private fun ef(v: Double?) = EfficiencyFactor.format(v)
    private fun min(sec: Double) = "%.0f min".format(Locale.ROOT, sec / 60)

    fun statusText(s: EfVerdict.Status) = when (s) {
        EfVerdict.Status.IMPROVED -> "indicatie van verbetering"
        EfVerdict.Status.DECLINED -> "indicatie van achteruitgang"
        EfVerdict.Status.STABLE -> "stabiel"
        EfVerdict.Status.UNCERTAIN -> "onzeker"
        EfVerdict.Status.INSUFFICIENT_DATA -> "onvoldoende data"
    }

    fun sections(r: PerformanceReport): List<PSection> = buildList {
        add(coverage(r))
        add(consistency(r))
        add(fitness(r))
        add(efTrends(r))
        add(drift(r))
        add(criticalSpeed(r))
        add(zones(r))
        add(intensity(r))
        add(durability(r))
        add(longRuns(r))
        add(intervals(r))
        add(hrr(r))
        add(power(r))
    }

    private fun coverage(r: PerformanceReport) = PSection(
        "coverage", "Databasis", "[berekend]",
        listOf("Algoritmeversie ${r.algorithmVersion}. Zones: ${r.zoneNote}", "Runs zonder stream of zonder hartslag tellen niet mee voor streamanalyses."),
        listOf("Onderdeel", "Aantal"),
        listOf(
            listOf("Runs (zonder handmatige en gemarkeerde)", r.runCount.toString()),
            listOf("Runs met stroomdata", r.runsWithStream.toString()),
            listOf("Runs met bruikbare hartslagstroom", r.runsWithHr.toString()),
            listOf("Stabiele runs (voldoen aan alle tests)", r.steadyCount.toString()),
            listOf("Geschatte max-HR (12 maanden)", r.maxHr?.let { "$it bpm" } ?: "-"),
        ) + r.failureCounts.entries.sortedByDescending { it.value }.map { listOf("Niet stabiel: ${it.key}", it.value.toString()) },
    )

    private fun consistency(r: PerformanceReport): PSection {
        val c = r.consistency
        val notes = listOf("Neutrale cijfers over volledige weken (ma-zo), de lopende week telt niet mee. Er is geen doel en geen oordeel; de oorzaak van een gat (blessure, ziekte, reis) kent de app niet.")
        if (!c.enough) return PSection("consistency", "Regelmaat", "[onbeschikbaar]", notes + (c.message ?: ""), emptyList(), emptyList())
        val rows = listOfNotNull(c.last12?.let { "Laatste ${it.weeks} weken" to it }, c.last52?.let { "Laatste ${it.weeks} weken" to it }).map { (l, w) ->
            listOf(
                l, "${w.runs}", f(w.medianRunsPerWeek), f(w.medianWeeklyKm), w.runsPerWeekDistribution.entries.joinToString(" | ") { "${it.key}: ${it.value}" },
                "%.0f %%".format(Locale.ROOT, w.shareWeeksWithRun * 100), "${w.longestGapDays ?: "-"} d",
                if (w.gaps7Plus.isEmpty()) "geen" else w.gaps7Plus.joinToString("; ") { "${it.from} tot ${it.to} (${it.days} d)" },
                "${w.longestStreakWeeks}", w.weeklyDistanceCv?.let { "%.0f %%".format(Locale.ROOT, it * 100) } ?: "-",
            )
        }
        return PSection(
            "consistency", "Regelmaat", "[berekend]", notes,
            listOf("Venster", "Runs", "Mediaan runs/week", "Mediaan km/week", "Weken met 0 | 1 | 2 | 3 | 4+ runs", "Weken met een run", "Langste gat", "Gaten van 7 d of meer", "Langste reeks weken", "Variatie weekafstand"),
            rows,
        )
    }

    private fun fitness(r: PerformanceReport): PSection {
        val s = r.fitness
        return PSection(
            "fitness", "Conditietrend (vier traag veranderende indicatoren)", "[app-schatting]",
            listOf(s.text, "Een presentatie, geen score: de indicatoren worden niet opgeteld en niet gewogen. Snelle variabelen (herstel, vermoeidheid, belasting) horen er niet bij. Vergelijking: laatste 90 dagen tegen de 90 dagen ervoor. Indicatie, geen uitspraak over wedstrijdvorm.", ASSOCIATION),
            listOf("Indicator", "Richting", "Gunstig?", "Betekenis", "Aantallen en verschil"),
            s.indicators.map {
                listOf(it.title, it.direction.text, when (it.isFavourable) { true -> "gunstige richting"; false -> "ongunstige richting"; null -> "-" }, it.meaning, it.detail)
            },
        )
    }

    private fun efTrends(r: PerformanceReport): PSection {
        val rows = r.efTrends.map { t ->
            listOf(
                t.stratum.text, "${t.windowDays} dagen", "${t.nA} tegen ${t.nB}", "${ef(t.medianEfA)} tegen ${ef(t.medianEfB)}", pct(t.changePct),
                "${pace(t.medianSpeedA)} / ${Format.hr(t.medianHrA)} tegen ${pace(t.medianSpeedB)} / ${Format.hr(t.medianHrB)}",
                statusText(t.status) + if (t.tempCapped) " (temperatuurverschil > 8 °C)" else "",
            )
        }
        return PSection(
            "ef", "Aerobe efficiëntie (stabiel deel) en trend", "[berekend]",
            listOf("EF = afstand per hartslag over het stabiele deel van de run (na de eerste 10 minuten), alleen vergeleken binnen dezelfde HR-zonegroep. Dit verschilt van de EF over de hele run op de vergelijkpagina.", ASSOCIATION, "Temperatuur is onbekend in je data (de streams bevatten geen temperatuur), dus de temperatuurcontrole kan niet werken."),
            listOf("Zonegroep", "Venster", "Runs (eerder tegen recent)", "Mediaan EF", "Verandering (gepoold)", "Tempo / HR (mediaan)", "Oordeel"),
            rows,
        )
    }

    private fun drift(r: PerformanceReport) = PSection(
        "drift", "Hartslagdrift en ontkoppeling (stabiele runs, 90 dagen)", "[berekend]",
        listOf(r.driftSummary, "Drift = HR tweede helft min eerste helft. Ontkoppeling = verlies van snelheid per hartslag in de tweede helft. Een positief getal betekent dat de HR steeg, respectievelijk dat je minder snelheid per hartslag had. De app geeft geen grens waarboven het slecht is; de vuistregel van ongeveer 5 % is niet gevalideerd (UNVERIFIED).", ASSOCIATION),
        listOf("Datum", "Duur stabiel deel", "HR-drift", "Tempoverhouding", "Drift", "Ontkoppeling", "Ontkoppeling (GAP)"),
        r.driftRows.map { d ->
            listOf(
                d.date.toString(), min(d.minutes * 60), "${f(d.drift.driftBpm)} bpm (${pct(d.drift.driftPct)})", f(d.drift.speedRatio, 2),
                if (d.drift.driftAvailable) "beschikbaar" else "[onbeschikbaar] tempo veranderde te veel", pct(d.drift.decouplingPct), pct(d.drift.gapDecouplingPct),
            )
        },
        r.driftRows.mapIndexed { i, d -> i to d.id }.toMap(),
    )

    private fun criticalSpeed(r: PerformanceReport): PSection {
        val c = r.cs
        val notes = mutableListOf(
            "Geschatte kritische snelheid uit je snelste inspanningen van 3 tot 30 minuten (1, 3, 5 en 10 km) in de laatste 90 dagen. Dit is niet je lactaatdrempel. Een snelste inspanning was niet per se maximaal, dus de schatting is een ondergrens.",
        )
        val rows = mutableListOf<List<String>>()
        when (val o = c.outcome) {
            is CriticalSpeed.Outcome.Ok -> {
                val fit = o.fit
                rows += listOf("Kritische snelheid", "${Format.paceFromSeconds(fit.csSecondsPerKm)} (${"%.2f".format(Locale.ROOT, fit.csMs)} m/s)", "[app-schatting]")
                rows += listOf("D' (anaerobe reserve)", "${"%.0f".format(Locale.ROOT, fit.dPrimeMeters)} m", "[app-schatting]")
                rows += listOf("R2 en ankers", "${"%.3f".format(Locale.ROOT, fit.r2)}, n = ${fit.anchors.size}", "[berekend]")
                rows += listOf("Onzekerheid (zonder één anker)", "${Format.paceFromSeconds(1000 / fit.csRangeMs.second)} tot ${Format.paceFromSeconds(1000 / fit.csRangeMs.first)} (${"%.1f".format(Locale.ROOT, fit.rangePct)} %)", "[berekend]")
                rows += listOf("HR bij die inspanning (mediaan)", c.hrAtCs?.let { "${Format.hr(it)} (n = ${c.hrAnchors})" } ?: "[onbeschikbaar]", "[gemeten]")
            }
            is CriticalSpeed.Outcome.Abstain -> rows += listOf("Kritische snelheid", "[onbeschikbaar] ${o.reason}", "-")
        }
        val anchors = when (val o = c.outcome) { is CriticalSpeed.Outcome.Ok -> o.fit.anchors; is CriticalSpeed.Outcome.Abstain -> o.anchors }
        anchors.forEach { rows += listOf("Anker ${Format.km(it.distanceMeters, 0)}", "${Format.duration(it.seconds)} op ${it.date}" + if (it.isRace) " (race)" else "", "[gemeten]") }
        rows += listOf("Voorgaande voorspellingen", c.validation.meanAbsErrorPct?.let { "weken gemiddeld ${"%.1f".format(Locale.ROOT, it)} % af (n = ${c.validation.n})" } ?: "te weinig eerdere ankers (n = ${c.validation.n})", "[berekend]")
        rows += listOf("Garmin lactaatdrempel-HR", c.garminLtHr?.let { "$it bpm (${c.garminSport}, laatst gesynchroniseerd)" } ?: "-", "[gemeten] Garmin-schatting, niet samengevoegd met de app-schatting")
        return PSection("cs", "Drempelschatting: kritische snelheid", "[app-schatting]", notes, listOf("Onderdeel", "Waarde", "Soort"), rows)
    }

    private fun zones(r: PerformanceReport): PSection {
        val set = r.zoneSet
        if (set == null) return PSection("zones", "Tijd in hartslagzones", "[onbeschikbaar]", listOf(r.zoneNote), emptyList(), emptyList())
        return PSection(
            "zones", "Tijd in hartslagzones per week (laatste 26 weken)", if (set.source == ZoneSource.GARMIN) "[gemeten] Garmin-zones" else "[app-schatting]",
            listOf("Zones: ${r.zoneNote}", "Ondergrenzen: ${set.floors.joinToString(", ")} bpm. Runs met minder dan 90 % hartslagdekking tellen niet mee."),
            listOf("Week vanaf", "Runs", "Minuten data", "Z1", "Z2", "Z3", "Z4", "Z5"),
            r.weeks.map { w -> listOf(w.label, w.runs.toString() + if (w.fewRuns) " (weinig runs)" else "", f(w.totalMinutes, 0)) + w.minutes.map { f(it, 0) } },
        )
    }

    private fun intensity(r: PerformanceReport): PSection {
        val rows = r.months.map { m ->
            listOf(m.label, m.runs.toString() + if (m.fewRuns) " (weinig runs)" else "", f(m.totalMinutes, 0), "%.0f %%".format(Locale.ROOT, m.easyPct), "%.0f %%".format(Locale.ROOT, m.moderatePct), "%.0f %%".format(Locale.ROOT, m.hardPct))
        }
        val mix = r.mix4Weeks.map { m ->
            listOf("4 weken vanaf ${m.label}", "${m.runs} runs: " + SessionType_ORDER.joinToString(", ") { t -> "${typeText(t)} ${m.counts[t] ?: 0}" })
        }
        return PSection(
            "intensity", "Intensiteitsverdeling per maand", "[app-schatting]",
            listOf("Drie klassen op basis van tijd in zones: makkelijk (zone 1-2), matig (zone 3), zwaar (zone 4-5). Dit is een groepering van de app en niet de drie zones van Seiler (die vragen om ventilatiedrempels die de app niet kent). De app beoordeelt geen verdeling als polarised of ideaal."),
            listOf("Maand", "Runs", "Minuten data", "Makkelijk", "Matig", "Zwaar"),
            rows + listOf(listOf("", "", "", "", "", "")) + mix.map { it + listOf("", "", "", "") },
        )
    }

    private val SessionType_ORDER = com.running.strava.domain.SessionType.entries
    fun typeText(t: com.running.strava.domain.SessionType) = when (t) {
        com.running.strava.domain.SessionType.RACE -> "race"
        com.running.strava.domain.SessionType.INTERVAL -> "interval"
        com.running.strava.domain.SessionType.TEMPO -> "tempo"
        com.running.strava.domain.SessionType.STRIDES -> "strides"
        com.running.strava.domain.SessionType.STEADY -> "rustig"
    }

    private fun durability(r: PerformanceReport) = PSection(
        "durability", "Duurzaamheid (stabiele runs van minstens 75 minuten)", "[berekend]",
        listOf(r.durabilitySummary, "EF per blok van 10 minuten als percentage van het eerste blok, en de EF-verandering van het blok vanaf minuut 60 tegenover het eerste blok.", ASSOCIATION),
        listOf("Datum", "Blokken", "EF-verandering op minuut 60", "EF per blok (% van eerste)"),
        r.durabilityRows.take(20).map { d -> listOf(d.date.toString(), d.run.blocks.size.toString(), pct(d.run.efChangeAt60Pct), d.run.efRelativePct.joinToString(" | ") { "%.0f".format(Locale.ROOT, it) }) },
        r.durabilityRows.take(20).mapIndexed { i, d -> i to d.id }.toMap(),
    )

    private fun longRuns(r: PerformanceReport) = PSection(
        "longruns", "Lange runs: laatste derde tegenover eerste derde", "[berekend]",
        listOf(r.longRunSummary, "Het eerste derde begint na de eerste 10 minuten. Bij een verschil van meer dan 2 procentpunt in helling tussen beide delen is de EF-vergelijking onzeker.", ASSOCIATION),
        listOf("Datum", "Duur", "Tempo", "HR", "EF", "Cadans", "Trager dan eerste derde", "Terrein"),
        r.longRuns.take(20).map { l ->
            val t = l.thirds
            listOf(l.date.toString(), min(l.minutes * 60), pct(t.speedChangePct), "${f(t.hrChangeBpm)} bpm", pct(t.efChangePct) + if (t.terrainDiffers) " (onzeker)" else "",
                t.cadenceChangeSpm?.let { "${f(it)} spm" } ?: "-", "%.0f %%".format(Locale.ROOT, t.slowerShare * 100), if (t.terrainDiffers) "verschillend" else "vergelijkbaar")
        },
        r.longRuns.take(20).mapIndexed { i, d -> i to d.id }.toMap(),
    )

    private fun intervals(r: PerformanceReport): PSection {
        val rows = r.intervals.take(20).map { i ->
            val s = i.session
            listOf(
                i.date.toString(), s.signature ?: "gemengd (niet gepoold)", s.reps.size.toString(),
                s.reps.joinToString(" | ") { Format.pace(it.speed, unit = false) },
                if (s.hasStatistics) "${pct(s.paceFadePct)} / ${s.speedCv?.let { "%.1f %%".format(Locale.ROOT, it * 100) } ?: "-"} / ${f(s.hrRiseBpm)} bpm" else "alleen lijst (minder dan 3 reps)",
            )
        }
        val sigNotes = r.signatureGroups.filterValues { it.size >= 3 }.map { (sig, l) -> "$sig: ${l.size} sessies (${l.sortedBy { it.date }.joinToString(", ") { it.date.toString() }})" }
        return PSection(
            "intervals", "Intervalprestaties (automatisch herkend)", "[app-schatting]",
            listOf("Reps worden automatisch herkend uit de rondes. Verval = tempoverschil laatste tegenover eerste rep; spreiding = variatiecoëfficiënt van de rep-snelheden; HR-stijging = laatste min eerste rep.", "Trends alleen over sessies met dezelfde repopbouw (minstens 3 sessies): " + (sigNotes.ifEmpty { listOf("nog geen") }.joinToString("; ")), ASSOCIATION),
            listOf("Datum", "Opbouw", "Reps", "Tempo per rep", "Verval / spreiding / HR-stijging"),
            rows,
            rows.mapIndexed { i, _ -> i to r.intervals[i].id }.toMap(),
        )
    }

    private fun hrr(r: PerformanceReport): PSection {
        val rows = r.hrr.map { listOf(it.protocol.text, it.n.toString(), if (it.enough) "${f(it.medianHrr60)} bpm" else "[onbeschikbaar] onvoldoende metingen") }
        return PSection(
            "hrr", "Hartslagherstel na een rep (60 seconden)", "[berekend]",
            listOf(
                "HR-herstel na een looprep is een benadering: de 60-secondenmaat komt uit inspanningstesten, niet uit een interval met wisselende rust. Protocolklassen (staand, wandelen, joggen) worden nooit gemengd. Laatste 90 dagen, ${r.hrrN} geldige metingen.",
                if (rows.isEmpty()) "[onbeschikbaar] de samplefrequentie van je streams is te laag." else "Minimum per klasse: 10 metingen.", ASSOCIATION,
            ),
            listOf("Protocol", "Metingen", "Mediaan HRR60"), rows,
        )
    }

    private fun power(r: PerformanceReport): PSection {
        val p = r.power
        val rows = if (!p.available) listOf(listOf("Vermogen", "[onbeschikbaar] ${p.reason}", "-", "-")) else listOf(
            listOf("Watt per hartslag", "${p.windowA.n} tegen ${p.windowB.n}", "${f(p.windowA.medianWattsPerBpm, 2)} tegen ${f(p.windowB.medianWattsPerBpm, 2)}", "${statusText(p.wattsPerBpmStatus)} (${pct(p.wattsPerBpmChangePct)})"),
            listOf("Snelheid per watt", "${p.windowA.n} tegen ${p.windowB.n}", "${f(p.windowA.medianSpeedPerWatt?.times(1000), 2)} tegen ${f(p.windowB.medianSpeedPerWatt?.times(1000), 2)} (mm/s per W)", "${statusText(p.speedPerWattStatus)} (${pct(p.speedPerWattChangePct)})"),
        )
        return PSection(
            "power", "Vermogen volgens je apparaat (alleen per run, geen tijdreeks)", "[gemeten] apparaat",
            listOf("Vermogen is een schatting van je horloge of sensor. Er is geen vermogensstroom opgeslagen, dus geen vermogensgebaseerde ontkoppeling, en er wordt niets gezegd over loopeconomie. Dekking: ${p.withPower} van ${p.eligible} stabiele runs (laatste 180 dagen) met gemeten vermogen.", ASSOCIATION),
            listOf("Maat", "Runs (eerder tegen recent)", "Mediaan", "Oordeel"), rows,
        )
    }
}
