package com.running.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiChatServiceTest {

    private val properties = AiProperties(apiKey = "test-key", maxPromptChars = 100, maxRequestsPerHour = 3)

    private class FakeChat(private val answer: String = "antwoord") : AiChat {
        val used = mutableListOf<String>()

        override fun complete(prompt: String, model: String): AiCompletion {
            used += model
            return AiCompletion(answer, model)
        }
    }

    private class FakeCatalog(private val free: List<String>) : AiModelCatalog {
        override fun freeModels() = free.map { AiModelOption(it, it) }
        override fun isAllowed(id: String) = id in free
    }

    private fun service(
        chat: AiChat?,
        props: AiProperties = properties,
        clock: Clock = Clock.systemUTC(),
        catalog: AiModelCatalog? = null,
    ) = AiChatService(chat, props, clock, catalog)

    @Test
    fun `a successful call returns the text and the model`() {
        val completion = service(FakeChat("  Goed getraind.  ")).complete("prompt", "session-1")
        assertEquals("Goed getraind.", completion.text)
        assertEquals(properties.model, completion.model)
    }

    @Test
    fun `the chosen model is the one that is called`() {
        val chat = FakeChat()
        val svc = service(chat, catalog = FakeCatalog(listOf("b/model:free")))
        svc.complete("prompt", "session-1", "b/model:free")
        assertEquals(listOf("b/model:free"), chat.used)
    }

    @Test
    fun `a model outside the free list is refused before any provider call`() {
        val chat = FakeChat()
        val svc = service(chat, catalog = FakeCatalog(listOf("b/model:free")))
        val error = assertThrows(AiFailureException::class.java) {
            svc.complete("prompt", "session-1", "openai/gpt-expensive")
        }
        assertEquals(AiFailure.MODEL_NOT_FREE, error.failure)
        assertTrue(chat.used.isEmpty())
    }

    @Test
    fun `the configured default is allowed even when the catalogue is empty`() {
        val chat = FakeChat()
        service(chat, catalog = FakeCatalog(emptyList())).complete("prompt", "session-1", properties.model)
        assertEquals(listOf(properties.model), chat.used)
    }

    @Test
    fun `a busy model does not use up the hourly budget`() {
        val busy = object : AiChat {
            override fun complete(prompt: String, model: String): AiCompletion =
                throw AiFailureException(AiFailure.PROVIDER_RATE_LIMIT)
        }
        val svc = service(busy, properties.copy(maxRequestsPerHour = 1), catalog = FakeCatalog(listOf("a:free")))

        repeat(3) {
            val error = assertThrows(AiFailureException::class.java) { svc.complete("prompt", "session-1", "a:free") }
            assertEquals(AiFailure.PROVIDER_RATE_LIMIT, error.failure)
        }
    }

    @Test
    fun `a blank prompt is rejected before any provider call`() {
        val chat = FakeChat()
        val error = assertThrows(AiFailureException::class.java) { service(chat).complete("   ", "session-1") }
        assertEquals(AiFailure.EMPTY_PROMPT, error.failure)
        assertTrue(chat.used.isEmpty())
    }

    @Test
    fun `an oversized prompt is rejected before any provider call`() {
        val chat = FakeChat()
        val error = assertThrows(AiFailureException::class.java) {
            service(chat).complete("x".repeat(101), "session-1")
        }
        assertEquals(AiFailure.TOO_LARGE, error.failure)
        assertTrue(chat.used.isEmpty())
    }

    @Test
    fun `the per-session hourly limit is enforced`() {
        val chat = FakeChat()
        val svc = service(chat)
        repeat(3) { svc.complete("prompt", "session-1") }
        val error = assertThrows(AiFailureException::class.java) { svc.complete("prompt", "session-1") }
        assertEquals(AiFailure.LOCAL_RATE_LIMIT, error.failure)
        assertEquals(3, chat.used.size)

        // Another session has its own budget.
        svc.complete("prompt", "session-2")
        assertEquals(4, chat.used.size)
    }

    @Test
    fun `the hour limit rolls forward`() {
        val chat = FakeChat()
        val clock = MutableClock(Instant.parse("2026-11-24T09:00:00Z"))
        val svc = service(chat, properties.copy(maxRequestsPerHour = 1), clock)
        svc.complete("prompt", "session-1")
        assertThrows(AiFailureException::class.java) { svc.complete("prompt", "session-1") }

        clock.now = clock.now.plusSeconds(3601)
        svc.complete("prompt", "session-1")
        assertEquals(2, chat.used.size)
    }

    @Test
    fun `a second concurrent call is rejected while the first is running`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : AiChat {
            override fun complete(prompt: String, model: String): AiCompletion {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return AiCompletion("klaar", model)
            }
        }
        val svc = service(slow)
        val first = Thread { svc.complete("prompt", "session-1") }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val error = assertThrows(AiFailureException::class.java) { svc.complete("prompt", "session-1") }
        assertEquals(AiFailure.IN_FLIGHT, error.failure)

        release.countDown()
        first.join(5000)
    }

    @Test
    fun `a blank answer from the provider is a failure`() {
        val error = assertThrows(AiFailureException::class.java) {
            service(FakeChat("   ")).complete("prompt", "session-1")
        }
        assertEquals(AiFailure.EMPTY_ANSWER, error.failure)
    }

    @Test
    fun `without a configured key the service refuses before any call`() {
        val chat = FakeChat()
        val error = assertThrows(AiFailureException::class.java) {
            service(chat, properties.copy(apiKey = "")).complete("prompt", "session-1")
        }
        assertEquals(AiFailure.NOT_CONFIGURED, error.failure)
        assertTrue(chat.used.isEmpty())
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
