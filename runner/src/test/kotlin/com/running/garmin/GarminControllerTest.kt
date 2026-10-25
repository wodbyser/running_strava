package com.running.garmin

import com.running.analysis.FakeActivityRepository
import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminStartOutcome
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.domain.*
import com.running.garmin.scheduler.GarminSyncScheduler
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.garmin.usecase.BackfillGarminData
import com.running.garmin.usecase.SyncGarminData
import com.running.garmin.web.GarminSyncController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

class GarminControllerTest {

    private class CountingUseCases : SyncGarminData, BackfillGarminData {
        var syncs = 0
        var backfills = mutableListOf<Pair<LocalDate?, LocalDate?>>()
        private fun result(kind: GarminRunKind) = GarminSyncResult(kind, LocalDate.EPOCH, LocalDate.EPOCH, Instant.EPOCH, Instant.EPOCH, emptyMap(), emptyMap(), emptyList(), false)
        override fun execute(): GarminSyncResult { syncs++; return result(GarminRunKind.SYNC) }
        override fun execute(from: LocalDate?, to: LocalDate?): GarminSyncResult { backfills += from to to; return result(GarminRunKind.BACKFILL) }
    }

    private class StatusRepo(var status: GarminSyncStatus = GarminSyncStatus.EMPTY) : GarminSyncStatusRepository {
        override fun get() = status
        override fun save(status: GarminSyncStatus) { this.status = status }
    }

    private class EmptyGarminRepo : GarminRepository by mock(GarminRepository::class.java) {
        override fun findAllActivities(): List<GarminActivityRef> = emptyList()
    }

    private class Harness(properties: GarminProperties, val status: StatusRepo = StatusRepo()) {
        val useCases = CountingUseCases()
        val queued = mutableListOf<Runnable>()
        val queueExecutor = Executor { queued += it }
        val coordinator = GarminSyncCoordinator(properties, useCases, useCases, queueExecutor)
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)
        val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
            GarminSyncController(properties, coordinator, status, EmptyGarminRepo(), FakeActivityRepository(emptyList()), clock),
        ).build()
    }

    private val configured = GarminProperties(apiKey = FAKE_KEY)

    @Test
    fun `a second trigger while a run is active gets 409 and starts nothing`() {
        val h = Harness(configured)
        h.mvc.perform(post("/garmin/sync")).andExpect(status().isAccepted)
        h.mvc.perform(post("/garmin/sync")).andExpect(status().isConflict)
        h.mvc.perform(post("/garmin/backfill")).andExpect(status().isConflict)
        h.mvc.perform(get("/garmin/status")).andExpect(jsonPath("$.state").value("RUNNING"))
        assertEquals(1, h.queued.size)
        assertEquals(0, h.useCases.syncs)

        h.queued.single().run()
        assertEquals(1, h.useCases.syncs)
        assertFalse(h.coordinator.isRunning)
        h.mvc.perform(post("/garmin/sync")).andExpect(status().isAccepted)
    }

    @Test
    fun `flag off starts nothing and reports DISABLED`() {
        val h = Harness(GarminProperties(enabled = false, apiKey = FAKE_KEY))
        h.mvc.perform(post("/garmin/sync")).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.state").value("DISABLED"))
        h.mvc.perform(post("/garmin/backfill")).andExpect(status().isServiceUnavailable)
        h.mvc.perform(get("/garmin/status")).andExpect(jsonPath("$.state").value("DISABLED"))
        assertTrue(h.queued.isEmpty())
        assertEquals(0, h.useCases.syncs)
    }

    @Test
    fun `no key starts nothing and reports NOT_CONFIGURED`() {
        val h = Harness(GarminProperties(apiKey = ""))
        h.mvc.perform(post("/garmin/sync")).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.state").value("NOT_CONFIGURED"))
        h.mvc.perform(get("/garmin/status")).andExpect(jsonPath("$.state").value("NOT_CONFIGURED"))
        assertTrue(h.queued.isEmpty())
    }

    @Test
    fun `AUTH failure in the last run shows AUTH_FAILED, partial failure and success show their state`() {
        val finished = Instant.parse("2026-10-25T09:00:00Z")
        val base = GarminSyncStatus.EMPTY.copy(lastRunFinishedAt = finished)
        val cases = listOf(
            GarminSyncStatus.EMPTY to "NEVER_RUN",
            base.copy(lastErrorCategory = GarminErrorCategory.AUTH, failedCalls = 1) to "AUTH_FAILED",
            base.copy(lastErrorCategory = GarminErrorCategory.SERVER, failedCalls = 2) to "PARTIAL_FAILURE",
            base to "OK",
        )
        cases.forEach { (status, expected) ->
            val h = Harness(configured, StatusRepo(status))
            h.mvc.perform(get("/garmin/status")).andExpect(jsonPath("$.state").value(expected))
        }
    }

    @Test
    fun `status shows records and null counts and never the key`() {
        val st = GarminSyncStatus.EMPTY.copy(
            lastRunKind = GarminRunKind.SYNC, lastRunFinishedAt = Instant.EPOCH, lastSuccessAt = Instant.EPOCH,
            recordsPerEndpoint = mapOf("hrv" to 4), nullFieldsPerEndpoint = mapOf("hrv" to 2),
        )
        val body = Harness(configured, StatusRepo(st)).mvc.perform(get("/garmin/status"))
            .andExpect(jsonPath("$.recordsPerEndpoint.hrv").value(4))
            .andExpect(jsonPath("$.nullFieldsPerEndpoint.hrv").value(2))
            .andExpect(jsonPath("$.lastRunKind").value("SYNC"))
            .andReturn().response.contentAsString
        assertFalse(body.contains(FAKE_KEY))
        assertFalse(body.lowercase().contains("token"))
    }

    @Test
    fun `backfill passes the dates and rejects bad ranges`() {
        val h = Harness(configured)
        h.mvc.perform(post("/garmin/backfill").param("from", "2026-01-01").param("to", "2026-01-31")).andExpect(status().isAccepted)
        h.queued.single().run()
        assertEquals(listOf(LocalDate.parse("2026-01-01") to LocalDate.parse("2026-01-31")), h.useCases.backfills)

        val bad = Harness(configured)
        bad.mvc.perform(post("/garmin/backfill").param("from", "2026-02-01").param("to", "2026-01-01")).andExpect(status().isBadRequest)
        bad.mvc.perform(post("/garmin/backfill").param("to", "2026-12-01")).andExpect(status().isBadRequest)
        assertTrue(bad.queued.isEmpty())
    }

    @Test
    fun `Garmin state changing endpoints are POST only`() {
        val h = Harness(configured)
        h.mvc.perform(get("/garmin/sync")).andExpect(status().isMethodNotAllowed)
        h.mvc.perform(get("/garmin/backfill")).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `scheduled run uses the same lock and returns NOT_CONFIGURED without a key`() {
        val h = Harness(GarminProperties(apiKey = ""))
        assertEquals(GarminStartOutcome.NOT_CONFIGURED, h.coordinator.startSync(async = false))
        val ok = Harness(configured)
        assertEquals(GarminStartOutcome.STARTED, ok.coordinator.startSync(async = false))
        assertEquals(1, ok.useCases.syncs)
    }

    @Configuration
    class SchedulerTestConfig {
        @Bean fun coordinator(): GarminSyncCoordinator = mock(GarminSyncCoordinator::class.java)
    }

    @Test
    fun `scheduler bean exists by default and is absent when the flag is off`() {
        val runner = ApplicationContextRunner().withUserConfiguration(SchedulerTestConfig::class.java, GarminSyncScheduler::class.java)
        runner.run { ctx -> assertTrue(ctx.containsBean("garminSyncScheduler")) }
        runner.withPropertyValues("garmin.enabled=false").run { ctx -> assertFalse(ctx.containsBean("garminSyncScheduler")) }
    }
}
