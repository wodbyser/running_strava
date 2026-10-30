package com.running.garmin.spi

import com.running.garmin.domain.GarminActivityRef
import com.running.garmin.domain.GarminBodyBatteryDay
import com.running.garmin.domain.GarminBodyBatterySample
import com.running.garmin.domain.GarminBodyBatterySeriesDay
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminRestingHrDay
import com.running.garmin.domain.GarminSleepDay
import com.running.garmin.domain.GarminStepsDay
import com.running.garmin.domain.GarminStressDay
import com.running.garmin.domain.GarminSyncStatus
import java.time.Instant
import java.time.LocalDate

/** Upserts are idempotent: the same calendar date or activity ID overwrites the previous row. Return the number written. */
interface GarminRepository {
    fun upsertHrv(records: List<GarminHrvDay>, syncedAt: Instant): Int
    fun upsertSleep(records: List<GarminSleepDay>, syncedAt: Instant): Int
    fun upsertRestingHr(records: List<GarminRestingHrDay>, syncedAt: Instant): Int
    fun upsertBodyBattery(records: List<GarminBodyBatteryDay>, syncedAt: Instant): Int
    /** Replaces all samples of each given day. Returns the number of samples written. */
    fun replaceBodyBatterySeries(records: List<GarminBodyBatterySeriesDay>, syncedAt: Instant): Int
    fun upsertStress(records: List<GarminStressDay>, syncedAt: Instant): Int
    fun upsertSteps(records: List<GarminStepsDay>, syncedAt: Instant): Int
    fun upsertActivities(records: List<GarminActivityRef>, syncedAt: Instant): Int
    fun upsertHeartRateZones(records: List<GarminHeartRateZones>, syncedAt: Instant): Int

    fun findActivities(from: Instant, to: Instant): List<GarminActivityRef>
    fun findAllActivities(): List<GarminActivityRef>

    /** Read access for the recovery analysis. Date ranges are inclusive, results are sorted by date. */
    fun findHrv(from: LocalDate, to: LocalDate): List<GarminHrvDay>
    fun findSleep(from: LocalDate, to: LocalDate): List<GarminSleepDay>
    fun findRestingHr(from: LocalDate, to: LocalDate): List<GarminRestingHrDay>
    fun findBodyBattery(from: LocalDate, to: LocalDate): List<GarminBodyBatteryDay>
    fun findBodyBatterySamples(fromMs: Long, toMs: Long): List<GarminBodyBatterySample>
    fun findStress(from: LocalDate, to: LocalDate): List<GarminStressDay>

    /** Latest stored snapshot of Garmin's heart rate zones, one row per sport (`DEFAULT`, `RUNNING`). */
    fun findHeartRateZones(): List<GarminHeartRateZones>
}

interface GarminSyncStatusRepository {
    fun get(): GarminSyncStatus
    fun save(status: GarminSyncStatus)
}
