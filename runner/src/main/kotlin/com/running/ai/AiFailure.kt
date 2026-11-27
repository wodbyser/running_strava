package com.running.ai

/**
 * User-facing failure kinds. The Dutch text lives in [AiMessages], so the same failure always reads the
 * same way, no matter which provider adapter reported it.
 */
enum class AiFailure {
    NOT_CONFIGURED,
    EMPTY_PROMPT,
    TOO_LARGE,
    MODEL_NOT_FREE,
    LOCAL_RATE_LIMIT,
    IN_FLIGHT,
    PROVIDER_RATE_LIMIT,
    EMPTY_ANSWER,
    AUTH,
    TIMEOUT,
    MODEL_UNAVAILABLE,
    PROVIDER_ERROR,
}

class AiFailureException(
    val failure: AiFailure,
    message: String = "",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
