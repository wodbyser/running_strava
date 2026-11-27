package com.running.ai

import dev.langchain4j.exception.AuthenticationException
import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.InvalidRequestException
import dev.langchain4j.exception.LangChain4jException
import dev.langchain4j.exception.ModelNotFoundException
import dev.langchain4j.exception.RateLimitException
import dev.langchain4j.exception.TimeoutException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter for every OpenAI-compatible endpoint (OpenRouter, Groq, Gemini's compatibility URL, Mistral,
 * Ollama/LM Studio). One client per model id is built once and kept, so switching models in the page costs
 * no extra wiring. The factory is injected so a test can replace it without a network call.
 */
class LangChain4jAiChat(
    private val modelFor: (String) -> ChatModel,
) : AiChat {

    override fun complete(prompt: String, model: String): AiCompletion {
        val raw: String? = try {
            modelFor(model).chat(prompt)
        } catch (e: Exception) {
            val failure = mapFailure(e)
            log.warn("AI call failed: {} ({})", failure, e.javaClass.simpleName)
            throw AiFailureException(failure, AiMessages.text(failure), e)
        }
        // Some providers return a completion with no content at all; LangChain4j then hands back null.
        val answer = raw?.trim().orEmpty()

        if (answer.isBlank()) {
            // Reasoning models can spend the whole output budget on reasoning and return no content.
            log.warn("AI call failed: {} ({})", AiFailure.EMPTY_ANSWER, "blank answer")
            throw AiFailureException(AiFailure.EMPTY_ANSWER, AiMessages.text(AiFailure.EMPTY_ANSWER))
        }
        return AiCompletion(answer, model)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LangChain4jAiChat::class.java)
    }
}

/** Builds and caches one LangChain4j client per model id, all with the same key, URL and limits. */
class AiChatModelFactory(private val properties: AiProperties) {

    private val models = ConcurrentHashMap<String, ChatModel>()

    fun forModel(model: String): ChatModel = models.computeIfAbsent(model) { name ->
        OpenAiChatModel.builder()
            .baseUrl(properties.baseUrl)
            .apiKey(properties.apiKey)
            .modelName(name)
            .temperature(properties.temperature)
            .maxTokens(properties.maxOutputTokens)
            .timeout(properties.timeout)
            .maxRetries(0)
            .logRequests(false)
            .logResponses(false)
            .build()
    }
}

/** Pure mapping so it can be unit-tested without a provider. Never reads the upstream response body into a message. */
fun mapFailure(error: Throwable): AiFailure = when (error) {
    is RateLimitException -> AiFailure.PROVIDER_RATE_LIMIT
    is AuthenticationException -> AiFailure.AUTH
    is TimeoutException -> AiFailure.TIMEOUT
    is ModelNotFoundException -> AiFailure.MODEL_UNAVAILABLE
    is InvalidRequestException ->
        if (looksTooLarge(error.message)) AiFailure.TOO_LARGE else AiFailure.MODEL_UNAVAILABLE
    is InternalServerException -> AiFailure.PROVIDER_ERROR
    is HttpException -> when (error.statusCode()) {
        401, 403 -> AiFailure.AUTH
        404 -> AiFailure.MODEL_UNAVAILABLE
        413 -> AiFailure.TOO_LARGE
        429 -> AiFailure.PROVIDER_RATE_LIMIT
        else -> AiFailure.PROVIDER_ERROR
    }
    is LangChain4jException -> AiFailure.PROVIDER_ERROR
    is java.util.concurrent.TimeoutException, is java.net.SocketTimeoutException -> AiFailure.TIMEOUT
    else -> AiFailure.PROVIDER_ERROR
}

private fun looksTooLarge(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return listOf("token", "length", "context", "too long", "maximum").any { it in text }
}
