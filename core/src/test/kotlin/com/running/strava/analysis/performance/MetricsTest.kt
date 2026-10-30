package com.running.strava.analysis.performance

import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.performance.Synth.Row
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CriticalSpeedTest {
    private val d0 = LocalDate.of(2026, 9, 1)
    private fun eff(meters: Double, seconds: Double, date: LocalDate = d0, hr: Double? = null, id: Long = 1) =
        CriticalSpeed.Effort(id, date, meters, seconds, false, hr)

    /** d = 4.0 t + 200 */
    private val exact = listOf(eff(920.0, 180.0), eff(1640.0, 360.0), eff(3080.0, 720.0))

    @Test
    fun `efforts generated from CS 4 and D prime 200 are recovered exactly`() {
        val fit = (CriticalSpeed.fit(exact) as CriticalSpeed.Outcome.Ok).fit
        assertEquals(4.0, fit.csMs, 1e-9)
        assertEquals(200.0, fit.dPrimeMeters, 1e-6)
        assertEquals(1.0, fit.r2, 1e-9)
        assertEquals(0.0, fit.rangePct, 1e-9)
        assertEquals(250.0, fit.csSecondsPerKm, 1e-9)
    }

    @Test
    fun `two anchors abstain`() {
        val o = CriticalSpeed.fit(exact.take(2)) as CriticalSpeed.Outcome.Abstain
        assertTrue(o.reason.contains("ankers"))
    }

    @Test
    fun `a duration ratio below 2 abstains`() {
        val close = listOf(eff(920.0, 180.0), eff(1320.0, 280.0), eff(1520.0, 330.0))
        val o = CriticalSpeed.fit(close) as CriticalSpeed.Outcome.Abstain
        assertTrue(o.reason.contains("duurverhouding"))
    }

    @Test
    fun `noisy anchors give an R2 below 0_98 and abstain`() {
        val noisy = listOf(eff(920.0, 180.0), eff(2000.0, 360.0), eff(3080.0, 720.0))
        val o = CriticalSpeed.fit(noisy) as CriticalSpeed.Outcome.Abstain
        assertTrue(o.reason.contains("R2"), o.reason)
    }

    @Test
    fun `a leave one out range wider than 5 percent abstains`() {
        val wide = listOf(eff(900.0, 180.0), eff(1700.0, 360.0), eff(3200.0, 720.0))
        val o = CriticalSpeed.fit(wide) as CriticalSpeed.Outcome.Abstain
        assertTrue(o.reason.contains("onzeker"), o.reason)
    }

    @Test
    fun `anchors are the fastest effort per distance inside the last 90 days`() {
        val today = LocalDate.of(2026, 10, 30)
        val all = listOf(
            eff(1000.0, 250.0, today.minusDays(10)), eff(1000.0, 240.0, today.minusDays(20)),
            eff(1000.0, 200.0, today.minusDays(120)), eff(5000.0, 1300.0, today.minusDays(5)),
        )
        val anchors = CriticalSpeed.anchors(all, today)
        assertEquals(2, anchors.size)
        assertEquals(240.0, anchors.first { it.distanceMeters == 1000.0 }.seconds, 0.0)
    }

    @Test
    fun `effort from a stream respects the 3 to 30 minute range and averages HR over the segment`() {
        val stream = Synth.stream(600, 1, { 4.0 }, { 160 })
        val e = CriticalSpeed.effort(1, d0, false, stream, 1000.0)!!
        assertEquals(250.0, e.seconds, 1e-6)
        assertEquals(160.0, e.avgHr!!, 0.01)
        assertNull(CriticalSpeed.effort(1, d0, false, Synth.stream(600, 1, { 7.0 }, { 160 }), 1000.0))
    }

    @Test
    fun `HR at CS uses anchors of 10 to 30 minutes only`() {
        val fit = (CriticalSpeed.fit(listOf(eff(920.0, 180.0, hr = 180.0), eff(1640.0, 360.0, hr = 175.0), eff(3080.0, 720.0, hr = 170.0), eff(4800.0, 1150.0, hr = 168.0))) as CriticalSpeed.Outcome.Ok).fit
        val (hr, n) = CriticalSpeed.hrAtCs(fit)
        assertEquals(2, n)
        assertEquals(169.0, hr!!, 1e-9)
    }

    @Test
    fun `walk forward predicts only from earlier efforts and is exact on the model line`() {
        val jan = LocalDate.of(2026, 1, 1)
        val history = listOf(
            eff(1000.0, 200.0, jan), eff(3000.0, 700.0, jan.plusDays(1)), eff(5000.0, 1200.0, jan.plusDays(2)),
            eff(1000.0, 200.0, jan.plusDays(10)),
        )
        val v = CriticalSpeed.walkForward(history)
        assertEquals(1, v.n)
        assertEquals(0.0, v.meanAbsErrorPct!!, 1e-6)
        val later = history + eff(1000.0, 190.0, jan.plusDays(30))
        val v2 = CriticalSpeed.walkForward(later)
        assertEquals(2, v2.n)
        assertEquals(5.263157894736842 / 2, v2.meanAbsErrorPct!!, 1e-6)
    }
}

class PowerAndGradeTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun pr(day: Long, watts: Double = 240.0, hr: Double = 150.0) =
        PowerRun(day, today.minusDays(day), 1800.0, 5400.0, hr, watts)

    @Test
    fun `power ratios by hand`() {
        val r = pr(1)
        assertEquals(1.6, r.wattsPerBpm, 1e-9)
        assertEquals(3.0 / 240.0, r.speedPerWatt, 1e-12)
    }

    @Test
    fun `only measured power counts`() {
        assertTrue(PowerEfficiency.hasMeasuredPower(Synth.activity(watts = 240.0, deviceWatts = true)))
        assertFalse(PowerEfficiency.hasMeasuredPower(Synth.activity(watts = 240.0, deviceWatts = false)))
        assertFalse(PowerEfficiency.hasMeasuredPower(Synth.activity(watts = null, deviceWatts = true)))
        assertFalse(PowerEfficiency.hasMeasuredPower(Synth.activity(watts = 240.0, deviceWatts = null)))
    }

    @Test
    fun `coverage below 70 percent makes power unavailable and 70 percent is accepted`() {
        val dates = (1L..10L).map { today.minusDays(it) }
        val six = (1L..6L).map { pr(it) }
        val low = PowerEfficiency.analyze(dates, six, today, 90)
        assertFalse(low.available)
        assertEquals(0.6, low.coverage, 1e-9)
        assertEquals("vermogen niet betrouwbaar aanwezig", low.reason)
        assertTrue(PowerEfficiency.analyze(dates, (1L..7L).map { pr(it) }, today, 90).available)
    }

    @Test
    fun `power trend needs 5 runs in each window`() {
        val a = (100L..104L).map { pr(it, 240.0) }
        val b = (1L..5L).map { pr(it, 264.0) }
        val dates = (a + b).map { it.date }
        val r = PowerEfficiency.analyze(dates, a + b, today, 90)
        assertTrue(r.available)
        assertEquals(EfVerdict.Status.IMPROVED, r.wattsPerBpmStatus)
        val few = PowerEfficiency.analyze(dates.drop(1), a.drop(1) + b, today, 90)
        assertEquals(EfVerdict.Status.INSUFFICIENT_DATA, few.wattsPerBpmStatus)
    }

    @Test
    fun `Minetti factor by hand at 0 plus 10 and minus 10 percent`() {
        assertEquals(3.6, GradeAdjusted.cost(0.0), 1e-9)
        assertEquals(1.0, GradeAdjusted.factor(0.0), 1e-9)
        assertEquals(5.968, GradeAdjusted.cost(0.1), 1e-3)
        assertEquals(1.658, GradeAdjusted.factor(10.0), 1e-3)
        assertEquals(2.1517, GradeAdjusted.cost(-0.1), 1e-3)
        assertEquals(0.5977, GradeAdjusted.factor(-10.0), 1e-3)
    }

    private fun gradeSamples(extremeShare: Double, extremeGrade: Float = 30f): List<Sample> {
        val c = CleanRun.clean(
            Synth.stream(1000, 1, { 3.0 }, { 150 }, grade = { t -> if (t <= extremeShare * 1000) extremeGrade else 0f }),
            1000.0,
        )!!
        return c.samples
    }

    @Test
    fun `grade beyond 20 percent is ignored and more than 5 percent extreme is unavailable`() {
        val ok = GradeAdjusted.assess(gradeSamples(0.04), true)
        assertTrue(ok.available)
        assertEquals(3.0, ok.gapSpeed!!, 1e-6)
        val bad = GradeAdjusted.assess(gradeSamples(0.06), true)
        assertFalse(bad.available)
        assertNull(bad.gapSpeed)
    }

    @Test
    fun `a missing grade stream is unavailable`() {
        val c = CleanRun.clean(Synth.stream(100), 100.0)!!
        assertFalse(GradeAdjusted.assess(c.samples, c.hasGrade).available)
        assertNull(GradeAdjusted.efGap(c.samples, c.hasGrade))
    }

    @Test
    fun `a constant 10 percent climb gives GAP of 1_658 times raw speed`() {
        val c = CleanRun.clean(Synth.stream(600, 1, { 2.5 }, { 150 }, grade = { 10f }), 600.0)!!
        val r = GradeAdjusted.assess(c.samples, true)
        assertEquals(2.5 * 5.968 / 3.6, r.gapSpeed!!, 0.01)
    }

    @Test
    fun `terrain class boundaries`() {
        assertEquals(TerrainClass.FLAT, TerrainClass.of(4.9))
        assertEquals(TerrainClass.ROLLING, TerrainClass.of(5.0))
        assertEquals(TerrainClass.ROLLING, TerrainClass.of(15.0))
        assertEquals(TerrainClass.HILLY, TerrainClass.of(15.01))
    }
}

class ZoneDistributionTest {
    private val set = HrZoneSet.app(190) // floors 95, 114, 133, 152, 171

    @Test
    fun `app zone floors and zone boundaries`() {
        assertEquals(listOf(95, 114, 133, 152, 171), set.floors)
        assertEquals(1, set.zoneOf(113))
        assertEquals(2, set.zoneOf(114))
        assertEquals(4, set.zoneOf(170))
        assertEquals(5, set.zoneOf(171))
        assertEquals(5, set.zoneOf(210))
        assertEquals(1, set.zoneOf(80))
    }

    @Test
    fun `garmin zones are used when complete and never mixed with the app zones`() {
        val src = com.running.garmin.domain.GarminSource("t", null, "t")
        fun z(sport: String, f1: Int? = 120) = com.running.garmin.domain.GarminHeartRateZones(sport, "LT", 52, 182, f1, 151, 164, 171, 186, 196, src)
        val g = HrZoneSet.garmin(listOf(z("DEFAULT"), z("RUNNING")))!!
        assertEquals(ZoneSource.GARMIN, g.source)
        assertEquals(listOf(120, 151, 164, 171, 186), g.floors)
        assertNull(HrZoneSet.garmin(listOf(z("RUNNING", f1 = null))))
        assertNull(HrZoneSet.garmin(emptyList()))
        assertNotEquals(g.floors, set.floors)
        assertEquals(ZoneSource.APP, set.source)
    }

    @Test
    fun `time in zones by hand with 3 s sample gaps`() {
        val c = CleanRun.clean(Synth.stream(600, 3, { 3.0 }, { if (it <= 300) 100 else 150 }), 600.0)!!
        val sec = ZoneDistribution.seconds(ZoneDistribution.histogram(c.samples), set)
        assertEquals(300.0, sec[0], 1e-9) // 99 and 101 bpm -> zone 1
        assertEquals(300.0, sec[2], 1e-9) // 149 and 151 bpm -> zone 3
        assertEquals(0.0, sec[1] + sec[3] + sec[4], 1e-9)
    }

    private fun run(date: String, hist: Map<Int, Double>, type: com.running.strava.domain.SessionType = com.running.strava.domain.SessionType.STEADY, km: Double = 10.0) =
        ZoneRun(LocalDate.parse(date), hist, type, km * 1000)

    @Test
    fun `weeks follow activity local Monday boundaries and classes add up to 100 percent`() {
        val runs = listOf(
            run("2026-10-25", mapOf(100 to 600.0)), // Sunday
            run("2026-10-26", mapOf(140 to 600.0, 175 to 600.0)), // Monday
            run("2026-10-27", mapOf(120 to 600.0)),
            run("2026-10-28", mapOf(120 to 600.0)),
        )
        val weeks = ZoneDistribution.byWeek(runs, set)
        assertEquals(listOf("2026-10-19", "2026-10-26"), weeks.map { it.label })
        assertEquals(1, weeks[0].runs)
        assertTrue(weeks[0].fewRuns)
        val w = weeks[1]
        assertEquals(3, w.runs)
        assertFalse(w.fewRuns)
        assertEquals(100.0, w.easyPct + w.moderatePct + w.hardPct, 1e-9)
        assertEquals(25.0, w.moderatePct, 1e-9)
        assertEquals(25.0, w.hardPct, 1e-9)
        assertEquals(50.0, w.easyPct, 1e-9)
        assertEquals(40.0, w.totalMinutes, 1e-9)
    }

    @Test
    fun `months and session mix`() {
        val runs = listOf(
            run("2026-09-23", mapOf(100 to 60.0), com.running.strava.domain.SessionType.STEADY, 10.0),
            run("2026-10-01", mapOf(100 to 60.0), com.running.strava.domain.SessionType.INTERVAL, 5.0),
            run("2026-10-02", mapOf(100 to 60.0), com.running.strava.domain.SessionType.STEADY, 5.0),
        )
        assertEquals(listOf("2026-09", "2026-10"), ZoneDistribution.byMonth(runs, set).map { it.label })
        val mix = ZoneDistribution.sessionMixByWeek(runs)
        assertEquals(2, mix.size)
        val last = mix.last()
        assertEquals(2, last.runs)
        assertEquals(1, last.counts[com.running.strava.domain.SessionType.INTERVAL])
        assertEquals(50.0, last.distanceShare[com.running.strava.domain.SessionType.INTERVAL]!!, 1e-9)
        val blocks = ZoneDistribution.sessionMixBy4Weeks(runs, LocalDate.parse("2026-10-30"))
        assertEquals(1, blocks.size)
        assertEquals(3, blocks.single().runs)
    }
}
