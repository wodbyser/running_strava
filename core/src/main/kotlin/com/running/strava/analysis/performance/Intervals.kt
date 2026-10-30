package com.running.strava.analysis.performance

import com.running.strava.analysis.Cadence
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.speedMs
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.domain.WorkoutStructure
import kotlin.math.abs

enum class RecoveryProtocol(val text: String) {
    STANDING("staand"),
    WALKING("wandelen"),
    JOGGING("joggen"),
}

/** #28 heart rate recovery after a rep end. Proxy only (design 3.10). Protocol classes are never mixed. */
object HeartRateRecovery {
    const val LAST_SECONDS = 15.0
    const val AVG_SECONDS = 5.0
    const val RECOVERY_SECONDS = 60.0
    const val MAX_GAP_SECONDS = 5.0
    const val MAX_SAMPLE_INTERVAL_SECONDS = 5.0

    data class Measurement(
        val endSecond: Double,
        val hrEnd: Double,
        val hr60: Double,
        val hrr60: Double,
        val protocol: RecoveryProtocol,
    )

    sealed interface Outcome {
        data class Ok(val measurement: Measurement) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    fun measure(stream: ActivityStream, endSecond: Double, maxHr: Int?): Outcome {
        val time = stream.time ?: return Outcome.Rejected("geen tijdstroom")
        val hr = stream.heartrate ?: return Outcome.Rejected("geen hartslagstroom")
        val dist = stream.distance ?: return Outcome.Rejected("geen afstandsstroom")
        val n = minOf(time.size, hr.size, dist.size)
        if (n < 2) return Outcome.Rejected("te weinig samples")
        val intervals = (1 until n).map { (time[it] - time[it - 1]).toDouble() }.filter { it > 0 }
        val median = Stats.median(intervals)
        if (median == null || median > MAX_SAMPLE_INTERVAL_SECONDS) return Outcome.Rejected("samplefrequentie te laag (mediaan ${median?.toInt()} s)")

        val e = endSecond
        val lo = e - LAST_SECONDS
        val hi = e + RECOVERY_SECONDS + 3
        val idx = (0 until n).filter { time[it] >= lo && time[it] <= hi }
        if (idx.isEmpty()) return Outcome.Rejected("geen samples rond het einde van de rep")
        val points = listOf(lo) + idx.map { time[it].toDouble() } + listOf(hi)
        if (points.zipWithNext().any { (a, b) -> b - a > MAX_GAP_SECONDS }) return Outcome.Rejected("gat in de hartslagdata groter dan 5 s")
        if (idx.any { hr[it] < CleanRun.MIN_HR || hr[it] > CleanRun.MAX_HR }) return Outcome.Rejected("hartslag buiten 40-230 bpm")

        val endIdx = idx.filter { time[it] <= e }
        if (endIdx.isEmpty()) return Outcome.Rejected("geen samples vlak voor het einde van de rep")
        val hrEnd = endIdx.maxOf { j ->
            val w = endIdx.filter { time[it] > time[j] - AVG_SECONDS && time[it] <= time[j] }
            w.map { hr[it].toDouble() }.average()
        }
        val after = idx.filter { time[it] >= e + 57 && time[it] <= e + 63 }
        if (after.isEmpty()) return Outcome.Rejected("geen samples 60 s na de rep")
        val hr60 = after.map { hr[it].toDouble() }.average()

        if (maxHr != null && hrEnd < maxHr * HrZones.HARD_EFFORT_FRACTION) return Outcome.Rejected("rep te licht (einde-HR onder 85 % van max-HR)")

        fun distAt(t: Double): Double {
            var k = 0
            while (k + 1 < n && time[k + 1] <= t) k++
            if (k + 1 >= n) return dist[n - 1].toDouble()
            val t0 = time[k].toDouble(); val t1 = time[k + 1].toDouble()
            val d0 = dist[k].toDouble(); val d1 = dist[k + 1].toDouble()
            return if (t1 > t0) d0 + (d1 - d0) * (t - t0) / (t1 - t0) else d0
        }
        val speeds = (0 until 6).map { s -> (distAt(e + 10.0 * (s + 1)) - distAt(e + 10.0 * s)) / 10.0 }
        val protocol = when {
            speeds.all { it < 0.5 } -> RecoveryProtocol.STANDING
            speeds.all { it < 2.0 } -> RecoveryProtocol.WALKING
            else -> RecoveryProtocol.JOGGING
        }
        return Outcome.Ok(Measurement(e, hrEnd, hr60, hrEnd - hr60, protocol))
    }

    data class Summary(val protocol: RecoveryProtocol, val n: Int, val medianHrr60: Double?, val enough: Boolean)

    fun summarize(measurements: List<Measurement>, minN: Int): List<Summary> =
        RecoveryProtocol.entries.map { p ->
            val m = measurements.filter { it.protocol == p }
            Summary(p, m.size, Stats.median(m.map { it.hrr60 }), m.size >= minN)
        }
}

/** #27 interval performance. Reps are "automatisch herkend" (LapClassifier heuristic). */
object IntervalPerformance {
    data class Rep(
        val number: Int,
        val distanceMeters: Double,
        val seconds: Int,
        val speed: Double,
        val avgHr: Double?,
        val maxHr: Double?,
        val cadenceSpm: Double?,
        val recoverySeconds: Int?,
        val hrr: HeartRateRecovery.Measurement?,
        val hrrNote: String?,
    )

    data class Session(
        /** E.g. "6 x 1000 m" or "6 x 3:00"; null for ladders and mixed rep sizes (never pooled). */
        val signature: String?,
        val reps: List<Rep>,
        val paceFadePct: Double?,
        val speedCv: Double?,
        val hrRiseBpm: Double?,
        val hasStatistics: Boolean,
    )

    const val MIN_REPS_FOR_STATISTICS = 3

    fun analyze(laps: List<Lap>, workoutType: Int?, stream: ActivityStream?, maxHr: Int?, elevationPerKm: Double? = null): Session? {
        if (SessionClassifier.classify(laps, workoutType, elevationPerKm) != SessionType.INTERVAL) return null
        val kinds = LapClassifier.classify(laps, workoutType, elevationPerKm)
        val efforts = LapClassifier.repEfforts(laps, kinds).filter { it.lap.movingTime >= LapClassifier.MIN_REP_SECONDS }
        if (efforts.isEmpty()) return null
        val time = stream?.time
        val reps = efforts.mapIndexed { i, e ->
            val lap = e.lap
            var hrr: HeartRateRecovery.Measurement? = null
            var note: String? = null
            if (stream != null && time != null && time.isNotEmpty()) {
                val end = time[minOf(laps[e.last].endIndex, time.size - 1).coerceAtLeast(0)].toDouble()
                when (val o = HeartRateRecovery.measure(stream, end, maxHr)) {
                    is HeartRateRecovery.Outcome.Ok -> hrr = o.measurement
                    is HeartRateRecovery.Outcome.Rejected -> note = o.reason
                }
            } else note = "geen stroomdata"
            Rep(
                number = i + 1, distanceMeters = lap.distance.toDouble(), seconds = lap.movingTime, speed = lap.speedMs(),
                avgHr = lap.averageHeartrate?.toDouble(), maxHr = lap.maxHeartrate?.toDouble(),
                cadenceSpm = Cadence.toSpm(lap.averageCadence, "Run"),
                recoverySeconds = laps.getOrNull(e.last + 1)?.movingTime,
                hrr = hrr, hrrNote = note,
            )
        }
        val stats = reps.size >= MIN_REPS_FOR_STATISTICS
        val first = reps.first()
        val last = reps.last()
        return Session(
            signature = signature(reps),
            reps = reps,
            paceFadePct = if (stats && first.speed > 0 && last.speed > 0) (1000 / last.speed - 1000 / first.speed) / (1000 / first.speed) * 100 else null,
            speedCv = if (stats) Stats.cv(reps.map { it.speed }) else null,
            hrRiseBpm = if (stats && first.avgHr != null && last.avgHr != null) last.avgHr - first.avgHr else null,
            hasStatistics = stats,
        )
    }

    private fun within(values: List<Double>): Boolean {
        val m = Stats.median(values) ?: return false
        return m > 0 && values.all { abs(it - m) / m <= WorkoutStructure.SAME_SIZE_TOLERANCE }
    }

    fun signature(reps: List<Rep>): String? {
        val dist = reps.map { it.distanceMeters }
        val dur = reps.map { it.seconds.toDouble() }
        return when {
            within(dist) -> "${reps.size} x ${(Math.round(Stats.median(dist)!! / 50.0) * 50)} m"
            within(dur) -> Stats.median(dur)!!.let { "${reps.size} x ${(it / 60).toInt()}:${(it.toInt() % 60).toString().padStart(2, '0')}" }
            else -> null
        }
    }
}
