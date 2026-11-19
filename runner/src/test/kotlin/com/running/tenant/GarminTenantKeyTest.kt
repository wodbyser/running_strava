package com.running.tenant

import com.running.config.GarminProperties
import com.running.garmin.NOW
import com.running.garmin.adapter.GarminRedactor
import com.running.garmin.adapter.GarminTokenProvider
import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.jwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.Base64

class GarminTenantKeyTest {
    private val ownerKey = "owner-env-key-0000001"
    private val annaKey = "anna-stored-key-000002"
    private val cipher = SecretCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
    private val stored = mapOf("anna" to cipher.encrypt(annaKey), "broken" to "enc:v1:AAAAAAAAAAAAAAAAAAAA")
    private val registry = TenantRegistry {
        listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false), Tenant("piet", "running_u_piet", false), Tenant("broken", "running_u_broken", false))
    }.also { it.reload() }
    private val properties = GarminProperties(apiKey = ownerKey, baseUrl = "https://garmin.test")
    private val settings = TenantSettings { stored[TenantContext.current()] }
    private val resolver = CredentialResolver(properties, registry, settings, cipher)
    private val tokenUrl = "https://garmin.test/personalapitoken-service/personal-api/personal-api-key/token"

    private val builder = RestClient.builder().baseUrl("https://garmin.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = GarminTokenProvider(builder.build(), properties, Clock.fixed(NOW, ZoneOffset.UTC), credentials = resolver)

    @Test
    fun `owner uses the env key, a tenant its stored key, an unknown tenant or none gets nothing`() {
        assertEquals(ownerKey, resolver.garminKey("wout"))
        assertEquals(annaKey, resolver.garminKey("anna"))
        assertNull(resolver.garminKey("piet"))
        assertNull(resolver.garminKey("broken"))
        assertNull(resolver.garminKey(null))
    }

    @Test
    fun `flag off has no registry and keeps the env key for everybody`() {
        val off = CredentialResolver(properties)
        assertEquals(ownerKey, off.garminKey(null))
        assertEquals(ownerKey, off.garminKey("anything"))
        assertNull(CredentialResolver(GarminProperties()).garminKey(null))
    }

    @Test
    fun `a tenant without a key makes zero Garmin calls`() {
        val e = assertThrows<GarminApiException> { TenantContext.asTenant("piet") { provider.accessToken() } }
        assertEquals(GarminErrorCategory.NOT_CONFIGURED, e.category)
        assertThrows<GarminApiException> { provider.accessToken() }
        server.verify()
        assertEquals(0, provider.exchangeCount)
    }

    @Test
    fun `each tenant exchanges and caches its own key`() {
        val tOwner = jwt(NOW.plus(Duration.ofHours(24)), "o")
        val tAnna = jwt(NOW.plus(Duration.ofHours(24)), "a")
        server.expect(requestTo(tokenUrl)).andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "ApiKey $ownerKey"))
            .andRespond(withSuccess(tOwner, MediaType.TEXT_PLAIN))
        server.expect(requestTo(tokenUrl)).andExpect(header("Authorization", "ApiKey $annaKey"))
            .andRespond(withSuccess(tAnna, MediaType.TEXT_PLAIN))
        assertEquals(tOwner, TenantContext.asTenant("wout") { provider.accessToken() })
        assertEquals(tAnna, TenantContext.asTenant("anna") { provider.accessToken() })
        assertEquals(tOwner, TenantContext.asTenant("wout") { provider.accessToken() })
        assertEquals(tAnna, TenantContext.asTenant("anna") { provider.accessToken() })
        assertEquals(2, provider.exchangeCount)
        server.verify()
    }

    @Test
    fun `invalidate and evict only touch one tenant`() {
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "o1"), MediaType.TEXT_PLAIN))
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "a1"), MediaType.TEXT_PLAIN))
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "a2"), MediaType.TEXT_PLAIN))
        TenantContext.asTenant("wout") { provider.accessToken() }
        TenantContext.asTenant("anna") { provider.accessToken() }
        TenantContext.asTenant("anna") { provider.invalidate() }
        TenantContext.asTenant("wout") { provider.accessToken() }
        TenantContext.asTenant("anna") { provider.accessToken() }
        provider.evict("anna")
        assertEquals(3, provider.exchangeCount)
        server.verify()
    }

    @Test
    fun `log redaction covers the key and token of every tenant`() {
        val tAnna = jwt(NOW.plus(Duration.ofHours(24)), "a")
        server.expect(requestTo(tokenUrl)).andRespond(withSuccess(tAnna, MediaType.TEXT_PLAIN))
        TenantContext.asTenant("anna") { provider.accessToken() }
        val redactor = GarminRedactor { provider.secrets() }
        val line = "failed with $annaKey and $tAnna while owner $ownerKey"
        val asAnna = TenantContext.asTenant("anna") { redactor.redact(line) }
        val asOwner = TenantContext.asTenant("wout") { redactor.redact(line) }
        for (out in listOf(asAnna, asOwner)) {
            assertFalse(out.contains(annaKey)); assertFalse(out.contains(tAnna)); assertFalse(out.contains(ownerKey))
            assertTrue(out.contains("[REDACTED]"))
        }
    }

    @Test
    fun `a rotated key drops the cached token`() {
        var current = "first-key-00000001"
        val rotating = CredentialResolver(properties, registry, { cipher.encrypt(current) }, cipher)
        val p = GarminTokenProvider(builder.build(), properties, Clock.fixed(NOW, ZoneOffset.UTC), credentials = rotating)
        server.expect(requestTo(tokenUrl)).andExpect(header("Authorization", "ApiKey first-key-00000001"))
            .andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "1"), MediaType.TEXT_PLAIN))
        server.expect(requestTo(tokenUrl)).andExpect(header("Authorization", "ApiKey second-key-0000002"))
            .andRespond(withSuccess(jwt(NOW.plus(Duration.ofHours(24)), "2"), MediaType.TEXT_PLAIN))
        TenantContext.asTenant("anna") { p.accessToken() }
        current = "second-key-0000002"
        TenantContext.asTenant("anna") { p.accessToken() }
        server.verify()
    }
}
