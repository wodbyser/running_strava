package com.running.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One model the AI card may offer. [id] is what the provider expects; [label] is what the page shows. */
data class AiModelOption(val id: String, val label: String)

/**
 * The models the AI card may offer. The browser sends an id, so the server decides what is allowed: the
 * configured default and the models the provider lists with a price of zero. Anything else is refused,
 * because a model id without a free price would bill the account.
 */
interface AiModelCatalog {
    fun freeModels(): List<AiModelOption>

    fun isAllowed(id: String): Boolean
}

/**
 * Free models from an OpenAI-compatible `GET {baseUrl}/models`, cached for an hour: the catalogue is large
 * and changes slowly. A failed or unreadable response keeps the previous list and never breaks a page; the
 * configured default stays selectable either way.
 */
class FreeModelList(
    private val properties: AiProperties,
    private val fetch: () -> String?,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofHours(1),
) : AiModelCatalog {

    @Volatile
    private var cached: List<AiModelOption> = emptyList()

    @Volatile
    private var fetchedAt: Instant? = null

    override fun freeModels(): List<AiModelOption> {
        if (isFresh()) return cached
        synchronized(this) {
            if (isFresh()) return cached
            val body = fetch()
            // A failed fetch is retried no sooner than the next hour, so one outage cannot stall every page.
            if (body != null) cached = parse(body)
            fetchedAt = clock.instant()
            return cached
        }
    }

    override fun isAllowed(id: String): Boolean =
        id == properties.model || freeModels().any { it.id == id }

    private fun isFresh(): Boolean {
        val at = fetchedAt ?: return false
        return at.plus(ttl).isAfter(clock.instant())
    }

    private fun parse(body: String): List<AiModelOption> = try {
        mapper.readTree(body).path("data")
            .mapNotNull { node -> option(node) }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    } catch (e: Exception) {
        log.warn("AI model list could not be read: {}", e.javaClass.simpleName)
        emptyList()
    }

    private fun option(node: JsonNode): AiModelOption? {
        val id = node.path("id").asText("")
        if (!id.endsWith(FREE_SUFFIX) || id.endsWith(BATCH_SUFFIX)) return null
        if (!isZeroPrice(node.path("pricing"), "prompt")) return null
        if (!isZeroPrice(node.path("pricing"), "completion")) return null
        return AiModelOption(id, node.path("name").asText("").ifBlank { id })
    }

    /** Only an explicit zero counts: a missing price field (another provider) must not turn a paid model free. */
    private fun isZeroPrice(pricing: JsonNode, field: String): Boolean {
        val value = pricing.path(field).asText("").toBigDecimalOrNull() ?: return false
        return value.compareTo(BigDecimal.ZERO) == 0
    }

    private companion object {
        const val FREE_SUFFIX = ":free"
        const val BATCH_SUFFIX = ":batch"
        val log = LoggerFactory.getLogger(FreeModelList::class.java)
    }
}

/** `GET {baseUrl}/models` with short timeouts. One line in the log on failure, never the response body. */
class AiModelsClient(
    private val properties: AiProperties,
    private val restClient: RestClient,
) {
    fun fetch(): String? = try {
        restClient.get()
            .uri(URI.create(properties.baseUrl.trimEnd('/') + "/models"))
            .retrieve()
            .body(String::class.java)
    } catch (e: Exception) {
        log.warn("AI model list unavailable: {}", e.javaClass.simpleName)
        null
    }

    private companion object {
        val log = LoggerFactory.getLogger(AiModelsClient::class.java)
    }
}
