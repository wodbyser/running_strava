package com.running.garmin.adapter

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.running.garmin.domain.EP_ACTIVITY_SEARCH
import com.running.garmin.domain.EP_DAILY_SUMMARIES
import com.running.garmin.domain.EP_HEART_RATE_ZONES
import com.running.garmin.domain.EP_HRV_SUMMARIES
import com.running.garmin.domain.EP_SLEEP_SUMMARIES
import com.running.garmin.domain.GarminActivityRef
import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminBodyBatteryDay
import com.running.garmin.domain.GarminDailySummary
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminRestingHrDay
import com.running.garmin.domain.GarminSleepDay
import com.running.garmin.domain.GarminSource
import com.running.garmin.domain.GarminStepsDay
import com.running.garmin.domain.GarminStressDay
import com.running.garmin.spi.GarminApiClient
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Spring `RestClient` adapter. Logs request paths and record counts only.
 * "No data" (204, empty body, `[]`, `{"data":[]}`) gives an empty list.
 * On HTTP 401 the key is exchanged again once and the call repeated once; a second 401 is an AUTH failure.
 */
class GarminApiClientImpl(
    private val restClient: RestClient,
    private val tokens: GarminTokenProvider,
    private val redactor: GarminRedactor,
    private val storeRawPayloads: Boolean,
    private val mapper: ObjectMapper = ObjectMapper(),
) : GarminApiClient {

    private val log = LoggerFactory.getLogger(GarminApiClientImpl::class.java)

    override fun getDailySummaries(start: LocalDate, end: LocalDate): List<GarminDailySummary> {
        val root = get("/usersummary-service/v2/dailysummaries", mapOf("startDate" to start, "endDate" to end)) ?: return emptyList()
        return elements(root.path("data"), EP_DAILY_SUMMARIES).mapNotNull { mapSummary(it) }.also { count(EP_DAILY_SUMMARIES, it.size) }
    }

    override fun getSleepSummaries(start: LocalDate, end: LocalDate): List<GarminSleepDay> {
        val root = get("/sleep-service/sleep/dailySleepSummariesByDate", mapOf("startDate" to start, "endDate" to end)) ?: return emptyList()
        return elements(root, EP_SLEEP_SUMMARIES).mapNotNull { mapSleep(it) }.also { count(EP_SLEEP_SUMMARIES, it.size) }
    }

    override fun getHrvSummaries(start: LocalDate, end: LocalDate): List<GarminHrvDay> {
        val root = get("/hrv-service/hrv/daily/$start/$end") ?: return emptyList()
        return elements(root.path("hrvSummaries"), EP_HRV_SUMMARIES).mapNotNull { mapHrv(it) }.also { count(EP_HRV_SUMMARIES, it.size) }
    }

    override fun searchActivities(offset: Int, limit: Int, startDate: LocalDate?, endDate: LocalDate?): List<GarminActivityRef> {
        val query = linkedMapOf<String, Any?>("start" to offset, "limit" to limit, "startDate" to startDate, "endDate" to endDate)
        val root = get("/activitylist-service/activities/search/activities", query) ?: return emptyList()
        return elements(root, EP_ACTIVITY_SEARCH).mapNotNull { mapActivity(it) }.also { count(EP_ACTIVITY_SEARCH, it.size) }
    }

    override fun getHeartRateZones(): List<GarminHeartRateZones> {
        val root = get("/biometric-service/heartRateZones/v2") ?: return emptyList()
        return elements(root, EP_HEART_RATE_ZONES).mapNotNull { mapZones(it) }.also { count(EP_HEART_RATE_ZONES, it.size) }
    }

    // --- HTTP ---

    private fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonNode? {
        log.debug("Garmin GET {}", path)
        var response = send(path, query)
        if (response.status == 401) {
            tokens.invalidate()
            response = send(path, query)
            if (response.status == 401) throw statusToException(401, path)
        }
        if (response.status !in 200..299) throw statusToException(response.status, path)
        if (response.status == 204 || response.body.isBlank()) return null
        return try {
            mapper.readTree(response.body)
        } catch (e: JsonProcessingException) {
            throw GarminApiException(GarminErrorCategory.PARSE, response.status, "Invalid JSON from $path")
        }
    }

    private fun send(path: String, query: Map<String, Any?>): GarminHttpResponse {
        val token = tokens.accessToken()
        return restClient.get()
            .uri { b ->
                b.path(path)
                query.forEach { (k, v) -> if (v != null) b.queryParam(k, v.toString()) }
                b.build()
            }
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .fetch(path)
    }

    private fun count(endpoint: String, n: Int) = log.debug("Garmin {} returned {} record(s)", endpoint, n)

    private fun elements(node: JsonNode, endpoint: String): List<JsonNode> = when {
        node.isMissingNode || node.isNull -> emptyList()
        node.isArray -> node.toList()
        else -> throw GarminApiException(GarminErrorCategory.PARSE, null, "Unexpected response shape from $endpoint")
    }

    // --- mapping ---

    private fun raw(node: JsonNode, drop: Set<String> = emptySet()): String? {
        if (!storeRawPayloads) return null
        val copy = node.deepCopy<JsonNode>()
        if (copy is ObjectNode) copy.remove(drop)
        return redactor.redact(copy.toString())
    }

    private fun source(endpoint: String, units: String, node: JsonNode, drop: Set<String> = emptySet()) =
        GarminSource(endpoint, null, units, raw(node, drop))

    private fun JsonNode.int(name: String): Int? = path(name).takeIf { it.isNumber }?.asInt()
    private fun JsonNode.long(name: String): Long? = path(name).takeIf { it.isNumber }?.asLong()
    private fun JsonNode.dbl(name: String): Double? = path(name).takeIf { it.isNumber }?.asDouble()
    private fun JsonNode.str(name: String): String? = path(name).takeIf { it.isTextual }?.asText()
    private fun date(node: JsonNode, name: String): LocalDate? =
        node.str(name)?.let { try { LocalDate.parse(it) } catch (e: DateTimeParseException) { null } }

    private fun mapSummary(n: JsonNode): GarminDailySummary? {
        val d = date(n, "calendarDate") ?: return null
        val units = "heart rate bpm"
        val hr = n.path("heartRate")
        val bb = n.path("bodyBattery")
        val st = n.path("stress")
        val steps = n.path("movement").path("steps")

        val rhr = GarminRestingHrDay(d, hr.int("restingValue"), hr.int("minValue"), hr.int("maxValue"), source(EP_DAILY_SUMMARIES, units, n))
            .takeIf { it.nullFieldCount() < 3 }
        val battery = GarminBodyBatteryDay(
            d, bb.int("minValue"), bb.int("maxValue"), bb.int("chargedValue"), bb.int("drainedValue"), bb.int("latestValue"),
            source(EP_DAILY_SUMMARIES, "Garmin Body Battery index, scale 5 to 100 (Garmin estimate)", n),
        ).takeIf { it.nullFieldCount() < 5 }
        val stress = GarminStressDay(
            d, st.int("avgLevel"), st.int("maxLevel"), st.long("restDurationInMillis"), st.long("lowStressDurationInMillis"),
            st.long("mediumStressDurationInMillis"), st.long("highStressDurationInMillis"), st.long("activityDurationInMillis"),
            st.long("uncategorizedDurationInMillis"), st.long("totalDurationInMillis"),
            source(EP_DAILY_SUMMARIES, "Garmin stress level 0 to 100 (Garmin estimate), durations in milliseconds", n),
        ).takeIf { it.nullFieldCount() < 9 }
        val stepDay = GarminStepsDay(d, steps.int("value"), steps.dbl("distanceInMeters"), steps.int("goal"), source(EP_DAILY_SUMMARIES, "steps count, distance metres", n))
            .takeIf { it.nullFieldCount() < 3 }
        return GarminDailySummary(d, rhr, battery, stress, stepDay)
    }

    private fun mapSleep(n: JsonNode): GarminSleepDay? {
        val d = date(n, "calendarDate") ?: return null
        val s = GarminSleepDay(
            d, n.int("sleepTimeSeconds"), n.int("deepSleepSeconds"), n.int("lightSleepSeconds"), n.int("remSleepSeconds"),
            n.int("awakeSleepSeconds"), n.int("unmeasurableSleepSeconds"), n.int("napTimeSeconds"),
            n.long("sleepStartTimestampGMT"), n.long("sleepEndTimestampGMT"),
            n.dbl("averageRespirationValue"), n.dbl("avgSleepStress"), n.dbl("avgHeartRate"),
            n.path("sleepScores").path("overall").int("value"), n.path("sleepScores").path("overall").str("qualifierKey"),
            n.path("sleepNeed").int("baseline"), n.path("sleepNeed").int("actual"),
            source(EP_SLEEP_SUMMARIES, "seconds; timestamps epoch ms UTC; Garmin sleep stages and scores are estimates; sleep need unit UNVERIFIED", n),
        )
        // Empty nights come back as an object of nulls: not data.
        val hasData = listOf(s.sleepTimeSeconds, s.deepSleepSeconds, s.lightSleepSeconds, s.remSleepSeconds, s.awakeSleepSeconds, s.sleepStartGmtMs).any { it != null }
        return s.takeIf { hasData }
    }

    private fun mapHrv(n: JsonNode): GarminHrvDay? {
        val d = date(n, "calendarDate") ?: return null
        val b = n.path("baseline")
        val h = GarminHrvDay(
            d, n.int("weeklyAvg"), n.int("lastNightAvg"), n.int("lastNight5MinHigh"),
            b.int("lowUpper"), b.int("balancedLow"), b.int("balancedUpper"), b.dbl("markerValue"),
            n.str("status"), n.str("feedbackPhrase"),
            source(EP_HRV_SUMMARIES, "HRV (Garmin, nachtgemiddelde), ms UNVERIFIED, measure not stated by Garmin", n),
        )
        return h.takeIf { it.nullFieldCount() < 9 }
    }

    private fun mapActivity(n: JsonNode): GarminActivityRef? {
        val id = n.long("activityId") ?: return null
        val start = n.str("startTimeGMT")?.let {
            try { LocalDateTime.parse(it, GMT_FORMAT).toInstant(ZoneOffset.UTC) } catch (e: DateTimeParseException) { null }
        } ?: return null
        val type = n.path("activityType")
        return GarminActivityRef(
            id, start, type.str("typeKey"), type.int("parentTypeId"),
            n.dbl("distance"), n.dbl("duration"), n.dbl("elapsedDuration"), n.dbl("movingDuration"),
            n.dbl("averageHR"), n.dbl("maxHR"), n.dbl("aerobicTrainingEffect"), n.dbl("anaerobicTrainingEffect"),
            n.dbl("activityTrainingLoad"), n.str("trainingEffectLabel"), n.str("manufacturer"),
            n.path("isManualActivity").asBoolean(false), n.dbl("startLatitude"), n.dbl("startLongitude"),
            source(EP_ACTIVITY_SEARCH, "metres, seconds, bpm; training effect and load are Garmin estimates, unit UNVERIFIED", n, PROFILE_IMAGE_FIELDS),
        )
    }

    private fun mapZones(n: JsonNode): GarminHeartRateZones? {
        val sport = n.str("sport") ?: return null
        return GarminHeartRateZones(
            sport, n.str("trainingMethod"), n.int("restingHeartRateUsed"), n.int("lactateThresholdHeartRateUsed"),
            n.int("zone1Floor"), n.int("zone2Floor"), n.int("zone3Floor"), n.int("zone4Floor"), n.int("zone5Floor"),
            n.int("maxHeartRateUsed"), source(EP_HEART_RATE_ZONES, "bpm (Garmin setting)", n),
        )
    }

    private companion object {
        val GMT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val PROFILE_IMAGE_FIELDS = setOf("ownerProfileImageUrlSmall", "ownerProfileImageUrlMedium", "ownerProfileImageUrlLarge")
    }
}
