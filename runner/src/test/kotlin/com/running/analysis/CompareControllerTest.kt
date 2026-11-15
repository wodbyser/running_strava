package com.running.analysis

import com.running.strava.spi.ActivityRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.ZonedDateTime

/**
 * Renders the real compare page with an empty database and with seeded runs, so template errors, missing
 * fragments and broken chart data show up without a browser.
 */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:comparepage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
    "garmin.api-key=",
])
@AutoConfigureMockMvc
class CompareControllerTest @Autowired constructor(
    private val mvc: MockMvc,
    private val activityRepository: ActivityRepository,
) {

    private fun at(date: String) = ZonedDateTime.parse("${date}T08:00:00Z")

    private fun period() = "fromA=2026-01-01&tillA=2026-01-31&fromB=2026-03-01&tillB=2026-03-31"

    @Test
    fun `page explains what to do without training data`() {
        val html = mvc.perform(get("/compare")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Nog geen trainingsdata"))
        assertTrue(html.contains("Periode A (vroeger)"))
        assertTrue(html.contains("Periode B (recenter)"))
        // Without data there is nothing to compare, so no empty charts or half-filled tables.
        assertFalse(html.contains("id=\"grafieken\""))
    }

    @Test
    fun `seeded runs render the charts, the folded tables and the glossary`() {
        val a = (1..5).map { Fixtures.run(it.toLong(), at("2026-01-0$it"), 10000.0, 3600, 140.0, maxHr = 190.0) }
        val b = (1..5).map { Fixtures.run(100L + it, at("2026-03-0$it"), 10000.0, 3300, 140.0, maxHr = 190.0) }
        activityRepository.saveAll(a + b)

        val html = mvc.perform(get("/compare?${period()}")).andExpect(status().isOk).andReturn().response.contentAsString

        // Conclusion first, then charts, then the two training types, the monthly trend and the terms.
        val order = listOf("id=\"conclusie\"", "id=\"grafieken\"", "id=\"rustig\"", "id=\"interval\"", "id=\"verloop\"", "id=\"begrippen\"")
            .map { html.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), order.toString())
        assertTrue(html.contains("Rustige lopen efficiënter bij dezelfde hartslag"))

        // All four charts and the trend chart are present, every table is folded away by default.
        listOf("cEf", "cPace", "cHr", "cKm", "cTrend").forEach { assertTrue(html.contains("id=\"$it\""), it) }
        assertTrue(html.contains("Toon alle cijfers als tabel"))
        assertTrue(html.contains("Toon als tabel"))

        // Numbers in display units: EF in metres per heartbeat, pace as min/km, Dutch months instead of 2026-03.
        assertTrue(html.contains("var paceEasyB = 330.0;"), html.substringAfter("var paceEasyB").take(80))
        assertTrue(html.contains("jan 2026") && html.contains("mrt 2026"))
        // Dates in the period form are ISO, but the month table itself must never show 2026-03.
        assertFalse(html.contains(">2026-03<"))
        assertTrue(html.contains("meter per hartslag"))

        // The glossary fragment is expanded and no unresolved placeholders are left behind.
        assertTrue(html.contains("id=\"term-ef\""))
        // This page pools whole easy runs, so it must not reuse the stable-part text from Prestaties.
        assertTrue(html.contains("berekend over de hele rustige loop"))
        assertFalse(html.contains("stabiele deel van een stabiele run"))
        assertFalse(html.contains("\${"))
    }

    @Test
    fun `the page is GET only and keeps a custom period`() {
        mvc.perform(post("/compare")).andExpect(status().isMethodNotAllowed)
    }
}
