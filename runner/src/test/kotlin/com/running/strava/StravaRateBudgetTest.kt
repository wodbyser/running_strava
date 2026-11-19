package com.running.strava

import com.running.strava.adapter.BackfillGate
import com.running.strava.adapter.StravaRateBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StravaRateBudgetTest {
    private var now = Instant.parse("2026-11-19T10:05:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val budget = StravaRateBudget(clock)

    private fun headers(usage: String, limit: String = "200,2000") = HttpHeaders().apply {
        add("X-ReadRateLimit-Usage", usage); add("X-ReadRateLimit-Limit", limit)
    }

    @Test
    fun `no reading yet allows`() = assertTrue(budget.tenantBackfillAllowed())

    @Test
    fun `stops at 70 percent of the 15 minute budget and recovers when the window is over`() {
        budget.observe(headers("100,300"))
        assertTrue(budget.tenantBackfillAllowed())
        budget.observe(headers("140,300"))
        assertFalse(budget.tenantBackfillAllowed())
        now = Instant.parse("2026-11-19T10:15:00Z")
        assertTrue(budget.tenantBackfillAllowed())
    }

    @Test
    fun `stops at 70 percent of the daily budget until midnight UTC`() {
        budget.observe(headers("10,1400"))
        assertFalse(budget.tenantBackfillAllowed())
        now = Instant.parse("2026-11-19T23:59:00Z")
        assertFalse(budget.tenantBackfillAllowed())
        now = Instant.parse("2026-11-20T00:00:00Z")
        assertTrue(budget.tenantBackfillAllowed())
    }

    @Test
    fun `a 429 blocks for the window and garbage headers are ignored`() {
        budget.observe(HttpHeaders().apply { add("X-ReadRateLimit-Usage", "x,y") })
        assertTrue(budget.tenantBackfillAllowed())
        budget.rateLimited()
        assertFalse(budget.tenantBackfillAllowed())
        now = Instant.parse("2026-11-19T10:15:00Z")
        assertTrue(budget.tenantBackfillAllowed())
    }

    @Test
    fun `only one backfill at a time`() {
        val gate = BackfillGate()
        var inner = true
        assertTrue(gate.runIfFree { inner = gate.runIfFree { } })
        assertFalse(inner)
        assertTrue(gate.runIfFree { })
        assertEquals(false, inner)
    }
}
