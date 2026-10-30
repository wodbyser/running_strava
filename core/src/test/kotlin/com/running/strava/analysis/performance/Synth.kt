package com.running.strava.analysis.performance

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import java.time.ZonedDateTime

/** Synthetic streams and activities. Shapes only: no real identifiers, coordinates or values. */
object Synth {
    /** One stream row: seconds since the previous sample, metres since the previous sample, heart rate. */
    data class Row(val dt: Int, val dd: Double, val hr: Int, val grade: Float? = null, val cadence: Int? = null)

    fun fromRows(rows: List<Row>): ActivityStream {
        val time = mutableListOf(0)
        val dist = mutableListOf(0f)
        val hr = mutableListOf(rows.first().hr)
        val grade = mutableListOf(rows.first().grade ?: 0f)
        val cad = mutableListOf(rows.first().cadence ?: 0)
        var t = 0
        var d = 0.0
        rows.forEach { r ->
            t += r.dt
            d += r.dd
            time += t; dist += d.toFloat(); hr += r.hr; grade += r.grade ?: 0f; cad += r.cadence ?: 0
        }
        val hasGrade = rows.any { it.grade != null }
        val hasCad = rows.any { it.cadence != null }
        return ActivityStream(time, dist, null, null, hr, if (hasCad) cad else null, null, if (hasGrade) grade else null, null)
    }

    /**
     * Stream of [seconds] at sample interval [step]; speed and heart rate are functions of the sample time.
     * Heart rate alternates by -1/+1 around the given value, because 30 identical values count as a strap failure.
     */
    fun stream(
        seconds: Int,
        step: Int = 1,
        speed: (Int) -> Double = { 3.0 },
        hr: (Int) -> Int = { 150 },
        grade: ((Int) -> Float)? = null,
        cadence: ((Int) -> Int)? = null,
    ): ActivityStream = fromRows(
        (step..seconds step step).map { t ->
            Row(step, speed(t) * step, hr(t) + if ((t / step) % 2 == 0) -1 else 1, grade?.invoke(t), cadence?.invoke(t))
        },
    )

    fun activity(
        id: Long = 1,
        distance: Double = 9000.0,
        movingTime: Int = 3000,
        elevation: Double = 0.0,
        avgHr: Double? = 150.0,
        workoutType: Int? = null,
        trainer: Boolean = false,
        watts: Double? = null,
        deviceWatts: Boolean? = null,
        date: String = "2026-10-01T08:00:00Z",
        latlng: List<Double>? = null,
    ) = Activity(
        id = id, name = "run $id", distance = distance.toFloat(), movingTime = movingTime, elapsedTime = movingTime,
        totalElevationGain = elevation.toFloat(), type = "Run", sportType = "Run", startDate = ZonedDateTime.parse(date),
        timezone = "(GMT+01:00) Europe/Brussels", averageSpeed = (distance / movingTime).toFloat(), maxSpeed = 0f,
        averageHeartrate = avgHr?.toFloat(), maxHeartrate = avgHr?.plus(20)?.toFloat(), averageCadence = 84f,
        averageWatts = watts?.toFloat(), maxWatts = null, weightedAverageWatts = null, kilojoules = null, deviceWatts = deviceWatts,
        description = null, calories = null, sufferScore = null, hasHeartrate = avgHr != null, elevHigh = null, elevLow = null,
        gearId = null, startLatlng = latlng, endLatlng = null, isTrainer = trainer, isCommute = false, isManual = false,
        isFlagged = false, workoutType = workoutType, originalStartDate = null, laps = null, splits = null, bestEfforts = null,
    )
}
