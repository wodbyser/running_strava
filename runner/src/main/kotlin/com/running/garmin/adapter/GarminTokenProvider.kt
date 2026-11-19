package com.running.garmin.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.running.config.GarminProperties
import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import com.running.tenant.CredentialResolver
import com.running.tenant.TenantContext
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exchanges the API key for a bearer token and keeps it in memory only (no disk cache), one entry per tenant.
 * The lifetime comes from the JWT `exp` claim. A token with less than one hour left is replaced.
 */
class GarminTokenProvider(
    private val restClient: RestClient,
    private val properties: GarminProperties,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val credentials: CredentialResolver = CredentialResolver(properties),
) {
    private class State {
        var key: String? = null
        var token: String? = null
        var expiresAt: Instant? = null
    }

    private val states = ConcurrentHashMap<String, State>()
    private val exchanges = AtomicInteger()
    val exchangeCount: Int get() = exchanges.get()

    private fun state(tenant: String?) = states.computeIfAbsent(tenant ?: NO_TENANT) { State() }

    /** Keys and tokens of every tenant seen so far, for redaction only: a line of one tenant must never show another's secret either. */
    fun secrets(): List<String> {
        val own = credentials.garminKey(TenantContext.current())
        val cached = states.values.flatMap { st -> synchronized(st) { listOfNotNull(st.key, st.token) } }
        return (listOfNotNull(own, properties.apiKey.takeIf { it.isNotBlank() }) + cached).distinct()
    }

    fun accessToken(): String {
        val tenant = TenantContext.current()
        val st = state(tenant)
        synchronized(st) {
            val key = credentials.garminKey(tenant)
                ?: throw GarminApiException(GarminErrorCategory.NOT_CONFIGURED, null, "Garmin API key is not configured")
            if (st.key != key) {
                st.key = key
                st.token = null
                st.expiresAt = null
            }
            val current = st.token
            val exp = st.expiresAt
            if (current != null && exp != null && Duration.between(clock.instant(), exp) > REFRESH_WINDOW) return current
            return exchange(st, key)
        }
    }

    /** Drops the cached token of the current tenant so the next call exchanges the key again. */
    fun invalidate() {
        val st = state(TenantContext.current())
        synchronized(st) {
            st.token = null
            st.expiresAt = null
        }
    }

    /** Forgets everything of one tenant (reset, delete). */
    fun evict(tenant: String) {
        states.remove(tenant)
    }

    private fun exchange(st: State, key: String): String {
        exchanges.incrementAndGet()
        val response = restClient.post()
            .uri(TOKEN_PATH)
            .header("Authorization", "ApiKey $key")
            .header("Accept", "*/*")
            .fetch("token exchange")
        if (response.status !in 200..299) {
            // A rejected key (4xx other than 429) is an AUTH failure and is not retried.
            throw if (response.status in 400..499 && response.status != 429) {
                GarminApiException(GarminErrorCategory.AUTH, response.status, "Garmin rejected the API key (HTTP ${response.status})")
            } else {
                statusToException(response.status, "token exchange", response.retryAfter)
            }
        }
        val jwt = response.body.trim()
        val exp = parseExpiry(jwt)
        st.token = jwt
        st.expiresAt = exp
        return jwt
    }

    private fun parseExpiry(jwt: String): Instant {
        val parts = jwt.split('.')
        if (parts.size != 3) throw GarminApiException(GarminErrorCategory.AUTH, null, "Token exchange did not return a JWT")
        val exp = try {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            mapper.readTree(payload).path("exp").takeIf { it.isNumber }?.asLong()
        } catch (e: Exception) {
            null
        } ?: throw GarminApiException(GarminErrorCategory.AUTH, null, "Token has no exp claim")
        return Instant.ofEpochSecond(exp)
    }

    companion object {
        private const val NO_TENANT = ""
        const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        val REFRESH_WINDOW: Duration = Duration.ofHours(1)
    }
}
