package com.running.garmin

import com.running.garmin.domain.*
import com.running.garmin.spi.GarminRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

/** Full context with H2, no key, no network. The page renders from stored rows only. */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:recoverypage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "garmin.api-key=",
])
@AutoConfigureMockMvc
class RecoveryControllerTest @Autowired constructor(private val mvc: MockMvc, private val repo: GarminRepository) {

    private val day = LocalDate.of(2026, 1, 15)
    private val src = GarminSource("test", null, "test")

    @Test
    fun `page renders without data and shows the safety line and abstain text`() {
        val html = mvc.perform(get("/recovery?date=2020-01-01")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Geen medische beoordeling."))
        assertTrue(html.contains("Onvoldoende data voor een samenvatting"))
        assertTrue(html.contains("geen meting"))
        assertTrue(html.contains("id=\"begrippen\""))
        assertTrue(html.contains("href=\"/recovery\""))
        assertTrue(html.contains("Onvoldoende data"))
        assertTrue(html.contains("class=\"spectrum"))
        assertTrue(html.contains("Hoe komt deze conclusie tot stand?"))
    }

    @Test
    fun `page and json render with data and expose no key or token`() {
        repo.upsertHrv((0..28).map { GarminHrvDay(day.minusDays(it.toLong()), 50 + it % 2, 50 + it % 2, null, 40, 45, 60, null, "BALANCED", null, src) }, Instant.EPOCH)
        repo.upsertRestingHr((0..28).map { GarminRestingHrDay(day.minusDays(it.toLong()), 50 + it % 2, null, null, src) }, Instant.EPOCH)
        val html = mvc.perform(get("/recovery?date=$day")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Nachtgemiddelde volgens Garmin"))
        assertTrue(html.contains(">gemeten<"))
        assertTrue(html.contains("Verloop van de laatste 60 dagen"))
        assertTrue(html.contains("id=\"cHrv\""))
        assertTrue(html.contains("Laatste 7 dagen"))
        assertTrue(html.contains("donderdag 15 januari 2026"))
        assertTrue(html.contains("Eigen norm 50,5 ms"), "own norm on the HRV card")
        assertTrue(html.contains("recovery-v1"))
        assertFalse(html.contains("RMSSD"))

        val json = mvc.perform(get("/recovery/data?date=$day"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.algorithmVersion").value("recovery-v1"))
            .andExpect(jsonPath("$.hrv.hasNight").value(true))
            .andReturn().response.contentAsString
        listOf("apiKey", "api-key", "token", "bearer", "Authorization").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `every period of the page can be changed and the other choices are kept`() {
        repo.upsertHrv((0..40).map { GarminHrvDay(day.minusDays(it.toLong()), 50 + it % 2, 50 + it % 2, null, 40, 45, 60, null, "BALANCED", null, src) }, Instant.EPOCH)
        val html = mvc.perform(get("/recovery?date=$day&norm=14&chart=30&table=10&debt=14"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Verloop van de laatste 30 dagen"))
        assertTrue(html.contains("Laatste 10 dagen"))
        assertTrue(html.contains("mediaan van 14 dagen"), "hero fact follows the norm window")
        assertTrue(html.contains("Minstens 10 nachten met data nodig"), "sleep debt minimum scales with the window")
        assertTrue(html.contains("Alle periodes terug naar standaard"))
        // A period form keeps the other choices, the day and the view as hidden fields.
        assertTrue(html.contains("type=\"hidden\" name=\"norm\" value=\"14\""))
        assertTrue(html.contains("type=\"hidden\" name=\"date\" value=\"$day\""))
        // The view buttons keep the chosen periods.
        assertTrue(html.contains("/recovery?view=week&amp;date=$day&amp;norm=14"))
        val clamped = mvc.perform(get("/recovery?date=$day&norm=999")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(clamped.contains("mediaan van 90 dagen"))
        val week = mvc.perform(get("/recovery?view=week&date=$day&norm=21")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(week.contains("mediaan van de 21 dagen vóór de periode"))
    }

    @Test
    fun `ai page offers the recovery block only on request`() {
        val plain = mvc.perform(get("/ai")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(plain.contains("Garmin-herstelgegevens toevoegen"))
        assertFalse(plain.contains("&lt;herstel"))
        val withBlock = mvc.perform(get("/ai?herstel=true")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(withBlock.contains("Garmin-herstelgegevens weglaten"))
        assertTrue(withBlock.contains("/ai/download?herstel=true"))
    }

    @Test
    fun `week and month views render`() {
        repo.upsertHrv((0..40).map { GarminHrvDay(day.minusDays(it.toLong()), 50 + it % 3, 50 + it % 3, null, 40, 45, 60, null, "BALANCED", null, src) }, Instant.EPOCH)
        val week = mvc.perform(get("/recovery?view=week&date=$day")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(week.contains("Week van 12 jan – 18 jan 2026"))
        assertTrue(week.contains("te weinig dagen met data (4 van 5)"), "only 4 days with data in that week")
        assertTrue(week.contains("Hoe komt deze conclusie tot stand?"))
        assertTrue(week.contains("id=\"cHrv\""))
        assertTrue(week.contains("Per dag"))
        assertTrue(week.contains("class=\"spectrum"))
        assertTrue(week.contains("Geen medische beoordeling."))
        val month = mvc.perform(get("/recovery?view=month&date=$day")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(month.contains("Maand: 1 jan – 31 jan 2026"))
    }

    @Test
    fun `sync button posts, makes no call without a key, and is a POST only`() {
        mvc.perform(get("/recovery?date=2020-01-01")).andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("action=\"/recovery/garmin-update\"")))
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recovery/garmin-update"))
            .andExpect(status().is3xxRedirection)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("flashType", "error"))
        mvc.perform(get("/recovery/garmin-update")).andExpect(status().isMethodNotAllowed)
    }
}

/** The HRV bar uses Garmin's own balanced range as the green zone. */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:recoveryhrvbar;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "garmin.api-key=",
])
@AutoConfigureMockMvc
class RecoveryHrvBarTest @Autowired constructor(private val mvc: MockMvc, private val repo: GarminRepository) {
    @Test
    fun `hrv bar shows Garmin's range and keeps the own norm in the text`() {
        val day = LocalDate.of(2026, 3, 10)
        val src = GarminSource("test", null, "test")
        repo.upsertHrv((0..28).map { GarminHrvDay(day.minusDays(it.toLong()), 68, if (it == 0) 69 else 72 + it % 3, null, 70, 71, 80, null, "LOW", null, src) }, Instant.EPOCH)
        val html = mvc.perform(get("/recovery?date=$day")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Garmin-bereik 71 tot 80 ms (Garmin past dit zelf aan)"))
        assertTrue(html.contains("69 ms ligt onder Garmins bereik"))
        assertTrue(html.contains("eigen norm"))
    }
}
