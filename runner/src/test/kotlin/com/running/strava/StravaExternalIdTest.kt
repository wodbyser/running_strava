package com.running.strava

import com.fasterxml.jackson.databind.ObjectMapper
import com.running.config.StravaProperties
import com.running.strava.adapter.StravaApiClientImpl
import com.running.strava.db.*
import com.running.strava.domain.StravaToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

@DataJpaTest(properties = ["spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"])
@Import(ActivityRepositoryImpl::class, com.fasterxml.jackson.databind.ObjectMapper::class)
class StravaExternalIdTest @Autowired constructor(private val repo: ActivityRepositoryImpl) {

    private fun detail(extra: String) = """{"id":42,"name":"x","distance":1000.0,"moving_time":300,"elapsed_time":310,"type":"Run","sport_type":"Run",
        "start_date":"2026-10-20T08:00:00Z","timezone":"(GMT+01:00) Europe/Brussels","average_speed":3.3,"max_speed":4.0 $extra}"""

    private fun client(body: String): StravaApiClientImpl {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://www.strava.com/api/v3/activities/42")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        return StravaApiClientImpl(builder.build(), StravaProperties())
    }

    private val token = StravaToken("a", "r", 0, 1)

    @Test
    fun `parser reads external_id and device_name when present and leaves null otherwise`() {
        val a = client(detail(""","external_id":"garmin_push_1.fit","device_name":"Some Watch"""")).getActivity(token, 42)
        assertEquals("garmin_push_1.fit", a.externalId)
        assertEquals("Some Watch", a.deviceName)
        val b = client(detail("")).getActivity(token, 42)
        assertNull(b.externalId)
        assertNull(b.deviceName)
    }

    @Test
    fun `a summary save without device_name keeps the stored value`() {
        val full = client(detail(""","external_id":"e1","device_name":"Some Watch"""")).getActivity(token, 42)
        repo.save(full)
        repo.saveAll(listOf(full.copy(deviceName = null, externalId = null)))
        val back = repo.findById(42)!!
        assertEquals("e1", back.externalId)
        assertEquals("Some Watch", back.deviceName)
    }
}
