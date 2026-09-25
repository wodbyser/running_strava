package com.running.analysis

import com.running.analysis.Fixtures.run
import com.running.strava.analysis.DateRange
import com.running.strava.domain.Lap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class AiPromptTest {

    private val now = ZonedDateTime.now()

    private fun sixBy800(): List<Lap> {
        val rows = listOf(1000 to 350, 1000 to 345) + (1..6).flatMap { listOf(800 to 180, 200 to 90) } + listOf(1000 to 355)
        return rows.mapIndexed { i, (d, t) ->
            Lap(i.toLong(), null, t, t, now, 0, 0, d.toFloat(), d.toFloat() / t, 0f,
                if (d == 800) 172.4f else 140f, null, 84f, i + 1, 0)
        }
    }

    private fun service(): Pair<AiAnalysisService, List<com.running.strava.domain.Activity>> {
        // 35 runs so the "last 30" list and the full-history totals differ
        val runs = (1..35).map { run(it.toLong(), now.minusDays(36L - it), 10000.0, 3000, 152.3, maxHr = 180.0) } +
            run(99, now.minusHours(1), 7800.0, 2200, hr = null) +
            run(98, now.minusDays(200), 5000.0, 1500) // outside the 12-week window
        val repo = FakeActivityRepository(runs, laps = mapOf(35L to sixBy800()))
        return AiAnalysisService(repo, PeriodComparisonService(repo)) to runs
    }

    private fun assertCommonBlocks(p: String) {
        assertTrue(p.contains("<definities_en_eenheden>"), "definitions block")
        assertTrue(p.contains("<werkwijze>"), "guardrails block")
        assertTrue(p.contains("niet bepaalbaar uit deze data"))
        assertTrue(p.contains("[gemeten]") && p.contains("[berekend]") && p.contains("[app-schatting]"))
        assertFalse(p.contains("rpm"), "cadence must never be labelled rpm")
        assertFalse(p.contains("NaN"))
        assertFalse(Regex("""\d+\.\d bpm""").containsMatchIn(p), "HR must be whole bpm")
    }

    @Test
    fun `training context separates recent list from full history and doubles cadence`() {
        val (s, _) = service()
        val ctx = s.buildTrainingContext()
        assertTrue(ctx.contains("## 1. Laatste 30 activiteiten"))
        assertFalse(ctx.contains("VOLLEDIGE historiek"))
        assertTrue(ctx.contains("## 2. Totalen laatste 12 weken [berekend]"))
        assertTrue(ctx.contains("Aantal runs: 36"))               // the 200-day-old run is excluded
        val weekLines = ctx.lines().filter { Regex("""^  \d{4}-\d{2}-\d{2}: \d+\.\d km$""").matches(it) }
        assertEquals(12, weekLines.size)
        assertTrue(ctx.contains("Gem. cadans: 168 spm"))          // 84 per foot -> 168
        assertTrue(ctx.contains("Gem. HR: 152 bpm"))              // 152.3 rounded
        assertTrue(ctx.contains("Gem. HR: onbekend (geen HR)"))   // HR-less run
        assertTrue(ctx.contains("Opbouw [app-schatting]: "))
        assertTrue(ctx.contains("Geschatte max. hartslag: 180 bpm"))
        // weighted: 350 km + 7.8 km over 105000 s + 2200 s -> 1000 / (357.8/107200) = 299.6 s -> 5:00
        assertTrue(ctx.contains("Gem. tempo (totale afstand / totale bewegingstijd): 5:00 /km"))
    }

    @Test
    fun `general prompt no longer asks for undeterminable conclusions as fact`() {
        val (s, _) = service()
        val p = s.buildAiPrompt()
        assertCommonBlocks(p)
        assertTrue(p.contains("Er is geen lactaatmeting"))
        assertTrue(p.contains("individueel blessurerisico is niet te bepalen"))
        assertTrue(p.contains("GEEN individuele laps"))
    }

    @Test
    fun `next training prompt keeps user parameters`() {
        val (s, _) = service()
        val p = s.buildNextTrainingPrompt(NextTrainingParams(distanceKm = 12.0, trainingType = "6x800m", goalTime = "1:45:00", notes = "zware benen"))
        assertCommonBlocks(p)
        assertTrue(p.contains("Geplande training: 12.0 km"))
        assertTrue(p.contains("Type training (uit schema): 6x800m"))
        assertTrue(p.contains("Doeltijd: 1:45:00"))
        assertTrue(p.contains("Extra context/opmerkingen: zware benen"))
    }

    @Test
    fun `rating prompt lists raw laps with estimated roles`() {
        val (s, _) = service()
        val p = s.buildActivityRatingPrompt(35)!!
        assertCommonBlocks(p)
        assertTrue(p.contains("Controleer de automatisch herkende opbouw zelf aan de hand van de individuele laps"))
        assertTrue(p.contains("Lap 3: 800 m in 3:00, 3:45 /km, gem. HR 172 bpm -> interval-rep (set 1)"), p)
        assertTrue(p.contains("-> herstel (set 1)"))
        assertNull(s.buildActivityRatingPrompt(12345))
        val noLaps = s.buildActivityRatingPrompt(1)!!
        assertTrue(noLaps.contains("niet te bepalen uit deze data"))
        assertFalse(noLaps.contains("Controleer de automatisch herkende opbouw zelf"))
    }

    @Test
    fun `comparison prompt defines EF, shows minimums and has no 150 bpm reference`() {
        val (s, _) = service()
        val p = s.buildComparisonPrompt(
            DateRange(LocalDate.now().minusDays(40), LocalDate.now().minusDays(20)),
            DateRange(LocalDate.now().minusDays(19), LocalDate.now()),
            "A", "B",
        )
        assertCommonBlocks(p)
        assertTrue(p.contains("Efficiëntiefactor (EF) [berekend]"))
        assertTrue(p.contains("minimum voor een oordeel: 5"))
        assertTrue(p.contains("minimum voor een oordeel: 3"))
        assertTrue(p.contains("km/week over 3.0 weken"))  // 21 days inclusive
        assertFalse(p.contains("150 bpm"))
    }

    @Test
    fun `empty history gives a Dutch no-data message`() {
        val s = AiAnalysisService(FakeActivityRepository(emptyList()), PeriodComparisonService(FakeActivityRepository(emptyList())))
        assertEquals(AiAnalysisService.NO_DATA, s.buildAiPrompt())
    }
}
