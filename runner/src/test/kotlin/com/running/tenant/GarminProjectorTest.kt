package com.running.tenant

import com.running.analysis.Fixtures
import com.running.garmin.adapter.GarminActivityProjector
import com.running.garmin.domain.GarminActivityRef
import com.running.garmin.domain.GarminSource
import com.running.garmin.spi.GarminRepository
import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.ZonedDateTime

class GarminProjectorTest {
    private fun ref(id: Long) = GarminActivityRef(
        id, Instant.parse("2026-10-20T08:00:00Z"), "running", 17, 10000.0, 3000.0, 3000.0, 3000.0, 150.0, 170.0, 3.0, 0.0, 100.0,
        "AEROBIC_BASE", "GARMIN", false, null, null, GarminSource("test", null, "test"),
    )

    private val garmin = mock(GarminRepository::class.java).also { `when`(it.findAllActivities()).thenReturn(listOf(ref(1), ref(2))) }

    @Test
    fun `projection is idempotent and refuses to overwrite a Strava row`() {
        val stored = mutableMapOf(2L to Fixtures.run(2, ZonedDateTime.now(), 5000.0, 1500).copy(name = "Van Strava"))
        val repo = mock(ActivityRepository::class.java)
        `when`(repo.findById(anyLong())).thenAnswer { stored[it.getArgument<Long>(0)] }
        `when`(repo.saveAll(anyList())).thenAnswer { inv -> inv.getArgument<List<Activity>>(0).forEach { stored[it.id] = it } }
        val projector = GarminActivityProjector(garmin, repo)
        assertEquals(1, projector.project())
        assertEquals(1, projector.project(), "second run rewrites the same row")
        assertEquals("GARMIN", stored[1]!!.source)
        assertEquals("Van Strava", stored[2]!!.name)
        assertNull(stored[2]!!.source)
    }
}
