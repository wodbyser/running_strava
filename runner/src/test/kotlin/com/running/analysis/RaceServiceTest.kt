package com.running.analysis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.running.config.GarminProperties
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.domain.GarminSource
import com.running.garmin.spi.GarminRepository
import com.running.race.RaceGoal
import com.running.race.RaceGoalRepository
import com.running.race.RaceWindows
import com.running.strava.db.StravaTokenRepositoryImpl
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RaceServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 30)
    private val src = GarminSource("test", null, "test")

    private class GarminStub : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHeartRateZones() = listOf(GarminHeartRateZones("RUNNING", "LT", 50, 170, 120, 140, 155, 170, 183, 190, GarminSource("test", null, "test")))
    }

    private class MemoryGoals : RaceGoalRepository {
        val goals = mutableListOf<RaceGoal>()
        private var next = 1L
        override fun findAll() = goals.sortedBy { it.date }
        override fun save(distanceMeters: Double, date: LocalDate, targetSeconds: Int?, name: String?): RaceGoal =
            RaceGoal(next++, distanceMeters, date, targetSeconds, name).also { goals += it }
        override fun delete(id: Long) { goals.removeIf { it.id == id } }
    }

    private fun start(daysAgo: Long) = ZonedDateTime.parse("2026-10-30T09:00:00Z").minusDays(daysAgo)
    private fun race(id: Long, daysAgo: Long, meters: Double, seconds: Int, elevation: Float = 0f): Activity =
        Fixtures.run(id, start(daysAgo), meters, seconds, hr = 150.0, workoutType = 1).copy(totalElevationGain = elevation)
    private fun easy(id: Long, daysAgo: Long) = Fixtures.run(id, start(daysAgo), 8000.0, 2800, hr = 140.0)

    private fun tokens(): StravaTokenRepositoryImpl {
        val m = mock(StravaTokenRepositoryImpl::class.java)
        `when`(m.getRestingHr()).thenReturn(null)
        return m
    }

    private fun service(activities: List<Activity>, goals: MemoryGoals = MemoryGoals(), streams: Map<Long, ActivityStream> = emptyMap()): RaceService {
        val repo = FakeActivityRepository(activities, streams = streams)
        val garmin = GarminStub()
        val props = GarminProperties()
        val perf = PerformanceService(repo, garmin, props, clock)
        val recovery = RecoveryService(garmin, tokens(), props, clock)
        val load = LoadService(repo, garmin, perf, recovery, tokens(), props, clock)
        return RaceService(repo, garmin, BestEffortService(repo), perf, load, recovery, goals, props, clock)
    }

    private val races = listOf(
        race(1, 120, 5000.0, 1200),
        race(2, 90, 10000.0, 2520),
        race(3, 60, 5000.0, 1190),
        race(4, 30, 10000.0, 2480),
    )

    @Test
    fun `the overview predicts from the marked races and lists every race with a walk forward outcome`() {
        val o = service(races + (0 until 20).map { easy(100L + it, 1L + it * 3) }).overview()
        assertEquals("race-v1", o.algorithmVersion)
        assertEquals(4, o.raceCount)
        val p5 = o.forecasts.first { it.distanceMeters == 5000.0 }.prediction!!
        assertTrue(p5.anchors.size >= 2)
        assertEquals(listOf(1L, 2L, 3L, 4L), o.outcomes.map { it.race.id })
        assertNull(o.outcomes[0].predictedSeconds)
        assertNotNull(o.outcomes[2].predictedSeconds)
        assertFalse(o.accuracy.enough)
        assertEquals(RangeBasis.ANCHOR_SPREAD, o.forecasts.first { it.prediction != null }.rangeBasis)
    }

    @Test
    fun `without any data the overview and the history still render their abstain state`() {
        val s = service(emptyList())
        val o = s.overview()
        assertEquals(0, o.runCount)
        assertTrue(o.forecasts.all { it.prediction == null })
        assertNull(o.focus)
        assertNull(o.plan)
        val h = s.history()
        assertTrue(h.outcomes.isEmpty())
        assertFalse(h.taperSummary.enough)
        val page = RacePageBuilder.build(o)
        assertEquals("Nog geen voorspelling mogelijk", page.hero.headline)
        assertEquals("Nog geen gemarkeerde wedstrijden", HistoryPageBuilder.build(h).headline)
    }

    @Test
    fun `a goal gets a band, a plan with the same total time and the race week check only inside 7 days`() {
        val goals = MemoryGoals().also { it.save(5000.0, today.plusDays(20), 1150, "Kerstloop") }
        val s = service(races, goals)
        val far = s.overview()
        val focus = far.focus!!
        assertEquals(20, focus.daysToGo)
        assertNotNull(focus.feasibility)
        assertEquals(1150.0, far.plan!!.even.sumOf { it.seconds }, 1e-6)
        assertEquals(1150.0, far.plan!!.calm.sumOf { it.seconds }, 1e-6)
        assertNull(far.raceWeek)
        assertEquals(20L, far.raceWeekHiddenDays)

        goals.save(5000.0, today.plusDays(3), null, null)
        val near = s.overview()
        assertEquals(3, near.focus!!.daysToGo)
        assertNotNull(near.raceWeek)
        assertNull(near.raceWeekHiddenDays)
    }

    @Test
    fun `a goal on the date of a marked race of about the same distance finds that race afterwards`() {
        val goals = MemoryGoals().also { it.save(5000.0, today.minusDays(60), 1200, null) }
        val o = service(races, goals).overview()
        val g = o.goals.single()
        assertTrue(g.past)
        assertEquals(3L, g.matchedRace!!.id)
        assertNull(g.forecast)
    }

    @Test
    fun `trail races are listed and never an anchor`() {
        val trail = race(9, 10, 21000.0, 7800, elevation = 700f)
        val o = service(races + trail).overview()
        assertTrue(o.outcomes.single { it.race.id == 9L }.trail)
        assertTrue(o.anchors.none { it.activityId == 9L })
        assertNull(o.outcomes.single { it.race.id == 9L }.errorPct)
    }

    @Test
    fun `a later race changes no earlier outcome in the history`() {
        val before = service(races).history().outcomes
        val after = service(races + race(8, 3, 5000.0, 1100)).history().outcomes
        before.forEach { b -> assertEquals(b.predictedSeconds, after.single { it.race.id == b.race.id }.predictedSeconds) }
        assertEquals(5, after.size)
    }

    @Test
    fun `the history explains each race with factors, never as a cause`() {
        val h = service(races + easy(50, 61)).history()
        val post = h.postRaces.first { it.race.id == 3L }
        assertNotNull(post.diffPct)
        assertTrue(post.factors.any { it.key == "elevation" })
        assertTrue(post.factors.any { it.key == "warmup" })
        assertTrue(com.running.race.PostRaceAnalysis.NOTE.startsWith("Verband, geen oorzaak"))
    }

    @Test
    fun `the window settings are clamped and the defaults are the design values`() {
        val w = RaceWindowSettings.from(mapOf("anchor" to "5", "chart" to "9999", "taper14" to "3", "taper7" to "10"))
        assertEquals(30, w.anchorDays)
        assertEquals(156, w.chartWeeks)
        assertEquals(10, w.taperLongDays)
        assertEquals(RaceWindows.DEFAULT, RaceWindowSettings.from(emptyMap()))
        assertTrue(RaceWindowSettings.changed(RaceWindows.DEFAULT).isEmpty())
        assertEquals(mapOf("anchor" to 60), RaceWindowSettings.changed(RaceWindows(anchorDays = 60)))
    }

    @Test
    fun `typed times parse as minutes and seconds or hours, minutes and seconds`() {
        assertEquals(1140, RaceFmt.parseTime("19:00"))
        assertEquals(6300, RaceFmt.parseTime("1:45:00"))
        assertEquals(1200, RaceFmt.parseTime("20"))
        assertNull(RaceFmt.parseTime("abc"))
        assertNull(RaceFmt.parseTime("19:"))
        assertNull(RaceFmt.parseTime("0:10"))
        assertNull(RaceFmt.parseTime(""))
    }

    @Test
    fun `no page text has a verdict word`() {
        val goals = MemoryGoals().also { it.save(5000.0, today.plusDays(3), 1150, null) }
        val o = service(races, goals).overview()
        val page = RacePageBuilder.build(o)
        val text = (listOf(page.hero.headline, page.hero.sub) + page.hero.lines + page.fitness.parts.map { it.detail + it.title } + page.planLines +
            (page.weekCheck?.lines.orEmpty())).joinToString(" ").lowercase()
        listOf("haalbaar", "veilig", "klaar voor", "je loopt ").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun `logging has counts only, no times, names or goals`() {
        val logger = LoggerFactory.getLogger(RaceService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val old = logger.level
        logger.level = Level.DEBUG
        try {
            val goals = MemoryGoals().also { it.save(5000.0, today.plusDays(20), 1150, "Kerstloop geheim") }
            val s = service(races, goals)
            s.overview()
            s.history()
        } finally {
            logger.detachAppender(appender)
            logger.level = old
        }
        val text = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(text.contains("race-v1"))
        listOf("run 1", "Kerstloop", "1150", "1200", "2520").forEach { assertFalse(text.contains(it), it) }
    }
}
