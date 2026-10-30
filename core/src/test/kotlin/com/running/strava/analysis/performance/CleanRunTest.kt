package com.running.strava.analysis.performance

import com.running.strava.analysis.performance.Synth.Row
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CleanRunTest {

    private fun base(n: Int = 10, vararg replace: Pair<Int, Row>): List<Row> =
        (0 until n).map { i -> replace.toMap()[i] ?: Row(1, 3.0, 150 + i % 2) }

    private fun clean(rows: List<Row>, moving: Double = rows.sumOf { it.dt }.toDouble()) =
        CleanRun.clean(Synth.fromRows(rows), moving)!!

    @Test
    fun `a clean constant stream is kept completely`() {
        val c = clean(base(100))
        assertEquals(100, c.samples.size)
        assertEquals(100.0, c.validSeconds, 1e-9)
        assertEquals(3.0, c.samples[50].speed, 1e-6)
        assertTrue(c.dropped.isEmpty())
        assertEquals(1.0, c.coverage, 1e-9)
    }

    @Test
    fun `a duplicate time stamp is dropped`() {
        val c = clean(base(10, 5 to Row(0, 0.0, 150)))
        assertEquals(9, c.samples.size)
        assertEquals(1, c.dropped["dt<=0"])
    }

    @Test
    fun `a pause longer than 30 s is dropped and a pause of exactly 30 s is kept`() {
        val gap = clean(base(10, 5 to Row(31, 93.0, 150)))
        assertEquals(1, gap.dropped["gap"])
        assertEquals(9, gap.samples.size)
        val ok = clean(base(10, 5 to Row(30, 90.0, 150)))
        assertNull(ok.dropped["gap"])
        assertEquals(10, ok.samples.size)
    }

    @Test
    fun `heart rate outside 40 to 230 is dropped, the limits themselves are kept`() {
        assertEquals(1, clean(base(10, 4 to Row(1, 3.0, 39))).dropped["hr-range"])
        assertEquals(1, clean(base(10, 4 to Row(1, 3.0, 231))).dropped["hr-range"])
        val edge = clean(base(10, 3 to Row(1, 3.0, 40), 4 to Row(1, 3.0, 230)))
        assertEquals(10, edge.samples.size)
    }

    @Test
    fun `a GPS jump above 12_5 m per s is dropped, 12_5 is kept`() {
        assertEquals(1, clean(base(10, 4 to Row(1, 12.6, 150))).dropped["gps-jump"])
        assertEquals(10, clean(base(10, 4 to Row(1, 12.5, 150))).samples.size)
    }

    @Test
    fun `standing below 0_5 m per s is dropped, 0_5 is kept`() {
        assertEquals(1, clean(base(10, 4 to Row(1, 0.4, 150))).dropped["standing"])
        assertEquals(10, clean(base(10, 4 to Row(1, 0.5, 150))).samples.size)
    }

    private fun withIdenticalRun(length: Int): List<Row> =
        (0 until 80).map { i -> if (i in 20 until 20 + length) Row(1, 3.0, 160) else Row(1, 3.0, 150 + i % 2) }

    @Test
    fun `30 identical heart rates are a strap failure, 29 are kept`() {
        val dropped = clean(withIdenticalRun(30))
        assertEquals(30, dropped.dropped["hr-dropout"])
        val kept = clean(withIdenticalRun(29))
        assertNull(kept.dropped["hr-dropout"])
        assertEquals(80, kept.samples.size)
    }

    @Test
    fun `coverage is valid time over moving time and caps at 1`() {
        val c = clean(base(89), moving = 100.0)
        assertEquals(0.89, c.coverage, 1e-9)
        assertEquals(1.0, clean(base(100), moving = 90.0).coverage, 1e-9)
    }

    @Test
    fun `unequal stream lengths use the shortest`() {
        val s = Synth.stream(100)
        val cut = s.copy(heartrate = s.heartrate!!.take(91))
        assertEquals(90, CleanRun.clean(cut, 90.0)!!.samples.size)
    }

    @Test
    fun `missing heart rate or distance gives no cleaned run`() {
        val s = Synth.stream(100)
        assertNull(CleanRun.clean(s.copy(heartrate = null), 100.0))
        assertNull(CleanRun.clean(s.copy(distance = null), 100.0))
        assertNull(CleanRun.clean(s.copy(time = null), 100.0))
    }

    @Test
    fun `a stream with 3 s steps gives the same averages as 1 Hz`() {
        val f = { t: Int -> if (t <= 300) 140 else 160 }
        val one = CleanRun.clean(Synth.stream(600, 1, { 3.0 }, f), 600.0)!!
        val three = CleanRun.clean(Synth.stream(600, 3, { 3.0 }, f), 600.0)!!
        assertEquals(Series.avgHr(one.samples)!!, Series.avgHr(three.samples)!!, 0.6)
        assertEquals(Series.avgSpeed(one.samples)!!, Series.avgSpeed(three.samples)!!, 1e-6)
        assertEquals(3.0, three.medianIntervalSeconds!!, 1e-9)
        assertEquals(600.0, three.validSeconds, 1e-9)
    }

    @Test
    fun `cadence is converted to steps per minute and zero cadence is ignored`() {
        val c = CleanRun.clean(Synth.stream(60, cadence = { if (it < 10) 0 else 85 }), 60.0)!!
        assertNull(c.samples[0].cadenceSpm)
        assertEquals(170.0, c.samples[30].cadenceSpm!!, 1e-9)
    }
}
