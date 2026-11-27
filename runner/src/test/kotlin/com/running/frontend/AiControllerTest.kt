package com.running.frontend

import com.running.ai.AiChat
import com.running.ai.AiCompletion
import com.running.ai.AiFailureException
import com.running.ai.AiFailure
import com.running.ai.AiModelCatalog
import com.running.ai.AiModelOption
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:aicontroller;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "garmin.api-key=",
        "app.ai.api-key=test-key",
        "app.ai.model=test/model:free",
        "app.ai.provider-name=TestProvider",
        "app.ai.max-prompt-chars=1000",
        "app.ai.max-requests-per-hour=2",
    ],
)
@AutoConfigureMockMvc
class AiControllerTest @Autowired constructor(private val mvc: MockMvc) {

    @MockitoBean
    lateinit var chat: AiChat

    @MockitoBean
    lateinit var catalog: AiModelCatalog

    private fun ask(
        prompt: String,
        session: MockHttpSession? = null,
        model: String? = null,
    ): org.springframework.test.web.servlet.ResultActions {
        val request = post("/ai/answer")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("prompt", prompt)
        if (model != null) request.param("model", model)
        if (session != null) request.session(session)
        return mvc.perform(request)
    }

    @Test
    fun `a successful call returns the answer, the model and the provider`() {
        given(chat.complete("Hallo", "test/model:free")).willReturn(AiCompletion("Een rustig antwoord.", "test/model:free"))

        ask("Hallo")
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.answer").value("Een rustig antwoord."))
            .andExpect(jsonPath("$.model").value("test/model:free"))
            .andExpect(jsonPath("$.provider").value("TestProvider"))
            .andExpect(jsonPath("$.error").doesNotExist())
    }

    @Test
    fun `a model from the free list is used for the call`() {
        given(catalog.isAllowed("free/other:free")).willReturn(true)
        given(chat.complete("Hallo", "free/other:free")).willReturn(AiCompletion("Antwoord.", "free/other:free"))

        ask("Hallo", model = "free/other:free")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.model").value("free/other:free"))
    }

    @Test
    fun `a model outside the free list is a 400 and never reaches the provider`() {
        ask("Hallo", model = "openai/gpt-expensive")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("gratis modellen")))
            .andExpect(jsonPath("$.answer").doesNotExist())

        Mockito.verifyNoInteractions(chat)
    }

    @Test
    fun `a blank prompt is a 400`() {
        ask("   ")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("De prompt is leeg."))
            .andExpect(jsonPath("$.answer").doesNotExist())
    }

    @Test
    fun `an oversized prompt is a 400`() {
        ask("x".repeat(1001))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("te groot")))
    }

    @Test
    fun `the local hour limit is a 429`() {
        given(chat.complete(anyString(), anyString())).willReturn(AiCompletion("ok", "test/model:free"))
        val session = MockHttpSession()
        ask("een", session).andExpect(status().isOk)
        ask("twee", session).andExpect(status().isOk)
        ask("drie", session)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value(containsString("maximum aantal AI-vragen per uur")))
    }

    @Test
    fun `a busy model does not count towards the local hour limit`() {
        given(chat.complete(anyString(), anyString()))
            .willThrow(AiFailureException(AiFailure.PROVIDER_RATE_LIMIT))
        val session = MockHttpSession()
        repeat(3) { ask("prompt", session).andExpect(status().isTooManyRequests) }
    }

    @Test
    fun `provider failures map to their own status`() {
        given(chat.complete(anyString(), anyString())).willThrow(AiFailureException(AiFailure.PROVIDER_RATE_LIMIT))
        ask("prompt").andExpect(status().isTooManyRequests)

        Mockito.reset(chat)
        given(chat.complete(anyString(), anyString())).willThrow(AiFailureException(AiFailure.AUTH))
        ask("prompt").andExpect(status().isBadGateway)

        Mockito.reset(chat)
        given(chat.complete(anyString(), anyString())).willThrow(AiFailureException(AiFailure.TIMEOUT))
        ask("prompt").andExpect(status().isGatewayTimeout)
    }

    @Test
    fun `the prompt page keeps its existing controls and gains the AI card with a model list`() {
        given(catalog.freeModels()).willReturn(
            listOf(
                AiModelOption("test/model:free", "Test model (free)"),
                AiModelOption("free/other:free", "Other model (free)"),
            ),
        )

        val html = mvc.perform(get("/ai")).andExpect(status().isOk).andReturn().response.contentAsString

        assertTrue(html.contains("id=\"aiPrompt\""))
        assertTrue(html.contains("id=\"copyPromptBtn\""))
        assertTrue(html.contains("/ai/download"))
        assertTrue(html.contains("id=\"askAiBtn\""))
        assertTrue(html.contains("id=\"aiAnswer\""))
        assertTrue(html.contains("AI-antwoord"))
        assertTrue(html.contains("id=\"aiModelSelect\""))
        assertTrue(html.contains("Test model (free)"))
        assertTrue(html.contains("Other model (free)"))
        assertTrue(html.contains("test/model:free"))
        assertTrue(html.contains("TestProvider"))
        assertFalse(html.contains("\${"))
    }
}

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ainotconfigured;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "garmin.api-key=",
        "app.ai.api-key=",
    ],
)
@AutoConfigureMockMvc
class AiControllerNotConfiguredTest @Autowired constructor(private val mvc: MockMvc) {

    @Test
    fun `without a key the endpoint answers 503 and never calls a provider`() {
        mvc.perform(
            post("/ai/answer")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("prompt", "Hallo"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("AI is niet ingesteld op deze installatie."))
    }

    @Test
    fun `the page keeps working and explains that AI is not configured`() {
        val html = mvc.perform(get("/ai")).andExpect(status().isOk).andReturn().response.contentAsString

        assertTrue(html.contains("id=\"aiPrompt\""))
        assertTrue(html.contains("id=\"copyPromptBtn\""))
        assertTrue(html.contains("/ai/download"))
        assertTrue(html.contains("AI is niet ingesteld"))
        assertFalse(html.contains("id=\"askAiBtn\""))
        assertFalse(html.contains("id=\"aiModelSelect\""))
        assertFalse(html.contains("\${"))
    }
}
