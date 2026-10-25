package com.running.garmin

import com.running.garmin.db.*
import com.running.garmin.domain.*
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate

@DataJpaTest(properties = ["spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"])
@Import(GarminRepositoryImpl::class, GarminSyncStatusRepositoryImpl::class, JacksonAutoConfiguration::class)
class GarminRepositoryImplTest @Autowired constructor(
    private val repo: GarminRepositoryImpl,
    private val statusRepo: GarminSyncStatusRepositoryImpl,
    private val hrvJpa: GarminHrvJpaRepository,
    private val rawJpa: GarminRawPayloadJpaRepository,
    private val em: EntityManager,
) {
    private val d = LocalDate.parse("2026-10-18")
    private val t1 = Instant.parse("2026-10-25T10:00:00Z")
    private val t2 = Instant.parse("2026-10-26T10:00:00Z")
    private fun src(raw: String? = """{"a":1}""") = GarminSource("/hrv-service/hrv/daily/{start}/{end}", null, "ms UNVERIFIED", raw)
    private fun hrv(avg: Int?) = GarminHrvDay(d, 71, avg, 100, 69, 71, 81, 0.25, "BALANCED", null, src())

    @Test
    fun `upsert twice keeps one row per date and overwrites values and sync time`() {
        repo.upsertHrv(listOf(hrv(74)), t1)
        repo.upsertHrv(listOf(hrv(75)), t2)
        em.flush(); em.clear()
        val rows = hrvJpa.findAll()
        assertEquals(1, rows.size)
        assertEquals(75, rows.single().lastNightAvg)
        assertEquals(t2, rows.single().provenance.syncedAt)
        assertEquals("/hrv-service/hrv/daily/{start}/{end}", rows.single().provenance.sourceEndpoint)
        assertEquals("ms UNVERIFIED", rows.single().provenance.originalUnits)
    }

    @Test
    fun `missing values are stored as null and raw payloads are upserted per key`() {
        repo.upsertHrv(listOf(hrv(null)), t1)
        repo.upsertHrv(listOf(hrv(null).copy(source = src("""{"a":2}"""))), t2)
        em.flush(); em.clear()
        assertNull(hrvJpa.findAll().single().lastNightAvg)
        val raws = rawJpa.findAll()
        assertEquals(1, raws.size)
        assertEquals("""{"a":2}""", raws.single().payload)
        assertEquals(raws.single().id, hrvJpa.findAll().single().provenance.rawPayloadId)
    }

    @Test
    fun `no raw row when raw storing is off`() {
        repo.upsertHrv(listOf(hrv(70).copy(source = src(null))), t1)
        em.flush()
        assertEquals(0, rawJpa.count())
        assertNull(hrvJpa.findAll().single().provenance.rawPayloadId)
    }

    @Test
    fun `activities and every daily table are idempotent`() {
        val src = GarminSource("x", null, "u")
        val act = GarminActivityRef(1, Instant.parse("2026-10-18T08:00:00Z"), "running", 17, 10000.0, 3000.0, 3000.0, 2990.0, 150.0, 170.0, 3.0, 1.0, 90.0, "BASE", "GARMIN", false, null, null, src)
        repeat(2) {
            repo.upsertActivities(listOf(act), t1)
            repo.upsertSleep(listOf(GarminSleepDay(d, 27000, 5000, 15000, 6000, 1000, null, null, null, null, null, null, null, null, null, null, null, src)), t1)
            repo.upsertRestingHr(listOf(GarminRestingHrDay(d, 45, 40, 100, src)), t1)
            repo.upsertBodyBattery(listOf(GarminBodyBatteryDay(d, 20, 100, 80, 60, 50, src)), t1)
            repo.upsertStress(listOf(GarminStressDay(d, 20, 80, 1, 1, 1, 1, 1, null, 6, src)), t1)
            repo.upsertSteps(listOf(GarminStepsDay(d, 5000, 4000.0, 8000, src)), t1)
            repo.upsertHeartRateZones(listOf(GarminHeartRateZones("RUNNING", "LT", 52, 182, 120, 151, 164, 171, 186, 196, src)), t1)
        }
        em.flush(); em.clear()
        for (table in listOf("garmin_activity_ref", "garmin_daily_sleep", "garmin_daily_resting_hr", "garmin_daily_body_battery", "garmin_daily_stress", "garmin_daily_steps", "garmin_hr_zones")) {
            assertEquals(1L, (em.createNativeQuery("select count(*) from $table").singleResult as Number).toLong(), table)
        }
        val back = repo.findAllActivities().single()
        assertEquals(1L, back.activityId)
        assertEquals(10000.0, back.distanceMeters)
        assertEquals(1, repo.findActivities(Instant.parse("2026-10-18T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z")).size)
    }

    @Test
    fun `sync status survives a round trip`() {
        assertEquals(GarminSyncStatus.EMPTY, statusRepo.get())
        val s = GarminSyncStatus(GarminRunKind.SYNC, t1, t2, t2, d, GarminErrorCategory.SERVER, "hrv: SERVER", mapOf("hrv" to 3), mapOf("hrv" to 1), 2)
        statusRepo.save(s)
        em.flush(); em.clear()
        assertEquals(s, statusRepo.get())
        statusRepo.save(s.copy(failedCalls = 0, lastErrorCategory = null))
        em.flush(); em.clear()
        assertEquals(0, statusRepo.get().failedCalls)
        assertEquals(1L, (em.createNativeQuery("select count(*) from garmin_sync_status").singleResult as Number).toLong())
    }
}
