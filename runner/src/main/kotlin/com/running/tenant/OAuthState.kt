package com.running.tenant

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class StateCheck { VALID, INVALID, EXPIRED, REPLAYED }

/**
 * Signed `state` for the Strava OAuth round trip (design §14 Phase 4): HMAC over tenant, session and expiry plus a
 * single-use nonce. A state from another tenant or session, an old one, or a second use is rejected.
 * Only a hash of the session id travels to Strava.
 */
class OAuthState(
    masterKey: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
) {
    private val key = SecretKeySpec(
        Mac.getInstance(MAC).apply { init(SecretKeySpec(Base64.getDecoder().decode(masterKey.trim()), MAC)) }.doFinal("oauth-state-v1".toByteArray()),
        MAC,
    )
    private val random = SecureRandom()
    private val used = ConcurrentHashMap<String, Long>()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun create(tenant: String, sessionId: String): String {
        val nonce = encoder.encodeToString(ByteArray(12).also(random::nextBytes))
        val payload = listOf(VERSION, tenant, sessionTag(sessionId), clock.instant().plus(ttl).epochSecond.toString(), nonce).joinToString(".")
        return encoder.encodeToString(payload.toByteArray()) + "." + encoder.encodeToString(sign(payload))
    }

    fun verify(state: String?, tenant: String, sessionId: String): StateCheck {
        val parts = state?.split('.') ?: return StateCheck.INVALID
        if (parts.size != 2) return StateCheck.INVALID
        val payload = try {
            String(decoder.decode(parts[0]))
        } catch (e: IllegalArgumentException) {
            return StateCheck.INVALID
        }
        val signature = try {
            decoder.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return StateCheck.INVALID
        }
        if (!MessageDigest.isEqual(signature, sign(payload))) return StateCheck.INVALID
        val fields = payload.split('.')
        if (fields.size != 5 || fields[0] != VERSION) return StateCheck.INVALID
        if (fields[1] != tenant || !MessageDigest.isEqual(fields[2].toByteArray(), sessionTag(sessionId).toByteArray())) return StateCheck.INVALID
        val expires = fields[3].toLongOrNull() ?: return StateCheck.INVALID
        val now = clock.instant().epochSecond
        if (now > expires) return StateCheck.EXPIRED
        used.entries.removeIf { it.value < now }
        return if (used.putIfAbsent(fields[4], expires) == null) StateCheck.VALID else StateCheck.REPLAYED
    }

    private fun sign(payload: String): ByteArray = Mac.getInstance(MAC).apply { init(key) }.doFinal(payload.toByteArray())

    private fun sessionTag(sessionId: String): String = encoder.encodeToString(sign("session:$sessionId")).take(22)

    private companion object {
        const val MAC = "HmacSHA256"
        const val VERSION = "v1"
    }
}
