package com.running.garmin.recovery

import com.running.garmin.domain.*
import com.running.garmin.source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PeriodAnalysisTest {
    private val mon = LocalDate.of(2026, 1, 12) // a Monday
    private fun hrv(date: LocalDate, v: Int) = GarminHrvDay(date, v, v, null, null, null, null, null, null, null, source())
    private fun sleep(date: LocalDate, seconds: Int?) =
        GarminSleepDay(date, seconds, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, source())
    private fun inputs(hrv: List<GarminHrvDay> = emptyList(), sleep: List<GarminSleepDay> = emptyList(), stress: List<GarminStressDay> = emptyList()) =
        RecoveryInputs(hrv, sleep, emptyList(), emptyList(), emptyList(), stress, null)

    private val thisWeek = listOf(70, 72, 68, 71, 69, 73, 70)

    private fun weekInputs() = inputs(
        hrv = thisWeek.mapIndexed { i, v -> hrv(mon.plusDays(i.toLong()), v) } + (0..6).map { hrv(mon.minusDays(7).plusDays(it.toLong()), 60) },
    )

    @Test
    fun `bounds of a week and a month`() {
        assertEquals(mon to mon.plusDays(6), PeriodAnalysis.bounds(PeriodKind.WEEK, LocalDate.of(2026, 1, 15)))
        assertEquals(mon to mon.plusDays(6), PeriodAnalysis.bounds(PeriodKind.WEEK, LocalDate.of(2026, 1, 18))) // Sunday
        assertEquals(LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28), PeriodAnalysis.bounds(PeriodKind.MONTH, LocalDate.of(2026, 2, 10)))
    }

    @Test
    fun `full week median and difference with the previous week by hand`() {
        val r = PeriodAnalysis.analyze(weekInputs(), PeriodKind.WEEK, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 25), 450)
        assertFalse(r.partial)
        assertEquals(7, r.elapsedDays)
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
    fun `current week is partial and compares the same number of days`() {
        val r = PeriodAnalysis.analyze(weekInputs(), PeriodKind.WEEK, LocalDate.of(2026, 1, 14), LocalDate.of(2026, 1, 14), 450)
        assertTrue(r.partial)
        assertEquals(3, r.elapsedDays)
        assertEquals(LocalDate.of(2026, 1, 14), r.to)
        assertEquals(LocalDate.of(2026, 1, 5), r.previousFrom)
        assertEquals(LocalDate.of(2026, 1, 7), r.previousTo)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(70.0, m.median) // 70 72 68
        assertEquals(60.0, m.previousMedian)
    }

    @Test
    fun `abstains below 70 percent of the days and never fills missing days`() {
        val four = (0..3).map { hrv(mon.plusDays(it.toLong()), 70) }
        val r = PeriodAnalysis.analyze(inputs(hrv = four), PeriodKind.WEEK, mon, LocalDate.of(2026, 1, 25), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(BaselineStatus.INSUFFICIENT, m.status)
        assertEquals(4, m.n)
        assertEquals(5, m.needed)
        assertNull(m.median)
        assertNull(m.difference)
        val five = PeriodAnalysis.analyze(inputs(hrv = four + hrv(mon.plusDays(4), 70)), PeriodKind.WEEK, mon, LocalDate.of(2026, 1, 25), 450)
        assertEquals(BaselineStatus.OK, five.metrics.first { it.kind == PeriodMetricKind.HRV }.status)
    }

    @Test
    fun `no difference when the previous period has too little data`() {
        val r = PeriodAnalysis.analyze(inputs(hrv = thisWeek.mapIndexed { i, v -> hrv(mon.plusDays(i.toLong()), v) }), PeriodKind.WEEK, mon, LocalDate.of(2026, 1, 25), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.HRV }
        assertEquals(70.0, m.median)
        assertNull(m.difference)
        assertEquals(0, m.previousN)
    }

    @Test
    fun `week sleep debt by hand`() {
        // target 450: 480 420 450 400 500 430 460 -> shortfall 100 min, net -10 min
        val nights = listOf(480, 420, 450, 400, 500, 430, 460).mapIndexed { i, m -> sleep(mon.plusDays(i.toLong()), m * 60) }
        val r = PeriodAnalysis.analyze(inputs(sleep = nights), PeriodKind.WEEK, mon, LocalDate.of(2026, 1, 25), 450)
        assertEquals(100L * 60, r.sleepDebt.shortfallSeconds)
        assertEquals(3, r.sleepDebt.nightsBelowTarget)
        assertEquals(-10L * 60, r.sleepDebt.netBalanceSeconds)
        assertEquals(450.0, r.metrics.first { it.kind == PeriodMetricKind.SLEEP_DURATION }.median) // 400 420 430 450 460 480 500
    }

    @Test
    fun `stress of today is left out`() {
        fun st(d: LocalDate, avg: Int) = GarminStressDay(d, avg, null, null, null, null, null, null, null, null, source())
        val days = (0..2).map { st(mon.plusDays(it.toLong()), 30) }
        val r = PeriodAnalysis.analyze(inputs(stress = days), PeriodKind.WEEK, mon, today = mon.plusDays(2), 450)
        val m = r.metrics.first { it.kind == PeriodMetricKind.STRESS_AVG }
        assertEquals(2, m.n) // 3 elapsed days need 3, the third (today) is excluded
        assertEquals(BaselineStatus.INSUFFICIENT, m.status)
        assertNull(r.days.last().stressAvg)
    }
}
