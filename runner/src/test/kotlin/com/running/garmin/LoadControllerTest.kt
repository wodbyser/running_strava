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
    "spring.datasource.url=jdbc:h2:mem:loadpage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "garmin.api-key=", "garmin.load.warm-up=false",
])
@AutoConfigureMockMvc
class LoadControllerTest @Autowired constructor(private val mvc: MockMvc) {

    @Test
    fun `the load page renders without any data and shows the safety line and the abstain texts`() {
        val html = mvc.perform(get("/load")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Geen medische beoordeling."))
        assertTrue(html.contains("load-v1"))
        assertTrue(html.contains("Nog geen belasting te berekenen"))
        assertTrue(html.contains("id=\"begrippen\""))
        assertTrue(html.contains("Welke training past vandaag?"))
        val order = listOf("id=\"vandaag\"", "id=\"tijd\"", "id=\"weken\"", "id=\"herstel\"", "id=\"signalen\"", "id=\"afwisseling\"", "id=\"model\"", "id=\"verwachting\"", "id=\"data\"")
            .map { html.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), order.toString())
        assertTrue(html.contains("href=\"/load\""))
    }

    @Test
    fun `the response page renders without any data and says the minimum`() {
        val html = mvc.perform(get("/load/response")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("Geen medische beoordeling."))
        assertTrue(html.contains("Nog geen duidelijke patronen"))
        assertTrue(html.contains("Verband, geen oorzaak"))
        assertTrue(html.contains("id=\"begrippen\""))
        assertTrue(html.contains("Een samenvatting vraagt minstens 3"))
    }

    @Test
    fun `every period is adjustable, keeps the other choices and is clamped`() {
        val html = mvc.perform(get("/load").param("acute", "14").param("chart", "9999")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(html.contains("value=\"14\""))
        assertTrue(html.contains("value=\"730\""))
        assertTrue(html.contains("type=\"hidden\" name=\"acute\" value=\"14\""))
        assertTrue(html.contains("Alle periodes terug naar standaard"))
        val resp = mvc.perform(get("/load/response").param("follow", "2").param("deload", "50")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(resp.contains("type=\"hidden\" name=\"deload\" value=\"50\""))
        mvc.perform(get("/load/data").param("acute", "1")).andExpect(status().isOk).andExpect(jsonPath("$.overview.windows.acuteDays").value(3))
    }

    @Test
    fun `json renders and exposes no key, token or coordinates`() {
        val json = mvc.perform(get("/load/data"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.overview.algorithmVersion").value("load-v1"))
            .andExpect(jsonPath("$.response.algorithmVersion").value("load-v1"))
            .andReturn().response.contentAsString
        listOf("apiKey", "api-key", "token", "bearer", "Authorization", "startLat", "latlng").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
    }

    @Test
    fun `the pages are GET only`() {
        mvc.perform(post("/load")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/load/response")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/load/data")).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `the navigation and the recovery page link to the load page`() {
        val nav = mvc.perform(get("/load")).andReturn().response.contentAsString
        assertTrue(nav.contains("href=\"/load\" data-match=\"/load\""))
        val rec = mvc.perform(get("/recovery")).andReturn().response.contentAsString
        assertTrue(rec.contains("href=\"/load#vandaag\""))
    }
}
