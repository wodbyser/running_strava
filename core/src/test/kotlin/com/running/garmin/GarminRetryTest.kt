package com.running.garmin

import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.usecase.GarminRetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GarminRetryTest {
    private fun fail(c: GarminErrorCategory) = GarminApiException(c, null, "x")

    @Test
    fun `retries transient failures with 1 s then 4 s backoff and gives up after 3 calls`() {
        val waits = mutableListOf<Long>()
        var calls = 0
        val retry = GarminRetry(3, listOf(1000, 4000, 16000)) { waits += it }
        val e = assertThrows(GarminApiException::class.java) { retry.execute<Unit> { calls++; throw fail(GarminErrorCategory.SERVER) } }
        assertEquals(GarminErrorCategory.SERVER, e.category)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 4000L), waits)
    }

    @Test
    fun `succeeds after a transient failure`() {
        var calls = 0
        val result = GarminRetry(3, listOf(1, 1, 1)) {}.execute { if (calls++ == 0) throw fail(GarminErrorCategory.RATE_LIMIT) else "ok" }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `does not retry auth, client, parse or not configured`() {
        for (c in listOf(GarminErrorCategory.AUTH, GarminErrorCategory.CLIENT, GarminErrorCategory.PARSE, GarminErrorCategory.NOT_CONFIGURED)) {
            var calls = 0
            assertThrows(GarminApiException::class.java) { GarminRetry(3, listOf(1)) {}.execute<Unit> { calls++; throw fail(c) } }
            assertEquals(1, calls, "category $c")
        }
    }

    @Test
    fun `retries network and timeout`() {
        for (c in listOf(GarminErrorCategory.NETWORK, GarminErrorCategory.TIMEOUT)) {
            var calls = 0
            assertThrows(GarminApiException::class.java) { GarminRetry(3, listOf(1)) {}.execute<Unit> { calls++; throw fail(c) } }
            assertEquals(3, calls)
        }
    }
}
