package com.running.garmin

import com.running.garmin.matching.GarminActivityMatcher
import com.running.garmin.matching.MatchTier
import com.running.garmin.projection.GarminActivityProjection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GarminActivityProjectionTest {
    @Test fun `maps the summary fields`() {
        val a = GarminActivityProjection.project(activity(42, start = "2026-11-17T07:30:00Z", distance = 10000.0, elapsed = 3000.0))
        assertEquals(42L, a.id)
        assertEquals("Hardlopen 17 nov", a.name)
        assertEquals("Run", a.type)
        assertEquals("Run", a.sportType)
        assertEquals(10000f, a.distance)
        assertEquals(3000, a.movingTime)
        assertEquals(3000, a.elapsedTime)
        assertEquals(0f, a.totalElevationGain)
        assertEquals("Europe/Brussels", a.timezone)
        assertEquals(8, a.startDate.hour)
        assertEquals(10000f / 3000, a.averageSpeed, 0.0001f)
        assertEquals(150f, a.averageHeartrate)
        assertEquals(170f, a.maxHeartrate)
        assertTrue(a.hasHeartrate)
        assertEquals("42", a.externalId)
        assertEquals("GARMIN", a.source)
        assertNull(a.laps)
    }

    @Test fun `type keys`() {
        assertEquals("Run", GarminActivityProjection.typeOf("trail_running"))
        assertEquals("Walk", GarminActivityProjection.typeOf("walking"))
        assertEquals("Hike", GarminActivityProjection.typeOf("hiking"))
        assertEquals("Ride", GarminActivityProjection.typeOf("cycling"))
        assertEquals("Other", GarminActivityProjection.typeOf("yoga"))
        assertEquals("Other", GarminActivityProjection.typeOf(null))
    }

    @Test fun `missing distance and time give zero speed`() {
        val a = GarminActivityProjection.project(activity(1, distance = null, elapsed = null))
        assertEquals(0f, a.distance)
        assertEquals(0f, a.averageSpeed)
    }

    @Test fun `a projected activity matches its own garmin ref`() {
        val ref = activity(7, start = "2026-10-20T08:00:00Z")
        val result = GarminActivityMatcher().match(listOf(ref), listOf(GarminActivityProjection.project(ref))).single()
        assertEquals(MatchTier.MATCHED, result.tier)
        assertEquals(7L, result.stravaActivityId)
    }
}
