package com.running.garmin

import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminApiClientImpl
import com.running.garmin.adapter.GarminRedactor
import com.running.garmin.adapter.GarminTokenProvider
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

const val FAKE_KEY = "test-key-not-a-real-secret-0001"
const val BASE = "https://garmin.test"
val NOW: Instant = Instant.parse("2026-10-25T10:00:00Z")

fun fixture(name: String): String =
    GarminTestSupport::class.java.getResource("/garmin/$name.json")!!.readText()

fun jwt(exp: Instant, tag: String = "a"): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = enc.encodeToString("""{"exp":${exp.epochSecond},"tag":"$tag"}""".toByteArray())
    return "$header.$payload.signature$tag"
}

class GarminTestSupport(
    key: String = FAKE_KEY,
    storeRaw: Boolean = true,
    var clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
) {
    val builder: RestClient.Builder = RestClient.builder().baseUrl(BASE)
    val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build()
    val properties = GarminProperties(apiKey = key, baseUrl = BASE, storeRawPayloads = storeRaw)
    val tokens = GarminTokenProvider(builder.build(), properties, clock)
    val client = GarminApiClientImpl(builder.build(), tokens, GarminRedactor { tokens.secrets() }, storeRaw)
}
