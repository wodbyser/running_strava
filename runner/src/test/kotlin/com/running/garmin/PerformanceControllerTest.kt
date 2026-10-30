package com.running.garmin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Full context with an empty H2 database, no key, no network. */
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:performancepage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "garmin.api-key=",
])
@AutoConfigureMockMvc
class PerformanceControllerTest @Autowired constructor(private val mvc: MockMvc) {

    @Test
    fun `overview renders without any data and shows the safety line and abstain texts`() {
        val html = mvc.perform(get("/performance")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Geen medische beoordeling."))
        assertTrue(html.contains("performance-v1"))
        assertTrue(html.contains("Verband, geen oorzaak."))
        assertTrue(html.contains("Onvoldoende data voor een samenvatting"))
        assertTrue(html.contains("href=\"/performance\""))
        assertTrue(html.contains("Nog te weinig data voor een conditietrend"))
        assertTrue(html.contains("id=\"begrippen\""))
        // Conditietrend first, then efficiency, zones and drift.
        val order = listOf("id=\"fitness\"", "id=\"ef\"", "id=\"zones\"", "id=\"drift\"").map { html.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), order.toString())
    }

    @Test
    fun `comparison windows are configurable and clamped`() {
        val html = mvc.perform(get("/performance").param("short", "14").param("long", "60")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("value=\"14\""))
        assertTrue(html.contains("value=\"60\""))
        mvc.perform(get("/performance/data").param("short", "1").param("long", "9999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.efWindows[0]").value(7))
            .andExpect(jsonPath("$.efWindows[1]").value(365))
    }

    @Test
    fun `json renders and exposes no key, token or coordinates`() {
        val json = mvc.perform(get("/performance/data"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.algorithmVersion").value("performance-v1"))
            .andReturn().response.contentAsString
        listOf("apiKey", "api-key", "token", "bearer", "Authorization", "startLat").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `unknown run is a 404 and the pages are GET only`() {
        mvc.perform(get("/performance/run/1")).andExpect(status().isNotFound)
        mvc.perform(post("/performance")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/performance/data")).andExpect(status().isMethodNotAllowed)
    }
}
