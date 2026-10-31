package com.running.analysis

import com.running.load.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class ResponseRow(val cells: List<String>, val css: String = "")
data class ResponseTable(val head: List<String>, val rows: List<ResponseRow>)

data class ResponseSection(
    val lead: String,
    val note: String? = null,
    val table: ResponseTable? = null,
    val lines: List<String> = emptyList(),
)

data class ResponsePage(
    val findings: List<String>,
    val headline: String,
    val reaction: ResponseSection,
    val recoveryTime: ResponseSection,
    val rest: ResponseSection,
    val deload: ResponseSection,
    val longRuns: ResponseSection,
    val fatigue: ResponseSection,
    val frequency: ResponseSection,
    val effect: ResponseSection,
    val detraining: ResponseSection,
    val periodLine: String,
    val charts: Map<String, Any?>,
)

object LoadResponsePageBuilder {
    private const val MIN_PLOT_N = 3

    private fun f(v: Double?, d: Int = 1) = if (v == null) "-" else "%.${d}f".format(Locale.ROOT, v)
    private fun s(v: Double?, d: Int = 1) = if (v == null) "-" else "%+.${d}f".format(Locale.ROOT, v)

    private val ordinal = mapOf(1 to "eerste", 2 to "tweede", 3 to "derde", 4 to "vierde", 5 to "vijfde")
    private fun morning(k: Int) = "${ordinal[k] ?: "${k}e"} ochtend"

    private fun verdictCss(v: Verdict) = when (v) {
        Verdict.DIFFERENT -> "tone-uncertain"
        Verdict.NOT_CLEAR -> "tone-neutral"
        Verdict.INSUFFICIENT -> "tone-none"
    }

    private fun metricUnit(m: ResponseMetric) = m.unit

    /** "−4.5 ms (z −1.35)" with the verdict word. */
    private fun cell(st: MorningStat, unit: String) = when {
        st.verdict == Verdict.INSUFFICIENT && st.n == 0 -> "geen data"
        st.verdict == Verdict.INSUFFICIENT -> "n = ${st.n}: onvoldoende data"
        else -> "${s(st.medianDiff)} $unit (${s(st.medianZ, 2)} SD) · n = ${st.n} · ${st.verdict.text}"
    }

    private fun direction(z: Double, lowerWord: String = "lager", higherWord: String = "hoger") = if (z < 0) lowerWord else higherWord

    fun build(r: LoadResponse): ResponsePage {
        val findings = findings(r)
        val headline = if (findings.isEmpty()) "Nog geen duidelijke patronen" else "De duidelijkste bevindingen uit je geschiedenis"
        val period = r.firstGarminDay?.let { "Garmin-herstelwaarden zijn er vanaf ${PerfDates.day(it)}; reacties daarvoor kunnen niet berekend worden." }
            ?: "Er zijn nog geen Garmin-herstelwaarden, dus geen reacties te berekenen."
        return ResponsePage(
            findings, headline, reaction(r), recoveryTime(r), rest(r), deload(r), longRuns(r), fatigue(r), frequency(r), effect(r), detraining(r), period, charts(r),
        )
    }

    // ------------------------------------------------------------------ findings

    private fun findings(r: LoadResponse): List<String> {
        data class F(val weight: Double, val text: String)
        val out = mutableListOf<F>()
        for (c in r.classes) for (m in c.metrics) for (st in m.mornings) {
            val z = st.medianZ
            if (st.verdict == Verdict.DIFFERENT && z != null) {
                out += F(abs(z) * kotlin.math.sqrt(st.n.toDouble()),
                    "Na ${c.cls.label.lowercase()}-trainingen lag je ${m.metric.label} de ${morning(st.morning)} gemiddeld ${f(abs(st.medianDiff ?: 0.0))} ${m.metric.unit} " +
                        "${direction(z)} dan je norm (n = ${st.n}, marge ${s(st.lowZ)} tot ${s(st.highZ)} SD). Verband, geen oorzaak.")
            }
        }
        for (g in r.rest) for (c in g.changes) {
            val ch = c.medianChange
            if (c.verdict == Verdict.DIFFERENT && ch != null && g.kind != DayKind.EASY) {
                out += F(abs(ch) * 0.5 + 1, "Na een dag in de categorie '${g.kind.label.lowercase()}' veranderde je ${c.metric.label} gemiddeld ${s(ch)} ${c.metric.unit} tot de volgende ochtend (n = ${c.n}). Verband, geen oorzaak.")
            }
        }
        for (g in r.longRuns) for (m in g.metrics) m.mornings.firstOrNull { it.verdict == Verdict.DIFFERENT }?.let { st ->
            out += F(abs(st.medianZ ?: 0.0) * 2, "Na lange duurlopen van ${g.label} lag je ${m.metric.label} de ${morning(st.morning)} gemiddeld ${direction(st.medianZ ?: 0.0)} dan je norm (n = ${st.n}). Verband, geen oorzaak.")
        }
        for (g in r.fatigue.byLoad + r.fatigue.byReadiness) {
            val med = g.median
            if (g.verdict == Verdict.DIFFERENT && med != null) {
                out += F(abs(med), "Stabiele runs bij '${g.label.lowercase()}' waren gemiddeld ${f(abs(med))} % ${if (med < 0) "minder" else "meer"} efficiënt dan je eigen niveau (n = ${g.n}). Verband, geen oorzaak.")
            }
        }
        for (e in r.blocks.effects) if (e.verdict == "verband gevonden") out += F(5.0, "Blokken van 4 weken met een groter aandeel ${e.cls.label.lowercase()} werden gevolgd door een ${if ((e.rho ?: 0.0) > 0) "betere" else "mindere"} efficiëntie (rangcorrelatie ${f(e.rho, 2)}, n = ${e.n} blokken). Verband, geen oorzaak.")
        if (r.lag.found) out += F(4.0, "Een verandering in je belasting ging samen met een verandering in je efficiëntie ${r.lag.bestLag} weken later (correlatie ${f(r.lag.bestCorrelation, 2)}, ${r.lag.weeksUsed} weken). Verband, geen oorzaak.")
        return out.sortedByDescending { it.weight }.take(3).map { it.text }
    }

    // ------------------------------------------------------------------ #5

    private fun reaction(r: LoadResponse): ResponseSection {
        val head = listOf("Soort training", "Losstaande trainingen") + ResponseMetric.entries.map { it.label + " (" + it.unit + ")" }
        val rows = r.classes.map { c ->
            ResponseRow(
                listOf(c.cls.label, "${c.isolated} van ${c.sessions}${if (c.enough) "" else " (te weinig)"}") +
                    c.metrics.map { m -> m.mornings.joinToString(" | ") { st -> if (st.n == 0) "-" else "${s(st.medianDiff)} (n ${st.n})" } },
            )
        }
        return ResponseSection(
            "Hoe liggen je HRV, rusthartslag, slaap en Body Battery in de ochtenden ná een training, vergeleken met je eigen norm van de ${r.windows.normDays} dagen voor die training? " +
                "Gebruikt worden alleen losstaande trainingen en de ${r.windows.followUpMornings} ochtenden erna. Dag, rustdag en eigen norm worden altijd gelijk behandeld.",
            "Een verschil telt alleen als de onzekerheidsmarge 0 niet bevat en er minstens ${LoadConfig.DEFAULT.minIsolated} trainingen zijn. Het verschil is in de eenheid van de waarde, tussen haakjes het aantal trainingen met een meting. " +
                "Per soort training staan de ochtenden 1, 2, 3 achter elkaar. ${LoadText.ASSOCIATION}",
            ResponseTable(head, rows),
        )
    }

    private fun recoveryTime(r: LoadResponse): ResponseSection {
        val rows = r.recoveryTimes.map { c ->
            val t = c.time
            val text = when (t) {
                is RecoveryTime.Insufficient -> "onvoldoende data (${t.n} losstaande trainingen met beide metingen, minstens ${t.needed})"
                RecoveryTime.NoDeviation -> "geen meetbare afwijking: al de eerste ochtend binnen je norm"
                is RecoveryTime.Mornings -> "typisch terug binnen je norm na ${t.mornings} ochtenden"
                is RecoveryTime.Longer -> "langer dan ${t.horizon} ochtenden, of niet meetbaar (de reeks wisselt te veel of ligt structureel naast je norm)"
            }
            ResponseRow(listOf(c.cls.label, text))
        }
        return ResponseSection(
            "Na hoeveel ochtenden zijn je HRV en rusthartslag na een training weer binnen je eigen norm? Dit is jouw eigen patroon, niet de 'hersteltijd' van Garmin en geen voorschrift.",
            "Regel: de eerste ochtend waarop zowel HRV als rusthartslag binnen je norm liggen (marge bevat 0 en mediaan minder dan een halve SD ernaast) en de ochtend erna ook. " +
                "Een resultaat als 'langer dan ${r.windows.recoveryHorizon} ochtenden' kan ook betekenen dat je reeks structureel iets naast je norm ligt, ook na rustige trainingen.",
            ResponseTable(listOf("Soort training", "Jouw hersteltijd"), rows),
        )
    }

    // ------------------------------------------------------------------ #13

    private fun rest(r: LoadResponse): ResponseSection {
        val head = listOf("Soort dag", "Dagen") + RestDays.METRICS.map { "Verandering ${it.label}" } + listOf("Slaap de nacht erna")
        val rows = r.rest.map { g ->
            ResponseRow(
                listOf("${g.kind.label}${if (g.withOtherActivity > 0) " (${g.withOtherActivity} met andere activiteit)" else ""}", g.days.toString()) +
                    g.changes.map { c -> if (c.n == 0) "geen data" else if (c.verdict == Verdict.INSUFFICIENT) "n = ${c.n}: onvoldoende data" else "${s(c.medianChange)} ${c.metric.unit} · n = ${c.n} · ${c.verdict.text}" } +
                    listOf(g.medianSleepMinutes?.let { "${(it / 60).toInt()}:${"%02d".format((it % 60).toInt())} u" } ?: "-"),
            )
        }
        return ResponseSection(
            "Wat verandert er in je ochtendwaarden over de nacht ná een rustdag, vergeleken met de nacht na een rustige run of een zware training? Een rustdag is een dag zonder run; wandelen of een andere sport mag, die dagen zijn gemarkeerd.",
            "Verschil = ochtend erna min de ochtend zelf. Minstens ${LoadConfig.DEFAULT.minRestDays} dagen met beide metingen per groep, anders onvoldoende data. De slaap van de nacht erna staat erbij zodat je ziet of een groep gewoon langer sliep. ${LoadText.ASSOCIATION}",
            ResponseTable(head, rows),
        )
    }

    // ------------------------------------------------------------------ #14

    private fun deload(r: LoadResponse): ResponseSection {
        val d = r.deload
        val rows = d.weeks.map { w ->
            val ch = w.changes.joinToString("\n") { c -> "${c.metric.label}: ${if (c.changeZ == null) "geen data" else s(c.changeZ, 1) + " SD"}" }
            val ef = if (w.efChange != null) "${s(w.efChange)} procentpunt\n${w.efBeforeN} runs ervoor, ${w.efAfterN} erna" else "te weinig runs\n${w.efBeforeN} ervoor, ${w.efAfterN} erna (minstens ${Deload.EF_MIN_PER_SIDE} per kant)"
            ResponseRow(listOf(PerfDates.weekOf(w.weekStart.toString()), "${f(w.pctOfPrior, 0)} % van de 3 weken ervoor", ch, ef))
        }
        val lines = mutableListOf<String>()
        d.summary?.let { sm ->
            lines += "Mediaan over ${sm.n} rustweken: ${sm.medianChange.entries.joinToString("; ") { (m, v) -> "${m.label} ${if (v == null) "-" else s(v, 1) + " SD"}" }}." +
                (sm.medianEfChange?.let { " Efficiëntie ${s(it)} procentpunt (n = ${sm.efN})." } ?: "")
        } ?: run { lines += "Een samenvatting vraagt minstens ${d.needed} rustweken (nu ${d.weeks.size}); de weken staan hieronder apart." }
        return ResponseSection(
            "Hielp een rustweek? De app zoekt weken met hooguit ${d.thresholdPct} % van de belasting van de 3 weken ervoor en kijkt naar je herstel in de laatste 3 dagen van die week en je efficiëntie in de 14 dagen erna.",
            "Gedetecteerde weken staan hieronder: kloppen ze met wat jij weet? Een week zonder run telt niet als rustweek (dat is een onderbreking). ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Week", "Belasting", "Herstelwaarden de laatste 3 dagen (SD tegenover je norm)", "Efficiëntie erna tegenover 4 weken ervoor"), rows),
            lines,
        )
    }

    // ------------------------------------------------------------------ #45

    private fun efCell(pct: Double, n: Int): String {
        val dir = when {
            abs(pct) < 0.05 -> "even efficiënt in het laatste derde"
            pct > 0 -> "efficiënter in het laatste derde"
            else -> "minder efficiënt in het laatste derde"
        }
        val runs = if (n == 1) "1 run" else "$n runs"
        val few = if (n < LoadConfig.DEFAULT.minLongRunsGroup) ", te weinig voor een oordeel" else ""
        return "${s(pct)} %: $dir ($runs$few)"
    }

    private fun longRuns(r: LoadResponse): ResponseSection {
        val rows = r.longRuns.map { g ->
            val hrv = g.metrics.first { it.metric == ResponseMetric.HRV }.mornings.first()
            val rhr = g.metrics.first { it.metric == ResponseMetric.RESTING_HR }.mornings.first()
            ResponseRow(listOf(
                g.label, "${g.isolated} van ${g.runs}${if (g.enough) "" else " (te weinig)"}",
                cell(hrv, "ms"), cell(rhr, "bpm"),
                g.efChangePct?.takeIf { g.efN > 0 }?.let { efCell(it, g.efN) } ?: "-",
            ))
        }
        val total = r.longRuns.sumOf { it.runs }
        val isolated = r.longRuns.sumOf { it.isolated }
        val lines = listOf(
            "Waarom zie je maar weinig? Je hebt $total lange duurlopen van minstens 75 minuten. Daarvan staan er $isolated los (zonder andere harde training in de ${r.windows.isolationDays} dagen ervoor en de ${r.windows.followUpMornings} dagen erna). " +
                "Alleen die tellen mee, want anders weet je niet of de reactie van de lange duurloop komt. Daarna valt er nog af wat geen HRV- of rusthartslagmeting had op de ochtend erna.",
            "Een staaf in de grafiek verschijnt pas bij minstens $MIN_PLOT_N ochtenden met meting. Een groep krijgt pas een oordeel bij minstens ${LoadConfig.DEFAULT.minLongRunsGroup} losstaande lange duurlopen " +
                "(in de tabel staat 'te weinig' als dat niet haalbaar is). Een ontbrekende staaf betekent dus 'te weinig data', geen 0. Een staaf van +0.0 is wel echt: geen verschil met je norm.",
        )
        return ResponseSection(
            "Hoe reageert je lichaam na een lange duurloop van 75 tot 90, 90 tot 105, 105 tot 120 of meer dan 120 minuten? De app kiest geen 'beste lengte'.",
            "Eerste ochtend na de run, losstaande lange duurlopen, minstens ${LoadConfig.DEFAULT.minLongRunsGroup} per groep. De efficiëntieverandering vergelijkt hoe efficiënt (meter per hartslag) je liep in het laatste derde van de lange duurloop met het eerste derde. Zo zie je hoeveel je naar het einde toe inzakt: +3 % betekent dat je aan het einde 3 % efficiënter liep dan aan het begin, -3 % dat je 3 % minder efficiënt liep (meer vermoeid). Het getal is de middelste waarde over de losstaande lange duurlopen in die groep; 'runs' is hoeveel er meetellen. ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Lengte", "Losstaande lange duurlopen", "HRV eerste ochtend", "Rusthartslag eerste ochtend", "Efficiëntie: laatste derde tegenover eerste derde van de run"), rows),
            lines,
        )
    }

    // ------------------------------------------------------------------ #31

    private fun fatigue(r: LoadResponse): ResponseSection {
        val fa = r.fatigue
        if (!fa.enough) {
            return ResponseSection("Ben je minder efficiënt op stabiele runs als je al veel belasting achter de rug hebt of je herstelwaarden afwijken?", null, null, listOf("Onvoldoende data: ${fa.reason}."))
        }
        fun row(g: FatigueGroup) = ResponseRow(listOf(g.label, g.n.toString(), if (g.verdict == Verdict.INSUFFICIENT) "onvoldoende data" else "${s(g.median)} %", if (g.low != null) "${s(g.low)} tot ${s(g.high)} %" else "-", g.verdict.text))
        val rows = (fa.byLoad + fa.byReadiness).map(::row)
        return ResponseSection(
            "Hoe efficiënt je liep (meter per hartslag) op je stabiele runs, vergeleken met wat gewoon is voor jou in de 90 dagen ervoor. Een plus betekent efficiënter dan je eigen niveau (meer meters per hartslag), een min minder efficiënt. 0 % is precies je eigen niveau. " +
                "De runs zijn gegroepeerd naar de belasting van de 3 dagen ervoor en naar hoeveel herstelwaarden die ochtend afweken.",
            "${fa.n} stabiele runs, minstens ${LoadConfig.DEFAULT.minFatigueGroup} per groep. De app rekent geen 'moeheidsvrije efficiëntie' uit: de groepen zijn de correctie. ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Groep", "Runs", "Verschil met je eigen niveau (+ efficiënter, - minder efficiënt)", "Onzekerheidsmarge", "Oordeel"), rows),
        )
    }

    // ------------------------------------------------------------------ #44

    private fun frequency(r: LoadResponse): ResponseSection {
        val fr = r.frequency
        val rows = fr.groups.map { g ->
            ResponseRow(listOf(g.label, "${g.weeks}${if (g.enough) "" else " (te weinig)"}", f(g.medianTrimp, 0), f(g.medianKm, 1), s(g.medianHrvZ, 2), s(g.medianRestingHrZ, 2)))
        }
        val lines = mutableListOf<String>()
        if (fr.frequencyTracksVolume) lines += "Let op: weken met meer runs hadden ook meer kilometers. Frequentie en volume zijn hier niet uit elkaar te halen."
        return ResponseSection(
            "Hoe zagen je ochtendwaarden eruit in de week ná een week met 1 tot 2, 3, 4 of meer runs? De app berekent geen 'optimale' frequentie.",
            "Groepen met minder dan ${LoadConfig.DEFAULT.minWeeksFrequency} weken worden samengevoegd. HRV en rusthartslag zijn in SD tegenover je eigen norm (mediaan van de ochtenden van de week erna). ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Runs per week", "Weken", "Belasting per week (mediaan)", "Km per week (mediaan)", "HRV (SD)", "Rusthartslag (SD)"), rows),
            lines,
        )
    }

    // ------------------------------------------------------------------ #43 and #48

    private fun effect(r: LoadResponse): ResponseSection {
        val b = r.blocks
        val rows = b.effects.map { e ->
            val rho = e.rho
            val p = e.pValue
            val verdict = when {
                rho == null || p == null -> "te weinig blokken om iets te zeggen"
                p < 0.05 && rho > 0 -> "duidelijk verband: blokken met meer ${e.cls.label.lowercase()} werden vaker gevolgd door een betere efficiëntie"
                p < 0.05 -> "duidelijk verband: blokken met meer ${e.cls.label.lowercase()} werden vaker gevolgd door een slechtere efficiëntie"
                else -> "geen duidelijk verband: het verschil is te klein om meer te zijn dan toeval"
            } + if (e.confounded) " (viel samen met totale belasting, effect niet te scheiden)" else ""
            ResponseRow(listOf(
                e.cls.label, e.n.toString(), if (rho == null) "-" else f(rho, 2),
                if (p == null) "-" else "${(p * 100).roundToInt()} %", verdict,
            ))
        }
        val lines = mutableListOf<String>()
        lines += if (!b.enough) "Onvoldoende data: ${b.usable} bruikbare blokken van 4 weken, minstens ${b.needed} nodig. Zolang dat zo is staat hier 'onvoldoende data' of 'geen duidelijk verband'."
        else "Vergeleken zijn ${b.usable} blokken van 4 weken, elk met het blok erna."
        val found = b.effects.filter { (it.pValue ?: 1.0) < 0.05 }
        if (b.enough) lines += if (found.isEmpty()) "Conclusie: bij geen enkel soort training zie je een duidelijk verband tussen hoeveel ervan je deed en hoe je efficiëntie daarna veranderde. Dat betekent niet dat training niet werkt, maar dat je eigen data dit niet aantoont."
        else "Conclusie: een duidelijk verband zie je bij ${found.joinToString(", ") { it.cls.label.lowercase() }}. Zie de tabel voor de richting."
        val l = r.lag
        lines += when {
            !l.enough -> "Vertraging: ${l.reason}."
            l.found -> "Vertraging: een verandering in je belasting werd gevolgd door een verandering in je efficiëntie ongeveer ${l.bestLag} weken later. De correlatie is ${f(l.bestCorrelation, 2)}" +
                " (0 = geen verband, ${if ((l.bestCorrelation ?: 0.0) >= 0) "positief: meer belasting ging samen met hogere efficiëntie later" else "negatief: meer belasting ging samen met lagere efficiëntie later"}). " +
                "Dat is sterker dan toeval zou geven: in 2000 willekeurig door elkaar geschudde reeksen bleef de correlatie in 95 % van de gevallen onder ${f(l.threshold, 2)}. Gebaseerd op ${l.weeksUsed} weken."
            else -> "Vertraging: geen duidelijke vertraging gevonden. De sterkste correlatie (${f(l.bestCorrelation, 2)}) bleef onder wat toeval ook kan geven (${f(l.threshold, 2)}), over ${l.weeksUsed} weken."
        }
        return ResponseSection(
            "Welk soort training ging samen met een betere efficiëntie, en hoeveel weken later zie je dat? De app vergelijkt blokken van 4 weken met meer of minder interval, tempo en lange duurlopen " +
                "met hoe je efficiëntie (meter per hartslag) in het blok erna veranderde. Daarnaast kijkt ze of een verandering in belasting pas na een aantal weken in je efficiëntie zichtbaar wordt.",
            "Verband (correlatie) loopt van -1 tot 1: 0 is geen verband, hoe verder van 0 hoe sterker. 'Kans dat het toeval is' komt uit een test waarbij de uitkomsten 2000 keer door elkaar werden geschud: " +
                "onder 5 % noemt de app het een duidelijk verband. ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Soort training", "Blokken", "Verband met verandering erna", "Kans dat het toeval is", "Oordeel"), rows),
            lines,
        )
    }

    // ------------------------------------------------------------------ #47

    private fun detraining(r: LoadResponse): ResponseSection {
        val d = r.detraining
        val rows = d.episodes.reversed().map { e ->
            ResponseRow(listOf(
                PerfDates.range(e.start, e.end), "${e.days} dagen",
                if (e.efChange != null) "${s(e.efChange)} procentpunt (${e.efBeforeN} runs ervoor, ${e.efAfterN} erna)" else "te weinig runs (${e.efBeforeN} ervoor, ${e.efAfterN} erna)",
                if (e.restingHrBefore != null && e.restingHrEnd != null) "${f(e.restingHrBefore, 0)} → ${f(e.restingHrEnd, 0)} bpm" else "-",
                if (e.hrvBefore != null && e.hrvEnd != null) "${f(e.hrvBefore, 0)} → ${f(e.hrvEnd, 0)} ms" else "-",
            ))
        }
        val lines = mutableListOf<String>()
        lines += if (d.summaryEfChange != null) "Mediaan over ${d.usable} bruikbare onderbrekingen: ${s(d.summaryEfChange)} procentpunt efficiëntie."
        else "Een samenvatting vraagt minstens ${d.needed} bruikbare onderbrekingen (nu ${d.usable}); ze staan hieronder apart."
        lines += "De oorzaak (blessure, ziekte, vakantie) kent de app niet."
        return ResponseSection(
            "Wat gebeurde er met je efficiëntie en je ochtendwaarden na een onderbreking van minstens ${d.minDays} dagen?",
            "Efficiëntie: mediaan van de 28 dagen voor de onderbreking tegenover de 28 dagen erna (minstens 3 stabiele runs aan elke kant). Rusthartslag en HRV: de week voor tegenover de laatste week van de onderbreking. ${LoadText.ASSOCIATION}",
            ResponseTable(listOf("Periode", "Duur", "Efficiëntie erna tegenover ervoor", "Rusthartslag (week ervoor → einde)", "HRV (week ervoor → einde)"), rows),
            lines,
        )
    }

    // ------------------------------------------------------------------ charts

    private fun series(label: String, stats: List<MorningStat>, enough: Boolean, dashed: Boolean = false) = mapOf(
        "label" to label, "enough" to enough, "dashed" to dashed,
        // Fewer than 3 sessions say too little to plot (they stay in the table with their count).
        "z" to stats.map { if (it.n >= MIN_PLOT_N) it.medianZ else null }, "lo" to stats.map { if (it.n >= MIN_PLOT_N) it.lowZ else null },
        "hi" to stats.map { if (it.n >= MIN_PLOT_N) it.highZ else null },
        "n" to stats.map { it.n }, "diff" to stats.map { it.medianDiff }, "verdict" to stats.map { it.verdict.text },
    )

    private fun charts(r: LoadResponse): Map<String, Any?> {
        val response = ResponseMetric.entries.map { m ->
            mapOf(
                "metric" to m.label, "unit" to m.unit,
                "series" to r.classes.map { c -> series(c.cls.label, c.metrics.first { it.metric == m }.mornings, c.enough) } +
                    (r.restReference.firstOrNull { it.metric == m }?.let { listOf(series("Rustdagen (referentie)", it.mornings, true, true)) } ?: emptyList()),
            )
        }
        val rest = RestDays.METRICS.map { m ->
            mapOf(
                "metric" to m.label, "unit" to m.unit,
                "labels" to r.rest.map { it.kind.label },
                "median" to r.rest.map { g -> g.changes.first { it.metric == m }.medianChange },
                "lo" to r.rest.map { g -> g.changes.first { it.metric == m }.low },
                "hi" to r.rest.map { g -> g.changes.first { it.metric == m }.high },
                "n" to r.rest.map { g -> g.changes.first { it.metric == m }.n },
                "verdict" to r.rest.map { g -> g.changes.first { it.metric == m }.verdict.text },
            )
        }
        val long = mapOf(
            "labels" to r.longRuns.map { it.label },
            "n" to r.longRuns.map { it.isolated },
            "hrv" to r.longRuns.map { g -> g.metrics.first { it.metric == ResponseMetric.HRV }.mornings.first().let { if (it.n >= MIN_PLOT_N) it.medianDiff else null } },
            "rhr" to r.longRuns.map { g -> g.metrics.first { it.metric == ResponseMetric.RESTING_HR }.mornings.first().let { if (it.n >= MIN_PLOT_N) it.medianDiff else null } },
            "enough" to r.longRuns.map { it.enough },
        )
        val freq = mapOf(
            "labels" to r.frequency.groups.map { it.label }, "trimp" to r.frequency.groups.map { it.medianTrimp }, "km" to r.frequency.groups.map { it.medianKm },
            "weeks" to r.frequency.groups.map { it.weeks },
        )
        return mapOf("response" to response, "rest" to rest, "long" to long, "freq" to freq, "horizon" to r.windows.followUpMornings)
    }
}
