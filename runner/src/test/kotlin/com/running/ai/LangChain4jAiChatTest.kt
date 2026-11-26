package com.running.ai

import dev.langchain4j.exception.AuthenticationException
import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.InvalidRequestException
import dev.langchain4j.exception.ModelNotFoundException
import dev.langchain4j.exception.RateLimitException
import dev.langchain4j.exception.TimeoutException
import dev.langchain4j.model.chat.ChatModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.net.SocketTimeoutException

class LangChain4jAiChatTest {

    private val properties = AiProperties(apiKey = "test-key", model = "test/model:free")

    private fun adapter(model: ChatModel) = LangChain4jAiChat(model, properties)

    @Test
    fun `a model answer is returned trimmed with the configured model name`() {
        val model = mock(ChatModel::class.java)
        given(model.chat(anyString())).willReturn("  Een rustige week is beter.  ")
        val completion = adapter(model).complete("prompt")
        assertEquals("Een rustige week is beter.", completion.text)
        assertEquals("test/model:free", completion.model)
    }

    @Test
    fun `an empty model answer is an empty-answer failure`() {
        val model = mock(ChatModel::class.java)
        given(model.chat(anyString())).willReturn("   ")
        val error = assertThrows(AiFailureException::class.java) { adapter(model).complete("prompt") }
        assertEquals(AiFailure.EMPTY_ANSWER, error.failure)
    }

    @Test
    fun `a null model answer is an empty-answer failure instead of a crash`() {
        // Some providers return a completion without content; LangChain4j then hands back null.
        val model = mock(ChatModel::class.java)
        given(model.chat(anyString())).willReturn(null)
        val error = assertThrows(AiFailureException::class.java) { adapter(model).complete("prompt") }
        assertEquals(AiFailure.EMPTY_ANSWER, error.failure)
    }

    @Test
    fun `exceptions are mapped to user-facing failure kinds`() {
        assertEquals(AiFailure.PROVIDER_RATE_LIMIT, mapFailure(RateLimitException("429")))
        assertEquals(AiFailure.AUTH, mapFailure(AuthenticationException("401")))
        assertEquals(AiFailure.TIMEOUT, mapFailure(TimeoutException("slow")))
        assertEquals(AiFailure.MODEL_UNAVAILABLE, mapFailure(ModelNotFoundException("gone")))
        assertEquals(AiFailure.PROVIDER_ERROR, mapFailure(InternalServerException("500")))
        assertEquals(AiFailure.TOO_LARGE, mapFailure(InvalidRequestException("maximum context length exceeded")))
        assertEquals(AiFailure.MODEL_UNAVAILABLE, mapFailure(InvalidRequestException("unsupported parameter")))
        assertEquals(AiFailure.PROVIDER_RATE_LIMIT, mapFailure(HttpException(429, "too many")))
        assertEquals(AiFailure.AUTH, mapFailure(HttpException(403, "forbidden")))
        assertEquals(AiFailure.MODEL_UNAVAILABLE, mapFailure(HttpException(404, "unknown model")))
        assertEquals(AiFailure.TOO_LARGE, mapFailure(HttpException(413, "payload too large")))
        assertEquals(AiFailure.PROVIDER_ERROR, mapFailure(HttpException(500, "oops")))
        assertEquals(AiFailure.TIMEOUT, mapFailure(java.util.concurrent.TimeoutException("slow")))
        assertEquals(AiFailure.TIMEOUT, mapFailure(SocketTimeoutException("slow")))
        assertEquals(AiFailure.PROVIDER_ERROR, mapFailure(IllegalStateException("anything")))
    }

    @Test
    fun `the adapter maps a provider exception to the same failure kind`() {
        val model = mock(ChatModel::class.java)
        given(model.chat(anyString())).willThrow(RateLimitException("429"))
        val error = assertThrows(AiFailureException::class.java) { adapter(model).complete("prompt") }
        assertEquals(AiFailure.PROVIDER_RATE_LIMIT, error.failure)
    }
}
