package com.running.garmin.adapter

/** Removes the key and any token from text before it is stored or logged. */
class GarminRedactor(private val secrets: () -> List<String>) {
    fun redact(text: String): String =
        secrets().filter { it.length >= MIN_SECRET_LENGTH }.fold(text) { acc, secret -> acc.replace(secret, "[REDACTED]") }

    private companion object {
        const val MIN_SECRET_LENGTH = 8
    }
}
