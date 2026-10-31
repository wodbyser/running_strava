package com.running.analysis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.running.config.GarminProperties
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminRestingHrDay
import com.running.garmin.domain.GarminSource
import com.running.garmin.spi.GarminRepository
import com.running.load.HrReference
import com.running.load.OwnLevel
import com.running.load.SessionClass
import com.running.load.Trimp
import com.running.strava.db.StravaTokenRepositoryImpl
import com.running.strava.domain.ActivityStream
import com.running.strava.spi.ActivityRepository
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

class LoadServiceTest {
    private val now = Instant.parse("2026-10-30T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 30)
    private val src = GarminSource("test", null, "test")
    private val zones = listOf(GarminHeartRateZones("RUNNING", "LT", 50, 170, 120, 140, 155, 170, 183, 190, src))

    private class GarminStub(
        val zones: List<GarminHeartRateZones> = emptyList(),
        val hrv: List<GarminHrvDay> = emptyList(),
        val rhr: List<GarminRestingHrDay> = emptyList(),
    ) : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHeartRateZones() = zones
        override fun findHrv(from: LocalDate, to: LocalDate) = hrv.filter { it.calendarDate in from..to }
        override fun findRestingHr(from: LocalDate, to: LocalDate) = rhr.filter { it.calendarDate in from..to }
    }

    private class CountingRepo(private val inner: FakeActivityRepository) : ActivityRepository by inner {
        var streamCalls = 0
        override fun findStreams(activityId: Long): ActivityStream? { streamCalls++; return inner.findStreams(activityId) }
    }

    private fun stream(seconds: Int, hr: Int = 150): ActivityStream {
        val t = (0..seconds).toList()
        return ActivityStream(t, t.map { it * 3f }, null, null, t.map { hr }, null, null, null, null)
    }

    private fun start(daysAgo: Long) = ZonedDateTime.parse("2026-10-30T09:00:00Z").minusDays(daysAgo)
    private fun run(id: Long, daysAgo: Long, hr: Double? = 150.0) = Fixtures.run(id, start(daysAgo), 7200.0, 2400, hr = hr, maxHr = 190.0)
    private fun walk(id: Long, daysAgo: Long) = Fixtures.run(id, start(daysAgo), 3000.0, 2400, hr = 110.0, type = "Walk")

    private fun hrv(days: Int) = (0 until days).map { GarminHrvDay(today.minusDays(it.toLong()), 50 + it % 2, 50, null, 40, 45, 60, null, "BALANCED", null, src) }
    private fun rhr(days: Int) = (0 until days).map { GarminRestingHrDay(today.minusDays(it.toLong()), 50 + it % 2, null, null, src) }

    private fun tokens(): StravaTokenRepositoryImpl {
        val m = mock(StravaTokenRepositoryImpl::class.java)
        `when`(m.getRestingHr()).thenReturn(null)
        return m
    }

    private val expectedRun = 2400 / 60.0 * Trimp.perMinute(HrReference(190, 50, "t").intensity(150.0), 1.92)

    private fun service(
        activities: List<com.running.strava.domain.Activity>,
        garmin: GarminStub = GarminStub(zones, hrv(120), rhr(120)),
        props: GarminProperties = GarminProperties(),
        streams: Map<Long, ActivityStream>? = null,
    ): Pair<LoadService, CountingRepo> {
        val repo = CountingRepo(FakeActivityRepository(activities, streams = streams ?: activities.filter { it.type == "Run" && it.averageHeartrate != null }.associate { it.id to stream(2400) }))
        val perf = PerformanceService(repo, garmin, props, clock)
        val recovery = RecoveryService(garmin, tokens(), props, clock)
        return LoadService(repo, garmin, perf, recovery, tokens(), props, clock) to repo
    }

    /** A run every 2 days for 80 days, the last one yesterday. */
    private val regular = (0 until 40).map { run(it + 1L, 1L + it * 2) }

    @Test
    fun `internal load per day is the Banister value of the stream and rest days are 0`() {
        val (s, _) = service(regular)
        val o = s.overview()
        assertEquals("load-v1", o.algorithmVersion)
        val runDay = o.days.first { it.date == today.minusDays(1) }
        assertEquals(expectedRun, runDay.runTrimp!!, 1e-6)
        val restDay = o.days.first { it.date == today.minusDays(2) }
        assertEquals(0.0, restDay.trimp)
        assertEquals(HrReference(190, 50, "").maxHr, o.reference!!.maxHr)
        assertTrue(o.reference!!.source.contains("Garmin"))
    }

    @Test
    fun `acute and chronic windows end yesterday and today is shown apart`() {
        val (s, _) = service(regular + run(500, 0))
        val o = s.overview()
        // runs 1, 3, 5, 7 days ago (days 1..7 before today): 4 runs; today's run is not in the window
        assertEquals(4 * expectedRun, o.acute.sum!!, 1e-6)
        assertEquals(expectedRun, o.todayLoad!!.trimp!!, 1e-6)
        assertEquals(28, o.chronic.knownDays)
        assertEquals(14 * expectedRun, o.chronic.sum!!, 1e-6)
        assertEquals((4 / 3.5 - 1) * 100, o.differencePct!!, 1e-6) // 4 runs in 7 days against an average week of 3.5 runs
        assertTrue(o.days.last().today)
    }

    @Test
    fun `session classes and coverage counts are reported`() {
        val (s, _) = service(regular + walk(600, 3) + run(601, 10, hr = null))
        val o = s.overview()
        assertEquals(40 + 1 + 1, o.coverage.sessions)
        assertEquals(41, o.coverage.runs)
        assertEquals(1, o.coverage.otherSports)
        assertEquals(1, o.coverage.withoutHr)
        assertEquals(40, o.coverage.fromStream)
        assertEquals(1, o.coverage.fromAverage) // the walk has an average heart rate but no stream
        assertNotNull(o.coverage.firstHrDate)
    }

    @Test
    fun `other sports with heart rate count apart, and can be switched off`() {
        val acts = regular + walk(600, 3)
        val on = service(acts).first.overview().days.first { it.date == today.minusDays(3) }
        assertTrue(on.otherTrimp!! > 0)
        val off = service(acts, props = GarminProperties(load = GarminProperties.Load(includeOtherSports = false))).first.overview()
        assertEquals(0, off.coverage.otherSports)
        assertEquals(0.0, off.days.first { it.date == today.minusDays(3) }.let { (it.otherTrimp ?: 0.0) })
    }

    @Test
    fun `an activity without heart rate is unknown, not 0, and days before the first heart rate are unknown`() {
        val acts = listOf(run(1, 40, hr = null)) + (0 until 10).map { run(10L + it, 30L - it * 2) }
        val o = service(acts, streams = (0 until 10).associate { (10L + it) to stream(2400) }).first.overview(com.running.load.LoadWindows(chartDays = 60))
        val before = o.days.first { it.date == today.minusDays(40) }
        assertNull(before.trimp)
        assertNull(before.runTrimp)
        assertEquals(1, o.coverage.withoutHr)
        assertEquals(today.minusDays(30), o.coverage.firstHrDate)
    }

    @Test
    fun `without heart rate data the page says so instead of failing`() {
        val acts = (0 until 5).map { run(it + 1L, 1L + it, hr = null) }
        val s = service(acts, streams = emptyMap()).first
        val o = s.overview()
        assertNull(o.coverage.firstHrDate)
        val page = LoadPageBuilder.build(o)
        assertEquals("hero-na", page.hero.css)
        assertTrue(page.hero.headline.contains("Nog geen belasting"))
        assertTrue(page.coverage.any { it.contains("Zonder hartslag, dus zonder belasting: 5") })
    }

    @Test
    fun `without any reference the page still renders`() {
        val (s, _) = service(regular, garmin = GarminStub(), streams = emptyMap())
        val o = s.overview()
        // no Garmin zones and no resting heart rate: no reference, no sessions
        assertNull(o.reference)
        assertEquals(0, o.coverage.sessions)
        assertEquals("hero-na", LoadPageBuilder.build(o).hero.css)
        assertNotNull(LoadResponsePageBuilder.build(s.response()).headline)
    }

    @Test
    fun `pages build with and without Garmin recovery data`() {
        val without = service(regular, garmin = GarminStub(zones)).first
        val o = without.overview()
        assertNull(o.readiness?.deviating)
        val page = LoadPageBuilder.build(o)
        assertTrue(page.hero.lines.any { it.contains("te weinig Garmin-data") })
        val with = service(regular).first.overview()
        assertNotNull(with.readiness)
        assertTrue(LoadPageBuilder.build(with).types.isNotEmpty())
    }

    @Test
    fun `the fatigue-fitness model abstains with too little history and says why`() {
        val o = service(regular).first.overview()
        assertFalse(o.model.showCurve)
        val m = LoadPageBuilder.build(o).model
        assertFalse(m.passed)
        assertTrue(m.lines.joinToString(" ").contains("Het model wordt pas getoond"))
        assertTrue(LoadPageBuilder.build(o).forecasts.all { !it.passed })
    }

    @Test
    fun `per activity results are cached`() {
        val (s, repo) = service(regular)
        s.overview()
        val first = repo.streamCalls
        s.overview()
        assertEquals(first, repo.streamCalls) // load cache and the Phase 3 cache both hit
    }

    @Test
    fun `warming up fills the caches so the first page request reads no stream again`() {
        val (s, repo) = service(regular)
        assertTrue(s.warmUp())
        val afterWarm = repo.streamCalls
        assertTrue(afterWarm > 0)
        s.overview()
        s.response()
        assertEquals(afterWarm, repo.streamCalls)
        assertTrue(GarminProperties().load.warmUp)
    }

    @Test
    fun `a failing warm-up is swallowed and reported as false`() {
        val broken = object : ActivityRepository by FakeActivityRepository(emptyList()) {
            override fun findAll(after: ZonedDateTime?): List<com.running.strava.domain.Activity> = error("boom")
        }
        val garmin = GarminStub(zones)
        val props = GarminProperties()
        val s = LoadService(broken, garmin, PerformanceService(broken, garmin, props, clock), RecoveryService(garmin, tokens(), props, clock), tokens(), props, clock)
        assertFalse(s.warmUp())
    }

    @Test
    fun `windows are read from parameters, clamped and keep the other choices`() {
        val w = LoadWindowSettings.from(mapOf("acute" to "1", "chronic" to "5", "chart" to "9999", "follow" to "5", "horizon" to "3", "deload" to "abc"))
        assertEquals(3, w.acuteDays)
        assertEquals(14, w.chronicDays)
        assertEquals(730, w.chartDays)
        assertEquals(5, w.followUpMornings)
        assertEquals(6, w.recoveryHorizon) // always longer than the follow-up
        assertEquals(70, w.deloadPct)
        assertEquals(com.running.load.LoadWindows.DEFAULT, LoadWindowSettings.from(emptyMap()))
        assertTrue(LoadWindowSettings.changed(com.running.load.LoadWindows.DEFAULT).isEmpty())
        assertEquals(mapOf("acute" to 14), LoadWindowSettings.changed(com.running.load.LoadWindows(acuteDays = 14)))
    }

    @Test
    fun `a chosen period changes what is counted`() {
        val (s, _) = service(regular)
        val short = s.overview(com.running.load.LoadWindows(acuteDays = 3, chronicDays = 14, chartDays = 60))
        assertEquals(60, short.days.size)
        assertEquals(2 * expectedRun, short.acute.sum!!, 1e-6) // runs 1 and 3 days ago
        assertEquals(3, short.acute.days)
    }

    @Test
    fun `week and tile texts use dutch dates and the abstain texts name the minimum`() {
        val (s, _) = service(regular)
        val page = LoadPageBuilder.build(s.overview())
        assertTrue(page.weekRows.none { it.label.contains("-") && it.label.matches(Regex(".*\\d{4}-\\d{2}-\\d{2}.*")) })
        assertTrue(page.tiles.first().label.startsWith("Laatste 7 dagen"))
        val resp = LoadResponsePageBuilder.build(s.response())
        assertTrue(resp.recoveryTime.table!!.rows.all { it.cells[1].contains("onvoldoende data") || it.cells[1].contains("ochtend") })
        assertTrue(resp.detraining.lines.joinToString(" ").contains("minstens 3 bruikbare onderbrekingen"))
    }

    @Test
    fun `no text says safe, ready, overtrained or injury risk`() {
        val (s, _) = service(regular)
        val o = s.overview()
        val p = LoadPageBuilder.build(o)
        val r = LoadResponsePageBuilder.build(s.response())
        val text = (listOf(p.hero.headline, p.hero.sub) + p.hero.lines + p.types.flatMap { listOf(it.label, it.level) + it.parts.flatMap { x -> listOf(x.title, x.detail) } } +
            p.signalRows.flatMap { listOf(it.title, it.rule, it.value) } + p.model.lines + p.forecasts.map { it.text } + r.findings + listOf(r.headline)).joinToString(" ").lowercase()
        listOf("veilig", "klaar om", "overtraind", "blessurerisico", "optimaal", "optimale").forEach { assertFalse(text.contains(it), it) }
        assertEquals(setOf(SessionClass.EASY, SessionClass.LONG), o.readiness!!.types.size.let { setOf(SessionClass.EASY, SessionClass.LONG) })
        // the own-norm level is never a risk zone
        assertTrue(OwnLevel.entries.none { it.name.contains("RISK") })
    }

    @Test
    fun `logging has counts only, no heart rate, load, activity names or recovery values`() {
        val logger = LoggerFactory.getLogger(LoadService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val old = logger.level
        logger.level = Level.DEBUG
        try {
            val (s, _) = service(regular)
            s.overview()
            s.response()
        } finally {
            logger.detachAppender(appender)
            logger.level = old
        }
        val text = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(text.contains("Load base load-v1"))
        assertTrue(text.contains("Load overview load-v1"))
        assertTrue(text.contains("Load response load-v1"))
        assertFalse(text.contains("run 1"))
        assertFalse(text.contains(expectedRun.toInt().toString() + "."))
    }
}
