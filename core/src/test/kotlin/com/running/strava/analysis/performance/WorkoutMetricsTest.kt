package com.running.strava.analysis.performance

import com.running.strava.analysis.performance.Synth.Row
import com.running.strava.domain.Lap
import com.running.strava.domain.SessionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class DurabilityTest {
    private fun cleaned(rows: List<Row>, moving: Double = rows.sumOf { it.dt }.toDouble()) =
        CleanRun.clean(Synth.fromRows(rows), moving)!!

    /** [seconds] of 3 m/s; HR is 150/151 alternating before [switchAt] and 158/159 after it, so EF falls. */
    private fun rows(seconds: Int, switchAt: Int = 3600, hrBefore: Int = 150, hrAfter: Int = 158, gapAt: Int? = null, gap: Int = 0): List<Row> {
        val list = ArrayList<Row>()
        for (t in 1..seconds) {
            val base = if (t > switchAt) hrAfter else hrBefore
            list += Row(1, 3.0, base + if (t % 2 == 0) -1 else 1)
            if (gapAt != null && t == gapAt) list += Row(gap, gap * 3.0, base)
        }
        return list
    }

    @Test
    fun `block EF and the EF change at minute 60 by hand`() {
        val run = DurabilityProfile.analyze(cleaned(rows(5400)).samples, 600.0)
        assertEquals(8, run.blocks.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), run.blocks.map { it.index })
        assertEquals(100.0, run.efRelativePct.first(), 1e-9)
        assertEquals(150.0 / 158.0 * 100, run.efRelativePct[5], 0.05)
        assertEquals((150.0 / 158.0 - 1) * 100, run.efChangeAt60Pct!!, 0.05)
        assertEquals(10.0, run.blocks.first().startMinute, 1e-9)
        assertEquals(60.0, run.blocks[5].startMinute, 1e-9)
    }

    @Test
    fun `a run shorter than 75 minutes is not analysed and exactly 75 minutes is`() {
        val short = DurabilityProfile.analyze(cleaned(rows(4499)).samples, 600.0)
        assertFalse(short.eligible)
        assertNull(short.efChangeAt60Pct)
        assertTrue(short.blocks.isEmpty())
        val ok = DurabilityProfile.analyze(cleaned(rows(4500)).samples, 600.0)
        assertTrue(ok.eligible)
        assertNotNull(ok.efChangeAt60Pct)
    }

    @Test
    fun `a block with 79 percent valid time is skipped and 80 percent counts`() {
        // 160 s pause inside block 2: 600 valid of 760 elapsed = 78.9 %; 150 s: 600 of 750 = 80 %
        val skipped = DurabilityProfile.analyze(cleaned(rows(5400, gapAt = 2100, gap = 160)).samples, 600.0)
        assertFalse(skipped.blocks.any { it.index == 2 })
        assertEquals(7, skipped.blocks.size)
        val kept = DurabilityProfile.analyze(cleaned(rows(5400, gapAt = 2100, gap = 150)).samples, 600.0)
        assertTrue(kept.blocks.any { it.index == 2 })
    }

    private fun thirds(
        speeds: Triple<Double, Double, Double> = Triple(3.0, 2.9, 2.8),
        hrs: Triple<Int, Int, Int> = Triple(150, 153, 156),
        grades: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        cad: Triple<Int, Int, Int> = Triple(85, 84, 83),
    ): LongRun.Thirds {
        // 600 s warm-up + 3 x 1600 s
        val c = CleanRun.clean(
            Synth.stream(
                5400, 1,
                speed = { t -> if (t <= 600) 3.0 else if (t <= 2200) speeds.first else if (t <= 3800) speeds.second else speeds.third },
                hr = { t -> if (t <= 600) 140 else if (t <= 2200) hrs.first else if (t <= 3800) hrs.second else hrs.third },
                grade = { t -> if (t <= 2200) grades.first else if (t <= 3800) grades.second else grades.third },
                cadence = { t -> if (t <= 2200) cad.first else if (t <= 3800) cad.second else cad.third },
            ),
            5400.0,
        )!!
        return LongRun.thirds(c.samples, 600.0)!!
    }

    @Test
    fun `long run thirds compare the last third with the first third`() {
        val t = thirds()
        assertEquals((2.8 - 3.0) / 3.0 * 100, t.speedChangePct, 0.05)
        assertEquals(6.0, t.hrChangeBpm, 0.05)
        assertEquals(4.0, t.hrChangePct, 0.05)
        assertEquals((2.8 / 156 - 3.0 / 150) / (3.0 / 150) * 100, t.efChangePct, 0.1)
        assertEquals(-4.0, t.cadenceChangeSpm!!, 0.05) // 85 -> 83 per foot = 170 -> 166 spm
        assertEquals(1.0 / 3.0, t.slowerShare, 0.03)
        assertFalse(t.terrainDiffers)
    }

    @Test
    fun `a hillier last third marks the EF comparison as terrain dependent`() {
        assertTrue(thirds(grades = Triple(0f, 1f, 3f)).terrainDiffers)
        assertFalse(thirds(grades = Triple(0f, 1f, 1f)).terrainDiffers)
    }

    @Test
    fun `long run definition`() {
        assertTrue(LongRun.isLongRun(2, 3000, null))
        assertTrue(LongRun.isLongRun(null, 5400, 3000.0))
        assertFalse(LongRun.isLongRun(null, 4400, 3000.0)) // under 75 minutes
        assertFalse(LongRun.isLongRun(null, 5400, 4000.0)) // under 1.5x median
        assertFalse(LongRun.isLongRun(null, 5400, null))
    }
}

class IntervalAndRecoveryTest {

    private data class L(val dist: Double, val seconds: Int, val hr: Int)

    private fun build(laps: List<L>): Pair<List<Lap>, com.running.strava.domain.ActivityStream> {
        val rows = ArrayList<Row>()
        val out = ArrayList<Lap>()
        var t = 0
        laps.forEachIndexed { i, l ->
            repeat(l.seconds) { rows += Row(1, l.dist / l.seconds, l.hr) }
            out += Lap(
                i.toLong(), null, l.seconds, l.seconds, ZonedDateTime.parse("2026-09-01T08:00:00Z"), t, t + l.seconds - 1,
                l.dist.toFloat(), (l.dist / l.seconds).toFloat(), 0f, l.hr.toFloat(), (l.hr + 5).toFloat(), 84f, i + 1, 0,
            )
            t += l.seconds
        }
        return out to Synth.fromRows(rows)
    }

    private fun session(reps: Int, paces: (Int) -> Int = { 230 + 2 * it }): List<L> =
        listOf(L(1000.0, 360, 140)) +
            (0 until reps).flatMap { i -> listOf(L(1000.0, paces(i), 170 + i), L(200.0, 90, 150)) } +
            listOf(L(1000.0, 360, 145))

    @Test
    fun `six times 1 km gives fade spread and HR rise by hand and HRR per rep`() {
        val (laps, stream) = build(session(6))
        val s = IntervalPerformance.analyze(laps, null, stream, 190)!!
        assertEquals(6, s.reps.size)
        assertEquals("6 x 1000 m", s.signature)
        assertTrue(s.hasStatistics)
        assertEquals(10.0 / 230.0 * 100, s.paceFadePct!!, 1e-6)
        assertEquals(0.0159, s.speedCv!!, 0.0003)
        assertEquals(5.0, s.hrRiseBpm!!, 1e-9)
        s.reps.forEachIndexed { i, r ->
            assertNotNull(r.hrr, "rep ${i + 1}: ${r.hrrNote}")
            assertEquals(RecoveryProtocol.JOGGING, r.hrr!!.protocol)
            assertEquals(170.0 + i - 150.0, r.hrr.hrr60, 1e-9)
            assertEquals(90, r.recoverySeconds)
        }
    }

    @Test
    fun `two reps are listed without statistics`() {
        val (laps, stream) = build(session(2))
        val s = IntervalPerformance.analyze(laps, null, stream, 190)!!
        assertEquals(2, s.reps.size)
        assertFalse(s.hasStatistics)
        assertNull(s.paceFadePct)
        assertNull(s.speedCv)
        assertNull(s.hrRiseBpm)
    }

    @Test
    fun `a session that is not an interval session produces nothing`() {
        val (laps, stream) = build((1..8).map { L(1000.0, 355 + it, 140 + it) })
        assertNull(IntervalPerformance.analyze(laps, null, stream, 190))
    }

    private fun rep(distance: Double, seconds: Int) =
        IntervalPerformance.Rep(1, distance, seconds, distance / seconds, null, null, null, null, null, null)

    @Test
    fun `different rep sizes have different or no signature and are never pooled`() {
        assertNull(IntervalPerformance.signature(listOf(rep(400.0, 90), rep(800.0, 190), rep(1200.0, 300))))
        assertEquals("5 x 1000 m", IntervalPerformance.signature((1..5).map { rep(1000.0, 230) }))
        assertNotEquals(IntervalPerformance.signature((1..5).map { rep(1000.0, 230) }), IntervalPerformance.signature((1..6).map { rep(1000.0, 230) }))
        assertEquals("6 x 3:00", IntervalPerformance.signature((1..6).map { rep(500.0 + it * 100, 180) }))
    }

    // ---- heart rate recovery on a plain stream ----

    private fun hrrStream(afterSpeed: Double, endHr: Int = 180, afterHr: Int = 150, gapAt: Int? = null, gap: Int = 0): com.running.strava.domain.ActivityStream {
        val rows = ArrayList<Row>()
        for (t in 1..250) {
            rows += if (t <= 100) Row(1, 4.3, endHr) else Row(1, afterSpeed, afterHr)
            if (gapAt != null && t == gapAt) rows += Row(gap, gap * afterSpeed, afterHr)
        }
        return Synth.fromRows(rows)
    }

    @Test
    fun `HRR60 by hand for the three protocol classes`() {
        fun m(speed: Double) = (HeartRateRecovery.measure(hrrStream(speed), 100.0, 190) as HeartRateRecovery.Outcome.Ok).measurement
        val jog = m(2.5)
        assertEquals(180.0, jog.hrEnd, 1e-9)
        assertEquals(150.0, jog.hr60, 1e-9)
        assertEquals(30.0, jog.hrr60, 1e-9)
        assertEquals(RecoveryProtocol.JOGGING, jog.protocol)
        assertEquals(RecoveryProtocol.WALKING, m(1.2).protocol)
        assertEquals(RecoveryProtocol.STANDING, m(0.0).protocol)
    }

    @Test
    fun `an end HR below 85 percent of max HR is rejected`() {
        val r = HeartRateRecovery.measure(hrrStream(2.5), 100.0, 220)
        assertTrue(r is HeartRateRecovery.Outcome.Rejected)
    }

    @Test
    fun `a gap of 6 s is rejected and 5 s is accepted`() {
        assertTrue(HeartRateRecovery.measure(hrrStream(2.5, gapAt = 130, gap = 6), 100.0, 190) is HeartRateRecovery.Outcome.Rejected)
        assertTrue(HeartRateRecovery.measure(hrrStream(2.5, gapAt = 130, gap = 5), 100.0, 190) is HeartRateRecovery.Outcome.Ok)
    }

    @Test
    fun `a sample interval above 5 s makes the metric unavailable`() {
        val slow = Synth.stream(600, 6, { 3.0 }, { 180 })
        val r = HeartRateRecovery.measure(slow, 300.0, 190) as HeartRateRecovery.Outcome.Rejected
        assertTrue(r.reason.contains("samplefrequentie"))
    }

    @Test
    fun `summary keeps protocol classes apart and needs the minimum`() {
        fun meas(p: RecoveryProtocol, v: Double) = HeartRateRecovery.Measurement(0.0, 180.0, 180 - v, v, p)
        val list = (1..10).map { meas(RecoveryProtocol.JOGGING, 20.0 + it) } + (1..4).map { meas(RecoveryProtocol.STANDING, 40.0) }
        val sum = HeartRateRecovery.summarize(list, 10).associateBy { it.protocol }
        assertTrue(sum.getValue(RecoveryProtocol.JOGGING).enough)
        assertEquals(25.5, sum.getValue(RecoveryProtocol.JOGGING).medianHrr60!!, 1e-9)
        assertFalse(sum.getValue(RecoveryProtocol.STANDING).enough)
        assertEquals(0, sum.getValue(RecoveryProtocol.WALKING).n)
    }
}

class ComparableAndConsistencyTest {
    private fun p(
        id: Long, date: String = "2026-10-01", type: SessionType = SessionType.STEADY, trainer: Boolean = false, dist: Double = 10000.0,
        elev: Double = 2.0, hasHr: Boolean = true, hr: Double? = 150.0, zone: Int? = 3, temp: Double? = null,
        lat: Double? = 50.0, lon: Double? = 4.0, eligible: Boolean = true,
    ) = RunProfile(id, LocalDate.parse(date), type, trainer, dist, 3000, TerrainClass.of(elev), elev, hasHr, hr, zone, temp, lat, lon, eligible, null, null, null)

    private val ref = p(1)

    @Test
    fun `each hard criterion excludes alone`() {
        fun why(c: RunProfile) = ComparableRuns.exclusion(ref, c)
        assertNull(why(p(2)))
        assertEquals(ExclusionReason.SESSION_TYPE, why(p(2, type = SessionType.RACE)))
        assertEquals(ExclusionReason.TRAINER, why(p(2, trainer = true)))
        assertEquals(ExclusionReason.DISTANCE, why(p(2, dist = 7900.0)))
        assertEquals(ExclusionReason.DISTANCE, why(p(2, dist = 12600.0)))
        assertNull(why(p(2, dist = 8000.0)))
        assertNull(why(p(2, dist = 12500.0)))
        assertEquals(ExclusionReason.TERRAIN, why(p(2, elev = 8.0)))
        assertEquals(ExclusionReason.HR_PRESENCE, why(p(2, hasHr = false, hr = null)))
        assertEquals(ExclusionReason.INELIGIBLE, why(p(2, eligible = false)))
    }

    @Test
    fun `soft criteria show unknown for missing values`() {
        val c = ComparableRuns.criteria(ref, p(2, hr = 154.0, zone = 3, temp = null, lat = 50.005, elev = 6.0))
        assertEquals(Agreement.AGREE, c[Criterion.HR])
        assertEquals(Agreement.AGREE, c[Criterion.ZONE])
        assertEquals(Agreement.UNKNOWN, c[Criterion.TEMPERATURE])
        assertEquals(Agreement.AGREE, c[Criterion.START])
        assertEquals(Agreement.AGREE, c[Criterion.ELEVATION])
        val d = ComparableRuns.criteria(ref, p(2, hr = 160.0, zone = 4, temp = 20.0, lat = 50.1, elev = 9.0))
        assertEquals(Agreement.DIFFER, d[Criterion.HR])
        assertEquals(Agreement.DIFFER, d[Criterion.ZONE])
        assertEquals(Agreement.DIFFER, d[Criterion.START])
        assertEquals(Agreement.DIFFER, d[Criterion.ELEVATION])
    }

    @Test
    fun `ordering is by agreeing criteria then date distance and is deterministic`() {
        val all = listOf(
            p(2, "2026-09-01", hr = 160.0), p(3, "2026-09-20"), p(4, "2026-09-25"), p(5, "2026-09-25"), p(6, "2026-08-01"),
        )
        val r = ComparableRuns.find(ref, all + ref)
        assertTrue(r.enough)
        assertEquals(listOf(4L, 5L, 3L, 6L, 2L), r.matches.map { it.run.activityId })
        assertEquals(r.matches.map { it.run.activityId }, ComparableRuns.find(ref, (all + ref).reversed()).matches.map { it.run.activityId })
    }

    @Test
    fun `fewer than 3 matches abstain with the main exclusion reason`() {
        val r = ComparableRuns.find(ref, listOf(p(2), p(3, type = SessionType.RACE), p(4, type = SessionType.RACE), p(5, type = SessionType.INTERVAL)))
        assertFalse(r.enough)
        assertEquals(1, r.matches.size)
        assertTrue(r.message!!.contains("te weinig vergelijkbare runs (n = 1)"))
        assertTrue(r.message!!.contains("sessietype"))
    }

    @Test
    fun `the output types have no score field`() {
        val names = listOf(ComparableRuns.Match::class.java, ComparableRuns.Result::class.java, RunProfile::class.java)
            .flatMap { it.declaredFields.map { f -> f.name.lowercase() } }
        assertTrue(names.none { it.contains("score") || it.contains("similar") })
        val fitness = FitnessView.Summary::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fitness.none { it.contains("score") || it.contains("weight") || it.contains("index") })
    }

    // ---- consistency ----

    private val today = LocalDate.of(2026, 10, 30) // Friday, current week starts 2026-10-26

    private fun weekly(weeks: Int, runsPerWeek: Int = 3): List<Pair<LocalDate, Double>> =
        (1..weeks).flatMap { w ->
            val monday = LocalDate.of(2026, 10, 26).minusWeeks(w.toLong())
            (0 until runsPerWeek).map { monday.plusDays(it * 2L) to 10000.0 }
        }

    @Test
    fun `7 complete weeks abstain and 8 are accepted`() {
        val seven = Consistency.analyze(weekly(7), today)
        assertFalse(seven.enough)
        assertTrue(seven.message!!.contains("7 weken"))
        assertTrue(Consistency.analyze(weekly(8), today).enough)
    }

    @Test
    fun `the current week is excluded and weeks without runs are counted`() {
        val runs = weekly(10).filterNot { LocalDate.of(2026, 10, 26).minusWeeks(3) <= it.first && it.first < LocalDate.of(2026, 10, 26).minusWeeks(2) } +
            listOf(LocalDate.of(2026, 10, 27) to 10000.0)
        val r = Consistency.analyze(runs, today)
        val w = r.last12!!
        assertEquals(12.coerceAtMost(r.completeWeeksOfHistory), w.weeks)
        assertEquals(9 * 3, w.runs)
        assertEquals(1, w.runsPerWeekDistribution["0"])
        assertEquals(9, w.runsPerWeekDistribution["3"])
        assertEquals(0.9, w.shareWeeksWithRun, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 26), r.currentWeekStart)
        assertEquals(7, w.longestStreakWeeks)
    }

    @Test
    fun `gaps of 6 days are not listed and gaps of 7 days are`() {
        val base = weekly(8).toMutableList()
        val gap6 = Consistency.analyze(listOf(LocalDate.of(2026, 9, 7) to 5000.0, LocalDate.of(2026, 9, 13) to 5000.0) + base.filter { it.first.isBefore(LocalDate.of(2026, 9, 1)) || it.first.isAfter(LocalDate.of(2026, 9, 14)) }, today)
        assertTrue(gap6.last12!!.gaps7Plus.none { it.from == LocalDate.of(2026, 9, 7) })
        val gap7 = Consistency.analyze(listOf(LocalDate.of(2026, 9, 7) to 5000.0, LocalDate.of(2026, 9, 14) to 5000.0) + base.filter { it.first.isBefore(LocalDate.of(2026, 9, 1)) || it.first.isAfter(LocalDate.of(2026, 9, 15)) }, today)
        assertTrue(gap7.last12!!.gaps7Plus.any { it.from == LocalDate.of(2026, 9, 7) && it.days == 7L })
    }

    // ---- fitness view ----

    private fun ind(key: String, d: Direction, fav: FitnessView.Favourable) = FitnessView.Indicator(key, key, d, fav, "", 5, 5, "")

    @Test
    fun `fitness view counts favourable directions and needs two verdicts`() {
        val s = FitnessView.summarize(
            listOf(
                ind("ef", Direction.HIGHER, FitnessView.Favourable.WHEN_HIGHER),
                ind("drift", Direction.LOWER, FitnessView.Favourable.WHEN_LOWER),
                ind("dur", Direction.HIGHER, FitnessView.Favourable.WHEN_LOWER),
                ind("cs", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_HIGHER),
            ),
        )
        assertTrue(s.hasSummary)
        assertEquals(2, s.favourable)
        assertEquals(1, s.unfavourable)
        assertEquals(1, s.insufficient)
        assertTrue(s.text.startsWith("Van 4 indicatoren: 2 in gunstige richting"))
    }

    @Test
    fun `fitness view with one verdict shows the indicators but no summary`() {
        val s = FitnessView.summarize(
            listOf(
                ind("ef", Direction.STABLE, FitnessView.Favourable.WHEN_HIGHER),
                ind("drift", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_LOWER),
                ind("dur", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_LOWER),
                ind("cs", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_HIGHER),
            ),
        )
        assertFalse(s.hasSummary)
        assertEquals(4, s.indicators.size)
        assertTrue(s.text.startsWith("Onvoldoende data voor een samenvatting"))
    }
}
