package com.running.garmin.adapter

import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminErrorCategory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException

internal data class GarminHttpResponse(val status: Int, val body: String)

/** Sends one request. Network and timeout problems become `GarminApiException`. Never puts a body or header in a message. */
internal fun RestClient.RequestHeadersSpec<*>.fetch(label: String): GarminHttpResponse = try {
    exchange { _, response ->
        GarminHttpResponse(response.statusCode.value(), response.body.readAllBytes().toString(Charsets.UTF_8))
    } ?: throw GarminApiException(GarminErrorCategory.NETWORK, null, "No response for $label")
} catch (e: ResourceAccessException) {
    val timeout = generateSequence<Throwable>(e) { it.cause }.any { it is SocketTimeoutException || it is HttpTimeoutException }
    throw GarminApiException(
        if (timeout) GarminErrorCategory.TIMEOUT else GarminErrorCategory.NETWORK,
        null,
        "${if (timeout) "Timeout" else "Network error"} calling $label",
        e,
    )
}

internal fun statusToException(status: Int, label: String): GarminApiException = when {
    status == 401 -> GarminApiException(GarminErrorCategory.AUTH, status, "Garmin rejected the credentials for $label")
    status == 403 -> GarminApiException(GarminErrorCategory.FORBIDDEN, status, "Garmin refused access (HTTP 403) for $label")
    status == 429 -> GarminApiException(GarminErrorCategory.RATE_LIMIT, status, "Garmin rate limit (HTTP 429) for $label")
    status in 500..599 -> GarminApiException(GarminErrorCategory.SERVER, status, "Garmin server error (HTTP $status) for $label")
    else -> GarminApiException(GarminErrorCategory.CLIENT, status, "Garmin returned HTTP $status for $label")
}
