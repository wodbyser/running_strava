package com.running.garmin.recovery

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class PeriodKind { WEEK, MONTH }

enum class PeriodMetricKind { HRV, RESTING_HR, SLEEP_DURATION, BODY_BATTERY_WAKE, STRESS_AVG }

/** Median of the days of the period with data, set beside the same number of days of the previous period. Only differences, no judgement. */
data class PeriodMetric(
    val kind: PeriodMetricKind,
    val status: BaselineStatus,
    val n: Int,
    val needed: Int,
    val median: Double?,
    val min: Double?,
    val max: Double?,
    val previousN: Int,
    val previousMedian: Double?,
    val difference: Double?,
)

data class PeriodDay(val date: LocalDate, val hrv: Int?, val restingHr: Int?, val sleepMinutes: Int?, val bodyBatteryWake: Int?, val stressAvg: Int?)

data class PeriodSleepDebt(
    val status: BaselineStatus,
    val nightsWithData: Int,
    val needed: Int,
    val targetSeconds: Int,
    val shortfallSeconds: Long?,
    val nightsBelowTarget: Int?,
    val netBalanceSeconds: Long?,
    val target: SleepTargetCheck? = null,
)

data class PeriodReport(
    val algorithmVersion: String,
    val kind: PeriodKind,
    val from: LocalDate,
    val to: LocalDate,
    val fullPeriodEnd: LocalDate,
    val elapsedDays: Int,
    val partial: Boolean,
    val previousFrom: LocalDate,
    val previousTo: LocalDate,
    val metrics: List<PeriodMetric>,
    val sleepDebt: PeriodSleepDebt,
    val readiness: Readiness,
    val days: List<PeriodDay>,
    /** The days of the period with their rolling own norm, for charts. */
    val daily: DailySeries = DailySeries.EMPTY,
    val windows: RecoveryWindows = RecoveryWindows.DEFAULT,
)

/** Week view (Monday to Sunday) and month view (calendar month) over the stored rows. Computed on demand. */
object PeriodAnalysis {
    const val HISTORY_DAYS_BACK = 62

    fun bounds(kind: PeriodKind, date: LocalDate): Pair<LocalDate, LocalDate> = when (kind) {
        PeriodKind.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        PeriodKind.MONTH -> date.withDayOfMonth(1).let { it to it.with(TemporalAdjusters.lastDayOfMonth()) }
    }

    fun previousStart(kind: PeriodKind, from: LocalDate): LocalDate = when (kind) {
        PeriodKind.WEEK -> from.minusWeeks(1)
        PeriodKind.MONTH -> from.minusMonths(1)
    }

    fun analyze(
        inputs: RecoveryInputs,
        kind: PeriodKind,
        date: LocalDate,
        today: LocalDate,
        sleepTargetMinutes: Int,
        w: RecoveryWindows = RecoveryWindows.DEFAULT,
    ): PeriodReport {
        val (from, fullEnd) = bounds(kind, date)
        val to = minOf(fullEnd, today)
        val elapsed = (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)
        val prevFrom = previousStart(kind, from)
        val prevTo = prevFrom.plusDays(elapsed - 1L)

        val hrv = inputs.hrv.toSeries { it.lastNightAvg }
        val rhr = inputs.restingHr.toSeries { it.restingHr }
        val sleepMin = inputs.sleep.toSeries { it.sleepTimeSeconds?.let { s -> s / 60.0 } }
        val wake = BodyBatteryAnalysis.wakeLevels(inputs.bodyBatterySamples, inputs.sleep).mapValues { it.value.toDouble() }
        val stress = inputs.stress.filter { it.calendarDate != today }.toSeries { it.avgLevel }

        fun values(series: Map<LocalDate, Double>, a: LocalDate, b: LocalDate) =
            generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.mapNotNull { series[it] }.toList()

        val needed = Baseline.minimumCount(elapsed)
        fun metric(k: PeriodMetricKind, series: Map<LocalDate, Double>): PeriodMetric {
            val cur = values(series, from, to)
            val prev = values(series, prevFrom, prevTo)
            val ok = cur.size >= needed
            val median = if (ok) Baseline.median(cur) else null
            val prevMedian = if (prev.size >= needed) Baseline.median(prev) else null
            return PeriodMetric(
                k, if (ok) BaselineStatus.OK else BaselineStatus.INSUFFICIENT, cur.size, needed, median,
                if (ok) cur.min() else null, if (ok) cur.max() else null, prev.size, prevMedian,
                if (median != null && prevMedian != null) median - prevMedian else null,
            )
        }

        val target = sleepTargetMinutes * 60
        val nights = inputs.sleep.filter { it.calendarDate in from..to && it.sleepTimeSeconds != null }.map { it.sleepTimeSeconds!! }
        val shortfall = nights.sumOf { maxOf(0, target - it).toLong() }
        val debt = if (nights.size >= needed) PeriodSleepDebt(
            BaselineStatus.OK, nights.size, needed, target, shortfall,
            nights.count { it < target }, nights.sumOf { (it - target).toLong() }, SleepAnalysis.targetCheck(shortfall, nights.size, target),
        ) else PeriodSleepDebt(BaselineStatus.INSUFFICIENT, nights.size, needed, target, null, null, null)

        val hrvByDate = inputs.hrv.associateBy { it.calendarDate }
        val rhrByDate = inputs.restingHr.associateBy { it.calendarDate }
        val sleepByDate = inputs.sleep.associateBy { it.calendarDate }
        val stressByDate = inputs.stress.associateBy { it.calendarDate }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map {
            PeriodDay(
                it, hrvByDate[it]?.lastNightAvg, rhrByDate[it]?.restingHr, sleepByDate[it]?.sleepTimeSeconds?.let { s -> s / 60 },
                wake[it]?.toInt(), if (it == today) null else stressByDate[it]?.avgLevel,
            )
        }.toList()

        val metrics = listOf(
            metric(PeriodMetricKind.HRV, hrv), metric(PeriodMetricKind.RESTING_HR, rhr), metric(PeriodMetricKind.SLEEP_DURATION, sleepMin),
            metric(PeriodMetricKind.BODY_BATTERY_WAKE, wake), metric(PeriodMetricKind.STRESS_AVG, stress),
        )
        // Readiness of the period: the period median against the 28 days before the period, same bands as the day view.
        fun comp(kind: ReadinessKind, pk: PeriodMetricKind, series: Map<LocalDate, Double>): ReadinessComponent {
            val value = metrics.first { it.kind == pk }.median
            val b = Baseline.fromValues(Baseline.windowBefore(series, from, w.normDays), w.normDays, Baseline.minimumCount(w.normDays), value)
            return ReadinessComponent(kind, b.status == BaselineStatus.OK && b.deviation != null, value, b.median, b.deviation, b.band, b.n)
        }
        val readiness = ReadinessAnalysis.fromComponents(
            listOf(
                comp(ReadinessKind.HRV, PeriodMetricKind.HRV, hrv), comp(ReadinessKind.RESTING_HR, PeriodMetricKind.RESTING_HR, rhr),
                comp(ReadinessKind.SLEEP, PeriodMetricKind.SLEEP_DURATION, sleepMin), comp(ReadinessKind.BODY_BATTERY, PeriodMetricKind.BODY_BATTERY_WAKE, wake),
            ),
            debt.target,
        )

        return PeriodReport(
            RecoveryAlgorithm.VERSION, kind, from, to, fullEnd, elapsed, to.isBefore(fullEnd), prevFrom, prevTo, metrics, debt, readiness, days,
            DailyNorms.series(inputs, from, to, today, w.normDays),
            w,
        )
    }
}
