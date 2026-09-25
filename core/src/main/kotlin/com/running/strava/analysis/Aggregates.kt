package com.running.strava.analysis

import com.running.strava.domain.Activity
import java.time.LocalDate

/**
 * Inclusive range of *local* calendar dates ("van ... t/m ..."). `null` bounds are open.
 * This is the one convention used for every date filter in the app.
 */
data class DateRange(val from: LocalDate?, val till: LocalDate?) {
    fun contains(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) && (till == null || !date.isAfter(till))

    fun contains(activity: Activity): Boolean = contains(ActivityTime.localDate(activity))

    companion object {
        val ALL = DateRange(null, null)
    }
}

/** Kilometres per ISO week (Monday start, activity-local date), including weeks with 0 km. */
object WeeklyVolume {
    fun weekStart(date: LocalDate): LocalDate = date.with(java.time.DayOfWeek.MONDAY)

    /** Returns an ordered map weekStart -> km covering every week between [firstDay] and [lastDay]. */
    fun compute(activities: List<Activity>, firstDay: LocalDate, lastDay: LocalDate): LinkedHashMap<LocalDate, Double> {
        val result = LinkedHashMap<LocalDate, Double>()
        var cursor = weekStart(firstDay)
        val end = weekStart(lastDay)
        while (!cursor.isAfter(end)) {
            result[cursor] = 0.0
            cursor = cursor.plusWeeks(1)
        }
        activities.forEach { a ->
            val w = weekStart(ActivityTime.localDate(a))
            if (w in result) result[w] = result.getValue(w) + a.distance / 1000.0
        }
        return result
    }
}

/**
 * Aggregates over a set of activities. Averages are weighted the way the underlying quantity accumulates:
 * - pace/speed = total distance / total moving time (NOT the mean of per-run speeds),
 * - heart rate and cadence = moving-time-weighted over the activities that actually have that sensor.
 * Averages are `null` when no activity has the data, so callers can show "-" instead of "NaN".
 */
data class RunAggregates(
    val count: Int,
    val totalDistanceMeters: Double,
    val totalMovingTimeSeconds: Long,
    val avgSpeedMs: Double?,
    val avgHr: Double?,
    val hrCount: Int,
    val avgCadenceSpm: Double?,
    val cadenceCount: Int,
) {
    companion object {
        fun of(activities: List<Activity>): RunAggregates {
            val dist = activities.sumOf { it.distance.toDouble() }
            val time = activities.sumOf { it.movingTime.toLong() }

            val withHr = activities.filter { it.averageHeartrate != null && it.averageHeartrate!! > 0 && it.movingTime > 0 }
            val hrTime = withHr.sumOf { it.movingTime.toLong() }
            val avgHr = if (hrTime > 0) withHr.sumOf { it.averageHeartrate!!.toDouble() * it.movingTime } / hrTime else null

            val withCad = activities.mapNotNull { a ->
                val spm = Cadence.toSpm(a.averageCadence, a.type)
                if (spm != null && a.movingTime > 0) spm to a.movingTime else null
            }
            val cadTime = withCad.sumOf { it.second.toLong() }
            val avgCad = if (cadTime > 0) withCad.sumOf { it.first * it.second } / cadTime else null

            return RunAggregates(
                count = activities.size,
                totalDistanceMeters = dist,
                totalMovingTimeSeconds = time,
                avgSpeedMs = if (time > 0 && dist > 0) dist / time else null,
                avgHr = avgHr,
                hrCount = withHr.size,
                avgCadenceSpm = avgCad,
                cadenceCount = withCad.size,
            )
        }
    }
}
