package com.running.garmin

import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminTokenProvider
import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.time.Duration
import java.time.ZoneOffset

class GarminTokenProviderTest {
    private val tokenUrl = "$BASE/personalapitoken-service/personal-api/personal-api-key/token"

    @Test
    fun `exchanges the key with the ApiKey header and caches the token`() {
        val t = GarminTestSupport()
        val token = jwt(NOW.plus(Duration.ofHours(24)))
        t.server.expect(requestTo(tokenUrl)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "ApiKey $FAKE_KEY"))
            .andRespond(withSuccess(" $token\n", MediaType.TEXT_PLAIN))
        assertEquals(token, t.tokens.accessToken())
        assertEquals(token, t.tokens.accessToken())
        assertEquals(1, t.tokens.exchangeCount)
        t.server.verify()
    }

    @Test
    fun `a token with less than one hour left is replaced`() {
        val t = GarminTestSupport()
        val soon = jwt(NOW.plus(Duration.ofMinutes(59)), "soon")
        val fresh = jwt(NOW.plus(Duration.ofHours(24)), "fresh")
        t.server.expect(requestTo(tokenUrl)).andRespond(withSuccess(soon, MediaType.TEXT_PLAIN))
        t.server.expect(requestTo(tokenUrl)).andRespond(withSuccess(fresh, MediaType.TEXT_PLAIN))
        assertEquals(soon, t.tokens.accessToken())
        assertEquals(fresh, t.tokens.accessToken())
        t.server.verify()
    }

    @Test
    fun `invalidate forces a new exchange`() {
        val t = GarminTestSupport()
        t.server.expect(requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "1"), MediaType.TEXT_PLAIN))
        t.server.expect(requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "2"), MediaType.TEXT_PLAIN))
        t.tokens.accessToken()
        t.tokens.invalidate()
        t.tokens.accessToken()
        assertEquals(2, t.tokens.exchangeCount)
        t.server.verify()
    }

    @Test
    fun `blank key makes no HTTP call and reports NOT_CONFIGURED`() {
        val t = GarminTestSupport(key = "")
        val e = assertThrows(GarminApiException::class.java) { t.tokens.accessToken() }
        assertEquals(GarminErrorCategory.NOT_CONFIGURED, e.category)
        t.server.verify()
    }

    @Test
    fun `rejected key is AUTH and the message holds no key`() {
        val t = GarminTestSupport()
        t.server.expect(requestTo(tokenUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("echo $FAKE_KEY"))
        val e = assertThrows(GarminApiException::class.java) { t.tokens.accessToken() }
        assertEquals(GarminErrorCategory.AUTH, e.category)
        assertFalse(e.message!!.contains(FAKE_KEY))
    }

    @Test
    fun `non JWT body and JWT without exp are failed exchanges`() {
        val t = GarminTestSupport()
        t.server.expect(requestTo(tokenUrl)).andRespond(withSuccess("not-a-jwt", MediaType.TEXT_PLAIN))
        assertEquals(GarminErrorCategory.AUTH, assertThrows(GarminApiException::class.java) { t.tokens.accessToken() }.category)

        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val noExp = "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString("""{"iat":1}""".toByteArray())}.sig"
        val t2 = GarminTestSupport()
        t2.server.expect(requestTo(tokenUrl)).andRespond(withSuccess(noExp, MediaType.TEXT_PLAIN))
        assertEquals(GarminErrorCategory.AUTH, assertThrows(GarminApiException::class.java) { t2.tokens.accessToken() }.category)
    }

    @Test
    fun `server error at exchange is SERVER and a 429 is RATE_LIMIT`() {
        val t = GarminTestSupport()
        t.server.expect(requestTo(tokenUrl)).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        assertEquals(GarminErrorCategory.SERVER, assertThrows(GarminApiException::class.java) { t.tokens.accessToken() }.category)
        val t2 = GarminTestSupport()
        t2.server.expect(requestTo(tokenUrl)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        assertEquals(GarminErrorCategory.RATE_LIMIT, assertThrows(GarminApiException::class.java) { t2.tokens.accessToken() }.category)
    }

    @Test
    fun `properties toString never prints the key and a zero timeout is rejected`() {
        assertFalse(GarminProperties(apiKey = FAKE_KEY).toString().contains(FAKE_KEY))
        assertThrows(IllegalArgumentException::class.java) { GarminProperties(timeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { GarminProperties(timeout = Duration.ofSeconds(-1)) }
        assertEquals(730, GarminProperties().backfill.defaultDays)
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(4)), GarminProperties().retry.delays)
        assertEquals(3, GarminProperties().retry.maxAttempts)
        assertEquals(Duration.ofSeconds(30), GarminProperties().timeout)
    }
}
