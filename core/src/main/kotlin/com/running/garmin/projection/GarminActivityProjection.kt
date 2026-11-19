package com.running.garmin.projection

import com.running.garmin.domain.GarminActivityRef
import com.running.strava.domain.Activity
import java.time.ZoneId

/** Maps a Garmin activity summary to the app's `Activity` for GARMIN-only tenants (design §9.4). Pure. */
object GarminActivityProjection {
    const val SOURCE = "GARMIN"
    private val zone: ZoneId = ZoneId.of("Europe/Brussels")
    private val months = listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")

    fun project(ref: GarminActivityRef): Activity {
        val type = typeOf(ref.typeKey)
        val start = ref.startTimeGmt.atZone(zone)
        val duration = ref.durationSeconds?.toInt() ?: 0
        val moving = (ref.movingSeconds?.toInt() ?: duration)
        val elapsed = (ref.elapsedSeconds?.toInt() ?: duration)
        val distance = (ref.distanceMeters ?: 0.0).toFloat()
        return Activity(
            id = ref.activityId,
            name = "${label(type)} ${start.dayOfMonth} ${months[start.monthValue - 1]}",
            distance = distance,
            movingTime = moving,
            elapsedTime = elapsed,
            totalElevationGain = 0f,
            type = type,
            sportType = type,
            startDate = start,
            timezone = zone.id,
            averageSpeed = if (moving > 0) distance / moving else 0f,
            maxSpeed = 0f,
            averageHeartrate = ref.averageHr?.toFloat(),
            maxHeartrate = ref.maxHr?.toFloat(),
            averageCadence = null,
            averageWatts = null,
            maxWatts = null,
            weightedAverageWatts = null,
            kilojoules = null,
            deviceWatts = null,
            description = null,
            calories = null,
            sufferScore = null,
            hasHeartrate = ref.averageHr != null,
            elevHigh = null,
            elevLow = null,
            gearId = null,
            startLatlng = null,
            endLatlng = null,
            isTrainer = false,
            isCommute = false,
            isManual = ref.isManual,
            isFlagged = false,
            workoutType = null,
            originalStartDate = null,
            laps = null,
            splits = null,
            bestEfforts = null,
            externalId = ref.activityId.toString(),
            deviceName = ref.manufacturer,
            source = SOURCE,
        )
    }

    fun typeOf(typeKey: String?): String {
        val key = typeKey.orEmpty()
        return when {
            "running" in key -> "Run"
            "walking" in key -> "Walk"
            "hiking" in key -> "Hike"
            "cycling" in key -> "Ride"
            else -> "Other"
        }
    }

    private fun label(type: String) = when (type) {
        "Run" -> "Hardlopen"
        "Walk" -> "Wandelen"
        "Hike" -> "Hiken"
        "Ride" -> "Fietsen"
        else -> "Activiteit"
    }
}
