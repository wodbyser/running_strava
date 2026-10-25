package com.running.garmin.usecase

import com.running.garmin.domain.GarminApiException

/**
 * Retries only network, timeout, 429 and 5xx failures. `maxAttempts` counts all calls, including the first.
 * `delaysMillis[i]` is the wait after failed attempt i + 1. Default: 3 calls in total, waits 1 s and 4 s (owner decision 2026-10-25).
 */
class GarminRetry(
    private val maxAttempts: Int = 3,
    private val delaysMillis: List<Long> = listOf(1_000L, 4_000L),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(delaysMillis.isNotEmpty()) { "delays must not be empty" }
    }

    fun <T> execute(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: GarminApiException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                sleeper(delaysMillis[minOf(attempt - 1, delaysMillis.size - 1)])
                attempt++
            }
        }
    }
}
