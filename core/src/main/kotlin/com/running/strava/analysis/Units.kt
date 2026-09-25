package com.running.strava.analysis

import com.running.strava.domain.Activity
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Activity types treated as running everywhere in the app. */
val RUN_TYPES = setOf("Run", "TrailRun", "VirtualRun")

fun Activity.isRun(): Boolean = type in RUN_TYPES

/** Strava `workout_type` for runs: 0 = default, 1 = race, 2 = long run, 3 = workout. */
object WorkoutType {
    const val RACE = 1
    const val LONG_RUN = 2
    const val WORKOUT = 3
}

/**
 * Single source of truth for number formatting shown to the user or put in AI prompts.
 * All values are rounded (never truncated) so the same input always renders the same way.
 */
object Format {

    /** Pace in min/km from a speed in m/s, rounded to the nearest second, e.g. 4.68 m/s -> "3:34 /km". */
    fun pace(speedMs: Double?, unit: Boolean = true): String {
        if (speedMs == null || speedMs.isNaN() || speedMs <= 0.0 || speedMs.isInfinite()) return "-"
        return paceFromSeconds(1000.0 / speedMs, unit)
    }

    /** Pace from seconds per km, rounded to the nearest second. */
    fun paceFromSeconds(secondsPerKm: Double?, unit: Boolean = true): String {
        if (secondsPerKm == null || secondsPerKm.isNaN() || secondsPerKm <= 0.0 || secondsPerKm.isInfinite()) return "-"
        val s = secondsPerKm.roundToLong()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" + if (unit) " /km" else ""
    }

    /** Duration as "H:MM:SS" (>= 1 h) or "M:SS", never dropping seconds. */
    fun duration(totalSeconds: Number?): String {
        if (totalSeconds == null) return "-"
        val t = totalSeconds.toDouble()
        if (t.isNaN() || t < 0) return "-"
        val sec = t.roundToLong()
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    }

    /** Heart rate as a whole number of bpm, or "-" when missing. */
    fun hr(bpm: Number?, unit: Boolean = true): String {
        if (bpm == null || bpm.toDouble().isNaN()) return "-"
        return "${bpm.toDouble().roundToInt()}" + if (unit) " bpm" else ""
    }

    /** Distance in km with 2 decimals. */
    fun km(meters: Number, decimals: Int = 2): String = "%.${decimals}f km".format(meters.toDouble() / 1000)
}

/**
 * Strava reports running cadence per foot ("rpm", ~80-90 for runners). Runners and coaches use steps per
 * minute of both feet (~160-180). We convert once, here, for every run-type activity/lap.
 */
object Cadence {
    fun toSpm(perFoot: Number?, activityType: String): Double? {
        if (perFoot == null) return null
        val v = perFoot.toDouble()
        if (v.isNaN() || v <= 0.0) return null
        return if (activityType in RUN_TYPES) v * 2 else v
    }

    fun formatSpm(perFoot: Number?, activityType: String, unit: Boolean = true): String {
        val spm = toSpm(perFoot, activityType) ?: return "-"
        return "${spm.roundToInt()}" + if (unit) (if (activityType in RUN_TYPES) " spm" else " rpm") else ""
    }
}

/**
 * Strava stores `start_date` in UTC and a `timezone` string like "(GMT+01:00) Europe/Brussels".
 * All user-facing dates and all day/week/month grouping must use the activity's own local time, otherwise
 * a Monday 00:30 run lands in the previous week.
 */
object ActivityTime {
    private val zoneCache = java.util.concurrent.ConcurrentHashMap<String, ZoneId>()

    fun zoneOf(timezone: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId {
        if (timezone.isNullOrBlank()) return fallback
        return zoneCache.getOrPut(timezone) { parseZone(timezone) ?: fallback }
    }

    private fun parseZone(tz: String): ZoneId? {
        val idPart = tz.substringAfter(") ", "").trim()
        if (idPart.isNotEmpty()) {
            try { return ZoneId.of(idPart) } catch (_: DateTimeException) { }
        }
        val offset = Regex("""GMT([+-]\d{2}):(\d{2})""").find(tz)
        if (offset != null) {
            try { return ZoneOffset.of("${offset.groupValues[1]}:${offset.groupValues[2]}") } catch (_: DateTimeException) { }
        }
        try { return ZoneId.of(tz.trim()) } catch (_: DateTimeException) { }
        return null
    }

    fun local(activity: Activity): ZonedDateTime = activity.startDate.withZoneSameInstant(zoneOf(activity.timezone))

    fun localDate(activity: Activity) = local(activity).toLocalDate()
}
