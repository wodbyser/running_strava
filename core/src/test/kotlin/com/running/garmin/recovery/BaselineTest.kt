package com.running.garmin.recovery

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BaselineTest {
    private val d = LocalDate.of(2026, 10, 25)
    private fun series(vararg v: Double?, end: LocalDate = d.minusDays(1)): Map<LocalDate, Double> =
        v.reversed().mapIndexedNotNull { i, x -> x?.let { end.minusDays(i.toLong()) to it } }.toMap()

    @Test
    fun `median and robust SD by hand`() {
        val values = listOf(50.0, 52.0, 48.0, 51.0, 49.0, 53.0, 47.0)
        assertEquals(50.0, Baseline.median(values))
        // abs deviations 0 2 2 1 1 3 3, MAD 2, robust SD 1.4826 * 2
        assertEquals(2.9652, Baseline.robustSd(values), 1e-9)
        val r = Baseline.compute(series(*values.toTypedArray()), d, 7, 56.0)
        assertEquals(BaselineStatus.OK, r.status)
        assertEquals(50.0, r.median)
        assertEquals(6.0 / 2.9652, r.deviation!!, 1e-9)
        assertEquals(2.0235, r.deviation!!, 1e-4)
        assertEquals(DeviationBand.STRONG, r.band)
    }

    @Test
    fun `flat series uses the floor of 1 so deviations stay small`() {
        val r = Baseline.compute(series(*Array(7) { 50.0 }), d, 7, 53.0)
        assertEquals(1.0, r.robustSd)
        assertEquals(3.0, r.deviation!!, 1e-9)
    }

    @Test
    fun `median of an even count averages the middle values`() {
        assertEquals(2.5, Baseline.median(listOf(1.0, 2.0, 3.0, 4.0)))
    }

    @Test
    fun `the evaluated day and the future never define the baseline`() {
        val base = series(*Array(7) { 50.0 }) + (d to 90.0) + (d.plusDays(1) to 200.0)
        val r = Baseline.compute(base, d, 7)
        assertEquals(50.0, r.median)
        assertEquals(7, r.n)
        assertEquals(90.0, r.value)
    }

    @Test
    fun `minimum data boundaries`() {
        assertEquals(listOf(5, 20, 42, 63), Baseline.WINDOWS.map { Baseline.minimumCount(it) })
        // 4 of 7 abstains, 5 of 7 is ok
        assertEquals(BaselineStatus.INSUFFICIENT, Baseline.compute(series(50.0, 51.0, null, 52.0, null, 49.0, null), d, 7, 50.0).status)
        assertEquals(BaselineStatus.OK, Baseline.compute(series(50.0, 51.0, null, 52.0, null, 49.0, 48.0), d, 7, 50.0).status)
        // 19 of 28 abstains, 20 is ok
        fun days(count: Int) = (1..count).associate { d.minusDays(it.toLong()) to 50.0 + it % 3 }
        val r19 = Baseline.compute(days(19), d, 28, 50.0)
        assertEquals(BaselineStatus.INSUFFICIENT, r19.status)
        assertEquals(19, r19.n)
        assertEquals(20, r19.needed)
        assertNull(r19.deviation)
        assertEquals(BaselineStatus.OK, Baseline.compute(days(20), d, 28, 50.0).status)
    }

    @Test
    fun `nulls are dropped, not counted as zero`() {
        val r = Baseline.compute(series(50.0, null, 50.0, 50.0, null, 50.0, 50.0), d, 7, 50.0)
        assertEquals(5, r.n)
        assertEquals(50.0, r.median)
    }

    @Test
    fun `band edges`() {
        assertEquals(DeviationBand.WITHIN, Baseline.band(0.99))
        assertEquals(DeviationBand.DEVIATING, Baseline.band(1.0))
        assertEquals(DeviationBand.DEVIATING, Baseline.band(1.99))
        assertEquals(DeviationBand.STRONG, Baseline.band(2.0))
        assertEquals(DeviationBand.WITHIN, Baseline.band(-0.99))
        assertEquals(DeviationBand.DEVIATING, Baseline.band(-1.0))
        assertEquals(DeviationBand.STRONG, Baseline.band(-2.5))
    }

    @Test
    fun `a future spike changes nothing`() {
        val base = (1..40).associate { d.minusDays(it.toLong()) to 50.0 + it % 4 }
        val a = MetricAnalyzer.analyze(base + (d to 52.0), d)
        val b = MetricAnalyzer.analyze(base + (d to 52.0) + (d.plusDays(1) to 500.0) + (d.plusDays(5) to -500.0), d)
        assertEquals(a, b)
    }

    @Test
    fun `sustained deviation needs 5 of the last 7 days the same way`() {
        // flat 50 baseline with floor SD 1, so +1 is a deviation of 1
        fun run(lastSeven: List<Double>): TrendResult {
            val history = (8..40).associate { d.minusDays(it.toLong()) to 50.0 }
            val recent = lastSeven.reversed().mapIndexed { i, v -> d.minusDays(i.toLong()) to v }.toMap()
            return Trend.evaluate(history + recent, d)
        }
        assertEquals(SustainedDirection.NONE, run(listOf(52.0, 52.0, 52.0, 52.0, 50.0, 50.0, 50.0)).sustained) // 4 of 7
        assertEquals(SustainedDirection.HIGHER, run(listOf(52.0, 52.0, 52.0, 52.0, 52.0, 50.0, 50.0)).sustained) // 5 of 7
        assertEquals(SustainedDirection.LOWER, run(listOf(48.0, 48.0, 48.0, 48.0, 48.0, 50.0, 50.0)).sustained)
        assertEquals(SustainedDirection.NONE, run(listOf(52.0, 48.0, 52.0, 48.0, 52.0, 48.0, 52.0)).sustained) // mixed signs
    }

    @Test
    fun `trend abstains with fewer than 5 recent days or no 60 day base`() {
        val history = (8..70).associate { d.minusDays(it.toLong()) to 50.0 }
        val four = (0..3).associate { d.minusDays(it.toLong()) to 52.0 }
        val t = Trend.evaluate(history + four, d)
        assertEquals(BaselineStatus.INSUFFICIENT, t.status)
        assertEquals(SustainedDirection.INSUFFICIENT, t.sustained)
        val short = Trend.evaluate((0..6).associate { d.minusDays(it.toLong()) to 52.0 } + (8..20).associate { d.minusDays(it.toLong()) to 50.0 }, d)
        assertEquals(BaselineStatus.INSUFFICIENT, short.status)
    }

    @Test
    fun `trend compares the 7 day median with the 60 day baseline before those days`() {
        val history = (7..66).associate { d.minusDays(it.toLong()) to 50.0 }
        val recent = (0..6).associate { d.minusDays(it.toLong()) to 53.0 }
        val t = Trend.evaluate(history + recent, d)
        assertEquals(BaselineStatus.OK, t.status)
        assertEquals(53.0, t.recentMedian)
        assertEquals(50.0, t.baselineMedian)
        assertEquals(3.0, t.deviation!!, 1e-9)
    }
}
