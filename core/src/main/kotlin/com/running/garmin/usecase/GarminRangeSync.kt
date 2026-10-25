package com.running.garmin.usecase

import com.running.garmin.domain.GarminApiException
import com.running.garmin.domain.GarminCallFailure
import com.running.garmin.domain.GarminDailyRecord
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.domain.GarminRunKind
import com.running.garmin.domain.GarminSyncResult
import com.running.garmin.domain.GarminSyncStatus
import com.running.garmin.spi.GarminApiClient
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class GarminSyncSettings(
    val chunkDays: Int = 7,
    val pauseMillis: Long = 1_000L,
    val overlapDays: Int = 3,
    val activityPageSize: Int = 100,
    val defaultBackfillDays: Int = 730,
    val zone: ZoneId = ZoneId.of("Europe/Brussels"),
) {
    init {
        require(chunkDays in 1..28) { "chunkDays must be between 1 and 28 (documented API limits)" }
        require(activityPageSize in 1..100) { "activityPageSize must be between 1 and 100 (larger pages returned HTTP 500)" }
        require(overlapDays >= 0 && defaultBackfillDays >= 1 && pauseMillis >= 0)
    }
}

const val DS_RESTING_HR = "resting_hr"
const val DS_BODY_BATTERY = "body_battery"
const val DS_BODY_BATTERY_SERIES = "body_battery_series"
const val DS_STRESS = "stress"
const val DS_STEPS = "steps"
const val DS_SLEEP = "sleep"
const val DS_HRV = "hrv"
const val DS_ACTIVITIES = "activities"
const val DS_HR_ZONES = "hr_zones"

/**
 * Shared engine of the sync and backfill use cases. Sequential calls, one chunk at a time.
 * A failed call is recorded and the run goes on, except AUTH and NOT_CONFIGURED, which end the run.
 */
class GarminRangeSync(
    private val client: GarminApiClient,
    private val repository: GarminRepository,
    private val statusRepository: GarminSyncStatusRepository,
    private val retry: GarminRetry,
    private val settings: GarminSyncSettings,
    private val clock: Clock,
    private val pause: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
) {
    private val log = LoggerFactory.getLogger(GarminRangeSync::class.java)

    val zone: ZoneId get() = settings.zone

    fun today(): LocalDate = LocalDate.now(clock.withZone(settings.zone))

    fun run(kind: GarminRunKind, from: LocalDate, to: LocalDate): GarminSyncResult {
        require(!from.isAfter(to)) { "from must not be after to" }
        val startedAt = clock.instant()
        val records = linkedMapOf<String, Int>()
        val nulls = linkedMapOf<String, Int>()
        val failures = mutableListOf<GarminCallFailure>()
        var aborted = false

        fun <T> guarded(endpoint: String, rangeFrom: LocalDate?, rangeTo: LocalDate?, block: () -> T): T? {
            if (aborted) return null
            return try {
                retry.execute(block)
            } catch (e: GarminApiException) {
                log.warn("Garmin call failed after retries: {} ({})", endpoint, e.message)
                failures += GarminCallFailure(endpoint, rangeFrom, rangeTo, e.category)
                if (e.category == GarminErrorCategory.AUTH || e.category == GarminErrorCategory.NOT_CONFIGURED) aborted = true
                null
            }
        }

        fun store(dataset: String, written: Int, nullCount: Int) {
            records.merge(dataset, written, Int::plus)
            nulls.merge(dataset, nullCount, Int::plus)
        }

        fun nullCount(list: List<GarminDailyRecord>) = list.sumOf { it.nullFieldCount() }

        var chunkStart = from
        while (!chunkStart.isAfter(to) && !aborted) {
            val chunkEnd = minOf(chunkStart.plusDays(settings.chunkDays - 1L), to)
            val syncedAt = clock.instant()

            guarded(DS_DAILY, chunkStart, chunkEnd) { client.getDailySummaries(chunkStart, chunkEnd) }?.let { days ->
                val rhr = days.mapNotNull { it.restingHr }
                val bb = days.mapNotNull { it.bodyBattery }
                val stress = days.mapNotNull { it.stress }
                val steps = days.mapNotNull { it.steps }
                store(DS_RESTING_HR, repository.upsertRestingHr(rhr, syncedAt), nullCount(rhr))
                store(DS_BODY_BATTERY, repository.upsertBodyBattery(bb, syncedAt), nullCount(bb))
                store(DS_STRESS, repository.upsertStress(stress, syncedAt), nullCount(stress))
                store(DS_STEPS, repository.upsertSteps(steps, syncedAt), nullCount(steps))
            }
            guarded(DS_SLEEP, chunkStart, chunkEnd) { client.getSleepSummaries(chunkStart, chunkEnd) }?.let {
                store(DS_SLEEP, repository.upsertSleep(it, syncedAt), nullCount(it))
            }
            guarded(DS_BODY_BATTERY_SERIES, chunkStart, chunkEnd) { client.getBodyBatterySeries(chunkStart, chunkEnd) }?.let {
                store(DS_BODY_BATTERY_SERIES, repository.replaceBodyBatterySeries(it, syncedAt), 0)
            }
            guarded(DS_HRV, chunkStart, chunkEnd) { client.getHrvSummaries(chunkStart, chunkEnd) }?.let {
                store(DS_HRV, repository.upsertHrv(it, syncedAt), nullCount(it))
            }

            chunkStart = chunkEnd.plusDays(1)
            if (!chunkStart.isAfter(to)) pause(settings.pauseMillis)
        }

        var offset = 0
        while (!aborted) {
            val page = guarded(DS_ACTIVITIES, from, to) {
                client.searchActivities(offset, settings.activityPageSize, from, to)
            } ?: break
            store(DS_ACTIVITIES, repository.upsertActivities(page, clock.instant()), page.sumOf { it.nullFieldCount() })
            if (page.size < settings.activityPageSize) break
            offset += page.size
            pause(settings.pauseMillis)
        }
        guarded(DS_HR_ZONES, null, null) { client.getHeartRateZones() }?.let { zones ->
            store(DS_HR_ZONES, repository.upsertHeartRateZones(zones, clock.instant()), zones.sumOf { it.nullFieldCount() })
        }

        val result = GarminSyncResult(kind, from, to, startedAt, clock.instant(), records, nulls, failures, aborted)
        statusRepository.save(nextStatus(statusRepository.get(), result))
        return result
    }

    private fun nextStatus(previous: GarminSyncStatus, result: GarminSyncResult): GarminSyncStatus {
        val firstFailure = result.failures.firstOrNull { it.category == GarminErrorCategory.AUTH }
            ?: result.failures.firstOrNull { it.category == GarminErrorCategory.NOT_CONFIGURED }
            ?: result.failures.firstOrNull()
        val detail = firstFailure?.let {
            val range = if (it.from != null) " ${it.from}..${it.to}" else ""
            "${it.endpoint}$range: ${it.category} (${result.failures.size} failed call(s))"
        }
        return previous.copy(
            lastRunKind = result.kind,
            lastRunStartedAt = result.startedAt,
            lastRunFinishedAt = result.finishedAt,
            lastSuccessAt = if (result.success) result.finishedAt else previous.lastSuccessAt,
            lastSuccessDate = if (result.success) maxOf(result.to, previous.lastSuccessDate ?: result.to) else previous.lastSuccessDate,
            lastErrorCategory = firstFailure?.category,
            lastErrorDetail = detail,
            recordsPerEndpoint = result.recordsPerEndpoint,
            nullFieldsPerEndpoint = result.nullFieldsPerEndpoint,
            failedCalls = result.failures.size,
        )
    }

    private companion object {
        const val DS_DAILY = "dailysummaries"
    }
}
