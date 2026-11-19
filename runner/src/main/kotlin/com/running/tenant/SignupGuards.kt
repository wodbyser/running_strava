package com.running.tenant

import jakarta.servlet.http.HttpServletRequest
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** The invite code from `APP_SIGNUP_CODE` (design §6.4). Signup is off when it is blank or shorter than 16 characters. */
class InviteCodes(private val envCode: String) {
    val signupEnabled: Boolean get() = envCode.length >= MIN_LENGTH

    /** Constant time: both sides are hashed first, so neither the length nor the first wrong character shows in the timing. */
    fun matches(candidate: String): Boolean {
        if (!signupEnabled) return false
        return MessageDigest.isEqual(sha256(candidate), sha256(envCode))
    }

    override fun toString() = "InviteCodes(signupEnabled=$signupEnabled)"

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    companion object {
        const val MIN_LENGTH = 16
    }
}

/** Counts events per key inside a sliding window, in memory only (no dependency, design §6.4). */
class AttemptLimiter(
    private val max: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val events = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun count(key: String): Int {
        val queue = events[key] ?: return 0
        synchronized(queue) {
            prune(queue)
            return queue.size
        }
    }

    fun blocked(key: String): Boolean = count(key) >= max

    /** Records one attempt and tells whether it was still allowed (the [max]th attempt is allowed, the next one is not). */
    fun hit(key: String): Boolean {
        if (events.size > MAX_KEYS) sweep()
        val queue = events.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            prune(queue)
            if (queue.size >= max) return false
            queue.add(clock.instant())
            return true
        }
    }

    fun record(key: String) {
        hit(key)
    }

    fun clear(key: String) {
        events.remove(key)
    }

    private fun prune(queue: ArrayDeque<Instant>) {
        val cutoff = clock.instant().minus(window)
        while (queue.isNotEmpty() && queue.peekFirst().isBefore(cutoff)) queue.pollFirst()
    }

    private fun sweep() {
        events.entries.removeIf { (_, queue) -> synchronized(queue) { prune(queue); queue.isEmpty() } }
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}

/**
 * The address of the caller. Behind the reverse proxy (Tailscale Funnel) `remoteAddr` is the proxy, so `X-Forwarded-For`
 * is only believed when the direct peer is a loopback or private address, and then only its last entry
 * (the one our own proxy appended; earlier entries are whatever the client sent).
 */
object ClientIp {
    fun of(request: HttpServletRequest): String {
        val remote = request.remoteAddr.orEmpty()
        if (!isInternal(remote)) return remote
        val forwarded = request.getHeader("X-Forwarded-For")?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        return forwarded?.take(64) ?: remote
    }

    fun isInternal(address: String): Boolean {
        if (address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("fc") || address.startsWith("fd") || address.startsWith("fe80")) return true
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 127 || parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 100 && parts[1] in 64..127)
    }
}
