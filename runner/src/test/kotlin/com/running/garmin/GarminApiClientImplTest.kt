package com.running.garmin

import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.LocalDate

class GarminApiClientImplTest {
    private val tokenUrl = "$BASE/personalapitoken-service/personal-api/personal-api-key/token"
    private val start = LocalDate.parse("2026-10-18")
    private val end = LocalDate.parse("2026-10-19")

    private fun GarminTestSupport.token(tag: String = "a", times: ExpectedCount = ExpectedCount.once()) {
        server.expect(times, requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), tag), MediaType.TEXT_PLAIN))
    }

    private fun GarminTestSupport.json(url: String, body: String) {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andExpect(header("Accept", "application/json"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    @Test
    fun `daily summaries map resting HR, Body Battery, stress and steps with provenance`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/usersummary-service/v2/dailysummaries?startDate=2026-10-18&endDate=2026-10-19", fixture("daily_summaries"))
        val days = t.client.getDailySummaries(start, end)
        assertEquals(2, days.size)
        val d = days.first()
        assertEquals(LocalDate.parse("2026-10-18"), d.calendarDate)
        assertEquals(45, d.restingHr!!.restingHr)
        assertEquals(26, d.bodyBattery!!.minValue)
        assertEquals(88, d.bodyBattery!!.charged)
        assertEquals(19, d.stress!!.avgLevel)
        assertEquals(5732, d.steps!!.steps)
        assertEquals("/usersummary-service/v2/dailysummaries", d.restingHr!!.source.endpoint)
        assertTrue(d.stress!!.source.originalUnits.contains("estimate"))
        assertNotNull(d.restingHr!!.source.rawJson)
        t.server.verify()
    }

    @Test
    fun `empty daily summaries is no data, not an error`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/usersummary-service/v2/dailysummaries?startDate=2026-10-18&endDate=2026-10-19", fixture("daily_summaries_empty"))
        assertTrue(t.client.getDailySummaries(start, end).isEmpty())
    }

    @Test
    fun `sleep summaries map stages and Garmin estimates, null filled nights are skipped`() {
        val t = GarminTestSupport()
        t.token()
        val withEmpty = "[" + fixture("sleep_summaries").trim().removePrefix("[").removeSuffix("]") + "," + fixture("sleep_empty") + "]"
        t.json("$BASE/sleep-service/sleep/dailySleepSummariesByDate?startDate=2026-10-18&endDate=2026-10-19", withEmpty)
        val nights = t.client.getSleepSummaries(start, end)
        assertEquals(2, nights.size)
        val n = nights.first()
        assertEquals(33060, n.sleepTimeSeconds)
        assertEquals(7020, n.deepSleepSeconds)
        assertEquals(88, n.overallScore)
        assertEquals("GOOD", n.overallQualifier)
        assertEquals(480, n.garminSleepNeedBaseline)
        assertEquals(1792271504000L, n.sleepStartGmtMs)
    }

    @Test
    fun `hrv summaries map the baseline band and keep missing fields null`() {
        val t = GarminTestSupport()
        t.token()
        val body = """{"hrvSummaries":[{"calendarDate":"2026-10-18","weeklyAvg":null,"lastNightAvg":74,"lastNight5MinHigh":null,"baseline":null,"status":"BALANCED","feedbackPhrase":null}]}"""
        t.json("$BASE/hrv-service/hrv/daily/2026-10-18/2026-10-19", body)
        val h = t.client.getHrvSummaries(start, end).single()
        assertEquals(74, h.lastNightAvg)
        assertNull(h.weeklyAvg)
        assertNull(h.baselineLowUpper)
        assertEquals(7, h.nullFieldCount())
        assertTrue(h.source.originalUnits.contains("nachtgemiddelde"))
    }

    @Test
    fun `body battery series uses chunks 480, merges duplicate timestamps and drops null levels`() {
        val t = GarminTestSupport()
        t.token()
        val body = """[{"date":"2026-10-18","bodyBatteryValuesArray":[[1000,46],[1000,46],[1180,null],[1180,47],[1360,null]],
            "bodyBatteryValueDescriptorDTOList":[{"bodyBatteryValueDescriptorIndex":0,"bodyBatteryValueDescriptorKey":"timestamp"},{"bodyBatteryValueDescriptorIndex":1,"bodyBatteryValueDescriptorKey":"bodyBatteryLevel"}]},
            {"date":"2026-10-19","bodyBatteryValuesArray":[[5,null]]}]"""
        t.json("$BASE/wellness-service/wellness/bodyBattery/reports/daily?startDate=2026-10-18&endDate=2026-10-19&chunks=480", body)
        val days = t.client.getBodyBatterySeries(start, end)
        assertEquals(1, days.size)
        assertEquals(listOf(1000L to 46, 1180L to 47), days.single().samples.map { it.timestampGmtMs to it.level })
        assertTrue(days.single().source.originalUnits.contains("UNVERIFIED"))
    }

    @Test
    fun `body battery fixtures parse`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/wellness-service/wellness/bodyBattery/reports/daily?startDate=2026-10-18&endDate=2026-10-19&chunks=480", fixture("body_battery"))
        assertEquals(2, t.client.getBodyBatterySeries(start, end).size)
        val e = GarminTestSupport()
        e.token()
        e.json("$BASE/wellness-service/wellness/bodyBattery/reports/daily?startDate=2026-10-18&endDate=2026-10-19&chunks=480", fixture("body_battery_empty"))
        assertTrue(e.client.getBodyBatterySeries(start, end).isEmpty())
    }

    @Test
    fun `403 is FORBIDDEN, makes exactly one data call and no second token exchange`() {
        val t = GarminTestSupport()
        t.token()
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("x"))
        val e = assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }
        assertEquals(GarminErrorCategory.FORBIDDEN, e.category)
        assertFalse(e.retryable)
        assertEquals(1, t.tokens.exchangeCount)
        t.server.verify()
    }

    @Test
    fun `hrv HTTP 204 is no data`() {
        val t = GarminTestSupport()
        t.token()
        t.server.expect(requestTo("$BASE/hrv-service/hrv/daily/2026-10-18/2026-10-19")).andRespond(withStatus(HttpStatus.NO_CONTENT))
        assertTrue(t.client.getHrvSummaries(start, end).isEmpty())
    }

    @Test
    fun `hrv fixture file parses`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/hrv-service/hrv/daily/2026-10-18/2026-10-19", fixture("hrv_summaries"))
        val h = t.client.getHrvSummaries(start, end)
        assertEquals(3, h.size)
        assertEquals(69, h.first().baselineLowUpper)
        assertEquals(0.25, h.first().baselineMarkerValue)
    }

    @Test
    fun `activity search maps references, sends paging parameters and drops profile image URLs from the raw copy`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/activitylist-service/activities/search/activities?start=0&limit=100&startDate=2026-10-18&endDate=2026-10-19", fixture("activity_search"))
        val acts = t.client.searchActivities(0, 100, start, end)
        assertEquals(3, acts.size)
        val a = acts.first()
        assertEquals(1000000001L, a.activityId)
        assertEquals("running", a.typeKey)
        assertEquals(java.time.Instant.parse("2026-10-11T08:02:15Z"), a.startTimeGmt)
        assertEquals(21284.630859375, a.distanceMeters!!, 0.0001)
        assertFalse(a.isManual)
        val raw = a.source.rawJson!!
        assertFalse(raw.contains("ownerProfileImageUrl"))
        assertTrue(raw.contains("ownerFullName"))
    }

    @Test
    fun `heart rate zones map per sport`() {
        val t = GarminTestSupport()
        t.token()
        t.json("$BASE/biometric-service/heartRateZones/v2", fixture("heart_rate_zones"))
        val z = t.client.getHeartRateZones()
        assertEquals(listOf("DEFAULT", "RUNNING"), z.map { it.sport })
        assertEquals(151, z[1].zone2Floor)
    }

    @Test
    fun `raw payloads are not kept when storing is off, and a secret in a payload is redacted`() {
        val off = GarminTestSupport(storeRaw = false)
        off.token()
        off.json("$BASE/biometric-service/heartRateZones/v2", fixture("heart_rate_zones"))
        assertNull(off.client.getHeartRateZones().first().source.rawJson)

        val on = GarminTestSupport()
        on.token()
        on.json("$BASE/biometric-service/heartRateZones/v2", """[{"sport":"RUNNING","trainingMethod":"$FAKE_KEY"}]""")
        val raw = on.client.getHeartRateZones().single().source.rawJson!!
        assertFalse(raw.contains(FAKE_KEY))
        assertTrue(raw.contains("[REDACTED]"))
    }

    @Test
    fun `raw payload text does not depend on the key order Garmin used`() {
        fun raw(body: String): String {
            val t = GarminTestSupport()
            t.token()
            t.json("$BASE/biometric-service/heartRateZones/v2", body)
            return t.client.getHeartRateZones().single().source.rawJson!!
        }
        assertEquals(raw("""[{"sport":"RUNNING","a":{"y":1,"x":2},"z":3}]"""), raw("""[{"z":3,"a":{"x":2,"y":1},"sport":"RUNNING"}]"""))
    }

    @Test
    fun `a 401 re-exchanges the key exactly once and repeats the call once`() {
        val t = GarminTestSupport()
        t.token("1")
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andExpect(header("Authorization", "Bearer ${jwt(NOW.plus(Duration.ofHours(24)), "1")}"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        t.token("2")
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andExpect(header("Authorization", "Bearer ${jwt(NOW.plus(Duration.ofHours(24)), "2")}"))
            .andRespond(withSuccess(fixture("heart_rate_zones"), MediaType.APPLICATION_JSON))
        assertEquals(2, t.client.getHeartRateZones().size)
        assertEquals(2, t.tokens.exchangeCount)
        t.server.verify()
    }

    @Test
    fun `a second 401 is an AUTH failure and no third call is made`() {
        val t = GarminTestSupport()
        t.token("1")
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        t.token("2")
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        val e = assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }
        assertEquals(GarminErrorCategory.AUTH, e.category)
        assertEquals(2, t.tokens.exchangeCount)
        t.server.verify()
    }

    @Test
    fun `HTTP errors map to categories and never leak the body`() {
        val cases = mapOf(
            HttpStatus.TOO_MANY_REQUESTS to GarminErrorCategory.RATE_LIMIT,
            HttpStatus.INTERNAL_SERVER_ERROR to GarminErrorCategory.SERVER,
            HttpStatus.SERVICE_UNAVAILABLE to GarminErrorCategory.SERVER,
            HttpStatus.BAD_REQUEST to GarminErrorCategory.CLIENT,
            HttpStatus.NOT_FOUND to GarminErrorCategory.CLIENT,
        )
        cases.forEach { (status, category) ->
            val t = GarminTestSupport()
            t.token()
            t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2")).andRespond(withStatus(status).body("secret health data $FAKE_KEY"))
            val e = assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }
            assertEquals(category, e.category, "status $status")
            assertEquals(status.value(), e.httpStatus)
            assertFalse(e.message!!.contains(FAKE_KEY))
            assertFalse(e.message!!.contains("health"))
        }
    }

    private fun failing(body: () -> Unit, respond: (GarminTestSupport) -> Unit): GarminApiException {
        val t = GarminTestSupport()
        t.token()
        respond(t)
        return assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }
    }

    private val zonesUrl = "$BASE/biometric-service/heartRateZones/v2"

    @Test
    fun `429 keeps the Retry-After header value in the message (not the body)`() {
        val t = GarminTestSupport()
        t.token()
        t.server.expect(requestTo("$BASE/biometric-service/heartRateZones/v2"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30").body("secret"))
        val e = assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }
        assertEquals(GarminErrorCategory.RATE_LIMIT, e.category)
        assertTrue(e.message!!.contains("Retry-After: 30"))
        assertFalse(e.message!!.contains("secret"))
    }

    @Test
    fun `network failure is NETWORK and a socket timeout is TIMEOUT`() {
        val net = failing({}) { it.server.expect(requestTo(zonesUrl)).andRespond(withException(java.io.IOException("down"))) }
        assertEquals(GarminErrorCategory.NETWORK, net.category)
        val slow = failing({}) { it.server.expect(requestTo(zonesUrl)).andRespond(withException(SocketTimeoutException("slow"))) }
        assertEquals(GarminErrorCategory.TIMEOUT, slow.category)
    }

    @Test
    fun `invalid JSON is PARSE and an unexpected shape is PARSE`() {
        assertEquals(GarminErrorCategory.PARSE, failing({}) { it.json(zonesUrl, "{not json") }.category)
        assertEquals(GarminErrorCategory.PARSE, failing({}) { it.json(zonesUrl, """{"unexpected":true}""") }.category)
    }

    @Test
    fun `empty body and empty array are no data`() {
        for (body in listOf("", "[]")) {
            val t = GarminTestSupport()
            t.token()
            t.json(zonesUrl, body)
            assertTrue(t.client.getHeartRateZones().isEmpty())
        }
    }

    @Test
    fun `no key means no HTTP call at all`() {
        val t = GarminTestSupport(key = "")
        assertEquals(GarminErrorCategory.NOT_CONFIGURED, assertThrows(GarminApiException::class.java) { t.client.getHeartRateZones() }.category)
        t.server.verify()
    }
}
