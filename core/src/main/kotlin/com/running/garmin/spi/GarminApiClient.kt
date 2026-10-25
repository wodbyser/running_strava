package com.running.garmin.spi

import com.running.garmin.domain.GarminActivityRef
import com.running.garmin.domain.GarminDailySummary
import com.running.garmin.domain.GarminHeartRateZones
import com.running.garmin.domain.GarminHrvDay
import com.running.garmin.domain.GarminSleepDay
import java.time.LocalDate

/**
 * Every method throws `GarminApiException` on failure. "No data" is an empty list, never an error.
 * Ranges are inclusive. Callers keep ranges inside the documented limits (see docs/garmin-capability-matrix.md).
 */
interface GarminApiClient {
    fun getDailySummaries(start: LocalDate, end: LocalDate): List<GarminDailySummary>
    fun getSleepSummaries(start: LocalDate, end: LocalDate): List<GarminSleepDay>
    fun getHrvSummaries(start: LocalDate, end: LocalDate): List<GarminHrvDay>
    fun searchActivities(offset: Int, limit: Int, startDate: LocalDate?, endDate: LocalDate?): List<GarminActivityRef>
    fun getHeartRateZones(): List<GarminHeartRateZones>
}
