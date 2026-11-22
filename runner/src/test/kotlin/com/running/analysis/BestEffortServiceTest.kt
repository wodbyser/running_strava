package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.spi.ActivityRepository
import com.running.tenant.TenantContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.ZonedDateTime

/** A stream that is not downloaded yet must not be remembered as "no best efforts". */
class BestEffortServiceTest {
    private val repo = mock(ActivityRepository::class.java)
    private val service = BestEffortService(repo)

    private fun stream(secondsPerKm: Int): ActivityStream {
        val distance = (0..6000 step 10).map { it.toFloat() }
        val time = distance.map { (it / 1000f * secondsPerKm).toInt() }
        return ActivityStream(time, distance, null, null, null, null, null, null, null)
    }

    private fun runActivity(id: Long): Activity =
        Fixtures.run(id, ZonedDateTime.parse("2026-10-20T10:00:00Z"), 6000.0, 1800, hr = null)

    private fun <T> asOwner(block: () -> T): T = TenantContext.asTenant("wout", block)

    @Test
    fun `a missing stream is looked up again once it has arrived`() {
        `when`(repo.findStreams(1L)).thenReturn(null)
        assertTrue(asOwner { service.streamEfforts(1L) }.isEmpty())

        `when`(repo.findStreams(1L)).thenReturn(stream(240))
        val after = asOwner { service.streamEfforts(1L) }
        assertFalse(after.isEmpty())
        assertEquals(1200.0, after.getValue(5000.0), 1.0) // 5 km at 4:00/km
    }

    @Test
    fun `a stream without time or distance is not cached either`() {
        `when`(repo.findStreams(2L)).thenReturn(ActivityStream(null, null, null, null, null, null, null, null, null))
        assertTrue(asOwner { service.streamEfforts(2L) }.isEmpty())
        `when`(repo.findStreams(2L)).thenReturn(stream(300))
        assertFalse(asOwner { service.streamEfforts(2L) }.isEmpty())
    }

    @Test
    fun `a found result is cached and not read again`() {
        `when`(repo.findStreams(3L)).thenReturn(stream(240))
        asOwner { service.streamEfforts(3L) }
        asOwner { service.streamEfforts(3L) }
        verify(repo, times(1)).findStreams(3L)
    }

    @Test
    fun `personal records appear after the stream arrives and use one lookup per run`() {
        val run = runActivity(4L)
        `when`(repo.findStreams(4L)).thenReturn(null)
        val before = asOwner { service.personalRecords(listOf(run)) }
        assertEquals(0, before.runsWithStreams)
        assertTrue(before.records.all { it.activityId == null })

        `when`(repo.findStreams(4L)).thenReturn(stream(240))
        val after = asOwner { service.personalRecords(listOf(run)) }
        assertEquals(1, after.runsWithStreams)
        assertTrue(after.records.any { it.activityId != null })
    }

    @Test
    fun `no runs gives empty records without failing`() {
        val r = asOwner { service.personalRecords(emptyList()) }
        assertEquals(0, r.runsConsidered)
        assertEquals(0, r.runsWithStreams)
        assertTrue(r.records.all { it.activityId == null })
    }
}
