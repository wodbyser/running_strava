package com.running.frontend

import com.fasterxml.jackson.annotation.JsonInclude
import com.running.ai.AiChatService
import com.running.ai.AiFailure
import com.running.ai.AiFailureException
import com.running.ai.AiMessages
import com.running.ai.AiProperties
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiAnswerResponse(
    val answer: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val error: String? = null,
)

/**
 * One JSON endpoint for the in-app AI card. It only forwards the prompt that is already in the page; the model
 * and the base URL are never taken from the client. A JSON endpoint never falls through to the error page.
 */
@Controller
class AiController(
    private val service: AiChatService,
    private val properties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(AiController::class.java)

    @PostMapping(
        "/ai/answer",
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseBody
    fun answer(
        @RequestParam(name = "prompt", required = false) prompt: String?,
        session: HttpSession,
    ): ResponseEntity<AiAnswerResponse> = try {
        val completion = service.complete(prompt.orEmpty(), session.id)
        ResponseEntity.ok(
            AiAnswerResponse(answer = completion.text, model = completion.model, provider = properties.providerName),
        )
    } catch (e: AiFailureException) {
        ResponseEntity.status(status(e.failure)).body(AiAnswerResponse(error = AiMessages.text(e.failure)))
    } catch (e: Exception) {
        // No upstream message or body reaches the client or the log.
        log.warn("AI request failed: {}", e.javaClass.simpleName)
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(AiAnswerResponse(error = AiMessages.text(AiFailure.PROVIDER_ERROR)))
    }

    private fun status(failure: AiFailure): HttpStatus = when (failure) {
        AiFailure.EMPTY_PROMPT, AiFailure.TOO_LARGE -> HttpStatus.BAD_REQUEST
        AiFailure.NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE
        AiFailure.LOCAL_RATE_LIMIT, AiFailure.IN_FLIGHT, AiFailure.PROVIDER_RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS
        AiFailure.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        AiFailure.AUTH, AiFailure.MODEL_UNAVAILABLE, AiFailure.EMPTY_ANSWER, AiFailure.PROVIDER_ERROR ->
            HttpStatus.BAD_GATEWAY
    }
}
