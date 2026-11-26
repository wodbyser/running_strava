package com.running.ai

import dev.langchain4j.model.openai.OpenAiChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata

@Configuration
@EnableConfigurationProperties(AiProperties::class)
class AiConfig {

    @Bean
    fun aiChatService(chat: ObjectProvider<AiChat>, properties: AiProperties) =
        AiChatService(chat.ifAvailable, properties)

    /** Only a configured key creates an adapter: a blank AI_API_KEY leaves the whole feature off. */
    @Bean
    @Conditional(AiConfiguredCondition::class)
    fun aiChat(properties: AiProperties): AiChat {
        val model = OpenAiChatModel.builder()
            .baseUrl(properties.baseUrl)
            .apiKey(properties.apiKey)
            .modelName(properties.model)
            .temperature(properties.temperature)
            .maxTokens(properties.maxOutputTokens)
            .timeout(properties.timeout)
            .maxRetries(0)
            .logRequests(false)
            .logResponses(false)
            .build()
        return LangChain4jAiChat(model, properties)
    }
}

class AiConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        Binder.get(context.environment)
            .bind("app.ai", Bindable.of(AiProperties::class.java))
            .map { it.isConfigured }
            .orElse(false)
}
