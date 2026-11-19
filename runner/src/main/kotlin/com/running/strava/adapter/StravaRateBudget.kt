package com.running.strava.adapter

import org.springframework.http.HttpHeaders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Semaphore

/**
 * What the app has used of Strava's read limits, which are shared by every athlete (design §10.4).
 * Fed by the response headers; no library, no persistence. Only tenant backfills ask it for permission:
 * the owner's own sync is never blocked by it.
 */
class StravaRateBudget(
    private val clock: Clock = Clock.systemUTC(),
    private val threshold: Double = 0.70,
) {
    private data class Reading(val at: Instant, val usage15: Int, val limit15: Int, val usageDay: Int, val limitDay: Int)

    @Volatile private var reading: Reading? = null
    @Volatile private var blockedUntil: Instant? = null

    fun observe(headers: HttpHeaders?) {
        if (headers == null) return
        val usage = pair(headers.getFirst("X-ReadRateLimit-Usage") ?: headers.getFirst("X-RateLimit-Usage")) ?: return
        val limit = pair(headers.getFirst("X-ReadRateLimit-Limit") ?: headers.getFirst("X-RateLimit-Limit")) ?: return
        reading = Reading(clock.instant(), usage.first, limit.first, usage.second, limit.second)
    }

    /** HTTP 429: stop until the current 15-minute window is over (or the day, when the daily limit is what ran out). */
    fun rateLimited(headers: HttpHeaders? = null) {
        observe(headers)
        val now = clock.instant()
        val r = reading
        blockedUntil = if (r != null && r.usageDay >= r.limitDay) nextMidnightUtc(now) else nextWindow(now)
    }

    /** False when a tenant backfill must stop for now: 429 seen, or ~70% of the 15-minute or daily read budget used. */
    fun tenantBackfillAllowed(): Boolean {
        val now = clock.instant()
        blockedUntil?.let { if (now.isBefore(it)) return false }
        val r = reading ?: return true
        val usage15 = if (nextWindow(r.at).isAfter(now)) r.usage15 else 0
        val usageDay = if (nextMidnightUtc(r.at).isAfter(now)) r.usageDay else 0
        return usage15 < r.limit15 * threshold && usageDay < r.limitDay * threshold
    }

    private fun pair(text: String?): Pair<Int, Int>? {
        val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        return if (parts.size >= 2 && parts[0] != null && parts[1] != null) parts[0]!! to parts[1]!! else null
    }

    private fun nextWindow(t: Instant): Instant = Instant.ofEpochSecond((t.epochSecond / WINDOW + 1) * WINDOW)

    private fun nextMidnightUtc(t: Instant): Instant = t.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    private companion object {
        const val WINDOW = 15 * 60L
    }
}

/** Only one tenant backfill runs at a time, app-wide (design §10.4). The owner's own jobs never pass through here. */
class BackfillGate {
    private val permit = Semaphore(1)

    /** Runs [block] when no other backfill is running; returns false (and runs nothing) otherwise. */
    fun runIfFree(block: () -> Unit): Boolean {
        if (!permit.tryAcquire()) return false
        try {
            block()
        } finally {
            permit.release()
        }
        return true
    }
}
