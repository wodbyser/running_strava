package com.running.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FreeModelListTest {

    private val properties = AiProperties(apiKey = "test-key", model = "default/model:free")
    private val mapper = ObjectMapper()

    private val catalogue = """
        {"data": [
          {"id": "zeta/model:free", "name": "Zeta: Free One", "pricing": {"prompt": "0", "completion": "0"}},
          {"id": "alpha/model:free", "name": "Alpha: Free Two", "pricing": {"prompt": "0", "completion": "0"}},
          {"id": "paid/model", "name": "Paid Model", "pricing": {"prompt": "0.000001", "completion": "0.000002"}},
          {"id": "odd/model:free", "name": "Odd Free", "pricing": {"prompt": "0.000001", "completion": "0"}},
          {"id": "no-price/model", "name": "No Price"},
          {"id": "batch/model:free:batch", "name": "Batch", "pricing": {"prompt": "0", "completion": "0"}}
        ]}
    """.trimIndent()

    @Test
    fun `only models with an explicit zero price and the free suffix are offered`() {
        val catalog = FreeModelList(properties, { catalogue }, mapper)
        val models = catalog.freeModels()

        assertEquals(listOf("alpha/model:free", "zeta/model:free"), models.map { it.id })
        assertEquals("Alpha: Free Two", models.first().label)
    }

    @Test
    fun `the configured default is always allowed and a paid model never is`() {
        val catalog = FreeModelList(properties, { catalogue }, mapper)
        assertTrue(catalog.isAllowed("default/model:free"))
        assertTrue(catalog.isAllowed("zeta/model:free"))
        assertFalse(catalog.isAllowed("paid/model"))
        assertFalse(catalog.isAllowed("no-price/model"))
    }

    @Test
    fun `the list is fetched once and reused within the hour`() {
        var calls = 0
        val clock = MutableClock(Instant.parse("2026-11-26T08:00:00Z"))
        val catalog = FreeModelList(properties, { calls++; catalogue }, mapper, clock)

        catalog.freeModels()
        catalog.freeModels()
        assertEquals(1, calls)

        clock.now = clock.now.plus(Duration.ofMinutes(61))
        catalog.freeModels()
        assertEquals(2, calls)
    }

    @Test
    fun `a failed fetch keeps the previous list`() {
        var body: String? = catalogue
        val clock = MutableClock(Instant.parse("2026-11-26T08:00:00Z"))
        val catalog = FreeModelList(properties, { body }, mapper, clock, ttl = Duration.ofMinutes(1))
        assertEquals(2, catalog.freeModels().size)

        body = null
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        assertEquals(2, catalog.freeModels().size)
    }

    @Test
    fun `an unreadable response is an empty list instead of a crash`() {
        val catalog = FreeModelList(properties, { "<html>not json</html>" }, mapper)
        assertTrue(catalog.freeModels().isEmpty())
        // The configured model stays allowed, so the page still works.
        assertTrue(catalog.isAllowed("default/model:free"))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
