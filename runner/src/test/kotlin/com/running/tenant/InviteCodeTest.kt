package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class InviteCodeTest {
    private val code = "correct-horse-battery-0123"

    @Test
    fun `only the exact code is accepted`() {
        val invites = InviteCodes(code)
        assertTrue(invites.matches(code))
        assertFalse(invites.matches(code.dropLast(1)))
        assertFalse(invites.matches("$code "))
        assertFalse(invites.matches(code.uppercase()))
        assertFalse(invites.matches(""))
    }

    @Test
    fun `signup is off for a blank or too short code and then nothing matches`() {
        for (weak in listOf("", "short-code", "x".repeat(15))) {
            val invites = InviteCodes(weak)
            assertFalse(invites.signupEnabled)
            assertFalse(invites.matches(weak))
        }
        assertTrue(InviteCodes("x".repeat(16)).signupEnabled)
    }

    @Test
    fun `the code never shows in toString`() {
        assertFalse(InviteCodes(code).toString().contains(code))
        assertFalse(MultiUserProperties(signupCode = code, secretKey = "SECRET-MASTER-KEY").toString().let { it.contains(code) || it.contains("SECRET-MASTER-KEY") })
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Test
    fun `the limiter allows five attempts per window, then blocks until the window passed`() {
        val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
        val limiter = AttemptLimiter(5, Duration.ofMinutes(15), clock)
        repeat(5) { assertTrue(limiter.hit("1.2.3.4"), "attempt ${it + 1}") }
        assertFalse(limiter.hit("1.2.3.4"))
        assertTrue(limiter.hit("5.6.7.8"), "another address is not affected")
        clock.now = clock.now.plus(Duration.ofMinutes(16))
        assertTrue(limiter.hit("1.2.3.4"))
        assertEquals(1, limiter.count("1.2.3.4"))
    }

    @Test
    fun `login failures count per address and name, success clears them`() {
        val limiter = AttemptLimiter(3, Duration.ofMinutes(15))
        repeat(3) { limiter.record("1.1.1.1|wout") }
        assertTrue(limiter.blocked("1.1.1.1|wout"))
        assertFalse(limiter.blocked("2.2.2.2|wout"), "a stranger's address does not lock the owner out")
        limiter.clear("1.1.1.1|wout")
        assertFalse(limiter.blocked("1.1.1.1|wout"))
    }

    @Test
    fun `behind the proxy the client is the last forwarded entry, from anywhere else the peer`() {
        fun request(remote: String, forwarded: String?) = MockHttpServletRequest().apply {
            remoteAddr = remote
            forwarded?.let { addHeader("X-Forwarded-For", it) }
        }
        assertEquals("203.0.113.9", ClientIp.of(request("172.18.0.1", "6.6.6.6, 203.0.113.9")))
        assertEquals("203.0.113.9", ClientIp.of(request("127.0.0.1", "203.0.113.9")))
        assertEquals("100.64.0.5", ClientIp.of(request("100.64.0.5", null)))
        assertEquals("198.51.100.7", ClientIp.of(request("198.51.100.7", "1.2.3.4")), "a public peer cannot choose its own address")
    }
}
