package com.running.analysis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.running.config.GarminProperties
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.spi.GarminRepository
import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.performance.Stratum
import com.running.strava.domain.ActivityStream
import com.running.strava.spi.ActivityRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PerformanceServiceTest {
    private val now = Instant.parse("2026-10-30T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class GarminStub(val zones: List<GarminHeartRateZones> = emptyList()) : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHeartRateZones() = zones
    }

    private class CountingRepo(private val inner: FakeActivityRepository) : ActivityRepository by inner {
        var streamCalls = 0
        override fun findStreams(activityId: Long): ActivityStream? { streamCalls++; return inner.findStreams(activityId) }
    }

    private fun stream(seconds: Int): ActivityStream {
        val t = (0..seconds).toList()
        return ActivityStream(t, t.map { it * 3f }, null, null, t.map { 150 + if (it % 2 == 0) -1 else 1 }, null, null, null, null)
    }

    private fun run(id: Long, daysAgo: Long) = Fixtures.run(
        id, ZonedDateTime.parse("2026-10-30T10:00:00Z").minusDays(daysAgo), 7200.0, 2400, hr = 150.0, maxHr = 190.0,
    ).copy(startLatlng = listOf(51.23456, 4.98765))

    private val ids = listOf(2L, 5L, 8L, 11L, 14L, 17L, 30L, 35L, 40L, 45L, 50L, 55L)
    private val runs = ids.mapIndexed { i, d -> run(i + 1L, d) } + Fixtures.run(99, ZonedDateTime.parse("2026-10-20T10:00:00Z"), 5000.0, 1800, hr = null)

    private fun service(zones: List<GarminHeartRateZones> = emptyList()): Pair<PerformanceService, CountingRepo> {
        val repo = CountingRepo(FakeActivityRepository(runs, streams = runs.filter { it.id != 99L }.associate { it.id to stream(2400) }))
        return PerformanceService(repo, GarminStub(zones), GarminProperties(), clock) to repo
    }

    @Test
    fun `report counts runs, steady runs and reasons, and compares inside one stratum`() {
        val (s, _) = service()
        val r = s.report()
        assertEquals(13, r.runCount)
        assertEquals(12, r.runsWithStream)
        assertEquals(12, r.steadyCount)
        assertEquals(1, r.failureCounts["geen stroomdata"])
        val t = r.efTrends.single { it.stratum == Stratum.ZONE_3 && it.windowDays == 28 }
        assertEquals(6, t.nA)
        assertEquals(6, t.nB)
        assertEquals(EfVerdict.Status.STABLE, t.status)
        assertEquals(0, r.efTrends.single { it.stratum == Stratum.ZONE_1_2 && it.windowDays == 28 }.nB)
        assertEquals("performance-v1", r.algorithmVersion)
    }

    @Test
    fun `drift and decoupling medians use the same runs, those with an equal pace in both halves`() {
        // runs 1-5: constant pace (decoupling 0). runs 6-11: 10 % faster in the second half at the same heart rate:
        // the pace explains the decoupling, so these runs must stay out of both medians.
        fun fastEnd(): ActivityStream {
            val t = (0..2400).toList()
            var d = 0f
            val dist = t.map { s -> d += if (s <= 1500) 3.0f else 3.3f; d }
            return ActivityStream(t, dist, null, null, t.map { 150 + if (it % 2 == 0) -1 else 1 }, null, null, null, null)
        }
        val acts = (1..11).map { run(it.toLong(), 2L + it * 3) }
        val streams = acts.associate { it.id to if (it.id <= 5) stream(2400) else fastEnd() }
        val s = PerformanceService(FakeActivityRepository(acts, streams = streams), GarminStub(), GarminProperties(), clock)
        val r = s.report()
        val ok = r.driftRows.filter { it.drift.driftAvailable }
        val skipped = r.driftRows.filter { !it.drift.driftAvailable }
        assertEquals(5, ok.size)
        assertTrue(skipped.isNotEmpty(), "the speed-up runs are steady but have no usable drift")
        assertTrue(skipped.all { it.drift.decouplingPct < -5.0 })
        // decoupling summary equals the median of the same population as the drift: about 0, not the -9 of the mixed set
        assertTrue(r.driftSummary.contains("ontkoppeling 0.0 %"), r.driftSummary)
        assertTrue(r.driftSummary.contains("over 5 stabiele runs"), r.driftSummary)
        val tiles = PerformancePageBuilder.build(r).drift.tiles
        assertEquals("0.0 %", tiles[1].value)
    }

    @Test
    fun `without any usable drift the summary and tiles stay empty and do not crash`() {
        val s = PerformanceService(FakeActivityRepository(emptyList()), GarminStub(), GarminProperties(), clock)
        val r = s.report()
        assertTrue(r.driftRows.isEmpty())
        assertTrue(r.driftSummary.startsWith("Onvoldoende data"))
        val tiles = PerformancePageBuilder.build(r).drift.tiles
        assertEquals("-", tiles[0].value)
        assertEquals("-", tiles[1].value)
        assertEquals("-", tiles[2].value)
    }

    @Test
    fun `per run results are cached`() {
        val (s, repo) = service()
        s.report()
        val first = repo.streamCalls
        s.report()
        assertEquals(first + 1, repo.streamCalls) // only the run without a stream is looked up again
    }

    @Test
    fun `garmin zones are used when present and the app zones otherwise`() {
        val z = GarminHeartRateZones("RUNNING", "LT", 52, 182, 120, 151, 164, 171, 186, 196, com.running.garmin.domain.GarminSource("t", null, "t"))
        assertEquals("GARMIN", service(listOf(z)).first.report().zoneSet!!.source.name)
        assertEquals("APP", service().first.report().zoneSet!!.source.name)
        assertEquals("APP", service(listOf(z)).first.report("app").zoneSet!!.source.name)
    }

    @Test
    fun `run report lists all comparable runs and an unknown id gives null`() {
        val (s, _) = service()
        val r = s.runReport(1)!!
        assertTrue(r.comparable.enough)
        assertEquals(11, r.comparable.matches.size)
        assertTrue(r.run.steady)
        assertNull(s.runReport(424242))
    }

    @Test
    fun `run page names comparable runs, hides criteria without data and explains every metric`() {
        val (s, _) = service()
        val page = RunPageBuilder.build(s.runReport(1)!!)
        assertTrue(page.cmpRows.first().self)
        assertEquals(12, page.cmpRows.size)
        assertTrue(page.cmpRows.drop(1).all { it.name.isNotBlank() })
        assertTrue(page.cmpHeads.none { it.short.startsWith("Temp") }, "no temperature in any stream, so no column")
        assertTrue(page.hiddenCriteria.any { it.startsWith("temperatuur") })
        assertTrue(page.metrics.all { it.explanation.isNotBlank() })
    }

    @Test
    fun `sorting comparable runs by date keeps the same selection and orders them newest first`() {
        val (s, _) = service()
        val report = s.runReport(1)!!
        val bySimilarity = RunPageBuilder.build(report)
        val byDate = RunPageBuilder.build(report, CmpSort.DATE, s::sportOf)
        assertEquals(12, byDate.cmpRows.size)
        assertEquals(
            bySimilarity.cmpRows.drop(1).map { it.id }.toSet(),
            byDate.cmpRows.drop(1).map { it.id }.toSet(),
            "the same comparable runs, only the order changes",
        )
        val dates = byDate.cmpRows.map { it.iso }
        assertEquals(dates.sortedDescending(), dates, "newest first, oldest last")
        assertTrue(byDate.cmpRows.first().self, "the current run is the newest one in this fixture")
        assertEquals(bySimilarity.cmpRows.map { it.id }.toSet(), byDate.cmpRows.map { it.id }.toSet())
        assertTrue(byDate.cmpSports.isNotEmpty())
        assertEquals(byDate.cmpRows.map { it.iso }.minOrNull(), byDate.cmpFrom)
        assertEquals(byDate.cmpRows.map { it.iso }.maxOrNull(), byDate.cmpTill)
    }

    @Test
    fun `overview page uses dutch dates and the chosen comparison windows`() {
        val (s, _) = service()
        val r = s.report(w = PerformanceWindows(efShortDays = 14, efLongDays = 60))
        assertEquals(listOf(14, 60), r.efWindows)
        val page = PerformancePageBuilder.build(r)
        assertTrue(page.ef.compares.all { it.windowDays in setOf(14, 60) })
        assertEquals("30 okt 2026", PerfDates.day(java.time.LocalDate.of(2026, 10, 30)))
        assertTrue(page.recent.none { it.date.contains("-") })
    }

    @Test
    fun `every period is adjustable and changes what is counted`() {
        val (s, _) = service()
        val def = s.report()
        assertEquals(12, def.driftRows.size) // all steady runs are within 90 days
        val short = s.report(w = PerformanceWindows(driftDays = 20, trendDays = 30))
        assertEquals(6, short.driftRows.size) // runs 2..17 days ago
        assertEquals(30, short.windows.trendDays)
        val page = PerformancePageBuilder.build(short)
        assertTrue(page.fitness.sub.startsWith("Laatste 30 dagen tegenover de 30 dagen daarvoor"))
        assertEquals(40, s.runReport(1, typicalDays = 40)!!.typical!!.days)
    }

    @Test
    fun `windows are read from parameters, clamped, ordered and fall back to the defaults`() {
        val w = PerformanceWindows.from(mapOf("short" to "120", "long" to "30", "drift" to "5", "cs" to "abc", "zoneWeeks" to "999"))
        assertEquals(30, w.efShortDays)
        assertEquals(120, w.efLongDays)
        assertEquals(14, w.driftDays)
        assertEquals(90, w.csDays)
        assertEquals(156, w.zoneWeeks)
        assertEquals(PerformanceWindows.DEFAULT, PerformanceWindows.from(emptyMap()))
        assertTrue(PerformanceWindows.DEFAULT.changed.isEmpty())
        assertEquals(mapOf("drift" to 14), PerformanceWindows(driftDays = 14).changed)
    }

    @Test
    fun `logging has counts only, no heart rate, coordinates or names`() {
        val logger = LoggerFactory.getLogger(PerformanceService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val old = logger.level
        logger.level = Level.DEBUG
        try {
            val (s, _) = service()
            s.report()
            s.runReport(1)
        } finally {
            logger.detachAppender(appender)
            logger.level = old
        }
        val text = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(text.contains("Performance computed performance-v1"))
        listOf("51.2345", "4.9876", "run 1", "150").forEach { assertFalse(text.contains(it) && it != "150", it) }
    }
}
