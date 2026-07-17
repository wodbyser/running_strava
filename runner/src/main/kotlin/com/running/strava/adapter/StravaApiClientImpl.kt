package com.running.strava.adapter

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.running.config.StravaProperties
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.StravaApiClient
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Component
class StravaApiClientImpl(
    private val restClient: RestClient,
    private val properties: StravaProperties,
) : StravaApiClient {

    private val baseUrl = "https://www.strava.com/api/v3"
    private val tokenUrl = "https://www.strava.com/oauth/token"

    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun exchangeToken(code: String): StravaToken {
        val response = restClient.post()
            .uri(tokenUrl)
            .body(mapOf(
                "client_id" to properties.clientId,
                "client_secret" to properties.clientSecret,
                "code" to code,
                "grant_type" to "authorization_code",
            ))
            .retrieve()
            .toEntity(Map::class.java)

        val body = response.body ?: throw RuntimeException("Empty token response")
        return StravaToken(
            accessToken = body["access_token"] as String,
            refreshToken = body["refresh_token"] as String,
            expiresAt = (body["expires_at"] as Number).toLong(),
            athleteId = ((body["athlete"] as Map<*, *>)["id"] as Number).toLong(),
        )
    }

    override fun refreshToken(refreshToken: String): StravaToken {
        val response = restClient.post()
            .uri(tokenUrl)
            .body(mapOf(
                "client_id" to properties.clientId,
                "client_secret" to properties.clientSecret,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ))
            .retrieve()
            .toEntity(Map::class.java)

        val body = response.body ?: throw RuntimeException("Empty token refresh response")
        return StravaToken(
            accessToken = body["access_token"] as String,
            refreshToken = body["refresh_token"] as String,
            expiresAt = (body["expires_at"] as Number).toLong(),
            athleteId = 0,
        )
    }

    override fun getAthleteActivities(
        token: StravaToken,
        page: Int,
        perPage: Int,
        after: Long?,
    ): List<Activity> {
        val uri = buildUri("$baseUrl/athlete/activities") {
            addQueryParam("page", page.toString())
            addQueryParam("per_page", perPage.toString())
            after?.let { addQueryParam("after", it.toString()) }
        }

        val json = try {
            restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer ${token.accessToken}")
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 429) throw RateLimitExceededException("Strava API rate limit on page $page: ${e.message}")
            throw e
        }

        val rawList = mapper.readValue(json, List::class.java) as List<Map<String, Any?>>
        return rawList.map { parseActivity(it) }
    }

    override fun getActivity(token: StravaToken, activityId: Long): Activity {
        val json = try {
            restClient.get()
                .uri("$baseUrl/activities/$activityId")
                .header("Authorization", "Bearer ${token.accessToken}")
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 429) throw RateLimitExceededException("Strava API rate limit on activity $activityId: ${e.message}")
            throw e
        }

        val raw = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return parseActivity(raw)
    }

    override fun getActivityStreams(token: StravaToken, activityId: Long): ActivityStream {
        val uri = buildUri("$baseUrl/activities/$activityId/streams") {
            addQueryParam("keys", "time,distance,latlng,altitude,heartrate,cadence,velocity_smooth,grade_smooth,temp")
            addQueryParam("key_by_type", "true")
        }

        val json = try {
            restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer ${token.accessToken}")
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 429) throw RateLimitExceededException("Strava API rate limit on streams for activity $activityId: ${e.message}")
            throw e
        }

        val raw = mapper.readValue(json, Map::class.java) as Map<String, Any?>

        fun extractList(key: String): List<Int>? {
            val entry = raw[key] as? Map<*, *> ?: return null
            return (entry["data"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
        }

        @Suppress("UNCHECKED_CAST")
        fun extractFloatList(key: String): List<Float>? {
            val entry = raw[key] as? Map<*, *> ?: return null
            return (entry["data"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
        }

        @Suppress("UNCHECKED_CAST")
        fun extractLatLngList(key: String): List<List<Double>>? {
            val entry = raw[key] as? Map<*, *> ?: return null
            return (entry["data"] as? List<*>)?.mapNotNull { sub ->
                (sub as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
            }
        }

        return ActivityStream(
            time = extractList("time"),
            distance = extractFloatList("distance"),
            latlng = extractLatLngList("latlng"),
            altitude = extractFloatList("altitude"),
            heartrate = extractList("heartrate"),
            cadence = extractList("cadence"),
            velocitySmooth = extractFloatList("velocity_smooth"),
            gradeSmooth = extractFloatList("grade_smooth"),
            temp = extractList("temp"),
        )
    }

    private fun parseActivity(raw: Map<String, Any?>): Activity = Activity(
        id = (raw["id"] as Number).toLong(),
        name = raw["name"] as? String ?: "",
        distance = (raw["distance"] as? Number)?.toFloat() ?: 0f,
        movingTime = (raw["moving_time"] as? Number)?.toInt() ?: 0,
        elapsedTime = (raw["elapsed_time"] as? Number)?.toInt() ?: 0,
        totalElevationGain = (raw["total_elevation_gain"] as? Number)?.toFloat() ?: 0f,
        type = raw["type"] as? String ?: "",
        sportType = raw["sport_type"] as? String,
        startDate = parseStravaDate(raw["start_date"] as? String),
        timezone = raw["timezone"] as? String ?: "",
        averageSpeed = (raw["average_speed"] as? Number)?.toFloat() ?: 0f,
        maxSpeed = (raw["max_speed"] as? Number)?.toFloat() ?: 0f,
        averageHeartrate = (raw["average_heartrate"] as? Number)?.toFloat(),
        maxHeartrate = (raw["max_heartrate"] as? Number)?.toFloat(),
        averageCadence = (raw["average_cadence"] as? Number)?.toFloat(),
        averageWatts = (raw["average_watts"] as? Number)?.toFloat(),
        maxWatts = (raw["max_watts"] as? Number)?.toFloat(),
        weightedAverageWatts = (raw["weighted_average_watts"] as? Number)?.toInt(),
        kilojoules = (raw["kilojoules"] as? Number)?.toFloat(),
        deviceWatts = raw["device_watts"] as? Boolean,
        description = raw["description"] as? String,
        calories = (raw["calories"] as? Number)?.toFloat(),
        sufferScore = (raw["suffer_score"] as? Number)?.toInt(),
        hasHeartrate = raw["has_heartrate"] as? Boolean ?: false,
        elevHigh = (raw["elev_high"] as? Number)?.toFloat(),
        elevLow = (raw["elev_low"] as? Number)?.toFloat(),
        gearId = raw["gear_id"] as? String,
        startLatlng = parseLatLng(raw["start_latlng"]),
        endLatlng = parseLatLng(raw["end_latlng"]),
        isTrainer = raw["trainer"] as? Boolean ?: false,
        isCommute = raw["commute"] as? Boolean ?: false,
        isManual = raw["manual"] as? Boolean ?: false,
        isFlagged = raw["flagged"] as? Boolean ?: false,
        workoutType = (raw["workout_type"] as? Number)?.toInt(),
        originalStartDate = raw["start_date"]?.let { parseStravaDate(it as? String) },
        laps = null,
        splits = null,
        bestEfforts = null,
    )

    private fun parseStravaDate(dateStr: String?): ZonedDateTime {
        if (dateStr == null) return ZonedDateTime.now()
        return ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLatLng(raw: Any?): List<Double>? {
        return (raw as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
    }

    private fun buildUri(base: String, block: UriBuilder.() -> Unit): String {
        val builder = UriBuilder(base)
        builder.block()
        return builder.build()
    }

    private class UriBuilder(private val base: String) {
        private val params = mutableListOf<String>()

        fun addQueryParam(key: String, value: String) {
            params.add("$key=$value")
        }

        fun build(): String = if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }
}
