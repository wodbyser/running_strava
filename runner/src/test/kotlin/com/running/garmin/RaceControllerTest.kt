package com.running.garmin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

/** Full context with an empty H2 database, no key, no network. */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:racepage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "garmin.api-key=",
    "garmin.load.warm-up=false", "garmin.race.warm-up=false",
])
@AutoConfigureMockMvc
class RaceControllerTest @Autowired constructor(private val mvc: MockMvc) {

    @Test
    fun `the race page renders without any data, with the safety line and the abstain texts`() {
        val html = mvc.perform(get("/race")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Geen medische beoordeling."))
        assertTrue(html.contains("race-v3"))
        assertTrue(html.contains("Nog geen voorspelling mogelijk"))
        val order = listOf("id=\"doelen\"", "id=\"tijden\"", "id=\"schema\"", "id=\"vorm\"", "id=\"wedstrijdweek\"", "id=\"kandidaten\"", "id=\"begrippen\"").map { html.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), order.toString())
        assertTrue(html.contains("Markeringen opnieuw ophalen"))
        listOf("klaar voor", "haalbaar", "veilig").forEach { assertFalse(html.contains(it), it) }
    }

    @Test
    fun `the history page renders without any race`() {
        val html = mvc.perform(get("/race/history")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Nog geen gemarkeerde wedstrijden"))
        assertTrue(html.contains("Te weinig wedstrijden om een verband te zoeken"))
        assertTrue(html.contains("id=\"begrippen\""))
    }

    @Test
    fun `a goal is saved with POST, shown on the page and deleted with POST`() {
        val day = LocalDate.now().plusDays(40).toString()
        mvc.perform(post("/race/goal").param("distance", "5000").param("date", day).param("target", "19:00").param("name", "Testloop uniek"))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrl("/race#doelen")).andExpect(flash().attribute("flashType", "success"))
        val html = mvc.perform(get("/race")).andReturn().response.contentAsString
        assertTrue(html.contains("Testloop uniek"))
        assertTrue(html.contains("19:00"))
        val json = mvc.perform(get("/race/data")).andExpect(status().isOk).andReturn().response.contentAsString
        val id = Regex("\"goal\":\\{\"id\":(\\d+)").find(json)!!.groupValues[1]
        mvc.perform(post("/race/goal/$id/delete")).andExpect(status().is3xxRedirection)
        assertFalse(mvc.perform(get("/race")).andReturn().response.contentAsString.contains("Testloop uniek"))
    }

    @Test
    fun `an invalid goal is refused with a message and nothing is saved`() {
        mvc.perform(post("/race/goal").param("distance", "5000").param("date", "morgen").param("name", "Fout uniek"))
            .andExpect(flash().attribute("flashType", "error"))
        mvc.perform(post("/race/goal").param("distance", "5000").param("date", LocalDate.now().plusDays(5).toString()).param("target", "abc").param("name", "Fout uniek"))
            .andExpect(flash().attribute("flashType", "error"))
        mvc.perform(post("/race/goal").param("distance", "custom").param("custom", "500").param("date", LocalDate.now().plusDays(5).toString()).param("name", "Fout uniek"))
            .andExpect(flash().attribute("flashType", "error"))
        assertFalse(mvc.perform(get("/race")).andReturn().response.contentAsString.contains("Fout uniek"))
    }

    @Test
    fun `every period is adjustable, keeps the other choices and is clamped`() {
        val html = mvc.perform(get("/race").param("anchor", "90").param("chart", "9999")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("value=\"90\""))
        assertTrue(html.contains("value=\"156\""))
        assertTrue(html.contains("type=\"hidden\" name=\"anchor\" value=\"90\""))
        assertTrue(html.contains("Alle periodes terug naar standaard"))
        mvc.perform(get("/race/data").param("anchor", "1")).andExpect(status().isOk).andExpect(jsonPath("$.overview.windows.anchorDays").value(30))
    }

    @Test
    fun `json renders and exposes no key, token, coordinates or health values`() {
        val json = mvc.perform(get("/race/data")).andExpect(status().isOk)
            .andExpect(jsonPath("$.overview.algorithmVersion").value("race-v3"))
            .andExpect(jsonPath("$.history.algorithmVersion").value("race-v3"))
            .andReturn().response.contentAsString
        listOf("apiKey", "api-key", "token", "bearer", "Authorization", "startLat", "latlng").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `state changing endpoints are POST only and the pages are GET only`() {
        mvc.perform(post("/race")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/race/history")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/race/data")).andExpect(status().isMethodNotAllowed)
        mvc.perform(get("/race/goal")).andExpect(status().isMethodNotAllowed)
        mvc.perform(get("/race/goal/1/delete")).andExpect(status().isMethodNotAllowed)
        mvc.perform(get("/race/strava-recheck")).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `the re-read of Strava markings without a token makes no call and says why`() {
        mvc.perform(post("/race/strava-recheck")).andExpect(status().is3xxRedirection).andExpect(redirectedUrl("/race#kandidaten"))
            .andExpect(flash().attribute("flashType", "warning"))
    }

    @Test
    fun `the navigation links to the race page and the coach page no longer has its own predictions`() {
        val nav = mvc.perform(get("/load")).andReturn().response.contentAsString
        assertTrue(nav.contains("href=\"/race\" data-match=\"/race\""))
        val coach = mvc.perform(get("/coach")).andReturn().response.contentAsString
        assertFalse(coach.contains("Vormvoorspelling"))
    }
}
