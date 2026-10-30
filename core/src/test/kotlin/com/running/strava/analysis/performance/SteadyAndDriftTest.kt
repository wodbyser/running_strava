package com.running.strava.analysis.performance

import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.HrZones
import com.running.strava.domain.SessionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SteadyAndDriftTest {

    private fun steady(
        seconds: Int = 2400,
        speed: (Int) -> Double = { 3.0 },
        hr: (Int) -> Int = { 150 },
        elevation: Double = 0.0,
        type: SessionType = SessionType.STEADY,
        avgHr: Double = 150.0,
        moving: Double = seconds.toDouble(),
        maxHr: Int? = 190,
    ): SteadyResult {
        val a = Synth.activity(distance = 3.0 * seconds, movingTime = seconds, elevation = elevation * 3.0 * seconds / 1000, avgHr = avgHr)
        return SteadyRun.evaluate(a, type, CleanRun.clean(Synth.stream(seconds, 1, speed, hr), moving), maxHr)
    }

    @Test
    fun `a constant run of 40 minutes is steady and its window excludes the warm-up`() {
        val r = steady(hr = { if (it <= 600) 120 else 150 })
        assertTrue(r.steady, r.failure?.text)
        assertEquals(1800.0, r.windowSeconds, 1e-9)
        assertEquals(150.0, r.avgHr!!, 0.01)
        assertEquals(3.0 / 150.0, r.ef!!, 1e-4)
    }

    @Test
    fun `a window of 29 minutes fails and exactly 30 minutes passes`() {
        assertEquals(SteadyFailure.WINDOW_SHORT, steady(seconds = 2340).failure)
        assertTrue(steady(seconds = 2400).steady)
    }

    @Test
    fun `block speed variation of 11 percent fails and 9 percent passes`() {
        fun alternating(d: Double) = { t: Int -> if (t <= 600) 3.0 else if (((t - 601) / 300) % 2 == 0) 3.0 + d else 3.0 - d }
        assertEquals(SteadyFailure.SPEED_VARIES, steady(speed = alternating(0.30)).failure)
        assertTrue(steady(speed = alternating(0.25)).steady)
    }

    @Test
    fun `elevation of 16 m per km fails and 14 passes`() {
        assertEquals(SteadyFailure.HILLY, steady(elevation = 16.0).failure)
        assertTrue(steady(elevation = 14.0).steady)
    }

    @Test
    fun `races intervals tempo and strides are not steady`() {
        listOf(SessionType.RACE, SessionType.INTERVAL, SessionType.TEMPO, SessionType.STRIDES).forEach {
            assertEquals(SteadyFailure.SESSION_TYPE, steady(type = it).failure, it.name)
        }
    }

    @Test
    fun `a hard effort at 89 percent of max HR is excluded`() {
        assertEquals(SteadyFailure.HARD_EFFORT, steady(avgHr = 170.0, maxHr = 190).failure)
        assertTrue(steady(avgHr = 150.0, maxHr = 190).steady)
        assertEquals(0.85, HrZones.HARD_EFFORT_FRACTION, 0.0)
    }

    @Test
    fun `heart rate coverage of 89 percent abstains and 90 percent is accepted`() {
        assertEquals(SteadyFailure.HR_COVERAGE, steady(moving = 2400 / 0.89).failure)
        assertTrue(steady(moving = 2400 / 0.90 - 0.001).steady)
    }

    @Test
    fun `no stream gives the reason no stream`() {
        val r = SteadyRun.evaluate(Synth.activity(), SessionType.STEADY, null, 190)
        assertEquals(SteadyFailure.NO_STREAM, r.failure)
    }

    // ---- drift and decoupling ----

    private fun window(speed2: Double = 3.0, hr2: Int = 156): List<Sample> {
        val c = CleanRun.clean(
            Synth.stream(2400, 1, { if (it <= 1500) 3.0 else speed2 }, { if (it <= 600) 130 else if (it <= 1500) 150 else hr2 }),
            2400.0,
        )!!
        return Series.afterWarmup(c.samples, 600.0)
    }

    @Test
    fun `drift of 6 bpm is 4 percent and decoupling is 3_85 percent`() {
        val d = DriftAnalysis.analyze(window(), false)!!
        assertEquals(150.0, d.hr1, 0.01)
        assertEquals(156.0, d.hr2, 0.01)
        assertEquals(6.0, d.driftBpm, 0.01)
        assertEquals(4.0, d.driftPct, 0.01)
        assertEquals(1.0, d.speedRatio, 1e-9)
        assertTrue(d.driftAvailable)
        assertEquals(3.846, d.decouplingPct, 0.02)
        assertNull(d.gapDecouplingPct)
    }

    @Test
    fun `a speed ratio of 1_06 makes drift unavailable but decoupling is still computed`() {
        val d = DriftAnalysis.analyze(window(speed2 = 3.18), false)!!
        assertEquals(1.06, d.speedRatio, 1e-6)
        assertFalse(d.driftAvailable)
        assertEquals((3.0 / 150 - 3.18 / 156) / (3.0 / 150) * 100, d.decouplingPct, 0.02)
    }

    @Test
    fun `negative drift and flat series`() {
        assertEquals(-4.0, DriftAnalysis.analyze(window(hr2 = 144), false)!!.driftPct, 0.01)
        val flat = DriftAnalysis.analyze(window(hr2 = 150), false)!!
        assertEquals(0.0, flat.driftBpm, 0.01)
        assertEquals(0.0, flat.decouplingPct, 0.02)
    }

    @Test
    fun `grade adjusted decoupling is computed from the grade stream and is null without it`() {
        val c = CleanRun.clean(
            Synth.stream(2400, 1, { 3.0 }, { if (it <= 600) 130 else if (it <= 1500) 150 else 156 }, grade = { 0f }),
            2400.0,
        )!!
        val w = Series.afterWarmup(c.samples, 600.0)
        assertEquals(3.846, DriftAnalysis.analyze(w, true)!!.gapDecouplingPct!!, 0.02)
    }

    // ---- difference trend and verdict ----

    @Test
    fun `difference trend abstains below the minimum and reports direction otherwise`() {
        assertEquals(Direction.INSUFFICIENT, DifferenceTrend.evaluate(listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 2.0, 3.0, 4.0, 5.0), 5, 1.0).direction)
        val a = listOf(2.0, 2.1, 1.9, 2.0, 2.0)
        assertEquals(Direction.HIGHER, DifferenceTrend.evaluate(a, a.map { it + 5 }, 5, 1.0).direction)
        assertEquals(Direction.LOWER, DifferenceTrend.evaluate(a.map { it + 5 }, a, 5, 1.0).direction)
        assertEquals(Direction.STABLE, DifferenceTrend.evaluate(a, a.map { it + 0.5 }, 5, 1.0).direction)
        assertEquals(Direction.UNCERTAIN, DifferenceTrend.evaluate(listOf(0.0, 4.0, 1.0, 5.0, 2.0), listOf(2.0, 6.0, 3.0, 7.0, 4.0), 5, 1.0).direction)
    }

    @Test
    fun `a later run never changes an earlier window`() {
        val today = LocalDate.of(2026, 10, 30)
        val runs = (1..6).map { efRun(0.02, today.minusDays(it * 3L)) to Stratum.ZONE_3 }
        val before = EfIndex.trend(runs, Stratum.ZONE_3, today.minusDays(7), 28)
        val after = EfIndex.trend(runs + (efRun(0.05, today) to Stratum.ZONE_3), Stratum.ZONE_3, today.minusDays(7), 28)
        assertEquals(before, after)
    }

    private fun efRun(ef: Double, date: LocalDate, hr: Double = 140.0, temp: Double? = null, id: Long = 1) =
        EfRun(id, date, ef * hr * 1800, 1800.0, hr, ef, null, temp, 2.0)

    @Test
    fun `EF trend verdicts for improved declined stable uncertain and insufficient`() {
        val today = LocalDate.of(2026, 10, 30)
        fun runs(a: List<Double>, b: List<Double>, tempA: Double? = null, tempB: Double? = null) =
            a.mapIndexed { i, ef -> efRun(ef, today.minusDays(100L + i), temp = tempA) to Stratum.ZONE_3 } +
                b.mapIndexed { i, ef -> efRun(ef, today.minusDays(5L + i), temp = tempB) to Stratum.ZONE_3 }
        val low = listOf(0.0200, 0.0201, 0.0199, 0.0200, 0.0200)
        val high = listOf(0.0230, 0.0231, 0.0229, 0.0230, 0.0230)
        assertEquals(EfVerdict.Status.IMPROVED, EfIndex.trend(runs(low, high), Stratum.ZONE_3, today, 90).status)
        assertEquals(EfVerdict.Status.DECLINED, EfIndex.trend(runs(high, low), Stratum.ZONE_3, today, 90).status)
        assertEquals(EfVerdict.Status.STABLE, EfIndex.trend(runs(low, low.map { it * 1.005 }), Stratum.ZONE_3, today, 90).status)
        val noisyA = listOf(0.018, 0.022, 0.020, 0.019, 0.021)
        val noisyB = listOf(0.0215, 0.0195, 0.0225, 0.0205, 0.0210)
        assertEquals(EfVerdict.Status.UNCERTAIN, EfIndex.trend(runs(noisyA, noisyB), Stratum.ZONE_3, today, 90).status)
        val four = EfIndex.trend(runs(low.take(4), high), Stratum.ZONE_3, today, 90)
        assertEquals(EfVerdict.Status.INSUFFICIENT_DATA, four.status)
        assertEquals(4, four.nA)
        assertEquals(5, four.nB)
    }

    @Test
    fun `a temperature gap above 8 degrees caps the verdict at uncertain`() {
        val today = LocalDate.of(2026, 10, 30)
        val low = listOf(0.0200, 0.0201, 0.0199, 0.0200, 0.0200)
        val high = listOf(0.0230, 0.0231, 0.0229, 0.0230, 0.0230)
        val runs = low.mapIndexed { i, ef -> efRun(ef, today.minusDays(100L + i), temp = 10.0) to Stratum.ZONE_3 } +
            high.mapIndexed { i, ef -> efRun(ef, today.minusDays(5L + i), temp = 20.0) to Stratum.ZONE_3 }
        val t = EfIndex.trend(runs, Stratum.ZONE_3, today, 90)
        assertEquals(EfVerdict.Status.UNCERTAIN, t.status)
        assertTrue(t.tempCapped)
        val close = runs.map { (r, s) -> r.copy(tempC = 12.0 + if (r.date.isAfter(today.minusDays(20))) 5 else 0) to s }
        assertEquals(EfVerdict.Status.IMPROVED, EfIndex.trend(close, Stratum.ZONE_3, today, 90).status)
    }

    @Test
    fun `strata are never pooled`() {
        val set = HrZoneSet.app(190)
        assertEquals(Stratum.ZONE_1_2, Stratum.of(120.0, set))
        assertEquals(Stratum.ZONE_3, Stratum.of(140.0, set))
        assertEquals(Stratum.ZONE_4_PLUS, Stratum.of(160.0, set))
        val today = LocalDate.of(2026, 10, 30)
        val mixed = (1..6).map { efRun(0.02, today.minusDays(it.toLong())) to Stratum.ZONE_3 } +
            (1..6).map { efRun(0.03, today.minusDays(it.toLong())) to Stratum.ZONE_1_2 }
        assertEquals(6, EfIndex.trend(mixed, Stratum.ZONE_3, today, 28).nB)
        assertEquals(0.02, EfIndex.trend(mixed, Stratum.ZONE_3, today, 28).pooledB!!, 1e-9)
    }

    @Test
    fun `EF of a constant 3 m per s at 150 bpm is 0_02`() {
        assertEquals(0.02, Series.ef(CleanRun.clean(Synth.stream(600), 600.0)!!.samples)!!, 1e-4)
    }
}
