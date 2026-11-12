package com.running.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** The one place Dutch dates and clock times are spelled; the UI never shows ISO notation (docs/ui-guidelines.md §5). */
class PerfDatesTest {

    @Test
    fun `day and clock time read as plain dutch`() {
        assertEquals("11 nov 2026 9:47", PerfDates.dayTime(LocalDateTime.of(2026, 11, 11, 9, 47)))
        assertEquals("1 jan 2026 0:05", PerfDates.dayTime(LocalDateTime.of(2026, 1, 1, 0, 5)))
        assertEquals("31 dec 2026 23:59", PerfDates.dayTime(LocalDateTime.of(2026, 12, 31, 23, 59)))
    }

    @Test
    fun `a range writes the shared year once`() {
        assertEquals("3 okt – 30 okt 2026", PerfDates.range(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 30)))
        assertEquals("28 dec 2025 – 3 jan 2026", PerfDates.range(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 3)))
    }

    @Test
    fun `a range of one day stays one day`() {
        val d = LocalDate.of(2026, 10, 30)
        assertEquals("30 okt 2026", PerfDates.range(d, d))
    }

    @Test
    fun `long and month forms stay short dutch`() {
        assertEquals("vrijdag 30 oktober 2026", PerfDates.dayLong(LocalDate.of(2026, 10, 30)))
        assertEquals("okt 2026", PerfDates.month(YearMonth.of(2026, 10)))
        assertEquals("week van 26 okt 2026", PerfDates.weekOf("2026-10-26"))
    }
}
