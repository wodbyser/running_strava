package com.running.strava.analysis

import com.running.strava.domain.Activity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** Expected values are computed by hand in the comments, not copied from the implementation. */
class UnitsAndAggregatesTest {

    @Test
    fun `pace is rounded to the nearest second`() {
        // 1000 / 4.68 = 213.675 s -> 214 s = 3:34 (the old max-pace bug rendered this as 0:59)
        assertEquals("3:34 /km", Format.pace(4.68))
        assertEquals("6:00 /km", Format.paceFromSeconds(359.6))
        assertEquals("5:59 /km", Format.paceFromSeconds(359.4))
        assertEquals("-", Format.pace(0.0))
        assertEquals("-", Format.pace(Double.NaN))
        assertEquals("-", Format.pace(null))
    }

    @Test
    fun `duration never drops seconds`() {
        assertEquals("1:30:06", Format.duration(5406)) // 1h 30m 6s
        assertEquals("19:23", Format.duration(1163))
        assertEquals("1:00", Format.duration(59.6))
        assertEquals("0:00", Format.duration(0))
        assertEquals("-", Format.duration(null))
    }

    @Test
    fun `hr is a whole number`() {
        assertEquals("152 bpm", Format.hr(152.3f))
        assertEquals("153 bpm", Format.hr(152.5))
        assertEquals("-", Format.hr(null))
    }

    @Test
    fun `running cadence per foot is doubled to spm, other sports untouched`() {
        assertEquals(164.2, Cadence.toSpm(82.1, "Run")!!, 1e-4)
        assertEquals("164 spm", Cadence.formatSpm(82.1f, "Run"))
        assertEquals("170 spm", Cadence.formatSpm(85f, "TrailRun"))
        assertEquals("82 rpm", Cadence.formatSpm(82f, "Ride"))
        assertNull(Cadence.toSpm(null, "Run"))
        assertNull(Cadence.toSpm(0f, "Run"))
    }

    @Test
    fun `local time uses the activity timezone, not UTC`() {
        // 17:04 UTC on 24/09/2026 is 19:04 CEST in Brussels
        val a = activity(start = "2026-09-24T17:04:00Z", tz = "(GMT+01:00) Europe/Brussels")
        assertEquals("2026-09-24T19:04", ActivityTime.local(a).toLocalDateTime().toString())
        // Sunday 27/09 22:30 UTC = Monday 28/09 00:30 local -> belongs to the week starting Monday 28/09
        val lateRun = activity(start = "2026-09-27T22:30:00Z", tz = "(GMT+01:00) Europe/Brussels")
        assertEquals(LocalDate.of(2026, 9, 28), ActivityTime.localDate(lateRun))
        assertEquals(LocalDate.of(2026, 9, 28), WeeklyVolume.weekStart(ActivityTime.localDate(lateRun)))
        // Sunday 27/09 21:30 UTC = Sunday 23:30 local -> week starting 21/09
        val sundayEvening = activity(start = "2026-09-27T21:30:00Z", tz = "(GMT+01:00) Europe/Brussels")
        assertEquals(LocalDate.of(2026, 9, 21), WeeklyVolume.weekStart(ActivityTime.localDate(sundayEvening)))
    }

    @Test
    fun `unknown timezone string falls back to the GMT offset`() {
        val a = activity(start = "2026-01-10T23:30:00Z", tz = "(GMT+02:00) Nowhere/Invalid")
        assertEquals(LocalDate.of(2026, 1, 11), ActivityTime.localDate(a))
    }

    @Test
    fun `weekly volume contains empty weeks and uses local dates`() {
        val runs = listOf(
            activity(start = "2026-09-27T21:30:00Z", distance = 10000f), // Sun 23:30 local, week 21/09
            activity(start = "2026-09-27T22:30:00Z", distance = 5000f),  // Mon 00:30 local, week 28/09
        )
        val weeks = WeeklyVolume.compute(runs, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28)),
            weeks.keys.toList(),
        )
        assertEquals(listOf(0.0, 0.0, 10.0, 5.0), weeks.values.toList())
    }

    @Test
    fun `aggregates are distance and time weighted and null when data is missing`() {
        val runs = listOf(
            activity(distance = 10000f, time = 3000, hr = 140f, cadence = 85f),
            activity(distance = 5000f, time = 1200, hr = 160f, cadence = null),
            activity(distance = 2000f, time = 600, hr = null, cadence = 80f),
        )
        val agg = RunAggregates.of(runs)
        // 17000 m / 4800 s = 3.54167 m/s = 282.35 s/km -> 4:42 (mean of speeds would give 4:37)
        assertEquals(17000.0 / 4800.0, agg.avgSpeedMs!!, 1e-9)
        assertEquals("4:42 /km", Format.pace(agg.avgSpeedMs))
        // (140*3000 + 160*1200) / 4200 = 145.714
        assertEquals(145.714, agg.avgHr!!, 1e-3)
        assertEquals(2, agg.hrCount)
        // (170*3000 + 160*600) / 3600 = 168.333 spm
        assertEquals(168.333, agg.avgCadenceSpm!!, 1e-3)

        val empty = RunAggregates.of(emptyList())
        assertNull(empty.avgSpeedMs)
        assertNull(empty.avgHr)
        assertNull(empty.avgCadenceSpm)

        val noHr = RunAggregates.of(listOf(activity(hr = null, cadence = null)))
        assertNull(noHr.avgHr)
        assertNull(noHr.avgCadenceSpm)
    }

    @Test
    fun `date range is inclusive on local dates`() {
        val range = DateRange(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27))
        assertEquals(true, range.contains(activity(start = "2026-09-27T21:30:00Z")))  // Sun 23:30 local
        assertEquals(false, range.contains(activity(start = "2026-09-27T22:30:00Z"))) // Mon 00:30 local
        assertEquals(true, range.contains(activity(start = "2026-09-20T22:30:00Z")))  // Mon 21/09 00:30 local
    }

    companion object {
        fun activity(
            start: String = "2026-09-01T08:00:00Z",
            tz: String = "(GMT+01:00) Europe/Brussels",
            distance: Float = 10000f,
            time: Int = 3000,
            hr: Float? = 150f,
            cadence: Float? = 85f,
            type: String = "Run",
            workoutType: Int? = null,
            id: Long = 1,
            name: String = "run",
            elapsed: Int = time,
        ) = Activity(
            id = id, name = name, distance = distance, movingTime = time, elapsedTime = elapsed,
            totalElevationGain = 0f, type = type, sportType = type, startDate = ZonedDateTime.parse(start), timezone = tz,
            averageSpeed = if (time > 0) distance / time else 0f, maxSpeed = 0f, averageHeartrate = hr,
            maxHeartrate = hr?.plus(15), averageCadence = cadence, averageWatts = null, maxWatts = null,
            weightedAverageWatts = null, kilojoules = null, deviceWatts = null, description = null, calories = null,
            sufferScore = null, hasHeartrate = hr != null, elevHigh = null, elevLow = null, gearId = null,
            startLatlng = null, endLatlng = null, isTrainer = false, isCommute = false, isManual = false,
            isFlagged = false, workoutType = workoutType, originalStartDate = null, laps = null, splits = null,
            bestEfforts = null,
        )
    }
}
