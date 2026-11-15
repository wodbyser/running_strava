package com.running.analysis

import com.running.analysis.Fixtures.run
import com.running.strava.analysis.DateRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class ComparisonPageTest {

    private fun at(date: String) = ZonedDateTime.parse("${date}T08:00:00Z")

    private fun view(a: List<com.running.strava.domain.Activity>, b: List<com.running.strava.domain.Activity>) =
        ComparisonPage.of(
            PeriodComparisonService(FakeActivityRepository(a + b)).compare(
                DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
            ),
        )

    @Test
    fun `efficiency is shown in metres per heartbeat and the table carries the change`() {
        val a = (1..5).map { run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0, maxHr = 190.0) }
        val b = (1..5).map { run(100L + it, at("2026-03-0$it"), 10000.0, 3300, 140.0, maxHr = 190.0) }
        val v = view(a, b)

        // A: 50000 m / (140 bpm * 18000 s); B: 50000 / (140 * 16500). x60 = metres per heartbeat.
        val efB = 50000.0 / (140 * 16500) * 60
        assertEquals("%.2f m".format(java.util.Locale.ROOT, efB), v.tiles.first().value)
        assertEquals(efB, v.chart.easyEfB!!, 1e-9)
        assertEquals("Rustige lopen efficiënter bij dezelfde hartslag", v.headline)

        val row = v.table.first { it.label == "Efficiëntie rustig" }
        assertEquals("+9.1 %", row.diff)
        assertEquals(Tone.GOOD, row.tone)
        // The table starts each block with a stronger line.
        assertTrue(v.table.count { it.groupStart } >= 3)
    }

    @Test
    fun `the monthly trend uses Dutch months and keeps a gap where a month has no runs`() {
        val a = (1..5).map { run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0, maxHr = 190.0) }
        val b = (1..5).map { run(100L + it, at("2026-03-0$it"), 10000.0, 3300, 140.0, maxHr = 190.0) }
        // A run without heart rate before both periods: no efficiency, so the trend should not start there.
        val old = listOf(run(50, at("2025-11-01"), 10000.0, 3600, hr = null))
        val v = view(a + old, b)

        assertEquals(listOf("jan 2026", "feb 2026", "mrt 2026"), v.months.map { it.month })
        assertEquals(listOf("jan 2026", "feb 2026", "mrt 2026"), v.chart.months)
        assertNull(v.chart.easyEfByMonth[1])
        assertEquals("-", v.months[1].easyEf)
        assertEquals(0, v.months[1].easyRuns)
        assertTrue(v.months.none { it.month.contains("2026-") })
    }

    @Test
    fun `pace differences are phrased as faster or slower and rep length warns`() {
        val a = (1..5).map { run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0, maxHr = 190.0) }
        val b = (1..5).map { run(100L + it, at("2026-03-0$it"), 10000.0, 3300, 140.0, maxHr = 190.0) }
        val v = view(a, b)

        val pace = v.table.first { it.label == "Gemiddeld tempo rustig" }
        assertEquals("5:30 /km", pace.b)
        assertEquals("30\u00A0s/km sneller", pace.diff)
        // No interval sessions in these runs: the interval card says so instead of showing dashes.
        assertEquals("te weinig data", v.interval.pill)
        assertTrue(v.interval.detailA.contains("geen sessies"))
    }

    @Test
    fun `without any steady run the page still builds a complete view`() {
        val v = view(
            listOf(run(1, at("2026-01-01"), 10000.0, 3000, hr = null)),
            listOf(run(2, at("2026-03-01"), 10000.0, 3000, hr = null)),
        )
        assertEquals("-", v.tiles.first().value)
        assertEquals(Tone.NONE, v.tone)
        assertEquals("Nog te weinig data voor een oordeel", v.headline)
        assertTrue(v.table.isNotEmpty())
    }
}
