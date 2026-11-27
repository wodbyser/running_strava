package com.running.ai

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Guardrails around one provider call: a per-session rolling-hour limit, one in-flight call per session,
 * a size cap and a model allowlist. It never reads tenant databases and never stores a prompt or an answer.
 */
class AiChatService(
    private val chat: AiChat?,
    private val properties: AiProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val catalog: AiModelCatalog? = null,
) {
    private val requests = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun complete(prompt: String, sessionId: String, model: String? = null): AiCompletion {
        if (!properties.isConfigured || chat == null) throw failure(AiFailure.NOT_CONFIGURED)
        if (prompt.isBlank()) throw failure(AiFailure.EMPTY_PROMPT)
        if (prompt.length > properties.maxPromptChars) throw failure(AiFailure.TOO_LARGE)
        val chosen = model?.trim().orEmpty().ifBlank { properties.model }
        if (!isAllowed(chosen)) throw failure(AiFailure.MODEL_NOT_FREE)
        if (!inFlight.add(sessionId)) throw failure(AiFailure.IN_FLIGHT)

        var stamp: Instant? = null
        try {
            stamp = recordRequest(sessionId)
            val completion = chat.complete(prompt, chosen)
            val text = completion.text.trim()
            if (text.isBlank()) throw failure(AiFailure.EMPTY_ANSWER)
            return completion.copy(text = text)
        } catch (e: AiFailureException) {
            // A model that never answered must not use up the hourly budget: the page invites the user to pick
            // another free model and try again, which would otherwise be blocked after a few attempts.
            if (e.failure in RETRY_WITH_ANOTHER_MODEL) stamp?.let { refund(sessionId, it) }
            throw e
        } finally {
            inFlight.remove(sessionId)
        }
    }

    /** The configured default is always allowed; other models must be on the provider's free list. */
    private fun isAllowed(model: String): Boolean =
        model == properties.model || catalog?.isAllowed(model) == true

    private fun recordRequest(sessionId: String): Instant {
        val now = clock.instant()
        val cutoff = now.minus(Duration.ofHours(1))
        val queue = requests.computeIfAbsent(sessionId) { ArrayDeque() }
        val allowed = synchronized(queue) {
            while (queue.isNotEmpty() && queue.first().isBefore(cutoff)) queue.removeFirst()
            if (queue.size >= properties.maxRequestsPerHour) {
                false
            } else {
                queue.addLast(now)
                true
            }
        }
        if (!allowed) throw failure(AiFailure.LOCAL_RATE_LIMIT)
        cleanup(now)
        return now
    }

    private fun refund(sessionId: String, stamp: Instant) {
        val queue = requests[sessionId] ?: return
        // One call per session is in flight, so the stamp this call added is still the newest entry.
        synchronized(queue) { if (queue.lastOrNull() == stamp) queue.removeLast() }
    }

    /** A long-lived server should not keep one deque per session that ever asked a question. */
    private fun cleanup(now: Instant) {
        if (requests.size < 64) return
        val cutoff = now.minus(Duration.ofHours(1))
        requests.entries.removeIf { (_, queue) ->
            synchronized(queue) { queue.isEmpty() || queue.last().isBefore(cutoff) }
        }
    }

    private fun failure(failure: AiFailure) = AiFailureException(failure, AiMessages.text(failure))

    private companion object {
        val RETRY_WITH_ANOTHER_MODEL = setOf(AiFailure.PROVIDER_RATE_LIMIT, AiFailure.MODEL_UNAVAILABLE)
    }
}
