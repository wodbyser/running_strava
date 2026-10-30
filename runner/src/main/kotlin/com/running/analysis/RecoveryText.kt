package com.running.analysis

import com.running.garmin.recovery.HrvResult
import com.running.garmin.recovery.BaselineResult
import com.running.garmin.recovery.BaselineStatus
import com.running.garmin.recovery.BodyBatteryResult
import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.MetricAnalysis
import com.running.garmin.recovery.PeriodKind
import com.running.garmin.recovery.PeriodMetricKind
import com.running.garmin.recovery.PeriodReport
import com.running.garmin.recovery.Readiness
import com.running.garmin.recovery.ReadinessBasis
import com.running.garmin.recovery.ReadinessDeviation
import com.running.garmin.recovery.ReadinessKind
import com.running.garmin.recovery.ReadinessSummary
import com.running.garmin.recovery.SleepAnalysis
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.recovery.RecoveryWindows
import com.running.garmin.recovery.RestingHrSource
import com.running.garmin.recovery.SustainedDirection
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fact labels (GARMIN_API.md section 4.3). */
enum class RecoveryTag(val text: String, val css: String) {
    MEASURED("[gemeten]", "badge-ok"),
    COMPUTED("[berekend]", "badge-calc"),
    ESTIMATE("[app-schatting]", "badge-est"),
    NA("[onbeschikbaar]", "badge-calc"),
}

data class RecoveryRow(val label: String, val value: String, val reference: String, val deviation: String, val tag: RecoveryTag)

data class RecoverySection(val id: String, val title: String, val notes: List<String>, val rows: List<RecoveryRow>)

enum class HeroLevel(val label: String, val css: String) {
    WITHIN("Binnen je eigen norm", "hero-ok"),
    SLIGHT("Licht afwijkend", "hero-warn"),
    CLEAR("Duidelijk afwijkend", "hero-alert"),
    INSUFFICIENT("Onvoldoende data", "hero-na"),
}

/** One spectrum bar. `position` is 0 to 100 on a scale of -3 to +3 robust SD, null without data. */
data class HeroComponent(val label: String, val value: String, val norm: String, val deviation: String, val css: String, val position: Double?, val aria: String, val axisMiddle: String = "eigen norm")

data class Hero(
    val level: HeroLevel,
    val headline: String,
    val sub: String,
    val footnote: String,
    val conclusion: List<String>,
    val facts: List<Pair<String, String>>,
    val components: List<HeroComponent>,
    val method: List<String>,
)

data class RecoveryPage(
    val hero: Hero,
    val date: LocalDate,
    val isToday: Boolean,
    val algorithmVersion: String,
    val summary: String,
    val sections: List<RecoverySection>,
    val recentNights: List<String>,
    val safety: String,
)

/**
 * Dutch wording for the recovery page and the AI block. Deviations are reported in both directions; the text never says
 * higher or lower is better, and never says someone is ready or safe to train.
 */
object RecoveryText {
    const val SAFETY =
        "Geen medische beoordeling. Pijn of klachten wegen zwaarder dan elke score. " +
            "Normale waarden zijn geen bewijs dat een blessure of ziekte hersteld is."
    const val ESTIMATE_LEGEND =
        "Slaapfasen, Body Battery, stress en HRV zijn Garmin-schattingen, geen klinische metingen. " +
            "Eenheid en berekening van de HRV-waarde zijn niet bevestigd."

    private val nl = Locale.forLanguageTag("nl-BE")
    private fun f(v: Double, digits: Int = 1) = String.format(nl, "%.${digits}f", v)
    private fun signed(v: Double) = String.format(nl, "%+.1f", v)
    private fun hm(minutes: Double): String { val m = minutes.roundToInt(); return "${m / 60}:${(m % 60).toString().padStart(2, '0')} u" }
    private fun hmSeconds(s: Long): String = hm(s / 60.0)

    fun bandText(b: DeviationBand) = when (b) {
        DeviationBand.WITHIN -> "binnen eigen norm"
        DeviationBand.DEVIATING -> "afwijkend"
        DeviationBand.STRONG -> "sterk afwijkend"
    }

    fun directionText(deviation: Double) = if (deviation > 0) "hoger dan jouw eigen norm" else "lager dan jouw eigen norm"

    fun deviationText(b: BaselineResult): String {
        val d = b.deviation ?: return "geen afwijking berekend"
        val band = b.band!!
        return if (band == DeviationBand.WITHIN) "${bandText(band)} (${signed(d)} SD)" else "${bandText(band)}: ${directionText(d)} (${signed(d)} SD)"
    }

    fun referenceText(b: BaselineResult, unit: String): String = when (b.status) {
        BaselineStatus.OK -> "mediaan ${f(b.median!!)} $unit, n=${b.n} van ${b.windowDays} dagen"
        BaselineStatus.INSUFFICIENT -> "onvoldoende data (n=${b.n} van ${b.windowDays} dagen, minimaal ${b.needed})"
    }

    fun trendText(m: MetricAnalysis, unit: String, w: RecoveryWindows = RecoveryWindows.DEFAULT): String {
        val t = m.trend
        val sustained = when (t.sustained) {
            SustainedDirection.HIGHER -> "aanhoudend hoger dan jouw eigen norm (${t.sustainedDaysHigher} van ${t.sustainedDaysEvaluated} dagen)"
            SustainedDirection.LOWER -> "aanhoudend lager dan jouw eigen norm (${t.sustainedDaysLower} van ${t.sustainedDaysEvaluated} dagen)"
            SustainedDirection.NONE -> "geen aanhoudende afwijking"
            SustainedDirection.INSUFFICIENT -> "onvoldoende data voor aanhoudende afwijking (${t.sustainedDaysEvaluated} van ${w.trendRecentDays} dagen, minimaal ${com.running.garmin.recovery.Baseline.minimumCount(w.trendRecentDays)})"
        }
        val level = if (t.status == BaselineStatus.OK) {
            "${w.trendRecentDays}-daagse mediaan ${f(t.recentMedian!!)} $unit tegenover ${w.trendBaselineDays}-daagse norm ${f(t.baselineMedian!!)} $unit (${signed(t.deviation!!)} SD); "
        } else "onvoldoende data voor ${w.trendRecentDays} tegenover ${w.trendBaselineDays} dagen (n=${t.recentN} en n=${t.baselineN}); "
        return level + sustained
    }

    private fun na(label: String, reason: String) = RecoveryRow(label, "onbeschikbaar", reason, "-", RecoveryTag.NA)

    /** Value, reference and deviation rows of one metric: measured value, own norm, and trend. */
    private fun metricRows(label: String, unit: String, m: MetricAnalysis, valueText: String, tag: RecoveryTag, noValueReason: String, w: RecoveryWindows = RecoveryWindows.DEFAULT): List<RecoveryRow> {
        if (m.value == null) return listOf(na(label, noValueReason))
        val p = m.primary
        val suffix = if (m.incomplete) " (onvolledig, dag nog bezig)" else ""
        return listOf(
            RecoveryRow(label + suffix, valueText, referenceText(p, unit), if (p.status == BaselineStatus.OK) deviationText(p) else "-", tag),
            RecoveryRow("$label: trend", "-", trendText(m, unit, w), "-", RecoveryTag.ESTIMATE),
        )
    }

    private fun windowRows(label: String, unit: String, m: MetricAnalysis) =
        m.windows.filter { it.windowDays != m.primary.windowDays }.map {
            RecoveryRow("$label: norm ${it.windowDays} d", "-", referenceText(it, unit), "-", RecoveryTag.COMPUTED)
        }

    fun component(kind: ReadinessKind) = when (kind) {
        ReadinessKind.HRV -> "HRV"
        ReadinessKind.RESTING_HR -> "rusthartslag"
        ReadinessKind.SLEEP -> "slaapduur"
        ReadinessKind.BODY_BATTERY -> "Body Battery bij ontwaken"
    }

    fun summaryText(r: RecoveryReport): String = summaryText(r.readiness)

    private fun deviationSentence(d: ReadinessDeviation, rd: Readiness): String {
        if (d.basis == ReadinessBasis.SLEEP_TARGET) {
            val t = rd.sleepTarget!!
            return "slaap onder je doel van ${hm(t.targetMinutes.toDouble())} (gemiddeld ${f(t.averageShortfallMinutes, 0)} min tekort per nacht over ${t.nights} nachten, ${bandText(d.band)})"
        }
        return "${component(d.kind)} ${directionText(d.deviation)} (${signed(d.deviation)} SD, ${bandText(d.band)})"
    }

    fun summaryText(rd: Readiness): String {
        val s = rd.summary
        if (!s.available) return "Onvoldoende data voor een samenvatting (${s.componentsWithData} van 4 componenten hebben data, minimaal 3). De componenten staan hieronder apart."
        val base = "${s.within} van ${s.componentsWithData} componenten binnen eigen norm"
        if (s.deviating.isEmpty()) return base
        return "$base, ${s.deviating.size} afwijkend: " + s.deviating.joinToString(", ") { deviationSentence(it, rd) }
    }

    fun page(r: RecoveryReport, sleepTargetMinutes: Int): RecoveryPage {
        val w = r.windows
        val nd = w.normDays
        val sections = mutableListOf<RecoverySection>()

        sections += RecoverySection("readiness", "Herstel: componenten", listOf("Geen score en geen weging tussen componenten: er bestaan geen gevalideerde gewichten. Recente belasting is nog geen component."),
            r.readiness.components.map { c ->
                if (c.value == null) na(component(c.kind), "geen waarde voor deze dag (eigen norm: n=${c.n} van $nd dagen)")
                else if (!c.hasData) na(component(c.kind), "onvoldoende data voor een eigen norm (n=${c.n} van $nd dagen)")
                else RecoveryRow(component(c.kind), f(c.value!!), "mediaan ${f(c.median!!)}, n=${c.n} van $nd dagen", "${bandText(c.band!!)}" + if (c.band != DeviationBand.WITHIN) ": ${directionText(c.deviation!!)}" else "", RecoveryTag.ESTIMATE)
            })

        val h = r.hrv
        sections += RecoverySection("hrv", "HRV (Garmin, nachtgemiddelde)", listOf(ESTIMATE_LEGEND, "Garmin-bereik en -status staan apart en worden nooit gemengd met de eigen norm van de app."),
            metricRows("HRV nachtgemiddelde", "ms", h.metric, h.lastNightAvg?.let { "$it ms" } ?: "-", RecoveryTag.MEASURED, "geen HRV voor deze nacht", w = w) +
                windowRows("HRV", "ms", h.metric) +
                listOfNotNull(
                    h.weeklyAvg?.let { RecoveryRow("Garmin weekgemiddelde", "$it ms", "-", "-", RecoveryTag.MEASURED) },
                    if (h.garminBalancedLow != null && h.garminBalancedUpper != null)
                        RecoveryRow("Garmin-bereik (balanced)", "${h.garminBalancedLow} tot ${h.garminBalancedUpper} ms", "-", h.garminStatus ?: "-", RecoveryTag.MEASURED) else null,
                ))

        val rr = r.restingHr
        val rhrRows = when (rr.source) {
            RestingHrSource.GARMIN -> metricRows("Rusthartslag", "bpm", rr.metric!!, "${rr.value} bpm", RecoveryTag.MEASURED, "", w = w) +
                windowRows("Rusthartslag", "bpm", rr.metric!!) + RecoveryRow("Bron", "Garmin", "-", "-", RecoveryTag.MEASURED)
            RestingHrSource.MANUAL -> listOf(
                RecoveryRow("Rusthartslag", "${rr.value} bpm", "geen basislijn (alleen handmatige waarde)", "-", RecoveryTag.MEASURED),
                RecoveryRow("Bron", "handmatig ingevoerd", "-", "-", RecoveryTag.MEASURED),
            )
            RestingHrSource.NONE -> listOf(na("Rusthartslag", "geen Garmin-waarde en geen handmatige waarde"))
        }
        sections += RecoverySection("rhr", "Rusthartslag", listOf("Meetmethode van Garmin (slaapgebaseerd of niet) is niet bevestigd. Hoger en lager worden beide gemeld, zonder oordeel."), rhrRows)

        val s = r.sleep
        val n = s.night
        val sleepRows = mutableListOf<RecoveryRow>()
        sleepRows += metricRows("Slaapduur", "min", s.duration, n?.durationSeconds?.let { hmSeconds(it.toLong()) } ?: "-", RecoveryTag.MEASURED, "geen slaapduur voor deze nacht", w = w)
        sleepRows += if (n?.awakeSharePercent != null) metricRows("Wakker-aandeel", "%", s.awakeShare, "${f(n.awakeSharePercent!!)} %", RecoveryTag.COMPUTED, "", w = w)
            else listOf(na("Wakker-aandeel", "ontbrekende waarden; aantal keer wakker wordt niet opgeslagen"))
        if (n == null || n.stagesHidden) {
            sleepRows += na("Slaapfasen", if (n == null) "geen nacht" else "meer dan 10 % van de nacht niet meetbaar (${f(n.unmeasurableSharePercent ?: 0.0)} %)")
        } else if (n.deepSharePercent != null) {
            sleepRows += metricRows("Diepe slaap (Garmin-schatting)", "%", s.deepShare, "${f(n.deepSharePercent!!)} %", RecoveryTag.COMPUTED, "", w = w)
            sleepRows += RecoveryRow("Diepe slaap: indicatieve referentie", "${s.deepReferenceLowPercent} tot ${s.deepReferenceHighPercent} %", "los van slaaptekort", "-", RecoveryTag.ESTIMATE)
            n.lightSharePercent?.let { sleepRows += RecoveryRow("Lichte slaap (Garmin-schatting)", "${f(it)} %", "-", "-", RecoveryTag.COMPUTED) }
            n.remSharePercent?.let { sleepRows += RecoveryRow("REM (Garmin-schatting)", "${f(it)} %", "-", "-", RecoveryTag.COMPUTED) }
        }
        sleepRows += n?.overallScore?.let { RecoveryRow("Garmin slaapscore", "$it" + (n.overallQualifier?.let { q -> " ($q)" } ?: ""), "naast, niet in de plaats van, de analyse hierboven", "-", RecoveryTag.MEASURED) }
            ?: na("Garmin slaapscore", "geen score")
        val reg = s.regularity
        sleepRows += if (reg.status == BaselineStatus.OK)
            RecoveryRow("Regelmaat (laatste ${w.regularityNights} nachten)", "inslapen SD ${f(reg.startSdMinutes!!, 0)} min, ontwaken SD ${f(reg.endSdMinutes!!, 0)} min", "n=${reg.nights} nachten, tijden in Europe/Brussels", "-", RecoveryTag.COMPUTED)
        else na("Regelmaat", "onvoldoende data (n=${reg.nights} nachten, minimaal ${reg.needed})")
        sections += RecoverySection("sleep", "Slaapkwaliteit", listOf("Geen gecombineerde slaapscore van de app. Slaapfasen zijn Garmin-schattingen."), sleepRows)

        val d = r.sleepDebt
        val debtRows = if (d.status == BaselineStatus.OK) listOfNotNull(
            RecoveryRow("Geschat slaaptekort (${w.debtNights} nachten)", hmSeconds(d.shortfallSeconds!!), "doel ${hm(sleepTargetMinutes.toDouble())} per nacht, ${d.nightsWithData} van ${w.debtNights} nachten met data", "${d.nightsBelowTarget} nacht(en) onder het doel", RecoveryTag.COMPUTED),
            RecoveryRow("Netto saldo (tweede getal)", (if (d.netBalanceSeconds!! < 0) "-" else "+") + hmSeconds(abs(d.netBalanceSeconds!!)), "lange nachten vullen het tekort hierboven niet aan", "-", RecoveryTag.COMPUTED),
            d.garminSleepNeedBaseline?.let { RecoveryRow("Garmin slaapbehoefte", "$it", "eenheid niet bevestigd, niet gebruikt in de berekening", "-", RecoveryTag.MEASURED) },
        ) else listOf(na("Geschat slaaptekort", "onvoldoende data (n=${d.nightsWithData} van ${w.debtNights} nachten, minimaal ${d.nightsNeeded})"))
        sections += RecoverySection("debt", "Slaaptekort", listOf("Doel van de eigenaar. Diepe slaap veroorzaakt nooit slaaptekort. Dutjes tellen niet mee."), debtRows)

        sections += RecoverySection("bb", "Body Battery (Garmin-schatting)", listOf("Alleen een patroon, geen oorzaak."), bbRows(r.bodyBattery, w))

        val st = r.stress
        val stressRows = metricRows("Gemiddeld stressniveau", "punten", st.avgLevelMetric, st.avgLevel?.toString() ?: "-", RecoveryTag.MEASURED, "geen stressdata", w = w) +
            when {
                st.highSharePercent != null -> metricRows("Aandeel gemiddeld/hoog stress", "%", st.highShareMetric, "${f(st.highSharePercent!!)} %", RecoveryTag.COMPUTED, "", w = w)
                st.tooShort -> listOf(na("Aandeel gemiddeld/hoog stress", "te weinig meetduur (${f(st.measuredHours ?: 0.0)} u, minimaal 8 u)"))
                else -> listOf(na("Aandeel gemiddeld/hoog stress", "geen stressdata"))
            }
        sections += RecoverySection("stress", "Stress (Garmin-schatting)", listOf("Er is geen stressreeks binnen de dag beschikbaar. Geen verband met trainingen getoond."), stressRows)

        val recent = r.recentNights.map {
            "${it.date}: HRV ${it.hrv?.let { v -> "$v ms" } ?: "-"}, rusthartslag ${it.restingHr?.let { v -> "$v bpm" } ?: "-"}, slaap ${it.sleepMinutes?.let { m -> hm(m.toDouble()) } ?: "-"}"
        }
        return RecoveryPage(hero(r, sleepTargetMinutes), r.date, r.isToday, r.algorithmVersion, summaryText(r), sections, recent, SAFETY)
    }

    private fun bbRows(b: BodyBatteryResult, w: RecoveryWindows): List<RecoveryRow> {
        val rows = mutableListOf<RecoveryRow>()
        rows += metricRows("Niveau bij ontwaken", "punten", b.wakeLevelMetric, b.wakeLevel?.toString() ?: "-", RecoveryTag.MEASURED, "geen meetpunt binnen 15 minuten van het ontwaken", w = w)
        rows += metricRows("Herstel tijdens de slaap", "punten", b.rechargeMetric, b.recharge?.let { signed(it.toDouble()) } ?: "-", RecoveryTag.COMPUTED, "geen meetpunt rond inslapen en ontwaken (binnen 15 minuten)", w = w)
        b.maxValue?.let { rows += RecoveryRow("Dagmaximum", "$it", "-", "-", RecoveryTag.MEASURED) }
        b.minValue?.let { rows += RecoveryRow("Dagminimum", "$it", "-", "-", RecoveryTag.MEASURED) }
        rows += metricRows("Opgeladen", "punten", b.chargedMetric, b.charged?.toString() ?: "-", RecoveryTag.MEASURED, "geen waarde", w = w)
        rows += metricRows("Verbruikt", "punten", b.drainedMetric, b.drained?.toString() ?: "-", RecoveryTag.MEASURED, "geen waarde", w = w)
        return rows
    }

    // ---------------------------------------------------------------- hero: conclusion at a glance

    fun heroLevel(r: RecoveryReport): HeroLevel = heroLevel(r.readiness.summary)

    fun heroLevel(s: ReadinessSummary): HeroLevel = when {
        !s.available -> HeroLevel.INSUFFICIENT
        s.deviating.isEmpty() -> HeroLevel.WITHIN
        s.deviating.size >= 2 || s.deviating.any { it.band == DeviationBand.STRONG } -> HeroLevel.CLEAR
        else -> HeroLevel.SLIGHT
    }

    private fun bandCss(b: DeviationBand?) = when (b) {
        DeviationBand.WITHIN -> "hero-ok"
        DeviationBand.DEVIATING -> "hero-warn"
        DeviationBand.STRONG -> "hero-alert"
        null -> "hero-na"
    }

    private fun metricOf(r: RecoveryReport, k: ReadinessKind): MetricAnalysis? = when (k) {
        ReadinessKind.HRV -> r.hrv.metric
        ReadinessKind.RESTING_HR -> r.restingHr.metric
        ReadinessKind.SLEEP -> r.sleep.duration
        ReadinessKind.BODY_BATTERY -> r.bodyBattery.wakeLevelMetric
    }

    private fun dayValue(r: RecoveryReport, k: ReadinessKind): String = when (k) {
        ReadinessKind.HRV -> r.hrv.lastNightAvg?.let { "$it ms" }
        ReadinessKind.RESTING_HR -> r.restingHr.value?.let { "$it bpm" }
        ReadinessKind.SLEEP -> r.sleep.night?.durationSeconds?.let { hmSeconds(it.toLong()) }
        ReadinessKind.BODY_BATTERY -> r.bodyBattery.wakeLevel?.toString()
    } ?: "-"

    private fun dayNorm(k: ReadinessKind, median: Double): String = when (k) {
        ReadinessKind.HRV -> "${f(median, 0)} ms"
        ReadinessKind.RESTING_HR -> "${f(median, 0)} bpm"
        ReadinessKind.SLEEP -> hm(median)
        ReadinessKind.BODY_BATTERY -> f(median, 0)
    }

    /**
     * HRV bar of the day view: the green zone is Garmin's own balanced range of that day (Garmin may change it; it is
     * read from the stored row of the day, not fixed). The scale is the range with one range width on each side. The
     * conclusion level still uses the app's own norm, which is stated on the card; the two are never merged.
     */
    private fun garminHrvComponent(h: HrvResult?, own: HeroComponent?): HeroComponent? {
        if (h == null || own == null) return null
        val low = h.garminBalancedLow ?: return null
        val up = h.garminBalancedUpper ?: return null
        val v = h.lastNightAvg ?: return null
        if (up <= low) return null
        val width = (up - low).toDouble()
        val min = low - width
        val pos = ((v - min) / (3 * width) * 100).coerceIn(0.0, 100.0)
        val words = when {
            v < low -> "$v ms ligt onder Garmins bereik"
            v > up -> "$v ms ligt boven Garmins bereik"
            else -> "$v ms ligt binnen Garmins bereik"
        }
        val ownText = if (own.position != null) "eigen norm ${own.norm.removePrefix("norm ")}: ${own.deviation}" else "eigen norm: ${own.deviation}"
        return own.copy(
            norm = "Garmin-bereik $low tot $up ms (Garmin past dit zelf aan)",
            deviation = "$words · $ownText",
            position = pos,
            aria = "${own.label}: $v ms, $words, groene zone is Garmins bereik $low tot $up ms, $ownText",
            axisMiddle = "Garmin-bereik",
        )
    }

    /** Shared by the day and the period view. */
    private fun buildHero(
        rd: Readiness,
        value: (ReadinessKind) -> String,
        facts: List<Pair<String, String>>,
        sustained: List<String>,
        debtLine: String?,
        scopeText: String,
        footnote: String,
        noValueWords: String,
        garminHrv: HrvResult? = null,
    ): Hero {
        val s = rd.summary
        val level = heroLevel(s)
        val comps = rd.components.map { c ->
            val label = component(c.kind).replaceFirstChar { it.uppercase() }
            if (!c.hasData) {
                val why = if (c.value == null) noValueWords else "onvoldoende data voor een eigen norm"
                HeroComponent(label, value(c.kind), "-", why, "hero-na", null, "$label: $why")
            } else {
                val dev = c.deviation!!
                val words = if (c.band == DeviationBand.WITHIN) "binnen eigen norm" else "${bandText(c.band!!)}, ${directionText(dev)}"
                val norm = "norm ${dayNorm(c.kind, c.median!!)}"
                HeroComponent(label, value(c.kind), norm, "$words (${signed(dev)} SD)", bandCss(c.band), (dev.coerceIn(-3.0, 3.0) + 3.0) / 6.0 * 100.0, "$label: ${value(c.kind)}, eigen $norm, $words")
            }
        }.toMutableList()
        garminHrvComponent(garminHrv, comps.firstOrNull { it.label == component(ReadinessKind.HRV).replaceFirstChar { c -> c.uppercase() } })?.let { g ->
            val i = comps.indexOfFirst { it.label == g.label }
            comps[i] = g
        }
        rd.sleepTarget?.let { t ->
            val words = if (t.band == DeviationBand.WITHIN) "binnen je doel" else "${bandText(t.band)}: onder je doel"
            val avg = "${f(t.averageShortfallMinutes, 0)} min tekort per nacht"
            comps += HeroComponent("Slaaptekort (doel ${hm(t.targetMinutes.toDouble())})", avg, "gemiddelde over ${t.nights} nachten", "$words (${signed(t.deviation)} SD)", bandCss(t.band),
                (t.deviation.coerceIn(-3.0, 3.0) + 3.0) / 6.0 * 100.0, "Slaaptekort: $avg over ${t.nights} nachten, $words")
        }

        val conclusion = mutableListOf<String>()
        if (!s.available) {
            conclusion += "Er is te weinig data voor een conclusie: ${s.componentsWithData} van 4 componenten hebben een waarde en een norm of doel (minimaal 3 nodig). Bekijk hieronder per component wat ontbreekt."
        } else {
            val deviatingKinds = s.deviating.map { it.kind }.toSet()
            val within = rd.components.filter { c -> (c.hasData || (c.kind == ReadinessKind.SLEEP && rd.sleepTarget != null)) && c.kind !in deviatingKinds }.map { component(it.kind) }
            if (within.isNotEmpty()) conclusion += "Binnen je eigen norm${if (rd.sleepTarget != null && ReadinessKind.SLEEP !in deviatingKinds) " (slaap ook binnen je doel)" else ""}: ${within.joinToString(", ")}."
            if (s.deviating.isNotEmpty()) conclusion += "Afwijkend: " + s.deviating.joinToString("; ") { deviationSentence(it, rd) } + "."
        }
        if (sustained.isNotEmpty()) conclusion += "Over de laatste dagen: ${sustained.joinToString("; ")}."
        debtLine?.let { conclusion += it }
        conclusion += "Dit is een patroon in je eigen cijfers, geen diagnose. Een hogere of lagere waarde is op zich geen goed of slecht teken. Hoe je je voelt weegt zwaarder dan deze cijfers."

        val method = listOf(
            scopeText,
            "Slaap wordt tweemaal beoordeeld: tegenover je eigen norm en tegenover je doel. Slaaptekort = gemiddeld aantal minuten per nacht onder je doel (lange nachten compenseren niet): minder dan ${SLEEP_BAND} min is binnen doel, ${SLEEP_BAND} tot ${SLEEP_BAND * 2} min afwijkend, vanaf ${SLEEP_BAND * 2} min sterk afwijkend. De grootste afwijking van beide telt voor de conclusie.",
            "Conclusie: alles binnen norm en doel = \"Binnen je eigen norm\". Eén component afwijkend (1 tot 2 SD) = \"Licht afwijkend\". Twee of meer afwijkend, of één sterk afwijkend = \"Duidelijk afwijkend\". Minder dan 3 componenten met data = \"Onvoldoende data\".",
            "Geen getal en geen weging: er bestaan geen gevalideerde gewichten tussen HRV, rusthartslag, slaap en Body Battery. De drempels zijn app-schattingen zonder externe validatie.",
            "Recente trainingsbelasting is nog geen onderdeel. Garmin-waarden zoals HRV, slaapfasen en Body Battery zijn Garmin-schattingen.",
        )
        return Hero(level, level.label, summaryText(rd), footnote, conclusion, facts, comps, method)
    }

    private val SLEEP_BAND = SleepAnalysis.TARGET_BAND_MINUTES.toInt()

    fun hero(r: RecoveryReport, sleepTargetMinutes: Int): Hero {
        val sustained = ReadinessKind.entries.mapNotNull { k ->
            val t = metricOf(r, k)?.trend ?: return@mapNotNull null
            when (t.sustained) {
                SustainedDirection.HIGHER -> "${component(k)} aanhoudend hoger dan je eigen norm (${t.sustainedDaysHigher} van ${t.sustainedDaysEvaluated} dagen)"
                SustainedDirection.LOWER -> "${component(k)} aanhoudend lager dan je eigen norm (${t.sustainedDaysLower} van ${t.sustainedDaysEvaluated} dagen)"
                else -> null
            }
        }
        val d = r.sleepDebt
        val debtLine = if (d.status == BaselineStatus.OK)
            "Geschat slaaptekort over ${r.windows.debtNights} nachten: ${hmSeconds(d.shortfallSeconds!!)} (doel ${hm(sleepTargetMinutes.toDouble())} per nacht, ${d.nightsBelowTarget} nacht(en) eronder)." else null
        return buildHero(
            r.readiness, { dayValue(r, it) },
            listOf("Dag" to PerfDates.dayLong(r.date) + if (r.isToday) " (vandaag)" else "", "Componenten met data" to "${r.readiness.summary.componentsWithData} van 4", "Eigen norm" to "mediaan van ${r.windows.normDays} dagen"),
            sustained, debtLine,
            "Elke component vergelijkt de waarde van deze dag met de mediaan van je ${r.windows.normDays} vorige dagen. De afwijking wordt uitgedrukt in robuuste standaarddeviatie (SD): onder 1 SD is binnen je norm, 1 tot 2 SD afwijkend, vanaf 2 SD sterk afwijkend.",
            "Algoritme ${r.algorithmVersion}." + if (r.isToday) " Vandaag: stress en Body Battery van vandaag zijn onvolledig en tellen niet mee in normen." else "",
            "geen waarde voor deze dag",
            r.hrv,
        )
    }

    // ---------------------------------------------------------------- week and month view

    data class PeriodPage(
        val hero: Hero,
        val title: String,
        val subtitle: String,
        val rows: List<RecoveryRow>,
        val dayHeaders: List<String>,
        val dayRows: List<List<String>>,
        val notes: List<String>,
    )

    private fun periodLabel(k: PeriodMetricKind) = when (k) {
        PeriodMetricKind.HRV -> "HRV nachtgemiddelde (ms)"
        PeriodMetricKind.RESTING_HR -> "Rusthartslag (bpm)"
        PeriodMetricKind.SLEEP_DURATION -> "Slaapduur"
        PeriodMetricKind.BODY_BATTERY_WAKE -> "Body Battery bij ontwaken"
        PeriodMetricKind.STRESS_AVG -> "Gemiddeld stressniveau"
    }

    fun periodPage(r: PeriodReport, sleepTargetMinutes: Int): PeriodPage {
        val dutch = if (r.kind == PeriodKind.WEEK) "Week" else "Maand"
        val sleep = { v: Double -> hm(v) }
        val rows = r.metrics.map { m ->
            val fmt: (Double) -> String = if (m.kind == PeriodMetricKind.SLEEP_DURATION) sleep else { v -> f(v) }
            val tag = RecoveryTag.COMPUTED
            val delta = m.difference
            if (m.status == BaselineStatus.INSUFFICIENT) {
                RecoveryRow(periodLabel(m.kind), "onbeschikbaar", "onvoldoende data (n=${m.n} van ${r.elapsedDays} dagen, minimaal ${m.needed})", "-", RecoveryTag.NA)
            } else {
                val diff = when {
                    delta == null -> "vorige periode: onvoldoende data (n=${m.previousN})"
                    m.kind == PeriodMetricKind.SLEEP_DURATION -> "vorige periode ${fmt(m.previousMedian!!)}, verschil ${if (delta < 0) "-" else "+"}${hm(abs(delta))}"
                    else -> "vorige periode ${fmt(m.previousMedian!!)}, verschil ${signed(delta)}"
                }
                RecoveryRow(periodLabel(m.kind), "mediaan ${fmt(m.median!!)}", "bereik ${fmt(m.min!!)} tot ${fmt(m.max!!)}, n=${m.n} van ${r.elapsedDays} dagen", diff, tag)
            }
        }.toMutableList()
        val d = r.sleepDebt
        rows += if (d.status == BaselineStatus.OK)
            RecoveryRow("Geschat slaaptekort", hmSeconds(d.shortfallSeconds!!), "doel ${hm(sleepTargetMinutes.toDouble())} per nacht, ${d.nightsWithData} nachten met data", "${d.nightsBelowTarget} nacht(en) onder het doel; netto saldo ${if (d.netBalanceSeconds!! < 0) "-" else "+"}${hmSeconds(abs(d.netBalanceSeconds!!))}", RecoveryTag.COMPUTED)
        else na("Geschat slaaptekort", "onvoldoende data (n=${d.nightsWithData} van ${r.elapsedDays} nachten, minimaal ${d.needed})")
        val dash = { v: Int? -> v?.toString() ?: "-" }
        val dayRows = r.days.map { listOf(it.date.toString(), dash(it.hrv), dash(it.restingHr), it.sleepMinutes?.let { m -> hm(m.toDouble()) } ?: "-", dash(it.bodyBatteryWake), dash(it.stressAvg)) }
        val unit = { k: ReadinessKind ->
            val pk = when (k) { ReadinessKind.HRV -> PeriodMetricKind.HRV; ReadinessKind.RESTING_HR -> PeriodMetricKind.RESTING_HR; ReadinessKind.SLEEP -> PeriodMetricKind.SLEEP_DURATION; ReadinessKind.BODY_BATTERY -> PeriodMetricKind.BODY_BATTERY_WAKE }
            val m = r.metrics.first { it.kind == pk }
            val med = m.median
            if (med == null) "-" else when (k) {
                ReadinessKind.HRV -> "${f(med, 0)} ms"
                ReadinessKind.RESTING_HR -> "${f(med, 0)} bpm"
                ReadinessKind.SLEEP -> hm(med)
                ReadinessKind.BODY_BATTERY -> f(med, 0)
            }
        }
        val periodDebtLine = if (d.status == BaselineStatus.OK)
            "Geschat slaaptekort in deze periode: ${hmSeconds(d.shortfallSeconds!!)} (doel ${hm(sleepTargetMinutes.toDouble())} per nacht, ${d.nightsWithData} nachten met data, ${d.nightsBelowTarget} eronder)." else null
        val hero = buildHero(
            r.readiness, unit,
            listOf("Periode" to PerfDates.range(r.from, r.to), "Componenten met data" to "${r.readiness.summary.componentsWithData} van 4", "Eigen norm" to "mediaan van de ${r.windows.normDays} dagen vóór de periode"),
            emptyList(), periodDebtLine,
            "Elke component vergelijkt de mediaan van de dagen met data in deze periode met de mediaan van de ${r.windows.normDays} dagen vóór de periode. De afwijking wordt uitgedrukt in robuuste standaarddeviatie (SD): onder 1 SD is binnen je norm, 1 tot 2 SD afwijkend, vanaf 2 SD sterk afwijkend.",
            "Algoritme ${r.algorithmVersion}." + if (r.partial) " Lopende periode: ${r.elapsedDays} dagen." else "",
            "te weinig dagen met data in deze periode",
        )
        return PeriodPage(
            hero,
            "$dutch ${r.from} tot ${r.to}",
            (if (r.partial) "Lopende periode: ${r.elapsedDays} dagen tot en met ${r.to}. " else "") + "Vergeleken met ${r.previousFrom} tot ${r.previousTo}.",
            rows, listOf("Dag", "HRV (ms)", "Rusthartslag (bpm)", "Slaap", "Body Battery ontwaken", "Stress gem."), dayRows,
            listOf("Mediaan van de dagen met data. Het verschil met de vorige periode is een berekend verschil, geen oordeel: hoger of lager zegt op zich niets over herstel.",
                "Een rusthartslag telt enkel als Garmin-waarde. Stress van vandaag telt niet mee (dag is niet af)."),
        )
    }

    /** The `<herstel>` block of a copy-paste prompt. Values, norms, deviations, n and labels; no raw payloads, no identifiers. */
    fun promptBlock(r: RecoveryReport, sleepTargetMinutes: Int): String = buildString {
        val p = page(r, sleepTargetMinutes)
        appendLine("<herstel algoritme=\"${p.algorithmVersion}\" datum=\"${p.date}\">")
        appendLine("Samenvatting ${AiAnalysisService.TAG_ESTIMATE}: ${p.summary}")
        p.sections.forEach { sec ->
            appendLine("${sec.title}:")
            sec.rows.forEach { row ->
                appendLine("- ${row.label}: ${row.value}; ${row.reference}; ${row.deviation} ${row.tag.text}")
            }
        }
        appendLine("Laatste 7 dagen ${AiAnalysisService.TAG_MEASURED}:")
        p.recentNights.forEach { appendLine("- $it") }
        appendLine(SAFETY)
        appendLine("</herstel>")
    }
}
