package com.running.analysis

import com.running.garmin.recovery.BaselineStatus
import com.running.garmin.recovery.DailySeries
import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.MetricAnalysis
import com.running.garmin.recovery.NormPoint
import com.running.garmin.recovery.PeriodKind
import com.running.garmin.recovery.PeriodMetricKind
import com.running.garmin.recovery.PeriodReport
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.recovery.RecoveryWindows
import com.running.garmin.recovery.RestingHrSource
import com.running.garmin.recovery.SustainedDirection
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Small coloured label. Colours mean distance from your own norm, never good or bad. */
data class Pill(val text: String, val tone: Tone)

data class StageShare(val label: String, val pct: Double, val css: String)

data class NightBar(val label: String, val heightPct: Int, val text: String, val below: Boolean)

/** Position (0-100) of the day minimum, maximum and wake level on a 0-100 Body Battery scale. */
data class BatteryRange(val min: Int, val max: Int, val wake: Int?)

data class MetricCard(
    val id: String,
    val title: String,
    val tip: String?,
    val kind: Kind,
    val value: String,
    val pill: Pill?,
    val norm: String?,
    val trend: String?,
    val facts: List<Pair<String, String>> = emptyList(),
    val norms: List<String> = emptyList(),
    val note: String? = null,
    val stages: List<StageShare> = emptyList(),
    val nights: List<NightBar> = emptyList(),
    val nightsTarget: Int? = null,
    val battery: BatteryRange? = null,
)

data class DayCell(val text: String, val css: String)

data class DayRow(val date: String, val cells: List<DayCell>)

data class PeriodTile(val label: String, val tip: String?, val value: String, val range: String, val change: String, val arrow: String, val available: Boolean)

data class RecoveryView(
    val cards: List<MetricCard>,
    val dayHeaders: List<String>,
    val dayRows: List<DayRow>,
    val charts: Map<String, Any?>,
    val tiles: List<PeriodTile> = emptyList(),
    val periodTitle: String? = null,
    val periodSub: String? = null,
)

/** Builds the redesigned Herstel page: cards, charts with the own-norm band, and a colour-coded day table. */
object RecoveryViewBuilder {
    private val nl = Locale.forLanguageTag("nl-BE")
    private fun num(v: Double): String = if (abs(v - v.roundToInt()) < 0.05) "%.0f".format(nl, v) else "%.1f".format(nl, v)
    private fun pct(v: Double) = "%.0f %%".format(nl, v)
    private fun hm(minutes: Double): String { val m = minutes.roundToInt(); return "${m / 60}:${(m % 60).toString().padStart(2, '0')} u" }
    private fun signed(v: Double) = "%+.1f".format(nl, v)
    private val DAYS = listOf("ma", "di", "wo", "do", "vr", "za", "zo")

    private fun shortDay(d: LocalDate) = "${DAYS[d.dayOfWeek.value - 1]} ${PerfDates.dayShort(d)}"

    fun tone(band: DeviationBand?) = when (band) {
        DeviationBand.WITHIN -> Tone.GOOD
        DeviationBand.DEVIATING -> Tone.UNCERTAIN
        DeviationBand.STRONG -> Tone.BAD
        null -> Tone.NONE
    }

    private fun pill(m: MetricAnalysis?): Pill? {
        val p = m?.primary ?: return null
        if (p.status != BaselineStatus.OK || p.deviation == null) return null
        val d = p.deviation!!
        val dir = if (d > 0) "hoger" else "lager"
        return when (p.band!!) {
            DeviationBand.WITHIN -> Pill("binnen je eigen norm", Tone.GOOD)
            DeviationBand.DEVIATING -> Pill("$dir dan normaal", Tone.UNCERTAIN)
            DeviationBand.STRONG -> Pill("duidelijk $dir dan normaal", Tone.BAD)
        }
    }

    private fun normLine(m: MetricAnalysis?, fmt: (Double) -> String): String? {
        val p = m?.primary ?: return null
        return when (p.status) {
            BaselineStatus.OK -> "Eigen norm ${fmt(p.median!!)} (mediaan van ${p.n} dagen)" + (p.deviation?.let { " · afwijking ${signed(it)} SD" } ?: "")
            BaselineStatus.INSUFFICIENT -> "Nog geen eigen norm: ${p.n} van de ${p.needed} nodige dagen met data."
        }
    }

    private fun trendLine(m: MetricAnalysis?, fmt: (Double) -> String, w: RecoveryWindows): String? {
        val t = m?.trend ?: return null
        val level = if (t.status == BaselineStatus.OK) {
            val dev = t.deviation!!
            val word = when {
                abs(dev) < 1 -> "vergelijkbaar"
                abs(dev) < 2 -> if (dev > 0) "hoger" else "lager"
                else -> if (dev > 0) "duidelijk hoger" else "duidelijk lager"
            }
            "Laatste ${w.trendRecentDays} dagen: ${fmt(t.recentMedian!!)}, tegenover ${fmt(t.baselineMedian!!)} over de ${w.trendBaselineDays} dagen ervoor ($word)."
        } else "Te weinig dagen voor een trend over de laatste ${w.trendRecentDays} dagen."
        val sustained = when (t.sustained) {
            SustainedDirection.HIGHER -> " Aanhoudend hoger dan je eigen norm: ${t.sustainedDaysHigher} van de laatste ${t.sustainedDaysEvaluated} dagen."
            SustainedDirection.LOWER -> " Aanhoudend lager dan je eigen norm: ${t.sustainedDaysLower} van de laatste ${t.sustainedDaysEvaluated} dagen."
            else -> ""
        }
        return level + sustained
    }

    private fun norms(m: MetricAnalysis?, fmt: (Double) -> String): List<String> =
        m?.windows.orEmpty().map { w -> "${w.windowDays} dagen: " + if (w.status == BaselineStatus.OK) fmt(w.median!!) else "te weinig data" }

    private fun garminHrvStatus(s: String?) = when (s?.uppercase()) {
        null -> null
        "BALANCED" -> "in balans"
        "UNBALANCED" -> "uit balans"
        "LOW" -> "laag"
        "POOR" -> "zeer laag"
        "NONE" -> "nog geen status"
        else -> s.lowercase()
    }

    private fun qualifier(q: String?) = when (q?.uppercase()) {
        null -> null
        "EXCELLENT" -> "uitstekend"
        "GOOD" -> "goed"
        "FAIR" -> "redelijk"
        "POOR" -> "slecht"
        else -> q.lowercase()
    }

    // ---------------------------------------------------------------- day view

    fun day(r: RecoveryReport, sleepTargetMinutes: Int): RecoveryView {
        val w = r.windows
        val ms = { v: Double -> "${num(v)} ms" }
        val bpm = { v: Double -> "${num(v)} bpm" }
        val points = { v: Double -> num(v) }
        val pctF = { v: Double -> "${num(v)} %" }
        val incomplete = " (dag nog bezig)"
        val cards = mutableListOf<MetricCard>()

        val h = r.hrv
        cards += MetricCard(
            "hrv", "HRV", "hrv", Kind.MEASURED, h.lastNightAvg?.let { "$it ms" } ?: "geen meting",
            pill(h.metric), normLine(h.metric, ms), trendLine(h.metric, ms, w),
            listOfNotNull(
                if (h.garminBalancedLow != null && h.garminBalancedUpper != null) "Garmin-bereik" to "${h.garminBalancedLow} tot ${h.garminBalancedUpper} ms" else null,
                garminHrvStatus(h.garminStatus)?.let { "Garmin-status" to it },
                h.weeklyAvg?.let { "Garmin weekgemiddelde" to "$it ms" },
            ),
            norms(h.metric, ms),
            "Nachtgemiddelde volgens Garmin. Het Garmin-bereik en de eigen norm van de app staan los van elkaar.",
        )

        val rr = r.restingHr
        cards += MetricCard(
            "rhr", "Rusthartslag", null, Kind.MEASURED, rr.value?.let { "$it bpm" } ?: "geen meting",
            pill(rr.metric), normLine(rr.metric, bpm) ?: if (rr.source == RestingHrSource.MANUAL) "Handmatig ingevoerde waarde: zonder geschiedenis is er geen eigen norm." else null,
            trendLine(rr.metric, bpm, w),
            listOf("Bron" to when (rr.source) { RestingHrSource.GARMIN -> "Garmin"; RestingHrSource.MANUAL -> "handmatig ingevoerd"; RestingHrSource.NONE -> "geen" }),
            norms(rr.metric, bpm),
        )

        val s = r.sleep
        val n = s.night
        val stages = if (n != null && !n.stagesHidden && n.deepSharePercent != null) {
            val sleepShare = 100.0 - (n.awakeSharePercent ?: 0.0)
            listOfNotNull(
                n.deepSharePercent?.let { StageShare("Diep", it * sleepShare / 100, "stage-deep") },
                n.lightSharePercent?.let { StageShare("Licht", it * sleepShare / 100, "stage-light") },
                n.remSharePercent?.let { StageShare("REM", it * sleepShare / 100, "stage-rem") },
                n.awakeSharePercent?.let { StageShare("Wakker", it, "stage-awake") },
            )
        } else emptyList()
        val reg = s.regularity
        cards += MetricCard(
            "sleep", "Slaap", null, Kind.MEASURED, n?.durationSeconds?.let { hm(it / 60.0) } ?: "geen meting",
            pill(s.duration), normLine(s.duration, ::hm), trendLine(s.duration, ::hm, w),
            listOfNotNull(
                n?.overallScore?.let { "Garmin-slaapscore" to "$it" + (qualifier(n.overallQualifier)?.let { q -> " ($q)" } ?: "") },
                n?.deepSharePercent?.let { "Diepe slaap" to "${pct(it)} van de slaap (indicatief ${s.deepReferenceLowPercent}-${s.deepReferenceHighPercent} %)" },
                n?.awakeSharePercent?.let { "Wakker in bed" to pct(it) },
                if (reg.status == BaselineStatus.OK) "Regelmaat (${w.regularityNights} nachten)" to
                    "inslapen schommelt ±${"%.0f".format(nl, reg.startSdMinutes)} min, opstaan ±${"%.0f".format(nl, reg.endSdMinutes)} min" else null,
            ),
            norms(s.duration, ::hm),
            when {
                n == null -> null
                n.stagesHidden -> "Slaapfasen niet getoond: meer dan 10 % van de nacht was niet meetbaar."
                else -> "Slaapfasen zijn een schatting van Garmin."
            },
            stages = stages,
        )

        val d = r.sleepDebt
        val last7 = r.daily.sleepMinutes.takeLast(w.debtNights)
        val maxMin = (last7.mapNotNull { it.value } + sleepTargetMinutes.toDouble()).maxOrNull() ?: sleepTargetMinutes.toDouble()
        cards += MetricCard(
            "debt", "Slaaptekort (doel ${hm(sleepTargetMinutes.toDouble())})", "slaaptekort", Kind.CALCULATED,
            if (d.status == BaselineStatus.OK) "${hm(d.shortfallSeconds!! / 60.0)} tekort" else "te weinig nachten",
            d.target?.let { t ->
                when (t.band) {
                    DeviationBand.WITHIN -> Pill("binnen je doel", Tone.GOOD)
                    DeviationBand.DEVIATING -> Pill("onder je doel", Tone.UNCERTAIN)
                    DeviationBand.STRONG -> Pill("duidelijk onder je doel", Tone.BAD)
                }
            },
            if (d.status == BaselineStatus.OK) "Over de laatste ${w.debtNights} nachten: ${d.nightsBelowTarget} ${if (d.nightsBelowTarget == 1) "nacht" else "nachten"} korter dan je doel."
            else "Minstens ${d.nightsNeeded} nachten met data nodig (nu ${d.nightsWithData}).",
            null,
            listOfNotNull(
                d.netBalanceSeconds?.let { "Netto saldo" to (if (it < 0) "-" else "+") + hm(abs(it) / 60.0) },
                d.target?.let { "Gemiddeld tekort" to "${"%.0f".format(nl, it.averageShortfallMinutes)} min per nacht" },
            ),
            note = "Lange nachten compenseren korte nachten niet; dutjes tellen niet mee.",
            nights = last7.map { p ->
                val v = p.value
                NightBar(shortDay(p.date), if (v == null) 0 else (v / maxMin * 100).roundToInt(), v?.let { hm(it) } ?: "geen meting", v != null && v < sleepTargetMinutes)
            },
            nightsTarget = (sleepTargetMinutes / maxMin * 100).roundToInt(),
        )

        val b = r.bodyBattery
        cards += MetricCard(
            "bb", "Body Battery bij ontwaken", "body-battery", Kind.ESTIMATE, b.wakeLevel?.toString() ?: "geen meting",
            pill(b.wakeLevelMetric), normLine(b.wakeLevelMetric, points), trendLine(b.wakeLevelMetric, points, w),
            listOfNotNull(
                b.recharge?.let { "Opgeladen tijdens de slaap" to signed(it.toDouble()).removeSuffix(",0") + " punten" + (pill(b.rechargeMetric)?.let { p -> " (${p.text})" } ?: "") },
                b.charged?.let { (if (r.isToday) "Opgeladen vandaag" else "Opgeladen die dag") to "$it" + if (b.incomplete) incomplete else "" },
                b.drained?.let { (if (r.isToday) "Verbruikt vandaag" else "Verbruikt die dag") to "$it" + if (b.incomplete) incomplete else "" },
            ),
            norms(b.wakeLevelMetric, points),
            battery = if (b.minValue != null && b.maxValue != null) BatteryRange(b.minValue!!, b.maxValue!!, b.wakeLevel) else null,
        )

        val st = r.stress
        cards += MetricCard(
            "stress", "Stress", "stress", Kind.ESTIMATE, st.avgLevel?.toString() ?: "geen meting",
            if (st.incomplete) Pill("dag nog bezig", Tone.NONE) else pill(st.avgLevelMetric), normLine(st.avgLevelMetric, points), trendLine(st.avgLevelMetric, points, w),
            listOfNotNull(
                when {
                    st.highSharePercent != null -> "Tijd met gemiddelde of hoge stress" to pct(st.highSharePercent!!) + (pill(st.highShareMetric)?.let { p -> " (${p.text})" } ?: "")
                    st.tooShort -> "Tijd met gemiddelde of hoge stress" to "te weinig meettijd (${"%.0f".format(nl, st.measuredHours ?: 0.0)} u)"
                    else -> null
                },
            ),
            norms(st.avgLevelMetric, points),
            if (st.incomplete) "Vandaag is nog bezig: de stress van vandaag wordt nog niet met je norm vergeleken." else null,
        )

        val (headers, rows) = dayTable(r.daily, w.tableDays)
        return RecoveryView(cards, headers, rows, charts(r.daily.last(w.chartDays), sleepTargetMinutes, highlightLast = true))
    }

    // ---------------------------------------------------------------- shared: day table and charts

    private fun cell(p: NormPoint?, fmt: (Double) -> String): DayCell {
        val v = p?.value ?: return DayCell("-", "cell-none")
        val dev = if (p.median != null && p.sd != null && p.sd!! > 0) (v - p.median!!) / p.sd!! else null
        val css = when {
            dev == null -> "cell-none"
            abs(dev) < 1 -> "cell-ok"
            abs(dev) < 2 -> "cell-warn"
            else -> "cell-alert"
        }
        return DayCell(fmt(v), css)
    }

    private fun dayTable(daily: DailySeries, lastDays: Int?): Pair<List<String>, List<DayRow>> {
        val headers = listOf("Dag", "HRV", "Rusthartslag", "Slaap", "Body Battery bij ontwaken", "Stress")
        val dates = daily.hrv.map { it.date }.let { if (lastDays != null) it.takeLast(lastDays) else it }
        fun at(list: List<NormPoint>, d: LocalDate) = list.firstOrNull { it.date == d }
        val rows = dates.reversed().map { d ->
            DayRow(shortDay(d), listOf(
                cell(at(daily.hrv, d)) { "${num(it)} ms" },
                cell(at(daily.restingHr, d)) { "${num(it)} bpm" },
                cell(at(daily.sleepMinutes, d), ::hm),
                cell(at(daily.bodyBatteryWake, d)) { num(it) },
                cell(at(daily.stressAvg, d)) { num(it) },
            ))
        }
        return headers to rows
    }

    private fun DailySeries.last(n: Int) = DailySeries(hrv.takeLast(n), restingHr.takeLast(n), sleepMinutes.takeLast(n), bodyBatteryWake.takeLast(n), stressAvg.takeLast(n))

    private fun round1(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }

    private fun chartSeries(points: List<NormPoint>, scale: Double = 1.0) = mapOf(
        "values" to points.map { round1(it.value?.div(scale)) },
        "low" to points.map { p -> if (p.median != null && p.sd != null) round1((p.median!! - p.sd!!) / scale) else null },
        "high" to points.map { p -> if (p.median != null && p.sd != null) round1((p.median!! + p.sd!!) / scale) else null },
        "median" to points.map { round1(it.median?.div(scale)) },
    )

    private fun charts(daily: DailySeries, sleepTargetMinutes: Int, highlightLast: Boolean): Map<String, Any?> = mapOf(
        "labels" to daily.hrv.map { PerfDates.dayShort(it.date) },
        "titles" to daily.hrv.map { shortDay(it.date) + " " + it.date.year },
        "highlight" to highlightLast,
        "hrv" to chartSeries(daily.hrv),
        "rhr" to chartSeries(daily.restingHr),
        "sleep" to chartSeries(daily.sleepMinutes, 60.0),
        "bb" to chartSeries(daily.bodyBatteryWake),
        "stress" to chartSeries(daily.stressAvg),
        "sleepTarget" to sleepTargetMinutes / 60.0,
    )

    // ---------------------------------------------------------------- week and month

    fun period(r: PeriodReport, sleepTargetMinutes: Int): RecoveryView {
        val word = if (r.kind == PeriodKind.WEEK) "week" else "maand"
        fun fmt(k: PeriodMetricKind): (Double) -> String = when (k) {
            PeriodMetricKind.HRV -> { v -> "${num(v)} ms" }
            PeriodMetricKind.RESTING_HR -> { v -> "${num(v)} bpm" }
            PeriodMetricKind.SLEEP_DURATION -> ::hm
            PeriodMetricKind.BODY_BATTERY_WAKE, PeriodMetricKind.STRESS_AVG -> { v -> num(v) }
        }
        fun label(k: PeriodMetricKind) = when (k) {
            PeriodMetricKind.HRV -> "HRV"
            PeriodMetricKind.RESTING_HR -> "Rusthartslag"
            PeriodMetricKind.SLEEP_DURATION -> "Slaap per nacht"
            PeriodMetricKind.BODY_BATTERY_WAKE -> "Body Battery bij ontwaken"
            PeriodMetricKind.STRESS_AVG -> "Stress"
        }
        fun tip(k: PeriodMetricKind) = when (k) {
            PeriodMetricKind.HRV -> "hrv"
            PeriodMetricKind.BODY_BATTERY_WAKE -> "body-battery"
            PeriodMetricKind.STRESS_AVG -> "stress"
            else -> null
        }
        val tiles = r.metrics.map { m ->
            val f = fmt(m.kind)
            if (m.status != BaselineStatus.OK) {
                PeriodTile(label(m.kind), tip(m.kind), "-", "te weinig dagen met data (${m.n} van ${m.needed})", "", "", false)
            } else {
                val diff = m.difference
                val change = when {
                    diff == null -> "vorige $word: te weinig data"
                    m.kind == PeriodMetricKind.SLEEP_DURATION -> "${if (diff < 0) "-" else "+"}${hm(abs(diff))} tegenover vorige $word (${f(m.previousMedian!!)})"
                    else -> "${signed(diff)} tegenover vorige $word (${f(m.previousMedian!!)})"
                }
                val arrow = when {
                    diff == null || abs(diff) < 0.5 -> "→"
                    diff > 0 -> "↑"
                    else -> "↓"
                }
                PeriodTile(label(m.kind), tip(m.kind), f(m.median!!), "van ${f(m.min!!)} tot ${f(m.max!!)} · ${m.n} dagen", change, arrow, true)
            }
        }.toMutableList()
        val d = r.sleepDebt
        tiles += if (d.status == BaselineStatus.OK) PeriodTile(
            "Slaaptekort (doel ${hm(sleepTargetMinutes.toDouble())})", "slaaptekort", hm(d.shortfallSeconds!! / 60.0),
            "${d.nightsBelowTarget} van ${d.nightsWithData} nachten korter dan je doel",
            "netto saldo ${if (d.netBalanceSeconds!! < 0) "-" else "+"}${hm(abs(d.netBalanceSeconds!!) / 60.0)}", "", true,
        ) else PeriodTile("Slaaptekort", "slaaptekort", "-", "te weinig nachten met data (${d.nightsWithData} van ${d.needed})", "", "", false)

        val (headers, rows) = dayTable(r.daily, null)
        val title = (if (r.kind == PeriodKind.WEEK) "Week van " else "Maand: ") + PerfDates.range(r.from, r.fullPeriodEnd)
        val sub = (if (r.partial) "Lopende $word: ${r.elapsedDays} dagen tot en met ${PerfDates.day(r.to)}. " else "") +
            "Mediaan per dag, vergeleken met dezelfde dagen van de vorige $word (${PerfDates.range(r.previousFrom, r.previousTo)})."
        return RecoveryView(emptyList(), headers, rows, charts(r.daily, sleepTargetMinutes, highlightLast = false), tiles, title, sub)
    }
}
