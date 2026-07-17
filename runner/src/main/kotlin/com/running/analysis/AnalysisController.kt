package com.running.analysis

import com.running.strava.spi.ActivityRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AnalysisController(
    private val activityRepository: ActivityRepository,
) {

    @GetMapping("/analysis/summary")
    fun summary(): Map<String, Any> {
        val activities = activityRepository.findAll()
        val runs = activities.filter { it.type in listOf("Run", "TrailRun", "VirtualRun") }

        if (runs.isEmpty()) return mapOf("message" to "No activities found. Run /fetch-all first.")

        val totalDistance = runs.sumOf { it.distance.toDouble() }
        val totalTime = runs.sumOf { it.movingTime.toLong() }
        val avgHeartrate = runs.mapNotNull { it.averageHeartrate }.average()
        val avgCadence = runs.mapNotNull { it.averageCadence }.average()
        val avgSpeed = runs.mapNotNull { it.averageSpeed }.average()

        return mapOf(
            "total_activities" to activities.size,
            "total_runs" to runs.size,
            "total_distance_km" to "%.1f".format(totalDistance / 1000),
            "total_time_hours" to "%.1f".format(totalTime / 3600.0),
            "average_heartrate" to "%.0f".format(avgHeartrate),
            "average_cadence" to "%.0f".format(avgCadence),
            "average_pace_min_per_km" to calculatePace(avgSpeed),
        )
    }

    @GetMapping("/analysis/activities")
    fun activities(): List<Map<String, Any?>> {
        return activityRepository.findAll()
            .sortedByDescending { it.startDate }
            .map { activity ->
                mapOf(
                    "id" to activity.id,
                    "name" to activity.name,
                    "date" to activity.startDate.toString(),
                    "type" to activity.type,
                    "distance_km" to "%.2f".format(activity.distance / 1000),
                    "duration_min" to "%.0f".format(activity.movingTime / 60.0),
                    "avg_hr" to activity.averageHeartrate,
                    "avg_pace" to calculatePace(activity.averageSpeed?.toDouble() ?: 0.0),
                    "elevation" to activity.totalElevationGain,
                )
            }
    }

    private fun calculatePace(speedMs: Double): String {
        if (speedMs <= 0) return "-"
        val paceSecondsPerKm = (1000 / speedMs).toInt()
        val min = paceSecondsPerKm / 60
        val sec = paceSecondsPerKm % 60
        return "${min}:${sec.toString().padStart(2, '0')} /km"
    }
}
