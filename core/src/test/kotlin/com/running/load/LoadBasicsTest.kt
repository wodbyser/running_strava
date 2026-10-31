package com.running.load

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.exp

class TrimpTest {
    private val ref = HrReference(200, 60, "test")

    @Test
    fun `60 minutes at x = 0,6 gives 72,9 with b = 1,92`() {
        // HR 144 is x = (144 - 60) / 140 = 0.6
        val r = Trimp.fromStream(T.constantStream(3600, 144), 3600.0, ref, 1.92)!!
        assertEquals(60 * 0.6 * 0.64 * exp(1.92 * 0.6), r.first, 1e-6)
        assertEquals(72.9, r.first, 0.1)
        assertEquals(1.0, r.second, 1e-9)
    }

    @Test
    fun `women's coefficient 1,67 gives a lower value`() {
        val r = Trimp.fromStream(T.constantStream(3600, 144), 3600.0, ref, 1.67)!!
        assertEquals(60 * 0.6 * 0.64 * exp(1.67 * 0.6), r.first, 1e-6)
        assertTrue(r.first < 72.9)
    }

    @Test
    fun `intensity is clamped to 0 and 1`() {
        assertEquals(0.0, ref.intensity(50.0))
        assertEquals(1.0, ref.intensity(230.0))
        assertEquals(0.0, Trimp.perMinute(0.0, 1.92))
        // a minute at the maximum: 1 * 0.64 * e^1.92
        assertEquals(0.64 * exp(1.92), Trimp.perMinute(1.0, 1.92), 1e-9)
        val above = Trimp.fromStream(T.constantStream(60, 230), 60.0, ref, 1.92)!!
        assertEquals(0.64 * exp(1.92), above.first, 1e-6)
    }

    @Test
    fun `a gap of more than 30 s is not interpolated`() {
        val s = T.stream(listOf(0, 10, 20, 100, 110), listOf(144, 144, 144, 144, 144))
        val (trimp, coverage) = Trimp.fromStream(s, 110.0, ref, 1.92)!!
        // valid intervals: 10 + 10 + 10 s (the 80 s gap is dropped)
        assertEquals(30.0 / 60 * Trimp.perMinute(0.6, 1.92), trimp, 1e-9)
        assertEquals(30.0 / 110, coverage, 1e-9)
    }

    @Test
    fun `3 s samples give the same load as 1 s samples`() {
        val a = Trimp.fromStream(T.constantStream(1800, 150, 1), 1800.0, ref, 1.92)!!.first
        val b = Trimp.fromStream(T.constantStream(1800, 150, 3), 1800.0, ref, 1.92)!!.first
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun `heart rate outside 40 to 230 is dropped`() {
        val s = T.stream(listOf(0, 1, 2, 3), listOf(144, 30, 250, 144))
        val (trimp, _) = Trimp.fromStream(s, 3.0, ref, 1.92)!!
        assertEquals(1.0 / 60 * Trimp.perMinute(0.6, 1.92), trimp, 1e-9)
    }

    @Test
    fun `below 80 percent coverage the average heart rate fallback is used and flagged`() {
        // half of the moving time has valid HR
        val s = T.constantStream(1800, 144)
        val r = Trimp.compute(s, 144.0, 3600, ref)
        assertEquals(TrimpSource.AVERAGE, r.source)
        assertEquals(60 * Trimp.perMinute(0.6, 1.92), r.trimp!!, 1e-9)
        assertEquals(0.5, r.coverage, 1e-9)
    }

    @Test
    fun `enough coverage uses the stream`() {
        val r = Trimp.compute(T.constantStream(3600, 144), 144.0, 3600, ref)
        assertEquals(TrimpSource.STREAM, r.source)
    }

    @Test
    fun `an activity without heart rate gives unknown, not 0`() {
        val r = Trimp.compute(null, null, 3600, ref)
        assertNull(r.trimp)
        assertEquals(TrimpSource.NONE, r.source)
    }
}

class DailyLoadTest {
    private val d = T.D0

    @Test
    fun `rest day is 0 and the days before the first heart rate activity are unknown`() {
        val sessions = listOf(T.session(1, d.plusDays(2), trimp = 80.0))
        val first = DailyLoad.firstKnown(sessions)
        assertEquals(d.plusDays(2), first)
        val days = DailyLoad.build(sessions, first, d, d.plusDays(4))
        assertNull(days[0].trimp)
        assertNull(days[1].trimp)
        assertEquals(80.0, days[2].trimp)
        assertEquals(0.0, days[3].trimp)
        assertEquals(0.0, days[4].trimp)
    }

    @Test
    fun `two sessions on one day are summed and flagged`() {
        val sessions = listOf(
            T.session(1, d, SessionClass.EASY, 50.0),
            T.session(2, d, SessionClass.INTERVAL, 70.0),
            T.session(3, d, SessionClass.OTHER, 10.0, isRun = false),
        )
        val day = DailyLoad.build(sessions, d, d, d).single()
        assertEquals(130.0, day.trimp)
        assertEquals(120.0, day.runTrimp)
        assertEquals(10.0, day.otherTrimp)
        assertTrue(day.multiple)
        assertEquals(SessionClass.INTERVAL, day.cls)
        assertEquals(2, day.runCount)
        assertEquals(1, day.otherCount)
    }

    @Test
    fun `a day with only a session without heart rate is unknown`() {
        val sessions = listOf(T.session(1, d, trimp = 60.0), T.session(2, d.plusDays(1), trimp = null))
        val days = DailyLoad.build(sessions, d, d, d.plusDays(1))
        assertEquals(60.0, days[0].trimp)
        assertNull(days[1].trimp)
        assertEquals(1, days[1].withoutHrCount)
    }

    @Test
    fun `hard sessions are by class or above the own 90th percentile`() {
        val easy = (0 until 20).map { T.session(it.toLong(), d.plusDays(it.toLong()), trimp = 50.0 + it) }
        val big = T.session(100, d.plusDays(25), trimp = 500.0)
        val tempo = T.session(101, d.plusDays(26), SessionClass.TEMPO, 40.0)
        val ids = HardSessions.ids(easy + big + tempo)
        assertTrue(100L in ids)
        assertTrue(101L in ids)
        assertFalse(5L in ids)
    }
}

class RollingTest {
    private val end = LocalDate.of(2026, 3, 1)

    @Test
    fun `rolling sums by hand`() {
        val daily = (0 until 28).associate { end.minusDays(it.toLong()) to if (it < 7) 10.0 else 5.0 }
        val acute = Rolling.window(daily, end, 7)
        val chronic = Rolling.window(daily, end, 28)
        assertEquals(70.0, acute.sum)
        assertEquals(70.0, acute.perWeek)
        assertEquals(70.0 + 21 * 5.0, chronic.sum)
        assertEquals((70.0 + 105.0) / 4, chronic.perWeek!!, 1e-9)
        // 70 against 43.75 = +60 %
        assertEquals(60.0, Rolling.differencePct(acute, chronic)!!, 1e-9)
    }

    @Test
    fun `70 percent known days rule`() {
        val five = (0 until 5).associate { end.minusDays(it.toLong()) to 10.0 }
        assertEquals(50.0, Rolling.window(five, end, 7).sum) // 5 of 7 = 71 %
        val four = (0 until 4).associate { end.minusDays(it.toLong()) to 10.0 }
        assertNull(Rolling.window(four, end, 7).sum) // 4 of 7 = 57 %
    }

    @Test
    fun `exponential average with tau 7 by hand`() {
        val from = LocalDate.of(2026, 3, 1)
        val daily = mapOf(from to 100.0, from.plusDays(1) to 0.0, from.plusDays(2) to 0.0)
        val s = Ewma.series(daily, from, from.plusDays(2), 7.0)
        val a = 1 - exp(-1.0 / 7)
        assertEquals(100 * a, s.getValue(from), 1e-9)
        assertEquals(100 * a * exp(-1.0 / 7), s.getValue(from.plusDays(1)), 1e-9)
        assertEquals(100 * a * exp(-2.0 / 7), s.getValue(from.plusDays(2)), 1e-9)
    }

    @Test
    fun `own band is relative to the own history and needs enough of it`() {
        val history = listOf(100.0, 110.0, 90.0, 105.0, 95.0, 100.0, 120.0, 80.0)
        assertEquals(OwnLevel.HIGH, OwnBand.evaluate(history, 200.0, 8).level)
        assertEquals(OwnLevel.NORMAL, OwnBand.evaluate(history, 101.0, 8).level)
        assertEquals(OwnLevel.LOW, OwnBand.evaluate(history, 10.0, 8).level)
        assertEquals(OwnLevel.UNKNOWN, OwnBand.evaluate(history.take(5), 101.0, 8).level)
    }
}

class MonotonyTest {
    @Test
    fun `monotony and strain by hand`() {
        // days 0, 50, 0, 80, 0, 120, 0: mean 35.714, population SD 45.31
        val (m, note) = Weekly.monotony(listOf(0.0, 50.0, 0.0, 80.0, 0.0, 120.0, 0.0))
        assertNull(note)
        assertEquals(0.788, m!!, 0.001)
        assertEquals(197.1, 250.0 * m, 0.1)
    }

    @Test
    fun `seven identical days give unavailable, not infinite`() {
        val (m, note) = Weekly.monotony(List(7) { 40.0 })
        assertNull(m)
        assertEquals("geen variatie", note)
        assertNull(Weekly.monotony(List(7) { 0.0 }).first)
    }

    @Test
    fun `an incomplete week is excluded`() {
        val d = T.D0
        val sessions = (0 until 7).map { T.session(it.toLong(), d.plusDays(it.toLong()), trimp = 10.0 + it * 10) }
        val days = DailyLoad.build(sessions, d, d, d.plusDays(6))
        val complete = Weekly.build(days, d.plusDays(7)).single()
        assertTrue(complete.complete)
        assertNotNull(complete.monotony)
        // today inside the week: the week is not finished
        val incomplete = Weekly.build(days, d.plusDays(3)).single()
        assertFalse(incomplete.complete)
        assertNull(incomplete.monotony)
        assertNull(incomplete.strain)
    }
}

class ProgressionTest {
    private val d = T.D0

    @Test
    fun `week over week change and change against the mean of 4 weeks`() {
        val weeks = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 150.0).mapIndexed { i, t -> T.week(d.plusWeeks(i.toLong()), t) }
        val rows = Progression.rows(weeks)
        val last = rows.last()
        assertEquals(50.0, last.changePct!!, 1e-9)
        assertEquals(50.0, last.vsMean4Pct!!, 1e-9)
        assertTrue(last.enough)
        assertNull(last.rank) // fewer than 8 earlier changes
        assertFalse(rows.first().enough)
    }

    @Test
    fun `the week after a break is flagged`() {
        val weeks = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 20.0, 100.0).mapIndexed { i, t -> T.week(d.plusWeeks(i.toLong()), t) }
        val rows = Progression.rows(weeks)
        assertTrue(rows.last().afterBreak)
        assertFalse(rows[5].afterBreak)
    }

    @Test
    fun `rank is the share of the owner's own earlier changes at most this one`() {
        val trimps = listOf(100.0, 102.0, 100.0, 104.0, 100.0, 103.0, 100.0, 101.0, 100.0, 102.0, 100.0, 200.0)
        val rows = Progression.rows(trimps.mapIndexed { i, t -> T.week(d.plusWeeks(i.toLong()), t) })
        assertEquals(1.0, rows.last().rank!!, 1e-9)
    }
}

class WeeklyKilojouleTest {
    @Test
    fun `kilojoules are summed over runs with a power meter and null without`() {
        val d = T.D0
        val with = LoadSession(1, d, "a", "Run", true, SessionClass.EASY, 3600, 10000.0, 0.0, null, null, 100.0, TrimpSource.STREAM, 500.0)
        val without = T.session(2, d.plusDays(1))
        val days = DailyLoad.build(listOf(with, without), d, d, d.plusDays(6))
        assertEquals(500.0, Weekly.build(days, d.plusDays(7)).single().kilojoules)
        val none = DailyLoad.build(listOf(without), d, d, d.plusDays(6))
        assertNull(Weekly.build(none, d.plusDays(7)).single().kilojoules)
    }
}
