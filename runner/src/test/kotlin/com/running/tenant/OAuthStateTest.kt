package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

class OAuthStateTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val state = OAuthState(key, clock, Duration.ofMinutes(10))

    @Test
    fun `a fresh state is valid exactly once`() {
        val s = state.create("anna", "session-1")
        assertEquals(StateCheck.VALID, state.verify(s, "anna", "session-1"))
        assertEquals(StateCheck.REPLAYED, state.verify(s, "anna", "session-1"))
    }

    @Test
    fun `another tenant's state or another session is rejected`() {
        val s = state.create("anna", "session-1")
        assertEquals(StateCheck.INVALID, state.verify(s, "piet", "session-1"))
        assertEquals(StateCheck.INVALID, state.verify(s, "anna", "session-2"))
        assertEquals(StateCheck.VALID, state.verify(s, "anna", "session-1"), "the rejected tries did not burn it")
    }

    @Test
    fun `an old state is rejected`() {
        val s = state.create("anna", "session-1")
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        assertEquals(StateCheck.EXPIRED, state.verify(s, "anna", "session-1"))
    }

    @Test
    fun `tampered, foreign-key and garbage states are invalid`() {
        val s = state.create("anna", "session-1")
        val (payload, signature) = s.split('.')
        assertEquals(StateCheck.INVALID, state.verify("$payload.${signature.reversed()}", "anna", "session-1"))
        assertEquals(StateCheck.INVALID, state.verify(payload, "anna", "session-1"))
        assertEquals(StateCheck.INVALID, state.verify(null, "anna", "session-1"))
        assertEquals(StateCheck.INVALID, state.verify("not a state", "anna", "session-1"))
        val other = OAuthState(Base64.getEncoder().encodeToString(ByteArray(32) { 9 }), clock)
        assertEquals(StateCheck.INVALID, other.verify(s, "anna", "session-1"))
    }

    @Test
    fun `the session id itself is not in the state`() {
        assertFalse(String(Base64.getUrlDecoder().decode(state.create("anna", "SESSION-SECRET-ID").substringBefore('.'))).contains("SESSION-SECRET-ID"))
    }
}
