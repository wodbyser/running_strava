package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Format
import com.running.strava.analysis.RUN_TYPES
import com.running.strava.analysis.RunAggregates
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
        val runs = activities.filter { it.type in RUN_TYPES }

        if (runs.isEmpty()) return mapOf("message" to "No activities found. Run /fetch-all first.")

        // Weighted: pace = total distance / total moving time; HR and cadence time-weighted over runs with data.
        val agg = RunAggregates.of(runs)

        return mapOf(
            "total_activities" to activities.size,
            "total_runs" to runs.size,
            "total_distance_km" to "%.1f".format(agg.totalDistanceMeters / 1000),
            "total_time_hours" to "%.1f".format(agg.totalMovingTimeSeconds / 3600.0),
            "average_heartrate" to (agg.avgHr?.let { "%.0f".format(it) } ?: "-"),
            "average_cadence_spm" to (agg.avgCadenceSpm?.let { "%.0f".format(it) } ?: "-"),
            "average_pace_min_per_km" to Format.pace(agg.avgSpeedMs),
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
                    "date_local" to ActivityTime.local(activity).toLocalDateTime().toString(),
                    "type" to activity.type,
                    "distance_km" to "%.2f".format(activity.distance / 1000),
                    "duration_min" to "%.0f".format(activity.movingTime / 60.0),
                    "avg_hr" to activity.averageHeartrate,
                    "avg_pace" to Format.pace(activity.averageSpeed.toDouble()),
                    "elevation" to activity.totalElevationGain,
                )
            }
    }
}
