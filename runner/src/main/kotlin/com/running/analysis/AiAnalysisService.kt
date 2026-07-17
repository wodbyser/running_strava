package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.DayOfWeek

@Service
class AiAnalysisService(
    private val activityRepository: ActivityRepository,
) {

    fun buildTrainingContext(): String {
        val activities = activityRepository.findAll()
            .filter { it.type in listOf("Run", "TrailRun", "VirtualRun") }
            .sortedBy { it.startDate }

        if (activities.isEmpty()) return "No training data available."

        return buildString {
            appendLine("TRAININGSHISTORIEK")
            appendLine("=" .repeat(50))

            val recentActivities = activities.takeLast(30)
            appendLine("\n## Laatste ${recentActivities.size} activiteiten\n")
            recentActivities.forEachIndexed { index, activity ->
                appendLine("${index + 1}. ${activity.startDate.toLocalDate()}")
                appendLine("   Type: ${activity.type}")
                appendLine("   Naam: ${activity.name}")
                appendLine("   Afstand: ${"%.2f".format(activity.distance / 1000)} km")
                appendLine("   Tijd: ${activity.movingTime / 60} min")
                appendLine("   Pace: ${calculatePace(activity.averageSpeed?.toDouble() ?: 0.0)}")
                activity.averageHeartrate?.let { appendLine("   Gem. HR: $it bpm") }
                activity.maxHeartrate?.let { appendLine("   Max HR: $it bpm") }
                activity.averageCadence?.let { appendLine("   Cadence: $it rpm") }
                activity.averageWatts?.let { appendLine("   Vermogen: $it W") }
                appendLine("   Elevation: ${activity.totalElevationGain} m")
                appendLine()
            }

            appendLine("\n## Totaaloverzicht\n")
            val totalDistance = activities.sumOf { it.distance.toDouble() }
            val totalTime = activities.sumOf { it.movingTime.toLong() }
            val avgHeartrate = activities.mapNotNull { it.averageHeartrate }.average()
            appendLine("Totale afstand: ${"%.1f".format(totalDistance / 1000)} km")
            appendLine("Totale tijd: ${totalTime / 3600} uur")
            appendLine("Gemiddelde HR: ${"%.0f".format(avgHeartrate)} bpm")
            appendLine("Aantal runs: ${activities.size}")

            val weeklyMileage = activities
                .groupBy { it.startDate.toLocalDate().with(DayOfWeek.MONDAY) }
                .mapValues { it.value.sumOf { a -> a.distance.toDouble() } }
            appendLine("\n## Wekelijkse kilometers\n")
            weeklyMileage.toSortedMap().forEach { (week, distance) ->
                appendLine("  $week: ${"%.1f".format(distance / 1000)} km")
            }
        }
    }

    private fun calculatePace(speedMs: Double): String {
        if (speedMs <= 0) return "-"
        val paceSeconds = (1000 / speedMs).toInt()
        return "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')} /km"
    }
}
