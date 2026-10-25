package com.running.garmin.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.running.config.GarminProperties
import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Exchanges the API key for a bearer token and keeps it in memory only (no disk cache).
 * The lifetime comes from the JWT `exp` claim. A token with less than one hour left is replaced.
 */
class GarminTokenProvider(
    private val restClient: RestClient,
    private val properties: GarminProperties,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private var token: String? = null
    private var expiresAt: Instant? = null
    var exchangeCount: Int = 0
        private set

    /** Key and current token, for redaction only. */
    @Synchronized
    fun secrets(): List<String> = listOfNotNull(properties.apiKey.takeIf { it.isNotBlank() }, token)

    @Synchronized
    fun accessToken(): String {
        val current = token
        val exp = expiresAt
        if (current != null && exp != null && Duration.between(clock.instant(), exp) > REFRESH_WINDOW) return current
        return exchange()
    }

    /** Drops the cached token so the next call exchanges the key again. */
    @Synchronized
    fun invalidate() {
        token = null
        expiresAt = null
    }

    private fun exchange(): String {
        if (!properties.configured) {
            throw GarminApiException(GarminErrorCategory.NOT_CONFIGURED, null, "Garmin API key is not configured")
        }
        exchangeCount++
        val response = restClient.post()
            .uri(TOKEN_PATH)
            .header("Authorization", "ApiKey ${properties.apiKey}")
            .header("Accept", "*/*")
            .fetch("token exchange")
        if (response.status !in 200..299) {
            // A rejected key (4xx other than 429) is an AUTH failure and is not retried.
            throw if (response.status in 400..499 && response.status != 429) {
                GarminApiException(GarminErrorCategory.AUTH, response.status, "Garmin rejected the API key (HTTP ${response.status})")
            } else {
                statusToException(response.status, "token exchange")
            }
        }
        val jwt = response.body.trim()
        val exp = parseExpiry(jwt)
        token = jwt
        expiresAt = exp
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
        const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        val REFRESH_WINDOW: Duration = Duration.ofHours(1)
    }
}
