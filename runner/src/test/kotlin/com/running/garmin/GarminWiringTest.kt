package com.running.garmin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val DB = "spring.datasource.url=jdbc:h2:mem:garminwiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
private const val DIALECT = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"

/** Full context with no Garmin key: the app starts and reports the state (acceptance criterion 6). No network. */
@SpringBootTest(properties = [DB, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", DIALECT, "garmin.api-key="])
@AutoConfigureMockMvc
class GarminWiringNoKeyTest @Autowired constructor(private val mvc: MockMvc) {
    @Test
    fun `app starts without a key, status says NOT_CONFIGURED and a trigger makes no call`() {
        mvc.perform(get("/garmin/status")).andExpect(status().isOk).andExpect(jsonPath("$.state").value("NOT_CONFIGURED"))
        mvc.perform(post("/garmin/sync")).andExpect(status().isServiceUnavailable)
        mvc.perform(get("/garmin/matches/review")).andExpect(status().isOk)
        val report = mvc.perform(get("/garmin/matches/report")).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(report.contains("apiKey"))
    }
}

@SpringBootTest(properties = [DB, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", DIALECT, "garmin.enabled=false", "garmin.api-key=some-key-value-123456"])
@AutoConfigureMockMvc
class GarminWiringDisabledTest @Autowired constructor(private val mvc: MockMvc, private val context: org.springframework.context.ApplicationContext) {
    @Test
    fun `flag off, no scheduler bean and status says DISABLED`() {
        assertFalse(context.containsBean("garminSyncScheduler"))
        mvc.perform(get("/garmin/status")).andExpect(jsonPath("$.state").value("DISABLED"))
        mvc.perform(post("/garmin/backfill")).andExpect(status().isServiceUnavailable)
    }
}
