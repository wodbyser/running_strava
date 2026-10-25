package com.running.garmin

import com.running.garmin.domain.*
import com.running.garmin.spi.GarminApiClient
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

val FIXED_NOW: Instant = Instant.parse("2026-10-25T10:00:00Z")
val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

fun source(endpoint: String = "test") = GarminSource(endpoint, null, "test")

fun hrv(date: LocalDate, avg: Int? = 70) = GarminHrvDay(date, avg, avg, avg, null, null, null, null, "BALANCED", null, source())
fun sleep(date: LocalDate, seconds: Int? = 27000) =
    GarminSleepDay(date, seconds, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, source())

fun summary(date: LocalDate) = GarminDailySummary(
    date,
    GarminRestingHrDay(date, 45, 40, 100, source()),
    GarminBodyBatteryDay(date, 20, 100, 80, 60, 50, source()),
    GarminStressDay(date, 20, 80, 1L, 1L, 1L, 1L, 1L, null, 6L, source()),
    GarminStepsDay(date, 5000, 4000.0, 8000, source()),
)

fun activity(id: Long, start: String = "2026-10-20T08:00:00Z", type: String? = "running", distance: Double? = 10000.0, elapsed: Double? = 3000.0, manual: Boolean = false, lat: Double? = null, lon: Double? = null) =
    GarminActivityRef(id, Instant.parse(start), type, 17, distance, elapsed, elapsed, elapsed, 150.0, 170.0, 3.0, 0.0, 100.0, "AEROBIC_BASE", "GARMIN", manual, lat, lon, source())

class FakeGarminClient : GarminApiClient {
    val calls = mutableListOf<String>()
    var dailyHandler: (LocalDate, LocalDate) -> List<GarminDailySummary> = { s, e -> days(s, e).map { summary(it) } }
    var sleepHandler: (LocalDate, LocalDate) -> List<GarminSleepDay> = { s, e -> days(s, e).map { sleep(it) } }
    var hrvHandler: (LocalDate, LocalDate) -> List<GarminHrvDay> = { s, e -> days(s, e).map { hrv(it) } }
    var batteryHandler: (LocalDate, LocalDate) -> List<GarminBodyBatterySeriesDay> = { s, e -> days(s, e).map { GarminBodyBatterySeriesDay(it, listOf(GarminBodyBatterySample(1, 50), GarminBodyBatterySample(2, 51)), source()) } }
    var activityHandler: (Int, Int) -> List<GarminActivityRef> = { _, _ -> emptyList() }

    override fun getDailySummaries(start: LocalDate, end: LocalDate): List<GarminDailySummary> { calls += "daily $start $end"; return dailyHandler(start, end) }
    override fun getSleepSummaries(start: LocalDate, end: LocalDate): List<GarminSleepDay> { calls += "sleep $start $end"; return sleepHandler(start, end) }
    override fun getBodyBatterySeries(start: LocalDate, end: LocalDate): List<GarminBodyBatterySeriesDay> { calls += "battery $start $end"; return batteryHandler(start, end) }
    override fun getHrvSummaries(start: LocalDate, end: LocalDate): List<GarminHrvDay> { calls += "hrv $start $end"; return hrvHandler(start, end) }
    override fun searchActivities(offset: Int, limit: Int, startDate: LocalDate?, endDate: LocalDate?): List<GarminActivityRef> { calls += "activities $offset $limit"; return activityHandler(offset, limit) }
    override fun getHeartRateZones(): List<GarminHeartRateZones> { calls += "zones"; return emptyList() }

    private fun days(s: LocalDate, e: LocalDate) = generateSequence(s) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.toList()
}

class FakeGarminRepository : GarminRepository {
    val hrv = sortedMapOf<LocalDate, GarminHrvDay>()
    val sleep = sortedMapOf<LocalDate, GarminSleepDay>()
    val restingHr = sortedMapOf<LocalDate, GarminRestingHrDay>()
    val bodyBattery = sortedMapOf<LocalDate, GarminBodyBatteryDay>()
    val stress = sortedMapOf<LocalDate, GarminStressDay>()
    val steps = sortedMapOf<LocalDate, GarminStepsDay>()
    val batterySamples = sortedMapOf<LocalDate, Int>()
    val activities = sortedMapOf<Long, GarminActivityRef>()

    override fun upsertHrv(records: List<GarminHrvDay>, syncedAt: Instant) = records.onEach { hrv[it.calendarDate] = it }.size
    override fun upsertSleep(records: List<GarminSleepDay>, syncedAt: Instant) = records.onEach { sleep[it.calendarDate] = it }.size
    override fun upsertRestingHr(records: List<GarminRestingHrDay>, syncedAt: Instant) = records.onEach { restingHr[it.calendarDate] = it }.size
    override fun upsertBodyBattery(records: List<GarminBodyBatteryDay>, syncedAt: Instant) = records.onEach { bodyBattery[it.calendarDate] = it }.size
    override fun replaceBodyBatterySeries(records: List<GarminBodyBatterySeriesDay>, syncedAt: Instant) = records.onEach { batterySamples[it.calendarDate] = it.samples.size }.sumOf { it.samples.size }
    override fun upsertStress(records: List<GarminStressDay>, syncedAt: Instant) = records.onEach { stress[it.calendarDate] = it }.size
    override fun upsertSteps(records: List<GarminStepsDay>, syncedAt: Instant) = records.onEach { steps[it.calendarDate] = it }.size
    override fun upsertActivities(records: List<GarminActivityRef>, syncedAt: Instant) = records.onEach { activities[it.activityId] = it }.size
    override fun upsertHeartRateZones(records: List<GarminHeartRateZones>, syncedAt: Instant) = records.size
    override fun findActivities(from: Instant, to: Instant) = activities.values.filter { it.startTimeGmt in from..to }
    override fun findAllActivities() = activities.values.toList()
    val samples = mutableListOf<GarminBodyBatterySample>()
    override fun findHrv(from: LocalDate, to: LocalDate) = hrv.values.filter { it.calendarDate in from..to }
    override fun findSleep(from: LocalDate, to: LocalDate) = sleep.values.filter { it.calendarDate in from..to }
    override fun findRestingHr(from: LocalDate, to: LocalDate) = restingHr.values.filter { it.calendarDate in from..to }
    override fun findBodyBattery(from: LocalDate, to: LocalDate) = bodyBattery.values.filter { it.calendarDate in from..to }
    override fun findBodyBatterySamples(fromMs: Long, toMs: Long) = samples.filter { it.timestampGmtMs in fromMs..toMs }.sortedBy { it.timestampGmtMs }
    override fun findStress(from: LocalDate, to: LocalDate) = stress.values.filter { it.calendarDate in from..to }
}

class FakeStatusRepository : GarminSyncStatusRepository {
    var status = GarminSyncStatus.EMPTY
    override fun get() = status
    override fun save(status: GarminSyncStatus) { this.status = status }
}
