package com.running.race

import com.running.load.LoadSession
import com.running.load.SessionClass
import com.running.load.TrimpSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TaperTest {
    private val race = LocalDate.of(2026, 6, 14)

    /** 100 a day from 42 to 15 days before the race, 100 a day 14 to 8 days before, 50 a day in the last 7 days. */
    private fun daily(): Map<LocalDate, Double> =
        (1..42).associate { race.minusDays(it.toLong()) to if (it <= 7) 50.0 else 100.0 }

    @Test
    fun `taper percentages by hand`() {
        val t = Taper.compute(daily(), race)
        assertEquals(700.0, t.baseWeek!!, 1e-9)
        assertEquals(50.0, t.load7Pct!!, 1e-9)
        // 14 days: 7 x 100 + 7 x 50 = 1050, per week 525, 525 / 700 = 75 %
        assertEquals(75.0, t.load14Pct!!, 1e-9)
    }

    @Test
    fun `no taper number when too few days are known`() {
        val sparse = daily().filterKeys { it.isBefore(race.minusDays(30)) || it.isAfter(race.minusDays(9)) }
        assertNull(Taper.compute(sparse.filterKeys { it.isBefore(race.minusDays(3)) }, race).load7Pct)
        assertNull(Taper.compute(emptyMap(), race).load7Pct)
    }

    @Test
    fun `five races list only, six summarise and the fixed seed is reproducible`() {
        val pairs = listOf(50.0 to -1.0, 70.0 to 0.5, 90.0 to 1.0, 60.0 to -0.5, 80.0 to 0.2, 100.0 to 2.0)
        assertFalse(Taper.summary(pairs.take(5)).enough)
        assertNull(Taper.summary(pairs.take(5)).rho)
        val a = Taper.summary(pairs)
        val b = Taper.summary(pairs)
        assertTrue(a.enough)
        assertNotNull(a.rho)
        assertEquals(a.pValue, b.pValue)
        assertEquals(a.rho, b.rho)
    }
}

class PostRaceTest {
    private val d = LocalDate.of(2026, 6, 14)

    private fun context(race: RaceRun, others: List<RaceRun> = emptyList(), predicted: Double? = 1200.0, taper: TaperResult? = null): PostRaceContext {
        val trail = Races.isTrail(race)
        val outcome = RaceOutcome(race, trail, if (trail) null else predicted, 3, predicted?.let { (race.movingSeconds - it) / it * 100 }, null, null)
        return PostRaceContext(outcome, others + race, emptyList(), taper, "2 herstelwaarden wijken af van je norm", 184)
    }

    @Test
    fun `a race with a slow second half, a warm up run and a short measured distance lists those factors`() {
        val race = R.race(1, d, 4820.0, 1260, hr = 178.0, halves = Halves(620.0, 640.0))
        val warm = R.run(2, d, 2000.0, 700, startHour = 8, elapsed = 700).copy(startEpochSeconds = race.startEpochSeconds - 1800 - 700)
        val earlier = listOf(R.race(3, d.minusDays(90), 5000.0, 1230, hr = 176.0), R.race(4, d.minusDays(60), 4900.0, 1225, hr = 174.0))
        val pr = PostRaceAnalysis.analyze(context(race, earlier + warm, taper = TaperResult(80.0, 85.0, 500.0)))
        assertEquals(5.0, pr.diffPct!!, 1e-9)
        assertEquals(60.0 / 4.82, pr.diffSecondsPerKm!!, 1e-9)
        val byKey = pr.factors.associateBy { it.key }
        assertTrue(byKey.getValue("pacing").detail.contains("trager in de tweede helft"))
        assertTrue(byKey.getValue("hr").detail.contains("n = 2"))
        assertTrue(byKey.getValue("hr").detail.contains("184"))
        assertTrue(byKey.getValue("distance").detail.contains("korter dan 5 km"))
        assertTrue(byKey.getValue("warmup").detail.startsWith("1 aparte run"))
        assertTrue(byKey.getValue("taper").detail.contains("80 %"))
        assertTrue(byKey.getValue("recovery").detail.contains("2 herstelwaarden"))
    }

    @Test
    fun `a trail race has a reason instead of a comparison`() {
        val pr = PostRaceAnalysis.analyze(context(R.race(1, d, 21000.0, 8000, elev = 30.0)))
        assertTrue(pr.trail)
        assertNull(pr.diffPct)
        assertEquals("Trail, geen vergelijking met een wegvoorspelling.", pr.note)
    }

    @Test
    fun `without a prediction the note says why`() {
        val pr = PostRaceAnalysis.analyze(context(R.race(1, d, 5000.0, 1200), predicted = null))
        assertNull(pr.diffPct)
        assertTrue(pr.note!!.contains("Geen voorspelling"))
    }

    @Test
    fun `no explanation text uses the word cause as a claim`() {
        assertTrue(PostRaceAnalysis.NOTE.startsWith("Verband, geen oorzaak"))
    }
}

class RetrospectiveTest {
    private val base = LocalDate.of(2026, 1, 5)

    private fun session(id: Long, date: LocalDate, km: Double, cls: SessionClass = SessionClass.EASY) =
        LoadSession(id, date, "run $id", "Run", true, cls, (km * 360).toInt(), km * 1000, 0.0, null, null, km * 10, TrimpSource.STREAM, null)

    @Test
    fun `preparation row of one race by hand`() {
        val race = base.plusWeeks(10)
        val outcome = RaceOutcome(R.race(1, race, 5000.0, 1200), false, 1250.0, 3, -4.0, null, null)
        val sessions = listOf(
            session(1, race.minusDays(40), 10.0),
            session(2, race.minusDays(20), 20.0, SessionClass.LONG),
            session(3, race.minusDays(10), 8.0, SessionClass.TEMPO),
            session(4, race.minusDays(60), 30.0),
        )
        val daily = (1..70).associate { race.minusDays(it.toLong()) to 100.0 }
        val row = Retrospective.rows(listOf(outcome), sessions, daily, 8, 7, 14).single()
        assertEquals(38.0 / 8, row.avgWeeklyKm, 1e-9)
        assertEquals(1, row.longRuns)
        assertEquals(1, row.hardSessions)
        assertEquals(20.0, row.longestRunKm, 1e-9)
        assertEquals(700.0, row.avgWeeklyLoad!!, 1e-9)
        assertEquals(100.0, row.taper7Pct!!, 1e-9)
        assertEquals(-4.0, row.errorPct)
    }

    private fun row(i: Int, err: Double) = PrepRow(i.toLong(), base.plusDays(i.toLong()), "r$i", 30.0 + i, 500.0 + i, i, i, 15.0 + i, 80.0 + i, err)

    @Test
    fun `groups of 3 abstain and groups of 4 compare`() {
        val three = (1..3).map { row(it, -1.0) } + (4..7).map { row(it, 1.0) }
        assertNull(Retrospective.groups(three))
        val four = (1..4).map { row(it, -1.0) } + (5..8).map { row(it, 1.0) }
        val g = Retrospective.groups(four)!!
        assertEquals(4, g.faster.n)
        assertEquals(4, g.slower.n)
        assertEquals(32.5, g.faster.medians.getValue("km")!!, 1e-9)
        assertEquals(36.5, g.slower.medians.getValue("km")!!, 1e-9)
    }

    @Test
    fun `trail races are not part of the retrospective`() {
        val trail = RaceOutcome(R.race(1, base, 21000.0, 8000, elev = 30.0), true, null, 0, null, null, null)
        assertTrue(Retrospective.rows(listOf(trail), emptyList(), emptyMap(), 8, 7, 14).isEmpty())
    }
}

class GoalMatchTest {
    private val goal = RaceGoal(1, 5000.0, LocalDate.of(2026, 12, 5), 1140, null)

    @Test
    fun `a goal matches a race on the date within 10 percent of the distance, not at 11 percent`() {
        val d = goal.date
        assertNotNull(Races.match(goal, listOf(R.race(1, d, 4820.0, 1200))))
        assertNotNull(Races.match(goal, listOf(R.race(1, d, 5500.0, 1300))))
        assertNull(Races.match(goal, listOf(R.race(1, d, 5550.0, 1300))))
        assertNull(Races.match(goal, listOf(R.race(1, d, 4450.0, 1200))))
        assertNull(Races.match(goal, listOf(R.race(1, d.plusDays(1), 5000.0, 1200))))
        assertNull(Races.match(goal, listOf(R.run(1, d, 5000.0, 1200, workoutType = 0))))
    }
}

class CandidatesTest {
    private val d = LocalDate.of(2026, 5, 1)

    @Test
    fun `a marked race is never a candidate, a race word or a time in the name is a strong one`() {
        val runs = listOf(
            R.race(1, d, 10000.0, 2900),
            R.run(2, d.plusDays(1), 21000.0, 6000, name = "Halve marathon Gent 1:39:12"),
            R.run(3, d.plusDays(2), 8000.0, 2900, name = "Ochtendloop"),
            R.run(4, d.plusDays(3), 8000.0, 2400, hr = 174.0, name = "Tempo"),
        )
        val c = Candidates.find(runs)
        assertEquals(setOf(2L, 4L), c.map { it.run.id }.toSet())
        assertTrue(c.single { it.run.id == 2L }.strong)
        assertFalse(c.single { it.run.id == 4L }.strong)
        assertTrue(c.single { it.run.id == 4L }.reasons.contains("gemiddelde hartslag van 174 bpm"))
    }

    @Test
    fun `short runs and treadmill runs are skipped`() {
        val runs = listOf(R.run(1, d, 2000.0, 400, name = "Race"), R.run(2, d, 9000.0, 2400, name = "Race", trainer = true))
        assertTrue(Candidates.find(runs).isEmpty())
    }
}
