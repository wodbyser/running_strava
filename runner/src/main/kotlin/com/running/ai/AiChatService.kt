package com.running.ai

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Guardrails around one provider call: a per-session rolling-hour limit, one in-flight call per session,
 * and a size cap. It never reads tenant databases and never stores a prompt or an answer.
 */
class AiChatService(
    private val chat: AiChat?,
    private val properties: AiProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val requests = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun complete(prompt: String, sessionId: String): AiCompletion {
        if (!properties.isConfigured || chat == null) throw failure(AiFailure.NOT_CONFIGURED)
        if (prompt.isBlank()) throw failure(AiFailure.EMPTY_PROMPT)
        if (prompt.length > properties.maxPromptChars) throw failure(AiFailure.TOO_LARGE)
        if (!inFlight.add(sessionId)) throw failure(AiFailure.IN_FLIGHT)

        try {
            recordRequest(sessionId)
            val completion = chat.complete(prompt)
            val text = completion.text.trim()
            if (text.isBlank()) throw failure(AiFailure.EMPTY_ANSWER)
            return completion.copy(text = text)
        } finally {
            inFlight.remove(sessionId)
        }
    }

    private fun recordRequest(sessionId: String) {
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
}
