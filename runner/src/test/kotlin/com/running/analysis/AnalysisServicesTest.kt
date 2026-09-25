package com.running.analysis

import com.running.analysis.Fixtures.run
import com.running.analysis.Fixtures.stream
import com.running.strava.analysis.DateRange
import com.running.strava.analysis.EfVerdict
import com.running.strava.domain.Lap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class AnalysisServicesTest {

    private fun at(date: String) = ZonedDateTime.parse("${date}T08:00:00Z")

    private fun sixBy800(hr: Float?): List<Lap> {
        val rows = listOf(1000 to 350, 1000 to 345) + (1..6).flatMap { listOf(800 to 180, 200 to 90) } + listOf(1000 to 355)
        return rows.mapIndexed { i, (d, t) ->
            val isRep = d == 800
            Lap(i.toLong(), null, t, t, at("2026-01-01"), 0, 0, d.toFloat(), d.toFloat() / t, 0f,
                if (hr == null) null else if (isRep) hr else 140f, null, 84f, i + 1, 0)
        }
    }

    @Test
    fun `period comparison - EF population, exclusions, verdict`() {
        val a = (1..5).map { run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0) } + listOf(
            run(10, at("2026-01-10"), 10000.0, 3000, hr = null),               // no HR: excluded
            run(11, at("2026-01-11"), 5000.0, 1200, 175.0, workoutType = 1),   // race: excluded
            run(12, at("2026-01-12"), 10000.0, 4000, 150.0, type = "TrailRun"), // trail: excluded
        )
        val b = (1..5).map { run(100L + it, at("2026-03-0$it"), 10000.0, 3300, 140.0) }
        val service = PeriodComparisonService(FakeActivityRepository(a + b))
        val r = service.compare(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        )
        assertEquals(5, r.periodA.easyRunCount)
        assertEquals(1, r.periodA.easyRunsWithoutHr)
        assertEquals(1, r.periodA.excludedRaces)
        assertEquals(1, r.periodA.excludedTrailOrTreadmill)
        // EF A = 50000 / (140 * 18000) ; EF B = 50000 / (140 * 16500)
        assertEquals(50000.0 / (140 * 18000), r.periodA.easyEf!!, 1e-12)
        assertEquals(50000.0 / (140 * 16500), r.periodB.easyEf!!, 1e-12)
        // pace of the EF population only: 3600 s / 10 km = 6:00 (the fast HR-less run must not pull it to 5:45)
        assertEquals("6:00 /km", r.periodA.easyAvgPace)
        // change = 3600/3300 - 1 = +9.09 %
        assertEquals(9.0909, r.easyEfChangePct!!, 1e-3)
        assertEquals(EfVerdict.Status.IMPROVED, r.easyVerdict.status)
        assertEquals(EfVerdict.Status.INSUFFICIENT_DATA, r.intervalVerdict.status)
        assertTrue(r.verdict.contains("onvoldoende data"))
    }

    @Test
    fun `period comparison - four easy runs is not enough for a verdict`() {
        val a = (1..4).map { run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0) }
        val b = (1..5).map { run(100L + it, at("2026-03-0$it"), 10000.0, 3000, 140.0) }
        val r = PeriodComparisonService(FakeActivityRepository(a + b)).compare(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        )
        assertEquals(EfVerdict.Status.INSUFFICIENT_DATA, r.easyVerdict.status)
        assertTrue(r.verdict.contains("onvoldoende data"))
        assertTrue(!r.verdict.contains("verbetering"))
    }

    @Test
    fun `period comparison - interval EF uses reps with HR only`() {
        val sessions = (1..3).map { run(it.toLong(), at("2026-01-0$it"), 7800.0, 2200, 150.0) }
        val laps = sessions.associate { it.id to sixBy800(170f) }
        val r = PeriodComparisonService(FakeActivityRepository(sessions, laps)).compare(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
        )
        assertEquals(3, r.periodA.intervalSessionCount)
        assertEquals(18, r.periodA.intervalRepCount)
        // 3 x 6 x 800 m in 180 s at 170 bpm: EF = 14400 / (170 * 3240)
        assertEquals(14400.0 / (170 * 3240), r.periodA.intervalEf!!, 1e-12)
        assertEquals("3:45 /km", r.periodA.intervalAvgPace)
        assertEquals(0, r.periodA.easyRunCount)
    }

    @Test
    fun `personal records are best efforts over the exact distance`() {
        val now = ZonedDateTime.now()
        val tenK = run(1, now.minusDays(3), 10000.0, 2700)
        val short = run(2, now.minusDays(2), 4900.0, 1089, workoutType = 1)
        val treadmill = run(3, now.minusDays(1), 5000.0, 1000, trainer = true)
        val repo = FakeActivityRepository(
            listOf(tenK, short, treadmill),
            streams = mapOf(
                1L to stream(1500 to 1000.0 / 300, 1200 to 1000.0 / 240), // 5 km @ 5:00 then 5 km @ 4:00
                2L to stream(1089 to 4.5),                                // 4900.5 m @ 4.5 m/s
                3L to stream(1000 to 5.0),
            ),
        )
        val pbs = BestEffortService(repo).personalRecords(listOf(tenK, short, treadmill))
        val byLabel = pbs.records.associateBy { it.label }
        assertEquals("3:42", byLabel.getValue("1 km").time)  // 1000 / 4.5 = 222.2 s, from the 4.9 km race
        assertEquals(2L, byLabel.getValue("1 km").activityId)
        assertEquals("20:00", byLabel.getValue("5 km").time) // fast second half of the 10 km, not the 4.9 km race
        assertEquals(1L, byLabel.getValue("5 km").activityId)
        assertEquals("45:00", byLabel.getValue("10 km").time)
        assertEquals("-", byLabel.getValue("Halve marathon").time)
        assertEquals(1, pbs.treadmillExcluded)
        assertEquals(2, pbs.runsWithStreams)
    }

    @Test
    fun `race predictions use continuous efforts only, median, rounded`() {
        val now = ZonedDateTime.now()
        val race5 = run(1, now.minusDays(20), 5000.0, 1200, 175.0, workoutType = 1)
        val race10 = run(2, now.minusDays(10), 10000.0, 2520, 172.0, workoutType = 1)
        val intervals = run(3, now.minusDays(5), 7800.0, 2200, 150.0)
        val repo = FakeActivityRepository(listOf(race5, race10, intervals), laps = mapOf(3L to sixBy800(175f)))
        val coach = CoachService(repo, BestEffortService(repo)).calculateCoachData(listOf(race5, race10, intervals))
        val p = coach.racePredictions.associateBy { it.distanceMeters }
        // 10 km: median(1200 * 2^1.06 = 2501.9, 2520) = 2510.96 -> 15 s steps -> 2505 s
        assertEquals("≈ 41:45", p.getValue(10000f).predictedTime)
        // 5 km: median(1200, 2520 / 2^1.06 = 1208.67) = 1204.3 s; >= 20 min so 15 s steps -> 1200 s
        assertEquals("≈ 20:00", p.getValue(5000f).predictedTime, p.getValue(5000f).basedOn)
        assertEquals("20:00 – 20:15", p.getValue(5000f).range)
        // marathon: no anchor within 0.4-2.5x -> no number, explicit note
        assertEquals("-", p.getValue(42195f).predictedTime)
        assertNotNull(p.getValue(42195f).note)
        // interval reps (800 m in 180 s) are never anchors
        assertTrue(coach.racePredictions.none { it.basedOn?.contains("intervalrep") == true })
    }

    @Test
    fun `no predictions from interval reps alone`() {
        val now = ZonedDateTime.now()
        val intervals = (1..3).map { run(it.toLong(), now.minusDays(it.toLong()), 7800.0, 2200, 150.0) }
        val repo = FakeActivityRepository(intervals, laps = intervals.associate { it.id to sixBy800(175f) })
        val coach = CoachService(repo, BestEffortService(repo)).calculateCoachData(intervals)
        assertTrue(coach.racePredictions.all { it.predictedTime == "-" })
        assertTrue(coach.recentFormPredictions.all { it.predictedTime == "-" })
    }

    @Test
    fun `hr zones only use karvonen with a user resting hr, and max hr is labelled an estimate`() {
        val now = ZonedDateTime.now()
        val runs = (1..20).map { run(it.toLong(), now.minusDays(it.toLong()), 8000.0, 2800, 145.0, maxHr = 180.0 + it) }
        val repo = FakeActivityRepository(runs)
        val coach = CoachService(repo, BestEffortService(repo))
        val noRhr = coach.calculateCoachData(runs)
        // max HR values 181..200 -> nearest-rank p95 = 19th value = 199
        assertEquals(199, noRhr.maxHr)
        assertEquals(listOf("pctMaxHr"), noRhr.hrZoneMethods.map { it.method })
        assertNull(noRhr.restingHr)
        assertTrue(noRhr.maxHrSource.startsWith("schatting"))
        val withRhr = coach.calculateCoachData(runs, restingHr = 48)
        assertEquals(listOf("pctMaxHr", "karvonen"), withRhr.hrZoneMethods.map { it.method })
        // profile cadence doubled: 84 per foot -> 168 spm
        assertEquals("168 spm", withRhr.runnerProfile.avgCadence)
    }
}
