package com.running.load

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Random

class DeloadTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun weeks(vararg trimp: Double, runs: (Int) -> Int = { 3 }) =
        trimp.mapIndexed { i, t -> T.week(d.plusWeeks(i.toLong()), t, runs(i)) }

    @Test
    fun `detection boundary 70 versus 71 percent`() {
        val w70 = weeks(100.0, 100.0, 100.0, 100.0, 70.0)
        val w71 = weeks(100.0, 100.0, 100.0, 100.0, 71.0)
        assertEquals(1, Deload.detect(w70, 0.70).size)
        assertEquals(0, Deload.detect(w71, 0.70).size)
        assertEquals(70.0, Deload.detect(w70, 0.70).single().third, 1e-9)
    }

    @Test
    fun `a week after a low week is not a deload because the weeks before must be normal`() {
        // prior weeks 50, 100, 100, 100: one below 80 % of the mean (87.5 -> 70)
        assertEquals(0, Deload.detect(weeks(100.0, 100.0, 100.0, 50.0, 50.0), 0.70).size)
    }

    @Test
    fun `a week without a run is a break, not a deload`() {
        val w = weeks(100.0, 100.0, 100.0, 100.0, 0.0) { if (it == 4) 0 else 3 }
        assertEquals(0, Deload.detect(w, 0.70).size)
    }

    @Test
    fun `an incomplete week is never a deload`() {
        val w = weeks(100.0, 100.0, 100.0, 100.0).plus(T.week(d.plusWeeks(4), 10.0, complete = false))
        assertEquals(0, Deload.detect(w, 0.70).size)
    }

    @Test
    fun `2 deloads are listed without summary and 3 are summarised`() {
        val pattern = doubleArrayOf(100.0, 100.0, 100.0, 100.0, 60.0, 100.0, 100.0, 100.0, 100.0, 60.0, 100.0, 100.0, 100.0, 100.0, 60.0)
        val series = RecoverySeries(T.series(d, 200) { 50.0 }, T.series(d, 200) { 50.0 }, T.series(d, 200) { 450.0 }, T.series(d, 200) { 80.0 })
        val two = Deload.analyze(weeks(*pattern.copyOf(10)), series, emptyList(), LoadWindows.DEFAULT, cfg)
        assertEquals(2, two.weeks.size)
        assertNull(two.summary)
        val three = Deload.analyze(weeks(*pattern), series, emptyList(), LoadWindows.DEFAULT, cfg)
        assertEquals(3, three.weeks.size)
        assertEquals(3, three.summary!!.n)
        assertNull(three.summary!!.medianEfChange) // no efficiency points
    }

    @Test
    fun `recovery change in the deload week and efficiency after`() {
        val pattern = doubleArrayOf(100.0, 100.0, 100.0, 100.0, 60.0)
        val deloadStart = d.plusWeeks(4)
        val hrv = T.series(d, 200) { 50.0 }.toMutableMap()
        // last 3 days of the deload week are 4 ms higher
        (4..6).forEach { hrv[deloadStart.plusDays(it.toLong())] = 54.0 }
        val series = RecoverySeries(hrv, emptyMap(), emptyMap(), emptyMap())
        val before = (1..4).map { PerfPoint(it.toLong(), deloadStart.minusDays(it * 5L), "b", 100.0) }
        val after = (1..3).map { PerfPoint(10L + it, deloadStart.plusDays(6L + it), "a", 103.0) }
        val r = Deload.analyze(weeks(*pattern), series, before + after, LoadWindows.DEFAULT, cfg)
        val w = r.weeks.single()
        val h = w.changes.first { it.metric == ResponseMetric.HRV }
        assertEquals(4.0, h.change!!, 1e-9)
        assertEquals(4.0 / 1.0, h.changeZ!!, 1e-9) // flat norm: SD floor 1
        assertEquals(3.0, w.efChange!!, 1e-9)
        assertEquals(4, w.efBeforeN)
        assertEquals(3, w.efAfterN)
    }

    @Test
    fun `efficiency with fewer than 3 points per side is not reported`() {
        val deloadStart = d.plusWeeks(4)
        val pts = listOf(PerfPoint(1, deloadStart.minusDays(3), "b", 100.0), PerfPoint(2, deloadStart.plusDays(8), "a", 100.0))
        val r = Deload.analyze(weeks(100.0, 100.0, 100.0, 100.0, 60.0), RecoverySeries.EMPTY, pts, LoadWindows.DEFAULT, cfg)
        assertNull(r.weeks.single().efChange)
    }
}

class FatigueAdjustedTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    @Test
    fun `too few steady runs abstain with the count`() {
        val pts = (0 until 10).map { PerfPoint(it.toLong(), d.plusDays(it * 3L), "r", 100.0) }
        val r = FatigueAdjusted.analyze(pts, emptyMap(), emptyMap(), cfg)
        assertFalse(r.enough)
        assertTrue(r.reason!!.contains("minimum 30"))
    }

    @Test
    fun `runs after a heavy load are lower than the own level`() {
        // 30 day cycle: days 0-2 very heavy, 10-12 moderate, the rest light; one steady run on day 3, 13 and 23 of each cycle
        val daily = (0 until 500).associate { d.plusDays(it.toLong()) to when (it % 30) { in 0..2 -> 300.0; in 10..12 -> 100.0; else -> 20.0 } }
        val pts = (0 until 16).flatMap { c ->
            listOf(3 to 95.0, 13 to 98.0, 23 to 100.0).map { (day, v) -> PerfPoint(c * 100L + day, d.plusDays(c * 30L + day), "r", v) }
        }.filter { !it.date.isBefore(d.plusDays(30)) }
        val deviating = pts.associate { it.date to (if (it.date.dayOfMonth % 2 == 0) 2 else 0) as Int? }
        val r = FatigueAdjusted.analyze(pts, daily, deviating, cfg)
        assertTrue(r.enough, r.reason ?: "")
        assertEquals(3, r.byLoad.size)
        val (low, normal, high) = r.byLoad
        assertTrue(high.n >= cfg.minFatigueGroup && low.n >= cfg.minFatigueGroup)
        assertTrue(high.median!! < 0.0, "high load group median ${high.median}")
        assertTrue(low.median!! > 0.0, "low load group median ${low.median}")
        assertTrue(normal.n > 0)
        assertEquals(3, r.byReadiness.size)
        assertEquals(r.n, r.byLoad.sumOf { it.n })
    }
}

class BlockEffectTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    /** [blocksCount] blocks of 4 weeks with a rising interval share and an EF that rises with it. */
    private fun scenario(blocksCount: Int): Triple<List<LoadSession>, List<WeekLoad>, List<PerfPoint>> {
        val sessions = ArrayList<LoadSession>()
        val points = ArrayList<PerfPoint>()
        val weeks = ArrayList<WeekLoad>()
        var id = 1L
        for (b in 0 until blocksCount) {
            for (w in 0 until 4) {
                val ws = d.plusWeeks((b * 4 + w).toLong())
                weeks += T.week(ws, 300.0 + (b % 3) * 20)
                // 4 runs per week: interval count grows with the block's index modulo 4
                for (k in 0 until 4) {
                    val cls = if (k < b % 4) SessionClass.INTERVAL else SessionClass.EASY
                    sessions += T.session(id++, ws.plusDays(k.toLong()), cls, 100.0)
                }
                // 1 steady point per week; EF = 100 + 2 per interval of the previous block
                points += PerfPoint(id++, ws.plusDays(5), "p", 100.0 + (b % 4) * 3.0)
            }
        }
        return Triple(sessions, weeks, points)
    }

    @Test
    fun `blocks are 4 weeks and counted back from the last complete week`() {
        val (s, w, p) = scenario(5)
        val blocks = BlockEffect.blocks(s, w, p)
        assertEquals(5, blocks.size)
        assertEquals(d, blocks.first().start)
        assertTrue(blocks.all { it.sessions == 16 })
        assertNull(blocks.last().nextChange)
        assertEquals(4, blocks.count { it.nextChange != null })
    }

    @Test
    fun `fewer than 12 usable blocks abstain`() {
        val (s, w, p) = scenario(8)
        val r = BlockEffect.analyze(BlockEffect.blocks(s, w, p), cfg)
        assertFalse(r.enough)
        r.effects.forEach { assertEquals("onvoldoende data", it.verdict) }
    }

    @Test
    fun `a clear relation between share and next block change is found and reproducible`() {
        val (s, w, p) = scenario(17)
        val blocks = BlockEffect.blocks(s, w, p)
        val a = BlockEffect.analyze(blocks, cfg)
        assertTrue(a.enough)
        val interval = a.effects.first { it.cls == SessionClass.INTERVAL }
        assertNotNull(interval.pValue)
        assertEquals(a, BlockEffect.analyze(blocks, cfg))
    }

    @Test
    fun `a share that moves with total load is flagged as confounded`() {
        val blocks = (0 until 14).map { i ->
            val share = (i % 7) + 1
            Block(d.plusWeeks(i * 4L), 20, mapOf(SessionClass.INTERVAL to share, SessionClass.TEMPO to 2, SessionClass.LONG to 2), share * 100.0, 100.0, 4, (i % 5).toDouble() - 2)
        }
        val e = BlockEffect.analyze(blocks, cfg).effects.first { it.cls == SessionClass.INTERVAL }
        assertTrue(e.confounded)
        assertEquals(1.0, e.loadRho!!, 1e-9)
    }
}

class FrequencyTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun weeks(runs: List<Int>) = runs.mapIndexed { i, r -> T.week(d.plusWeeks(i.toLong()), r * 80.0, r) }

    @Test
    fun `groups below the minimum are merged upward`() {
        // 30 weeks: 10 with 2 runs, 10 with 3 runs, 10 with 4 runs, none with 5
        val runs = List(10) { 2 } + List(10) { 3 } + List(10) { 4 }
        val series = RecoverySeries(T.series(d, 400) { 50.0 }, T.series(d, 400) { 50.0 }, emptyMap(), emptyMap())
        val r = Frequency.analyze(weeks(runs), series, LoadWindows.DEFAULT, cfg)
        assertEquals(listOf("1 tot 2 runs per week", "3 runs per week", "4 of meer runs per week"), r.groups.map { it.label })
        assertTrue(r.groups.all { it.enough })
        assertEquals(10, r.groups[0].weeks)
        assertEquals(160.0, r.groups[0].medianTrimp)
    }

    @Test
    fun `frequency and volume moving together is reported`() {
        val runs = List(8) { 2 } + List(8) { 4 }
        val series = RecoverySeries(T.series(d, 400) { 50.0 }, emptyMap(), emptyMap(), emptyMap())
        val r = Frequency.analyze(weeks(runs), series, LoadWindows.DEFAULT, cfg)
        assertTrue(r.frequencyTracksVolume)
    }
}

class DetrainingTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun days(runDays: Set<Int>, total: Int = 61): List<DayLoad> {
        val sessions = runDays.map { T.session(it.toLong(), d.plusDays(it.toLong()), trimp = 60.0) }
        return DailyLoad.build(sessions, d, d, d.plusDays(total - 1L))
    }

    @Test
    fun `14 days without a run is an episode and 13 is not`() {
        // runs every other day until day 20, then a gap, then runs again
        val before = (0..20 step 2).toSet()
        val gap14 = before + (35..60 step 2).toSet() // days 21..34 = 14 days without a run
        val gap13 = before + (34..60 step 2).toSet() // days 21..33 = 13 days
        val a = Detraining.spans(days(gap14), emptyList(), 14)
        assertEquals(listOf(d.plusDays(21) to d.plusDays(34)), a)
        assertTrue(Detraining.spans(days(gap13), emptyList(), 14).none { it.first == d.plusDays(21) })
    }

    @Test
    fun `two consecutive weeks below 30 percent of the level before are an episode`() {
        val weeks = listOf(100.0, 100.0, 100.0, 100.0, 20.0, 20.0, 100.0).mapIndexed { i, t -> T.week(d.plusWeeks(i.toLong()), t) }
        val allRuns = (0 until 49).toSet()
        val s = Detraining.spans(days(allRuns, 49), weeks, 14)
        assertEquals(listOf(d.plusWeeks(4) to d.plusWeeks(6).minusDays(1)), s)
    }

    @Test
    fun `before and after efficiency and the recovery values of the episode`() {
        val dayList = days((0..20 step 2).toSet() + (35..60 step 2).toSet())
        val points = (0..3).map { PerfPoint(it.toLong(), d.plusDays(2L + it * 5), "b", 100.0) } +
            (0..3).map { PerfPoint(10L + it, d.plusDays(36L + it * 5), "a", 96.0) }
        val rhr = T.series(d, 90) { if (it < 21) 50.0 else if (it < 35) 47.0 else 50.0 }
        val series = RecoverySeries(emptyMap(), rhr, emptyMap(), emptyMap())
        val r = Detraining.analyze(dayList, emptyList(), series, points, LoadWindows.DEFAULT, cfg)
        val e = r.episodes.single()
        assertEquals(14, e.days)
        assertEquals(-4.0, e.efChange!!, 1e-9)
        assertEquals(50.0, e.restingHrBefore)
        assertEquals(47.0, e.restingHrEnd)
        assertEquals(1, r.usable)
        assertNull(r.summaryEfChange) // summary needs 3 episodes
    }

    @Test
    fun `a side with fewer than 3 runs is not usable`() {
        val dayList = days((0..20 step 2).toSet() + (35..60 step 2).toSet())
        val points = listOf(PerfPoint(1, d.plusDays(5), "b", 100.0), PerfPoint(2, d.plusDays(40), "a", 96.0))
        val r = Detraining.analyze(dayList, emptyList(), RecoverySeries.EMPTY, points, LoadWindows.DEFAULT, cfg)
        assertEquals(0, r.usable)
        assertNull(r.episodes.single().efChange)
    }
}

class AdaptationLagTest {
    private val d = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun weeksOf(n: Int, seed: Long): List<WeekLoad> {
        val r = Random(seed)
        return (0 until n).map { T.week(d.plusWeeks(it.toLong()), 200.0 + r.nextInt(200)) }
    }

    @Test
    fun `fewer than 52 weeks abstain`() {
        val r = AdaptationLag.analyze(weeksOf(40, 1), emptyList(), null, cfg)
        assertFalse(r.enough)
        assertFalse(r.found)
        assertTrue(r.reason!!.contains("minimum 52"))
    }

    @Test
    fun `unrelated series give no measurable lag`() {
        val weeks = weeksOf(120, 1)
        val r = Random(99)
        val points = (0 until 120).flatMap { w -> (0 until 3).map { PerfPoint(w * 10L + it, d.plusWeeks(w.toLong()).plusDays(it.toLong()), "p", 100 + r.nextGaussian() * 2) } }
        val res = AdaptationLag.analyze(weeks, points, null, cfg)
        assertTrue(res.enough)
        assertFalse(res.found, "best ${res.bestCorrelation} threshold ${res.threshold}")
        assertEquals("geen meetbare vertraging gevonden", res.reason)
        assertEquals(9, res.lags.size)
    }

    @Test
    fun `a planted relation with a delay is found above the permutation threshold`() {
        val weeks = weeksOf(150, 4)
        val trimp = weeks.map { it.trimp!! }
        // efficiency level 3 weeks after follows the 4 week mean of the load
        val points = (0 until 150).flatMap { w ->
            val lvl = if (w >= 7) 100 + 0.1 * trimp.subList(w - 6, w - 2).average() else 100.0
            (0 until 3).map { PerfPoint(w * 10L + it, d.plusWeeks(w.toLong()).plusDays(it.toLong()), "p", lvl) }
        }
        val res = AdaptationLag.analyze(weeks, points, null, cfg)
        assertTrue(res.found, "best ${res.bestCorrelation} at ${res.lags.maxByOrNull { kotlin.math.abs(it.correlation ?: 0.0) }} threshold ${res.threshold}")
        assertNotNull(res.bestLag)
        assertEquals(res, AdaptationLag.analyze(weeks, points, null, cfg))
    }

    @Test
    fun `weeks in the run-in are not used`() {
        val weeks = weeksOf(80, 2)
        val r = AdaptationLag.analyze(weeks, emptyList(), d.plusWeeks(40), cfg)
        assertEquals(40, r.weeksUsed)
        assertFalse(r.enough)
    }
}
