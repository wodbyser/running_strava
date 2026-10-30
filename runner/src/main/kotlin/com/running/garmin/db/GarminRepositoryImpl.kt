package com.running.garmin.db

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.running.garmin.domain.*
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

interface GarminHrvJpaRepository : JpaRepository<GarminHrvEntity, LocalDate> {
    fun findByCalendarDateBetweenOrderByCalendarDate(from: LocalDate, to: LocalDate): List<GarminHrvEntity>
}
interface GarminSleepJpaRepository : JpaRepository<GarminSleepEntity, LocalDate> {
    fun findByCalendarDateBetweenOrderByCalendarDate(from: LocalDate, to: LocalDate): List<GarminSleepEntity>
}
interface GarminRestingHrJpaRepository : JpaRepository<GarminRestingHrEntity, LocalDate> {
    fun findByCalendarDateBetweenOrderByCalendarDate(from: LocalDate, to: LocalDate): List<GarminRestingHrEntity>
}
interface GarminBodyBatteryJpaRepository : JpaRepository<GarminBodyBatteryEntity, LocalDate> {
    fun findByCalendarDateBetweenOrderByCalendarDate(from: LocalDate, to: LocalDate): List<GarminBodyBatteryEntity>
}
interface GarminBodyBatterySampleJpaRepository : JpaRepository<GarminBodyBatterySampleEntity, Long> {
    fun deleteByCalendarDate(calendarDate: LocalDate)
    fun findByTimestampGmtMsBetweenOrderByTimestampGmtMs(from: Long, to: Long): List<GarminBodyBatterySampleEntity>
}
interface GarminStressJpaRepository : JpaRepository<GarminStressEntity, LocalDate> {
    fun findByCalendarDateBetweenOrderByCalendarDate(from: LocalDate, to: LocalDate): List<GarminStressEntity>
}
interface GarminStepsJpaRepository : JpaRepository<GarminStepsEntity, LocalDate>
interface GarminActivityJpaRepository : JpaRepository<GarminActivityEntity, Long> {
    fun findByStartTimeGmtBetween(from: Instant, to: Instant): List<GarminActivityEntity>
}
interface GarminHrZonesJpaRepository : JpaRepository<GarminHrZonesEntity, String>
interface GarminRawPayloadJpaRepository : JpaRepository<GarminRawPayloadEntity, Long> {
    fun findByEndpointAndRecordKey(endpoint: String, recordKey: String): GarminRawPayloadEntity?
}
interface GarminSyncStatusJpaRepository : JpaRepository<GarminSyncStatusEntity, Long>

@Repository
class GarminRepositoryImpl(
    private val hrvRepo: GarminHrvJpaRepository,
    private val sleepRepo: GarminSleepJpaRepository,
    private val restingHrRepo: GarminRestingHrJpaRepository,
    private val bodyBatteryRepo: GarminBodyBatteryJpaRepository,
    private val bodyBatterySampleRepo: GarminBodyBatterySampleJpaRepository,
    private val stressRepo: GarminStressJpaRepository,
    private val stepsRepo: GarminStepsJpaRepository,
    private val activityRepo: GarminActivityJpaRepository,
    private val zonesRepo: GarminHrZonesJpaRepository,
    private val rawRepo: GarminRawPayloadJpaRepository,
) : GarminRepository {

    private fun prov(source: GarminSource, key: String, syncedAt: Instant) = GarminProvenance(
        sourceEndpoint = source.endpoint,
        sourceTimestamp = source.sourceTimestamp,
        syncedAt = syncedAt,
        originalUnits = source.originalUnits,
        rawPayloadId = source.rawJson?.let { json ->
            val row = rawRepo.findByEndpointAndRecordKey(source.endpoint, key) ?: GarminRawPayloadEntity(endpoint = source.endpoint, recordKey = key)
            row.payload = json
            row.syncedAt = syncedAt
            rawRepo.save(row).id
        },
    )

    @Transactional
    override fun upsertHrv(records: List<GarminHrvDay>, syncedAt: Instant): Int {
        hrvRepo.saveAll(records.map {
            GarminHrvEntity(it.calendarDate, it.weeklyAvg, it.lastNightAvg, it.lastNight5MinHigh, it.baselineLowUpper, it.baselineBalancedLow,
                it.baselineBalancedUpper, it.baselineMarkerValue, it.status, it.feedbackPhrase, prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertSleep(records: List<GarminSleepDay>, syncedAt: Instant): Int {
        sleepRepo.saveAll(records.map {
            GarminSleepEntity(it.calendarDate, it.sleepTimeSeconds, it.deepSleepSeconds, it.lightSleepSeconds, it.remSleepSeconds, it.awakeSleepSeconds,
                it.unmeasurableSleepSeconds, it.napTimeSeconds, it.sleepStartGmtMs, it.sleepEndGmtMs, it.avgRespiration, it.avgSleepStress,
                it.avgHeartRate, it.overallScore, it.overallQualifier, it.garminSleepNeedBaseline, it.garminSleepNeedActual,
                prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertRestingHr(records: List<GarminRestingHrDay>, syncedAt: Instant): Int {
        restingHrRepo.saveAll(records.map {
            GarminRestingHrEntity(it.calendarDate, it.restingHr, it.minHr, it.maxHr, prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertBodyBattery(records: List<GarminBodyBatteryDay>, syncedAt: Instant): Int {
        bodyBatteryRepo.saveAll(records.map {
            GarminBodyBatteryEntity(it.calendarDate, it.minValue, it.maxValue, it.charged, it.drained, it.latestValue, prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun replaceBodyBatterySeries(records: List<GarminBodyBatterySeriesDay>, syncedAt: Instant): Int {
        records.forEach { day ->
            bodyBatterySampleRepo.deleteByCalendarDate(day.calendarDate)
            bodyBatterySampleRepo.flush()
            val provenance = { prov(day.source, day.calendarDate.toString(), syncedAt) }
            bodyBatterySampleRepo.saveAll(day.samples.map {
                GarminBodyBatterySampleEntity(it.timestampGmtMs, day.calendarDate, it.level, provenance())
            })
        }
        return records.sumOf { it.samples.size }
    }

    @Transactional
    override fun upsertStress(records: List<GarminStressDay>, syncedAt: Instant): Int {
        stressRepo.saveAll(records.map {
            GarminStressEntity(it.calendarDate, it.avgLevel, it.maxLevel, it.restDurationMs, it.lowDurationMs, it.mediumDurationMs, it.highDurationMs,
                it.activityDurationMs, it.uncategorizedDurationMs, it.totalDurationMs, prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertSteps(records: List<GarminStepsDay>, syncedAt: Instant): Int {
        stepsRepo.saveAll(records.map {
            GarminStepsEntity(it.calendarDate, it.steps, it.distanceMeters, it.stepGoal, prov(it.source, it.calendarDate.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertActivities(records: List<GarminActivityRef>, syncedAt: Instant): Int {
        activityRepo.saveAll(records.map {
            GarminActivityEntity(it.activityId, it.startTimeGmt, it.startTimeGmt.atOffset(ZoneOffset.UTC).toLocalDate(), it.typeKey, it.parentTypeId,
                it.distanceMeters, it.durationSeconds, it.elapsedSeconds, it.movingSeconds, it.averageHr, it.maxHr, it.aerobicTrainingEffect,
                it.anaerobicTrainingEffect, it.activityTrainingLoad, it.trainingEffectLabel, it.manufacturer, it.isManual, it.startLatitude,
                it.startLongitude, prov(it.source, it.activityId.toString(), syncedAt))
        })
        return records.size
    }

    @Transactional
    override fun upsertHeartRateZones(records: List<GarminHeartRateZones>, syncedAt: Instant): Int {
        zonesRepo.saveAll(records.map {
            GarminHrZonesEntity(it.sport, it.trainingMethod, it.restingHr, it.lactateThresholdHr, it.zone1Floor, it.zone2Floor, it.zone3Floor,
                it.zone4Floor, it.zone5Floor, it.maxHr, prov(it.source, it.sport, syncedAt))
        })
        return records.size
    }

    @Transactional(readOnly = true)
    override fun findHeartRateZones(): List<GarminHeartRateZones> = zonesRepo.findAll().map {
        GarminHeartRateZones(it.sport, it.trainingMethod, it.restingHr, it.lactateThresholdHr, it.zone1Floor, it.zone2Floor, it.zone3Floor,
            it.zone4Floor, it.zone5Floor, it.maxHr, src(it.provenance))
    }

    @Transactional(readOnly = true)
    override fun findActivities(from: Instant, to: Instant): List<GarminActivityRef> =
        activityRepo.findByStartTimeGmtBetween(from, to).map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findAllActivities(): List<GarminActivityRef> = activityRepo.findAll().map { toDomain(it) }

    private fun src(p: GarminProvenance) = GarminSource(p.sourceEndpoint, p.sourceTimestamp, p.originalUnits)

    @Transactional(readOnly = true)
    override fun findHrv(from: LocalDate, to: LocalDate) = hrvRepo.findByCalendarDateBetweenOrderByCalendarDate(from, to).map {
        GarminHrvDay(it.calendarDate, it.weeklyAvg, it.lastNightAvg, it.lastNight5MinHigh, it.baselineLowUpper, it.baselineBalancedLow,
            it.baselineBalancedUpper, it.baselineMarkerValue, it.status, it.feedbackPhrase, src(it.provenance))
    }

    @Transactional(readOnly = true)
    override fun findSleep(from: LocalDate, to: LocalDate) = sleepRepo.findByCalendarDateBetweenOrderByCalendarDate(from, to).map {
        GarminSleepDay(it.calendarDate, it.sleepTimeSeconds, it.deepSleepSeconds, it.lightSleepSeconds, it.remSleepSeconds, it.awakeSleepSeconds,
            it.unmeasurableSleepSeconds, it.napTimeSeconds, it.sleepStartGmtMs, it.sleepEndGmtMs, it.avgRespiration, it.avgSleepStress,
            it.avgHeartRate, it.overallScore, it.overallQualifier, it.garminSleepNeedBaseline, it.garminSleepNeedActual, src(it.provenance))
    }

    @Transactional(readOnly = true)
    override fun findRestingHr(from: LocalDate, to: LocalDate) = restingHrRepo.findByCalendarDateBetweenOrderByCalendarDate(from, to).map {
        GarminRestingHrDay(it.calendarDate, it.restingHr, it.minHr, it.maxHr, src(it.provenance))
    }

    @Transactional(readOnly = true)
    override fun findBodyBattery(from: LocalDate, to: LocalDate) = bodyBatteryRepo.findByCalendarDateBetweenOrderByCalendarDate(from, to).map {
        GarminBodyBatteryDay(it.calendarDate, it.minValue, it.maxValue, it.charged, it.drained, it.latestValue, src(it.provenance))
    }

    @Transactional(readOnly = true)
    override fun findBodyBatterySamples(fromMs: Long, toMs: Long) =
        bodyBatterySampleRepo.findByTimestampGmtMsBetweenOrderByTimestampGmtMs(fromMs, toMs).map { GarminBodyBatterySample(it.timestampGmtMs, it.level) }

    @Transactional(readOnly = true)
    override fun findStress(from: LocalDate, to: LocalDate) = stressRepo.findByCalendarDateBetweenOrderByCalendarDate(from, to).map {
        GarminStressDay(it.calendarDate, it.avgLevel, it.maxLevel, it.restDurationMs, it.lowDurationMs, it.mediumDurationMs, it.highDurationMs,
            it.activityDurationMs, it.uncategorizedDurationMs, it.totalDurationMs, src(it.provenance))
    }

    private fun toDomain(e: GarminActivityEntity) = GarminActivityRef(
        e.activityId, e.startTimeGmt, e.typeKey, e.parentTypeId, e.distanceMeters, e.durationSeconds, e.elapsedSeconds, e.movingSeconds,
        e.averageHr, e.maxHr, e.aerobicTrainingEffect, e.anaerobicTrainingEffect, e.activityTrainingLoad, e.trainingEffectLabel,
        e.manufacturer, e.isManual, e.startLatitude, e.startLongitude,
        GarminSource(e.provenance.sourceEndpoint, e.provenance.sourceTimestamp, e.provenance.originalUnits),
    )
}

@Repository
class GarminSyncStatusRepositoryImpl(
    private val jpa: GarminSyncStatusJpaRepository,
    private val mapper: ObjectMapper,
) : GarminSyncStatusRepository {

    private val mapType = object : TypeReference<Map<String, Int>>() {}

    @Transactional(readOnly = true)
    override fun get(): GarminSyncStatus {
        val e = jpa.findById(1).orElse(null) ?: return GarminSyncStatus.EMPTY
        return GarminSyncStatus(
            lastRunKind = e.lastRunKind?.let { GarminRunKind.valueOf(it) },
            lastRunStartedAt = e.lastRunStartedAt,
            lastRunFinishedAt = e.lastRunFinishedAt,
            lastSuccessAt = e.lastSuccessAt,
            lastSuccessDate = e.lastSuccessDate,
            lastErrorCategory = e.lastErrorCategory?.let { GarminErrorCategory.valueOf(it) },
            lastErrorDetail = e.lastErrorDetail,
            recordsPerEndpoint = e.recordsPerEndpoint?.let { mapper.readValue(it, mapType) } ?: emptyMap(),
            nullFieldsPerEndpoint = e.nullFieldsPerEndpoint?.let { mapper.readValue(it, mapType) } ?: emptyMap(),
            failedCalls = e.failedCalls,
        )
    }

    @Transactional
    override fun save(status: GarminSyncStatus) {
        val e = jpa.findById(1).orElse(GarminSyncStatusEntity())
        e.lastRunKind = status.lastRunKind?.name
        e.lastRunStartedAt = status.lastRunStartedAt
        e.lastRunFinishedAt = status.lastRunFinishedAt
        e.lastSuccessAt = status.lastSuccessAt
        e.lastSuccessDate = status.lastSuccessDate
        e.lastErrorCategory = status.lastErrorCategory?.name
        e.lastErrorDetail = status.lastErrorDetail?.take(500)
        e.recordsPerEndpoint = mapper.writeValueAsString(status.recordsPerEndpoint)
        e.nullFieldsPerEndpoint = mapper.writeValueAsString(status.nullFieldsPerEndpoint)
        e.failedCalls = status.failedCalls
        jpa.save(e)
    }
}
