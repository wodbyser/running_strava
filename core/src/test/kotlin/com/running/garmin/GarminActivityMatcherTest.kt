package com.running.garmin

import com.running.garmin.matching.GarminActivityMatcher
import com.running.garmin.matching.MatchTier
import com.running.strava.domain.Activity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class GarminActivityMatcherTest {
    private val matcher = GarminActivityMatcher()

    private fun strava(id: Long, start: String = "2026-10-20T08:00:00Z", type: String = "Run", distance: Float = 10000f, elapsed: Int = 3000, manual: Boolean = false, external: String? = null) = Activity(
        id = id, name = "x", distance = distance, movingTime = elapsed, elapsedTime = elapsed, totalElevationGain = 0f, type = type, sportType = type,
        startDate = ZonedDateTime.parse(start), timezone = "", averageSpeed = 3f, maxSpeed = 4f, averageHeartrate = null, maxHeartrate = null,
        averageCadence = null, averageWatts = null, maxWatts = null, weightedAverageWatts = null, kilojoules = null, deviceWatts = null,
        description = null, calories = null, sufferScore = null, hasHeartrate = false, elevHigh = null, elevLow = null, gearId = null,
        startLatlng = null, endLatlng = null, isTrainer = false, isCommute = false, isManual = manual, isFlagged = false, workoutType = null,
        originalStartDate = null, laps = null, splits = null, bestEfforts = null, externalId = external,
    )

    private fun one(g: com.running.garmin.domain.GarminActivityRef, vararg s: Activity) = matcher.match(listOf(g), s.toList()).single()

    @Test fun `one compatible candidate all strong is MATCHED`() {
        val r = one(activity(1), strava(10, start = "2026-10-20T08:01:00Z", distance = 10100f, elapsed = 3020))
        assertEquals(MatchTier.MATCHED, r.tier)
        assertEquals(10L, r.stravaActivityId)
    }

    @Test fun `start 5 minutes off is REVIEW`() {
        val r = one(activity(1), strava(10, start = "2026-10-20T08:05:00Z"))
        assertEquals(MatchTier.REVIEW, r.tier)
        assertNull(r.stravaActivityId)
    }

    @Test fun `two candidates within limits is REVIEW and links nothing`() {
        val r = one(activity(1), strava(10), strava(11, start = "2026-10-20T08:00:30Z"))
        assertEquals(MatchTier.REVIEW, r.tier)
        assertNull(r.stravaActivityId)
        assertEquals(2, r.candidates.size)
    }

    @Test fun `one Strava activity claimed by two Garmin activities is REVIEW for both`() {
        val results = matcher.match(listOf(activity(1), activity(2, start = "2026-10-20T08:00:40Z")), listOf(strava(10)))
        assertTrue(results.all { it.tier == MatchTier.REVIEW })
    }

    @Test fun `distance 6 percent off or start 11 minutes off is UNMATCHED`() {
        assertEquals(MatchTier.UNMATCHED, one(activity(1), strava(10, distance = 10600f)).tier)
        assertEquals(MatchTier.UNMATCHED, one(activity(1), strava(10, start = "2026-10-20T08:11:00Z")).tier)
    }

    @Test fun `type mismatch is UNMATCHED and unmapped types never match`() {
        assertEquals(MatchTier.UNMATCHED, one(activity(1), strava(10, type = "Ride")).tier)
        assertEquals(MatchTier.UNMATCHED, one(activity(1, type = "other"), strava(10, type = "Workout")).tier)
        assertEquals(MatchTier.UNMATCHED, one(activity(1, type = null), strava(10)).tier)
    }

    @Test fun `manual activity is never MATCHED`() {
        assertEquals(MatchTier.REVIEW, one(activity(1, manual = true), strava(10)).tier)
        assertEquals(MatchTier.REVIEW, one(activity(1), strava(10, manual = true)).tier)
    }

    @Test fun `start position is only a supporting component, far apart means review and never a rejection or a link`() {
        val far = one(activity(1, lat = 51.0, lon = 4.0), strava(10).copy(startLatlng = listOf(51.05, 4.0)))
        assertEquals(MatchTier.REVIEW, far.tier)
        assertNull(far.stravaActivityId)
        val farButTimeWrong = one(activity(1, lat = 51.0, lon = 4.0), strava(10, start = "2026-10-20T08:30:00Z").copy(startLatlng = listOf(51.0, 4.0)))
        assertEquals(MatchTier.UNMATCHED, farButTimeWrong.tier)
        val mid = one(activity(1, lat = 51.0, lon = 4.0), strava(10).copy(startLatlng = listOf(51.005, 4.0)))
        assertEquals(MatchTier.REVIEW, mid.tier)
    }

    @Test fun `matcher is pure and idempotent`() {
        val g = listOf(activity(1), activity(2, start = "2026-10-21T08:00:00Z"))
        val s = listOf(strava(10), strava(11, start = "2026-10-21T08:00:00Z"))
        assertEquals(matcher.match(g, s), matcher.match(g, s))
    }

    @Test fun `report counts tiers, distributions and the external id check`() {
        val g = listOf(activity(1), activity(2, start = "2026-10-21T08:00:00Z"))
        val s = listOf(strava(10, external = "garmin_push_1.fit"), strava(11, start = "2026-10-21T08:00:30Z", external = "other"))
        val report = matcher.report(g, s)
        assertEquals(2, report.tiers[MatchTier.MATCHED])
        assertEquals(2, report.nearestPairs)
        assertEquals(2, report.externalIdPresent)
        assertEquals(1, report.externalIdContainsGarminId)
        assertEquals(30.0, report.startDiffSeconds.max)
    }
}
