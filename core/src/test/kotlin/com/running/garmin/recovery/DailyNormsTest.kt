package com.running.garmin.recovery

import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DailyNormsTest {
    private val src = GarminSource("t", null, "t")
    private val today = LocalDate.of(2026, 3, 31)

    private fun inputs(hrv: List<GarminHrvDay>) = RecoveryInputs(hrv, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)

    @Test
    fun `every day carries the norm of the 28 days before it, same as the baseline`() {
        val days = (0 until 60).map { GarminHrvDay(today.minusDays(it.toLong()), 60, 60 + it % 5, null, null, null, null, null, null, null, src) }
        val series = days.associate { it.calendarDate to it.lastNightAvg!!.toDouble() }
        val d = DailyNorms.series(inputs(days), today.minusDays(6), today, today)
        assertEquals(7, d.hrv.size)
        d.hrv.forEach { p ->
            val b = Baseline.compute(series, p.date, Baseline.PRIMARY_WINDOW)
            assertEquals(b.median, p.median)
            assertEquals(b.robustSd, p.sd)
            assertEquals(series[p.date], p.value)
        }
    }

    @Test
    fun `without enough history there is a value but no norm`() {
        val days = (0 until 10).map { GarminHrvDay(today.minusDays(it.toLong()), 60, 62, null, null, null, null, null, null, null, src) }
        val p = DailyNorms.series(inputs(days), today, today, today).hrv.single()
        assertEquals(62.0, p.value)
        assertNull(p.median)
        assertNull(p.sd)
    }
}

class RecoveryWindowsTest {
    private val today = java.time.LocalDate.of(2026, 3, 31)

    @Test
    fun `defaults are the documented periods and minimum counts scale with the window`() {
        val d = RecoveryWindows.DEFAULT
        assertEquals(listOf(28, 7, 60, 7, 14, 60, 7), listOf(d.normDays, d.trendRecentDays, d.trendBaselineDays, d.debtNights, d.regularityNights, d.chartDays, d.tableDays))
        val series = (0 until 120).associate { today.minusDays(it.toLong()) to (60.0 + it % 4) }
        val t = Trend.evaluate(series, today, RecoveryWindows(trendRecentDays = 14, trendBaselineDays = 90))
        assertEquals(14, t.recentN)
        assertEquals(90, t.baselineN)
        assertEquals(14, t.sustainedDaysEvaluated)
        val m = MetricAnalyzer.analyze(series, today, w = RecoveryWindows(normDays = 21))
        assertEquals(21, m.primary.windowDays)
        assertEquals(listOf(7, 21, 28, 60, 90), m.windows.map { it.windowDays })
    }
}
