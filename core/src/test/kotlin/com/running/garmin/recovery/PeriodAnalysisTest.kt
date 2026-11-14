package com.running.garmin.recovery

import com.running.garmin.domain.*
import com.running.garmin.source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PeriodAnalysisTest {
    private val mon = LocalDate.of(2026, 1, 12) // a Monday
    private val wed = mon.plusDays(2) // a Wednesday, never aligned with a calendar week
    private val windowStart = wed.minusDays(6)
    private fun hrv(date: LocalDate, v: Int) = GarminHrvDay(date, v, v, null, null, null, null, null, null, null, source())
    private fun sleep(date: LocalDate, seconds: Int?) =
        GarminSleepDay(date, seconds, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, source())
    private fun inputs(hrv: List<GarminHrvDay> = emptyList(), sleep: List<GarminSleepDay> = emptyList(), stress: List<GarminStressDay> = emptyList()) =
        RecoveryInputs(hrv, sleep, emptyList(), emptyList(), emptyList(), stress, null)

    private val windowValues = listOf(70, 72, 68, 71, 69, 73, 70)

    private fun weekInputs() = inputs(
        hrv = windowValues.mapIndexed { i, v -> hrv(windowStart.plusDays(i.toLong()), v) } +
            (0..6).map { hrv(windowStart.minusDays(7).plusDays(it.toLong()), 60) },
    )

    @Test
    fun `a week is the last 7 days and a month the last 30, whatever the weekday`() {
        assertEquals(LocalDate.of(2026, 1, 9) to LocalDate.of(2026, 1, 15), PeriodAnalysis.bounds(PeriodKind.WEEK, LocalDate.of(2026, 1, 15)))
        assertEquals(LocalDate.of(2026, 1, 12) to LocalDate.of(2026, 1, 18), PeriodAnalysis.bounds(PeriodKind.WEEK, LocalDate.of(2026, 1, 18))) // Sunday
        assertEquals(LocalDate.of(2026, 1, 12) to LocalDate.of(2026, 2, 10), PeriodAnalysis.bounds(PeriodKind.MONTH, LocalDate.of(2026, 2, 10)))
        assertEquals(7, PeriodAnalysis.windowDays(PeriodKind.WEEK))
        assertEquals(30, PeriodAnalysis.windowDays(PeriodKind.MONTH))
    }

    @Test
    fun `window median and difference with the 7 days before by hand`() {
        val r = PeriodAnalysis.analyze(weekInputs(), PeriodKind.WEEK, wed, LocalDate.of(2026, 1, 25), 450)
        assertEquals(7, r.windowDays)
        assertEquals(windowStart, r.from)
        assertEquals(wed, r.to)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(BaselineStatus.OK, m.status)
        assertEquals(70.0, m.median) // 68 69 70 70 71 72 73
        assertEquals(68.0, m.min)
        assertEquals(73.0, m.max)
        assertEquals(60.0, m.previousMedian)
        assertEquals(10.0, m.difference!!, 1e-9)
        assertEquals(7, r.days.size)
    }

    @Test
    fun `the window always ends on the chosen day, also mid week`() {
        val r = PeriodAnalysis.analyze(weekInputs(), PeriodKind.WEEK, wed, wed, 450)
        assertEquals(7, r.windowDays)
        assertEquals(7, r.days.size) // never a shorter running week
        assertEquals(wed, r.to)
        assertEquals(windowStart.minusDays(7), r.previousFrom)
        assertEquals(windowStart.minusDays(1), r.previousTo)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(70.0, m.median) // 70 72 68 71 69 73 70
        assertEquals(60.0, m.previousMedian)
    }

    @Test
    fun `abstains below 70 percent of the days and never fills missing days`() {
        val four = (0..3).map { hrv(windowStart.plusDays(it.toLong()), 70) }
        val r = PeriodAnalysis.analyze(inputs(hrv = four), PeriodKind.WEEK, wed, LocalDate.of(2026, 1, 25), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(BaselineStatus.INSUFFICIENT, m.status)
        assertEquals(4, m.n)
        assertEquals(5, m.needed)
        assertNull(m.median)
        assertNull(m.difference)
        val five = PeriodAnalysis.analyze(inputs(hrv = four + hrv(windowStart.plusDays(4), 70)), PeriodKind.WEEK, wed, LocalDate.of(2026, 1, 25), 450)
        assertEquals(BaselineStatus.OK, five.metrics.first { it.kind == PeriodMetricKind.HRV }.status)
    }

    @Test
    fun `no difference when the previous period has too little data`() {
        val r = PeriodAnalysis.analyze(inputs(hrv = windowValues.mapIndexed { i, v -> hrv(windowStart.plusDays(i.toLong()), v) }), PeriodKind.WEEK, wed, LocalDate.of(2026, 1, 25), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(70.0, m.median)
        assertNull(m.difference)
        assertEquals(0, m.previousN)
    }

    @Test
    fun `week sleep debt by hand`() {
        // target 450: 480 420 450 400 500 430 460 -> shortfall 100 min, net -10 min
        val nights = listOf(480, 420, 450, 400, 500, 430, 460).mapIndexed { i, m -> sleep(windowStart.plusDays(i.toLong()), m * 60) }
        val r = PeriodAnalysis.analyze(inputs(sleep = nights), PeriodKind.WEEK, wed, LocalDate.of(2026, 1, 25), 450)
        assertEquals(100L * 60, r.sleepDebt.shortfallSeconds)
        assertEquals(3, r.sleepDebt.nightsBelowTarget)
        assertEquals(-10L * 60, r.sleepDebt.netBalanceSeconds)
        assertEquals(450.0, r.metrics.first { it.kind == PeriodMetricKind.SLEEP_DURATION }.median) // 400 420 430 450 460 480 500
    }

    @Test
    fun `a month window keeps 30 days even at the start of the month`() {
        val end = LocalDate.of(2026, 3, 2)
        val nights = (0..29).map { sleep(end.minusDays(it.toLong()), 450 * 60) }
        val r = PeriodAnalysis.analyze(inputs(sleep = nights), PeriodKind.MONTH, end, end, 450)
        assertEquals(30, r.windowDays)
        assertEquals(LocalDate.of(2026, 2, 1), r.from)
        assertEquals(30, r.days.size)
        assertEquals(30, r.sleepDebt.nightsWithData)
    }

    @Test
    fun `stress of today is left out`() {
        fun st(d: LocalDate, avg: Int) = GarminStressDay(d, avg, null, null, null, null, null, null, null, null, source())
        val days = (0..2).map { st(windowStart.plusDays(it.toLong()), 30) }
        val r = PeriodAnalysis.analyze(inputs(stress = days), PeriodKind.WEEK, windowStart.plusDays(2), today = windowStart.plusDays(2), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.STRESS_AVG }
        assertEquals(2, m.n) // the third day is today and is excluded
        assertEquals(BaselineStatus.INSUFFICIENT, m.status)
        assertNull(r.days.last().stressAvg)
    }
}
