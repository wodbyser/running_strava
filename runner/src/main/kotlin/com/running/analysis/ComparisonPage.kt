package com.running.analysis

import com.running.analysis.PeriodComparisonService.ComparisonResult
import com.running.analysis.PeriodComparisonService.PeriodStats
import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.Format
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reading order of the "Periodes vergelijken" page: conclusion first, then charts, then the folded
 * tables. All formatting and colour choices live here so the template only shows what it is given.
 *
 * EF is shown in metres per heartbeat (EF x 60), not as 0.0214: easier to read and the same unit as
 * the Prestaties page.
 */
object ComparisonPage {

    data class Tile(
        val label: String,
        val value: String,
        val sub: String,
        val tone: Tone = Tone.NEUTRAL,
        val tip: String? = null,
    )

    /** One line of the full comparison table. */
    data class Row(
        val label: String,
        val a: String,
        val b: String,
        val diff: String,
        val tone: Tone = Tone.NEUTRAL,
        val tip: String? = null,
        /** First row of a block: the table draws a stronger line above it. */
        val groupStart: Boolean = false,
    )

    data class Card(
        val title: String,
        val lead: String,
        val efA: String,
        val efB: String,
        val change: String,
        val tone: Tone,
        val pill: String,
        val summary: String,
        val paceA: String,
        val paceB: String,
        val hrA: String,
        val hrB: String,
        val detailA: String,
        val detailB: String,
        val note: String?,
    )

    data class MonthRow(
        val month: String,
        val easyEf: String,
        val easyRuns: Int,
        val intervalEf: String,
        val intervalReps: Int,
        val intervalSessions: Int,
    )

    /**
     * Numbers for the charts, already in display units: EF in metres per heartbeat, pace in seconds
     * per km. A null means "no data": the chart leaves a gap instead of drawing a misleading zero.
     */
    data class ChartData(
        val easyEfA: Double?,
        val easyEfB: Double?,
        val intervalEfA: Double?,
        val intervalEfB: Double?,
        val easyPaceA: Double?,
        val easyPaceB: Double?,
        val intervalPaceA: Double?,
        val intervalPaceB: Double?,
        val easyHrA: Double?,
        val easyHrB: Double?,
        val intervalHrA: Double?,
        val intervalHrB: Double?,
        val kmA: Double,
        val kmB: Double,
        val months: List<String>,
        val easyEfByMonth: List<Double?>,
        val intervalEfByMonth: List<Double?>,
    )

    data class View(
        val headline: String,
        val sub: String,
        val tone: Tone,
        val rangeA: String,
        val rangeB: String,
        val tiles: List<Tile>,
        val easy: Card,
        val interval: Card,
        val table: List<Row>,
        val months: List<MonthRow>,
        val chart: ChartData,
        val hasEasyEf: Boolean,
        val hasIntervalEf: Boolean,
        val hasMonths: Boolean,
    )

    /** One month of the trend, including months without runs so the line can break instead of bridge a gap. */
    private data class MonthPoint(
        val month: YearMonth,
        val easyEf: Double?,
        val easyRuns: Int,
        val intervalEf: Double?,
        val intervalReps: Int,
        val intervalSessions: Int,
    )

    fun of(r: ComparisonResult): View {
        val a = r.periodA
        val b = r.periodB
        val months = monthlyPoints(r)
        return View(
            headline = headline(r),
            sub = r.verdict,
            tone = overallTone(r),
            rangeA = rangeText(a),
            rangeB = rangeText(b),
            tiles = tiles(r),
            easy = easyCard(r),
            interval = intervalCard(r),
            table = table(r),
            months = months.map {
                MonthRow(
                    month = PerfDates.month(it.month),
                    easyEf = PerformanceText.efMetres(it.easyEf),
                    easyRuns = it.easyRuns,
                    intervalEf = PerformanceText.efMetres(it.intervalEf),
                    intervalReps = it.intervalReps,
                    intervalSessions = it.intervalSessions,
                )
            },
            chart = ChartData(
                easyEfA = metresValue(a.easyEf),
                easyEfB = metresValue(b.easyEf),
                intervalEfA = metresValue(a.intervalEf),
                intervalEfB = metresValue(b.intervalEf),
                easyPaceA = a.easyPaceSeconds,
                easyPaceB = b.easyPaceSeconds,
                intervalPaceA = a.intervalPaceSeconds,
                intervalPaceB = b.intervalPaceSeconds,
                easyHrA = a.easyAvgHr,
                easyHrB = b.easyAvgHr,
                intervalHrA = a.intervalAvgHr,
                intervalHrB = b.intervalAvgHr,
                kmA = a.totalDistanceKm,
                kmB = b.totalDistanceKm,
                months = months.map { PerfDates.month(it.month) },
                easyEfByMonth = months.map { metresValue(it.easyEf) },
                intervalEfByMonth = months.map { metresValue(it.intervalEf) },
            ),
            hasEasyEf = a.easyEf != null || b.easyEf != null,
            hasIntervalEf = a.intervalEf != null || b.intervalEf != null,
            hasMonths = months.any { it.easyRuns > 0 || it.intervalReps > 0 },
        )
    }

    // ---------------------------------------------------------------- conclusion

    private fun headline(r: ComparisonResult): String {
        val easy = r.easyVerdict.status
        val interval = r.intervalVerdict.status
        val easyUp = easy == EfVerdict.Status.IMPROVED
        val easyDown = easy == EfVerdict.Status.DECLINED
        val intUp = interval == EfVerdict.Status.IMPROVED
        val intDown = interval == EfVerdict.Status.DECLINED
        return when {
            (easyUp && intDown) || (easyDown && intUp) -> "Gemengd beeld tussen de twee periodes"
            easyUp && intUp -> "Beide soorten trainingen efficiënter dan in periode A"
            easyDown && intDown -> "Beide soorten trainingen minder efficiënt dan in periode A"
            easyUp -> "Rustige lopen efficiënter bij dezelfde hartslag"
            intUp -> "Intervallen sneller bij dezelfde hartslag"
            easyDown -> "Rustige lopen minder efficiënt bij dezelfde hartslag"
            intDown -> "Intervallen minder snel bij dezelfde hartslag"
            easy == EfVerdict.Status.STABLE || interval == EfVerdict.Status.STABLE -> "Geen duidelijke verandering"
            easy == EfVerdict.Status.UNCERTAIN || interval == EfVerdict.Status.UNCERTAIN -> "Een verschil, maar niet eenduidig"
            else -> "Nog te weinig data voor een oordeel"
        }
    }

    private fun overallTone(r: ComparisonResult): Tone {
        val statuses = listOf(r.easyVerdict.status, r.intervalVerdict.status)
        val up = statuses.count { it == EfVerdict.Status.IMPROVED }
        val down = statuses.count { it == EfVerdict.Status.DECLINED }
        return when {
            up > 0 && down > 0 -> Tone.UNCERTAIN
            up > 0 -> Tone.GOOD
            down > 0 -> Tone.BAD
            statuses.any { it == EfVerdict.Status.UNCERTAIN } -> Tone.UNCERTAIN
            statuses.all { it == EfVerdict.Status.INSUFFICIENT_DATA } -> Tone.NONE
            else -> Tone.NEUTRAL
        }
    }

    // ---------------------------------------------------------------- tiles

    private fun tiles(r: ComparisonResult): List<Tile> {
        val a = r.periodA
        val b = r.periodB
        val easyTone = PerformanceText.statusTone(r.easyVerdict.status)
        val intervalTone = PerformanceText.statusTone(r.intervalVerdict.status)
        return listOf(
            Tile(
                label = "Rustige lopen",
                value = PerformanceText.efMetres(b.easyEf),
                sub = "A: " + PerformanceText.efMetres(a.easyEf) + changeSuffix(r.easyEfChangePct),
                tone = easyTone,
                tip = "ef",
            ),
            Tile(
                label = "Intervallen",
                value = PerformanceText.efMetres(b.intervalEf),
                sub = "A: " + PerformanceText.efMetres(a.intervalEf) + changeSuffix(r.intervalEfChangePct),
                tone = intervalTone,
                tip = "ef",
            ),
            Tile(
                label = "Afstand",
                value = km(b.totalDistanceKm),
                sub = "A: " + km(a.totalDistanceKm) + changeSuffix(pctChange(a.totalDistanceKm, b.totalDistanceKm)),
            ),
            Tile(
                label = "Trainingen",
                value = b.activityCount.toString(),
                sub = "A: " + a.activityCount + countSuffix(b.activityCount - a.activityCount),
            ),
        )
    }

    // ---------------------------------------------------------------- cards

    private fun easyCard(r: ComparisonResult): Card {
        val a = r.periodA
        val b = r.periodB
        return Card(
            title = "Rustige / duurlopen",
            lead = "Rustige, gelijkmatige weglopen op de weg met hartslag, zonder intervallen, tempo, " +
                "versnellingen of wedstrijden. Meer meter per hartslag betekent een sterkere aerobe basis.",
            efA = PerformanceText.efMetres(a.easyEf),
            efB = PerformanceText.efMetres(b.easyEf),
            change = changeText(r.easyEfChangePct),
            tone = PerformanceText.statusTone(r.easyVerdict.status),
            pill = pill(r.easyVerdict.status),
            summary = summary(r.easyVerdict.status, r.easyVerdict.nA, r.easyVerdict.nB, EfVerdict.MIN_EASY_RUNS, "lopen"),
            paceA = a.easyAvgPace,
            paceB = b.easyAvgPace,
            hrA = a.easyAvgHrText,
            hrB = b.easyAvgHrText,
            detailA = "${a.easyRunCount} rustige lopen met hartslag",
            detailB = "${b.easyRunCount} rustige lopen met hartslag",
            note = exclusionNote(a, b),
        )
    }

    private fun intervalCard(r: ComparisonResult): Card {
        val a = r.periodA
        val b = r.periodB
        return Card(
            title = "Intervaltrainingen",
            lead = "Snelle reps van 30 seconden of langer met hartslag, herkend uit je rondes en telkens " +
                "gescheiden door herstel. Meer meter per hartslag in de reps betekent efficiënter op snelheid.",
            efA = PerformanceText.efMetres(a.intervalEf),
            efB = PerformanceText.efMetres(b.intervalEf),
            change = changeText(r.intervalEfChangePct),
            tone = PerformanceText.statusTone(r.intervalVerdict.status),
            pill = pill(r.intervalVerdict.status),
            summary = summary(r.intervalVerdict.status, r.intervalVerdict.nA, r.intervalVerdict.nB, EfVerdict.MIN_INTERVAL_SESSIONS, "sessies"),
            paceA = a.intervalAvgPace,
            paceB = b.intervalAvgPace,
            hrA = a.intervalAvgHrText,
            hrB = b.intervalAvgHrText,
            detailA = intervalDetail(a),
            detailB = intervalDetail(b),
            note = repLengthNote(a, b),
        )
    }

    private fun summary(status: EfVerdict.Status, nA: Int, nB: Int, min: Int, unit: String): String = when (status) {
        EfVerdict.Status.INSUFFICIENT_DATA ->
            "Te weinig data voor een oordeel: minstens $min per periode nodig, nu $nA en $nB $unit met hartslag."
        EfVerdict.Status.STABLE -> "Stabiel: het verschil blijft binnen de normale schommeling."
        EfVerdict.Status.UNCERTAIN -> "Er is een verschil, maar het valt binnen de spreiding tussen je trainingen. Geen duidelijke richting."
        EfVerdict.Status.IMPROVED -> "Hoger in periode B dan in periode A: een indicatie van verbetering."
        EfVerdict.Status.DECLINED -> "Lager in periode B dan in periode A: een indicatie van achteruitgang."
    }

    private fun pill(status: EfVerdict.Status): String = when (status) {
        EfVerdict.Status.IMPROVED -> "efficiënter"
        EfVerdict.Status.DECLINED -> "minder efficiënt"
        EfVerdict.Status.STABLE -> "stabiel"
        EfVerdict.Status.UNCERTAIN -> "onzeker"
        EfVerdict.Status.INSUFFICIENT_DATA -> "te weinig data"
    }

    // ---------------------------------------------------------------- table

    private fun table(r: ComparisonResult): List<Row> {
        val a = r.periodA
        val b = r.periodB
        val easyTone = PerformanceText.statusTone(r.easyVerdict.status)
        val intervalTone = PerformanceText.statusTone(r.intervalVerdict.status)
        return listOf(
            Row("Trainingen", a.activityCount.toString(), b.activityCount.toString(), signed(b.activityCount - a.activityCount), groupStart = true),
            Row("Afstand", km(a.totalDistanceKm), km(b.totalDistanceKm), signedDecimals(b.totalDistanceKm - a.totalDistanceKm, " km", 1)),
            Row("Looptijd", hours(a.totalTimeHours), hours(b.totalTimeHours), signedDecimals(b.totalTimeHours - a.totalTimeHours, " u", 1)),

            Row("Rustige lopen met hartslag", a.easyRunCount.toString(), b.easyRunCount.toString(), signed(b.easyRunCount - a.easyRunCount), groupStart = true),
            Row("Gemiddeld tempo rustig", a.easyAvgPace, b.easyAvgPace, paceDiff(a.easyPaceSeconds, b.easyPaceSeconds)),
            Row("Gemiddelde hartslag rustig", a.easyAvgHrText, b.easyAvgHrText, bpmDiff(a.easyAvgHr, b.easyAvgHr)),
            Row("Efficiëntie rustig", PerformanceText.efMetres(a.easyEf), PerformanceText.efMetres(b.easyEf), changeText(r.easyEfChangePct), easyTone, "ef"),

            Row("Intervalsessies met hartslag", a.intervalSessionCount.toString(), b.intervalSessionCount.toString(), signed(b.intervalSessionCount - a.intervalSessionCount), groupStart = true),
            Row("Aantal reps", a.intervalRepCount.toString(), b.intervalRepCount.toString(), signed(b.intervalRepCount - a.intervalRepCount)),
            Row("Gemiddelde repduur", seconds(a.intervalAvgRepSeconds), seconds(b.intervalAvgRepSeconds), secondsDiff(a.intervalAvgRepSeconds, b.intervalAvgRepSeconds)),
            Row("Gemiddeld tempo per rep", a.intervalAvgPace, b.intervalAvgPace, paceDiff(a.intervalPaceSeconds, b.intervalPaceSeconds)),
            Row("Gemiddelde hartslag per rep", a.intervalAvgHrText, b.intervalAvgHrText, bpmDiff(a.intervalAvgHr, b.intervalAvgHr)),
            Row("Efficiëntie intervallen", PerformanceText.efMetres(a.intervalEf), PerformanceText.efMetres(b.intervalEf), changeText(r.intervalEfChangePct), intervalTone, "ef"),
        )
    }

    // ---------------------------------------------------------------- trend

    private fun monthlyPoints(r: ComparisonResult): List<MonthPoint> {
        val points = r.monthlyTrend
        if (points.isEmpty()) return emptyList()
        val byMonth = points.mapNotNull { p -> runCatching { YearMonth.parse(p.month) }.getOrNull()?.let { it to p } }.toMap()
        val first = byMonth.keys.minOrNull() ?: return emptyList()
        val last = byMonth.keys.maxOrNull() ?: return emptyList()
        val rows = mutableListOf<MonthPoint>()
        var month = first
        while (!month.isAfter(last)) {
            val p = byMonth[month]
            rows += MonthPoint(
                month = month,
                easyEf = p?.easyEf,
                easyRuns = p?.easyRunCount ?: 0,
                intervalEf = p?.intervalEf,
                intervalReps = p?.intervalRepCount ?: 0,
                intervalSessions = p?.intervalSessionCount ?: 0,
            )
            month = month.plusMonths(1)
        }
        // Leading and trailing months without any efficiency only widen the axis; keep the gaps in between
        // so the line breaks instead of bridging a month without data.
        return rows.dropWhile { it.easyEf == null && it.intervalEf == null }
            .dropLastWhile { it.easyEf == null && it.intervalEf == null }
    }

    // ---------------------------------------------------------------- formatting

    private fun rangeText(p: PeriodStats): String {
        val from = p.from
        val till = p.till
        return when {
            from != null && till != null -> PerfDates.range(from, till)
            from != null -> "vanaf " + PerfDates.day(from)
            till != null -> "t/m " + PerfDates.day(till)
            else -> "alle trainingen"
        }
    }

    private fun metresValue(ef: Double?): Double? = ef?.let { it * 60 }

    private fun km(value: Double): String = Format.km(value * 1000, 1)

    private fun hours(value: Double): String = "%.1f u".format(Locale.ROOT, value)

    private fun seconds(value: Double?): String = if (value == null) "-" else "%.0f\u00A0s".format(Locale.ROOT, value)

    private fun signed(value: Int): String = if (value == 0) "0" else "%+d".format(Locale.ROOT, value)

    /** Number and unit stay together when a narrow table column wraps (non-breaking space). */
    private fun signedDecimals(value: Double, unit: String, decimals: Int): String =
        "%+.${decimals}f\u00A0%s".format(Locale.ROOT, value, unit)

    private fun changeText(changePct: Double?): String = if (changePct == null) "-" else PerformanceText.pct(changePct)

    private fun changeSuffix(changePct: Double?): String = if (changePct == null) "" else " · " + changeText(changePct)

    private fun countSuffix(diff: Int): String = if (diff == 0) "" else " · " + signed(diff)

    private fun pctChange(a: Double, b: Double): Double? = if (a > 0) (b - a) / a * 100 else null

    /** Pace difference in seconds per km, phrased as faster or slower instead of a bare number. */
    private fun paceDiff(a: Double?, b: Double?): String {
        if (a == null || b == null) return "-"
        val diff = b - a
        val rounded = abs(diff).roundToLong()
        if (rounded == 0L) return "even snel"
        return "$rounded\u00A0s/km " + if (diff < 0) "sneller" else "trager"
    }

    private fun bpmDiff(a: Double?, b: Double?): String =
        if (a == null || b == null) "-" else "%+.0f\u00A0bpm".format(Locale.ROOT, b - a)

    private fun secondsDiff(a: Double?, b: Double?): String =
        if (a == null || b == null) "-" else "%+.0f\u00A0s".format(Locale.ROOT, b - a)

    private fun intervalDetail(p: PeriodStats): String {
        if (p.intervalSessionCount == 0) return "geen sessies met hartslag"
        val repSeconds = p.intervalAvgRepSeconds?.let { " · " + seconds(it) + " per rep" } ?: ""
        return "${p.intervalSessionCount} sessies · ${p.intervalRepCount} reps$repSeconds"
    }

    private fun exclusionNote(a: PeriodStats, b: PeriodStats): String? {
        val parts = listOfNotNull(
            exclusions(a)?.let { "Periode A: $it" },
            exclusions(b)?.let { "Periode B: $it" },
        )
        return if (parts.isEmpty()) null else "Niet meegeteld. " + parts.joinToString(" · ")
    }

    private fun exclusions(p: PeriodStats): String? {
        val parts = listOf(
            p.easyRunsWithoutHr to "zonder hartslag",
            p.excludedTrailOrTreadmill to "trail of loopband",
            p.excludedRaces to "wedstrijd",
            p.excludedTempo to "tempo",
            p.excludedStrides to "versnellingen",
            p.excludedHardEfforts to "wedstrijdinspanning",
        ).filter { it.first > 0 }.map { "${it.first} ${it.second}" }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private fun repLengthNote(a: PeriodStats, b: PeriodStats): String? {
        val repA = a.intervalAvgRepSeconds
        val repB = b.intervalAvgRepSeconds
        if (repA == null || repB == null) return null
        return if (abs(repA - repB) / maxOf(repA, repB) > 0.2) {
            "Let op: de gemiddelde repduur verschilt sterk (" + seconds(repA) + " tegenover " + seconds(repB) +
                "). Interval-efficiëntie hangt af van de replengte, dus deze vergelijking is minder betrouwbaar."
        } else {
            null
        }
    }
}
