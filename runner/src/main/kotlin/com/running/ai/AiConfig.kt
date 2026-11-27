package com.running.ai

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(AiProperties::class)
class AiConfig {

    @Bean
    fun aiChatService(
        chat: ObjectProvider<AiChat>,
        catalog: ObjectProvider<AiModelCatalog>,
        properties: AiProperties,
    ) = AiChatService(chat.ifAvailable, properties, catalog = catalog.ifAvailable)

    /** Only a configured key creates an adapter: a blank AI_API_KEY leaves the whole feature off. */
    @Bean
    @Conditional(AiConfiguredCondition::class)
    fun aiChat(factory: AiChatModelFactory): AiChat = LangChain4jAiChat(factory::forModel)

    @Bean
    @Conditional(AiConfiguredCondition::class)
    fun aiChatModelFactory(properties: AiProperties) = AiChatModelFactory(properties)

    /** The free models the page may offer. Short timeouts: a slow catalogue must not delay a page. */
    @Bean
    @Conditional(AiConfiguredCondition::class)
    fun aiModelCatalog(properties: AiProperties, mapper: ObjectMapper): AiModelCatalog {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3_000)
            setReadTimeout(8_000)
        }
        val client = AiModelsClient(properties, RestClient.builder().requestFactory(requestFactory).build())
        return FreeModelList(properties, { client.fetch() }, mapper)
    }
}

class AiConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        Binder.get(context.environment)
            .bind("app.ai", Bindable.of(AiProperties::class.java))
            .map { it.isConfigured }
            .orElse(false)
}
