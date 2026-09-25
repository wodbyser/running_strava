package com.running.strava.analysis

import com.running.strava.analysis.EfficiencyFactor.Sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelsTest {

    @Test
    fun `EF uses only samples with HR and is distance over heartbeats`() {
        val samples = listOf(
            Sample(10000.0, 3000.0, 150.0),
            Sample(5000.0, 1500.0, 140.0),
            Sample(8000.0, 2000.0, null), // no HR strap: must not move speed or HR
        )
        // (10000 + 5000) / (150*3000 + 140*1500) = 15000 / 660000 = 0.0227273
        assertEquals(15000.0 / 660000.0, EfficiencyFactor.pooled(samples)!!, 1e-12)
        // time-weighted HR (450000 + 210000) / 4500 = 146.667
        assertEquals(146.667, EfficiencyFactor.avgHr(samples)!!, 1e-3)
        // speed of the same population: 15000 / 4500 = 3.3333 (the HR-less fast run is excluded)
        assertEquals(15000.0 / 4500.0, EfficiencyFactor.avgSpeed(samples)!!, 1e-12)
        assertNull(EfficiencyFactor.pooled(listOf(Sample(1000.0, 300.0, null))))
        assertEquals("0.0227", EfficiencyFactor.format(15000.0 / 660000.0))
    }

    @Test
    fun `verdict requires minimum sample size`() {
        val a = listOf(0.020, 0.020, 0.020, 0.020)
        val b = listOf(0.022, 0.022, 0.022, 0.022, 0.022)
        val r = EfVerdict.evaluate(0.020, 0.022, a, b, minSessions = 5)
        assertEquals(EfVerdict.Status.INSUFFICIENT_DATA, r.status)
        assertEquals(10.0, r.changePct!!, 1e-9)
        val ok = EfVerdict.evaluate(0.020, 0.022, a + 0.020, b, minSessions = 5)
        assertEquals(EfVerdict.Status.IMPROVED, ok.status)
    }

    @Test
    fun `verdict threshold and spread`() {
        val a = listOf(0.0200, 0.0202, 0.0198, 0.0201, 0.0199)
        // +2.5 %: within the 3 % band
        assertEquals(EfVerdict.Status.STABLE, EfVerdict.evaluate(0.0200, 0.0205, a, a.map { it * 1.025 }, 5).status)
        // +3.5 % with tight spread: clear improvement
        assertEquals(EfVerdict.Status.IMPROVED, EfVerdict.evaluate(0.0200, 0.0207, a, a.map { it * 1.035 }, 5).status)
        // -5 %: decline
        assertEquals(EfVerdict.Status.DECLINED, EfVerdict.evaluate(0.0200, 0.0190, a, a.map { it * 0.95 }, 5).status)
        // +5 % mean but huge spread (sd ≈ 0.0034, 2·SE ≈ 0.0030 > diff 0.0010): uncertain
        val noisy = listOf(0.017, 0.025, 0.019, 0.024, 0.020)
        assertEquals(EfVerdict.Status.UNCERTAIN, EfVerdict.evaluate(0.0200, 0.0210, a, noisy, 5).status)
    }

    @Test
    fun `riegel matches the published formula`() {
        // 2^1.06 = 2.084931
        assertEquals(1200 * 2.084931, RacePredictor.riegel(1200.0, 5000.0, 10000.0), 0.01)
    }

    @Test
    fun `prediction is the median of anchors in window, rounded and ranged`() {
        val anchors = listOf(
            RacePredictor.Anchor(5000.0, 1200.0, "5 km"),
            RacePredictor.Anchor(10000.0, 2520.0, "10 km"),
        )
        val p = RacePredictor.predict(10000.0, anchors)!!
        // from 5 km: 2501.92; from 10 km: 2520 -> median 2510.96
        assertEquals(2510.96, p.seconds, 0.01)
        assertEquals(2501.92, p.lowSeconds, 0.01)
        assertEquals(2520.0, p.highSeconds, 0.01)
        // < 1 h -> 15 s steps: 2510.96 / 15 = 167.4 -> 2505 s = 41:45
        assertEquals(2505.0, RacePredictor.roundSeconds(p.seconds))
        assertEquals("41:45", Format.duration(RacePredictor.roundSeconds(p.seconds)))
    }

    @Test
    fun `no prediction from a single anchor, from far extrapolation or from a sub-3,5-minute effort`() {
        val five = RacePredictor.Anchor(5000.0, 1200.0, "5 km")
        assertNull(RacePredictor.predict(10000.0, listOf(five)))
        // marathon is 8.4x a 5 km: outside 0.4-2.5 window
        assertNull(RacePredictor.predict(42195.0, listOf(five, five.copy(seconds = 1250.0))))
        // 400 m rep in 80 s is never an anchor (< 210 s)
        val rep = RacePredictor.Anchor(400.0, 80.0, "rep")
        assertNull(RacePredictor.predict(1000.0, listOf(rep, rep)))
    }

    @Test
    fun `predicted paces slow down with distance`() {
        val anchors = listOf(
            RacePredictor.Anchor(5000.0, 1200.0, "a"), RacePredictor.Anchor(5000.0, 1230.0, "b"),
            RacePredictor.Anchor(10000.0, 2520.0, "c"), RacePredictor.Anchor(21097.5, 5500.0, "d"),
        )
        val paces = listOf(3000.0, 5000.0, 10000.0, 15000.0, 21097.5).mapNotNull { d -> RacePredictor.predict(d, anchors)?.let { it.seconds / d } }
        assertTrue(paces.size >= 4)
        assertEquals(paces.sorted(), paces)
    }

    @Test
    fun `hr zones are contiguous and non overlapping`() {
        val z = HrZones.pctMax(196)
        // 98, 117.6->118, 137.2->137, 156.8->157, 176.4->176
        assertEquals(listOf(98 to 117, 118 to 136, 137 to 156, 157 to 175, 176 to 196), z.map { it.minBpm to it.maxBpm })
        val k = HrZones.karvonen(196, 50)
        // 50 + p*146: 123, 137.6->138, 152.2->152, 166.8->167, 181.4->181
        assertEquals(listOf(123 to 137, 138 to 151, 152 to 166, 167 to 180, 181 to 196), k.map { it.minBpm to it.maxBpm })
        assertTrue(z.none { it.description.contains("vetverbranding") })
    }

    @Test
    fun `max hr estimate is the 95th percentile nearest rank`() {
        // 20 values 181..200: rank ceil(0.95*20) = 19 -> 199
        assertEquals(199, HrZones.estimateMaxHr((181..200).toList()))
        assertEquals(203, HrZones.estimateMaxHr(listOf(180, 190, 203)))
        assertNull(HrZones.estimateMaxHr(emptyList()))
    }
}
