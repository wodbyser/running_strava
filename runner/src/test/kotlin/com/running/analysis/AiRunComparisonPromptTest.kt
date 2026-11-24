package com.running.analysis

import com.running.analysis.Fixtures.run
import com.running.config.GarminProperties
import com.running.garmin.spi.GarminRepository
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The prompt behind the "AI-prompt voor deze vergelijking" button on Trainingen vergelijken. */
class AiRunComparisonPromptTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC)
    private val start = ZonedDateTime.parse("2026-10-30T08:00:00Z")

    private class GarminStub : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHeartRateZones() = emptyList<com.running.garmin.domain.GarminHeartRateZones>()
    }

    /** Steady 1 Hz stream at [speed] m/s and a heart rate around [hr]. */
    private fun stream(seconds: Int, speed: Double, hr: Int): ActivityStream {
        val t = (0..seconds).toList()
        return ActivityStream(t, t.map { (it * speed).toFloat() }, null, null, t.map { hr + if (it % 2 == 0) -1 else 1 }, null, null, null, null)
    }

    private fun service(
        runs: List<Activity>,
        laps: Map<Long, List<Lap>> = emptyMap(),
        streams: Map<Long, ActivityStream> = emptyMap(),
    ): AiAnalysisService {
        val repo = FakeActivityRepository(runs, laps = laps, streams = streams)
        val comparison = RunComparisonService(
            PerformanceService(repo, GarminStub(), GarminProperties(), clock),
            TrainingRatingService(repo),
            repo,
        )
        return AiAnalysisService(repo, PeriodComparisonService(repo), runComparisonService = comparison)
    }

    /** The same lap set as the rating-prompt test: 6 x 800 m with 200 m recovery. */
    private fun sixBy800(): List<Lap> {
        val rows = listOf(1000 to 350, 1000 to 345) + (1..6).flatMap { listOf(800 to 180, 200 to 90) } + listOf(1000 to 355)
        return rows.mapIndexed { i, (d, t) ->
            Lap(i.toLong(), null, t, t, start, 0, 0, d.toFloat(), d.toFloat() / t, 0f,
                if (d == 800) 172.4f else 140f, null, 84f, i + 1, 0)
        }
    }

    @Test
    fun `steady runs get their summary, analysis, zones and the app comparison`() {
        val runs = listOf(
            run(1, start.minusDays(2), 3.4 * 2400, 2400, hr = 150.0, maxHr = 190.0),
            run(2, start.minusDays(9), 3.0 * 2400, 2400, hr = 150.0, maxHr = 190.0),
        )
        val streams = runs.associate { it.id to stream(2400, it.distance / 2400.0, 150) }
        val p = service(runs, streams = streams).buildRunComparisonPrompt(listOf(1, 2))!!

        assertTrue(p.contains("<definities_en_eenheden>"))
        assertTrue(p.contains("<werkwijze>"))
        assertTrue(p.contains("Efficiëntiefactor (EF)"))
        assertTrue(p.contains("<trainingsvergelijking>"))
        assertTrue(p.contains("VERGELIJKING VAN 2 TRAININGEN"))
        assertTrue(p.contains("### Training A: run 1"))
        assertTrue(p.contains("### Training B: run 2"))
        assertTrue(p.contains("EF van het stabiele deel"))
        assertTrue(p.contains("Ontkoppeling (efficiëntieverlies tweede helft)"))
        assertTrue(p.contains("Tijd in hartslagzones [berekend]"))
        assertTrue(p.contains("## Vergelijking van de app"))
        assertTrue(p.contains("Vergelijkbaarheid: Goed vergelijkbaar"))
        assertTrue(p.contains("- Snelheid: Best: A"))
        assertTrue(p.contains("Geen rondes/laps: de opbouw (intervallen, tempo) is niet te bepalen uit deze data."))
        assertFalse(p.contains("NaN"))
        assertFalse(p.contains("Garmin-herstel"))
    }

    @Test
    fun `interval runs list the recognised build-up and every rep with its recovery`() {
        val runs = listOf(
            run(11, start.minusDays(3), 9000.0, 2670, hr = 160.0, maxHr = 190.0),
            run(12, start.minusDays(10), 9000.0, 2670, hr = 160.0, maxHr = 190.0),
        )
        val p = service(runs, laps = mapOf(11L to sixBy800(), 12L to sixBy800()))
            .buildRunComparisonPrompt(listOf(11, 12))!!

        assertTrue(p.contains("- Intervalopbouw: "))
        assertTrue(p.contains("Rep 1: 800 m in 3:00, 3:45 /km, gem. HR 172 bpm"))
        assertTrue(p.contains("herstel erna 1:30"))
        assertTrue(p.contains("Hartslagherstel 60 s na de reps: geen betrouwbare meting"))
        assertTrue(p.contains("Rondes/laps (15): afstand, tijd, tempo en HR zijn [gemeten]; de rol na '->' is [app-schatting]:"))
        assertTrue(p.contains("-> interval-rep (set 1)"))
        assertTrue(p.contains("-> herstel (set 1)"))
        assertTrue(p.contains("Controleer de automatisch herkende opbouw zelf aan de hand van de individuele laps"))
        assertFalse(p.contains("NaN"))
    }

    @Test
    fun `without two runs or without the comparison service there is no prompt`() {
        val runs = listOf(run(1, start.minusDays(2), 10000.0, 2400, hr = 150.0))
        assertNull(service(runs).buildRunComparisonPrompt(listOf(1)))
        assertNull(service(runs).buildRunComparisonPrompt(emptyList()))

        val repo = FakeActivityRepository(runs)
        assertNull(AiAnalysisService(repo, PeriodComparisonService(repo)).buildRunComparisonPrompt(listOf(1)))
    }
}
