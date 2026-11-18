package com.running.analysis

import com.running.config.GarminProperties
import com.running.garmin.spi.GarminRepository
import com.running.strava.analysis.performance.RunComparison.CompareAspect
import com.running.strava.analysis.performance.RunComparison.Status
import com.running.strava.domain.ActivityStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RunComparisonServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC)

    private class GarminStub : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHeartRateZones() = emptyList<com.running.garmin.domain.GarminHeartRateZones>()
    }

    /** Steady 1 Hz stream at [speed] m/s and a heart rate around [hr]. */
    private fun stream(seconds: Int, speed: Double, hr: Int): ActivityStream {
        val t = (0..seconds).toList()
        return ActivityStream(t, t.map { (it * speed).toFloat() }, null, null, t.map { hr + if (it % 2 == 0) -1 else 1 }, null, null, null, null)
    }

    private fun run(id: Long, daysAgo: Long, speed: Double, hr: Double) = Fixtures.run(
        id, ZonedDateTime.parse("2026-10-30T08:00:00Z").minusDays(daysAgo), speed * 2400, 2400, hr = hr, maxHr = 190.0,
    )

    private fun service(): RunComparisonService {
        val runs = listOf(run(1, 2, 3.4, 150.0), run(2, 9, 3.0, 150.0), run(3, 16, 3.0, 150.0))
        val repo = FakeActivityRepository(runs, streams = runs.associate { it.id to stream(2400, it.distance / 2400.0, 150) })
        val perf = PerformanceService(repo, GarminStub(), GarminProperties(), clock)
        return RunComparisonService(perf, TrainingRatingService(repo), repo)
    }

    @Test
    fun `the faster run at the same heart rate wins speed and condition`() {
        val c = service().compare(listOf(1, 2))!!
        assertEquals(listOf(1L, 2L), c.entries.map { it.analysis.id })
        val speed = c.result.aspect(CompareAspect.SPEED)
        assertEquals(Status.WINNER, speed.status)
        assertEquals(setOf(1L), speed.winners)
        val ef = c.result.aspect(CompareAspect.EFFICIENCY)
        assertFalse(ef.wholeRun) // both are steady runs, so the steady window is used
        assertEquals(setOf(1L), ef.winners)
        assertEquals(1L, c.result.leader)
        assertTrue(c.entries.all { it.curve.isNotEmpty() })
    }

    @Test
    fun `the page names the leader, the margin and keeps the order of the choice`() {
        val c = service().compare(listOf(1, 2))!!
        val v = RunComparisonPage.build(c, listOf(1, 2))
        assertEquals(listOf("A", "B"), v.runs.map { it.letter })
        assertTrue(v.hero.headline.startsWith("A was het best op"), v.hero.headline)
        val speed = v.aspects.first { it.key == "speed" }
        assertEquals("Best: A", speed.statusLabel)
        assertTrue(speed.verdict.contains("sneller dan B"), speed.verdict)
        // Better is always to the right on the axis.
        assertTrue(speed.rows[0].pos!! > speed.rows[1].pos!!)
        assertEquals("/compare-runs?ids=2", v.runs[0].removeUrl)
    }

    @Test
    fun `two identical runs give no winner`() {
        val c = service().compare(listOf(2, 3))!!
        assertEquals(Status.NO_DIFFERENCE, c.result.aspect(CompareAspect.SPEED).status)
        assertNull(c.result.leader)
        assertEquals("Geen duidelijk verschil tussen deze runs", RunComparisonPage.build(c, listOf(2, 3)).hero.headline)
    }

    @Test
    fun `fewer than two known runs is no comparison, more than six are cut`() {
        val s = service()
        assertNull(s.compare(listOf(1)))
        assertNull(s.compare(listOf(1, 999)))
        assertEquals(6, s.normalize((1L..9L).toList()).size)
        assertEquals(listOf(1L, 2L, 3L), RunComparisonService.parseIds(listOf("1,2", "x", "3", "2")))
    }

    @Test
    fun `candidates keep the chosen runs first and mark similar runs`() {
        val list = service().candidates(listOf(2))
        assertEquals(2L, list.first().activity.id)
        assertTrue(list.any { it.similar })
        assertEquals(3, list.size)
    }
}
