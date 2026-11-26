package com.running.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.ai")
data class AiProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val model: String = "particle-ai/particle-1:free",
    val providerName: String = "OpenRouter",
    val temperature: Double = 0.3,
    val maxOutputTokens: Int = 4000,
    val timeout: Duration = Duration.ofSeconds(90),
    val maxPromptChars: Int = 60000,
    val maxRequestsPerHour: Int = 10,
) {
    /** A blank key means the feature is off: no outbound call, no adapter bean, no button. */
    val isConfigured: Boolean get() = enabled && apiKey.isNotBlank()

    /** The key must never reach a log line. */
    override fun toString(): String =
        "AiProperties(enabled=$enabled, baseUrl=$baseUrl, model=$model, provider=$providerName, apiKeyPresent=${apiKey.isNotBlank()})"
}
