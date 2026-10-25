package com.running.garmin

import com.running.garmin.domain.*
import com.running.garmin.usecase.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GarminRangeSyncTest {
    private val client = FakeGarminClient()
    private val repo = FakeGarminRepository()
    private val statusRepo = FakeStatusRepository()
    private val settings = GarminSyncSettings(chunkDays = 7, pauseMillis = 0)
    private val pauses = mutableListOf<Long>()
    private fun engine(retry: GarminRetry = GarminRetry(3, listOf(1)) {}) =
        GarminRangeSync(client, repo, statusRepo, retry, settings, FIXED_CLOCK) { pauses += it }
    private val backfill get() = BackfillGarminDataImpl(engine(), settings)
    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test
    fun `backfill splits the range in 7 day chunks, pauses between chunks and is idempotent`() {
        val r1 = backfill.execute(d("2026-10-01"), d("2026-10-15"))
        val dailyCalls = client.calls.filter { it.startsWith("daily") }
        assertEquals(listOf("daily 2026-10-01 2026-10-07", "daily 2026-10-08 2026-10-14", "daily 2026-10-15 2026-10-15"), dailyCalls)
        assertEquals(15, repo.hrv.size)
        assertEquals(15, r1.recordsPerEndpoint[DS_RESTING_HR])
        assertTrue(r1.success)
        assertEquals(2, pauses.size)

        backfill.execute(d("2026-10-01"), d("2026-10-15"))
        assertEquals(15, repo.hrv.size)
        assertEquals(15, repo.sleep.size)
        assertEquals(15, repo.restingHr.size)
        assertEquals(15, repo.steps.size)
    }

    @Test
    fun `default backfill is 730 days ending yesterday in Brussels`() {
        val r = backfill.execute()
        assertEquals(d("2026-10-24"), r.to)
        assertEquals(d("2026-10-24").minusDays(730), r.from)
    }

    @Test
    fun `missing fields stay null and are counted, all-null days are not stored`() {
        client.hrvHandler = { s, _ -> listOf(hrv(s, null)) }
        val r = backfill.execute(d("2026-10-01"), d("2026-10-01"))
        assertNull(repo.hrv.getValue(d("2026-10-01")).lastNightAvg)
        assertEquals(8, r.nullFieldsPerEndpoint[DS_HRV])
        assertEquals(8, statusRepo.status.nullFieldsPerEndpoint[DS_HRV])
    }

    @Test
    fun `failed endpoint is retried then recorded and the run continues`() {
        var sleepCalls = 0
        client.sleepHandler = { s, _ -> sleepCalls++; if (s == d("2026-10-01")) throw GarminApiException(GarminErrorCategory.SERVER, 500, "boom") else listOf(sleep(s)) }
        val r = backfill.execute(d("2026-10-01"), d("2026-10-10"))
        assertEquals(3 + 1, sleepCalls)
        assertFalse(r.success)
        assertEquals(1, r.failures.size)
        assertEquals(GarminErrorCategory.SERVER, r.failures.single().category)
        assertEquals(1, repo.sleep.size) // only the second chunk (one record from the fake)
        assertEquals(10, repo.hrv.size)
        assertEquals(GarminErrorCategory.SERVER, statusRepo.status.lastErrorCategory)
        assertNull(statusRepo.status.lastSuccessAt)
        assertEquals(1, statusRepo.status.failedCalls)
    }

    @Test
    fun `auth failure aborts the run without further calls`() {
        client.dailyHandler = { _, _ -> throw GarminApiException(GarminErrorCategory.AUTH, 401, "no") }
        val r = backfill.execute(d("2026-09-01"), d("2026-10-10"))
        assertTrue(r.aborted)
        assertTrue(r.authFailed)
        assertEquals(listOf("daily 2026-09-01 2026-09-07"), client.calls)
        assertEquals(GarminErrorCategory.AUTH, statusRepo.status.lastErrorCategory)
    }

    @Test
    fun `activity search pages until a short page`() {
        client.activityHandler = { offset, limit -> if (offset < 200) (1..limit).map { activity(offset + it.toLong()) } else listOf(activity(999)) }
        val r = backfill.execute(d("2026-10-01"), d("2026-10-01"))
        assertEquals(listOf("activities 0 100", "activities 100 100", "activities 200 100"), client.calls.filter { it.startsWith("activities") })
        assertEquals(201, r.recordsPerEndpoint[DS_ACTIVITIES])
        assertEquals(201, repo.activities.size)
    }

    @Test
    fun `incremental sync without a previous success uses the default range, then re-fetches the last 3 days`() {
        val sync = SyncGarminDataImpl(engine(), statusRepo, settings)
        val first = sync.execute()
        assertEquals(d("2026-10-25").minusDays(730), first.from)
        assertEquals(d("2026-10-25"), first.to)
        assertEquals(d("2026-10-25"), statusRepo.status.lastSuccessDate)

        client.calls.clear()
        val second = sync.execute()
        assertEquals(d("2026-10-22"), second.from)
        assertEquals(d("2026-10-25"), second.to)
        assertEquals(listOf("daily 2026-10-22 2026-10-25"), client.calls.filter { it.startsWith("daily") })
    }

    @Test
    fun `failed run does not move the last success date`() {
        statusRepo.status = GarminSyncStatus.EMPTY.copy(lastSuccessDate = d("2026-10-20"))
        client.hrvHandler = { _, _ -> throw GarminApiException(GarminErrorCategory.NETWORK, null, "down") }
        val r = SyncGarminDataImpl(engine(), statusRepo, settings).execute()
        assertEquals(d("2026-10-17"), r.from)
        assertEquals(d("2026-10-20"), statusRepo.status.lastSuccessDate)
    }

    @Test
    fun `settings reject chunks above the documented limit and activity pages above 100`() {
        assertThrows(IllegalArgumentException::class.java) { GarminSyncSettings(chunkDays = 29) }
        assertThrows(IllegalArgumentException::class.java) { GarminSyncSettings(activityPageSize = 175) }
    }
}
