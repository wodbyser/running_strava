package com.running.strava.analysis.performance

import com.running.strava.analysis.WeeklyVolume
import com.running.strava.domain.SessionType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the comparable run finder needs to know about one run. No score field exists on purpose (rule 6). */
data class RunProfile(
    val activityId: Long,
    val date: LocalDate,
    val sessionType: SessionType,
    val isTrainer: Boolean,
    val distanceMeters: Double,
    val movingSeconds: Int,
    val terrain: TerrainClass,
    val elevationPerKm: Double,
    val hasHr: Boolean,
    val avgHr: Double?,
    val zone: Int?,
    val tempC: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val eligible: Boolean,
    val gapSpeed: Double?,
    val efWindow: Double?,
    val driftPct: Double?,
) {
    val speed: Double get() = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0
}

enum class Criterion(val text: String) {
    HR("gemiddelde HR binnen 5 bpm"),
    ZONE("zelfde HR-zone"),
    TEMPERATURE("temperatuur binnen 5 graden C"),
    START("startpunt binnen 1 km"),
    ELEVATION("hoogtemeters per km binnen 5"),
}

enum class Agreement { AGREE, DIFFER, UNKNOWN }

enum class ExclusionReason(val text: String) {
    SESSION_TYPE("sessietype"),
    TRAINER("buiten tegenover trainer"),
    DISTANCE("afstand"),
    TERRAIN("terreinklasse"),
    HR_PRESENCE("hartslag aanwezig tegenover niet"),
    INELIGIBLE("handmatig of gemarkeerd"),
}

object ComparableRuns {
    const val MIN_MATCHES = 3
    const val MAX_RESULTS = 10
    const val MIN_DISTANCE_RATIO = 0.8
    const val MAX_DISTANCE_RATIO = 1.25

    data class Match(
        val run: RunProfile,
        val criteria: Map<Criterion, Agreement>,
        val agreeing: Int,
        val dayDistance: Long,
    )

    data class Result(
        val matches: List<Match>,
        val candidates: Int,
        val excluded: Map<ExclusionReason, Int>,
        val enough: Boolean,
        val message: String?,
    )

    fun exclusion(ref: RunProfile, c: RunProfile): ExclusionReason? {
        val ratio = if (ref.distanceMeters > 0) c.distanceMeters / ref.distanceMeters else Double.MAX_VALUE
        return when {
            !c.eligible -> ExclusionReason.INELIGIBLE
            c.sessionType != ref.sessionType -> ExclusionReason.SESSION_TYPE
            c.isTrainer != ref.isTrainer -> ExclusionReason.TRAINER
            ratio < MIN_DISTANCE_RATIO || ratio > MAX_DISTANCE_RATIO -> ExclusionReason.DISTANCE
            c.terrain != ref.terrain -> ExclusionReason.TERRAIN
            c.hasHr != ref.hasHr -> ExclusionReason.HR_PRESENCE
            else -> null
        }
    }

    fun criteria(ref: RunProfile, c: RunProfile): Map<Criterion, Agreement> {
        fun cmp(a: Double?, b: Double?, limit: Double) =
            if (a == null || b == null) Agreement.UNKNOWN else if (abs(a - b) <= limit) Agreement.AGREE else Agreement.DIFFER
        val start = if (ref.startLat == null || ref.startLon == null || c.startLat == null || c.startLon == null) Agreement.UNKNOWN
        else if (haversineMeters(ref.startLat, ref.startLon, c.startLat, c.startLon) <= 1000.0) Agreement.AGREE else Agreement.DIFFER
        return linkedMapOf(
            Criterion.HR to cmp(ref.avgHr, c.avgHr, 5.0),
            Criterion.ZONE to if (ref.zone == null || c.zone == null) Agreement.UNKNOWN else if (ref.zone == c.zone) Agreement.AGREE else Agreement.DIFFER,
            Criterion.TEMPERATURE to cmp(ref.tempC, c.tempC, 5.0),
            Criterion.START to start,
            Criterion.ELEVATION to cmp(ref.elevationPerKm, c.elevationPerKm, 5.0),
        )
    }

    fun find(reference: RunProfile, all: List<RunProfile>): Result {
        val others = all.filter { it.activityId != reference.activityId }
        val excluded = linkedMapOf<ExclusionReason, Int>()
        val matches = ArrayList<Match>()
        for (c in others) {
            val why = exclusion(reference, c)
            if (why != null) { excluded[why] = (excluded[why] ?: 0) + 1; continue }
            val crit = criteria(reference, c)
            matches += Match(c, crit, crit.values.count { it == Agreement.AGREE }, abs(ChronoUnit.DAYS.between(reference.date, c.date)))
        }
        val ordered = matches.sortedWith(compareByDescending<Match> { it.agreeing }.thenBy { it.dayDistance }.thenBy { it.run.activityId })
        val enough = ordered.size >= MIN_MATCHES
        val top = excluded.maxByOrNull { it.value }?.key
        val message = if (enough) null
        else "te weinig vergelijkbare runs (n = ${ordered.size})" + (top?.let { "; meeste runs vielen af op: ${it.text}" } ?: "")
        return Result(ordered.take(MAX_RESULTS), others.size, excluded, enough, message)
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** #46 training consistency: neutral numbers over complete weeks only, no target and no score. */
object Consistency {
    data class Gap(val from: LocalDate, val to: LocalDate, val days: Long)

    data class Window(
        val weeks: Int,
        val runs: Int,
        val runsPerWeekDistribution: Map<String, Int>,
        val shareWeeksWithRun: Double,
        val longestGapDays: Long?,
        val gaps7Plus: List<Gap>,
        val longestStreakWeeks: Int,
        val weeklyDistanceCv: Double?,
        val medianWeeklyKm: Double?,
        val medianRunsPerWeek: Double?,
    )

    data class Week(val start: LocalDate, val runs: Int, val km: Double)

    data class Result(
        val completeWeeksOfHistory: Int,
        val enough: Boolean,
        val shortWindow: Window?,
        val longWindow: Window?,
        val currentWeekStart: LocalDate,
        val message: String?,
        /** Every complete week of the last 52 (or fewer when the history is shorter), oldest first, empty weeks included. */
        val weeks: List<Week> = emptyList(),
    )

    /**
     * [runs]: (local date, distance in metres). Only complete weeks (Monday to Sunday before [today]'s week) count.
     * Two windows of [shortWeeks] and [longWeeks]; the weekly list covers the long window.
     */
    fun analyze(runs: List<Pair<LocalDate, Double>>, today: LocalDate, minWeeks: Int = 8, shortWeeks: Int = 12, longWeeks: Int = 52): Result {
        val currentWeek = WeeklyVolume.weekStart(today)
        val done = runs.filter { it.first.isBefore(currentWeek) }
        if (done.isEmpty()) return Result(0, false, null, null, currentWeek, "onvoldoende data (0 weken)")
        val firstWeek = WeeklyVolume.weekStart(done.minOf { it.first })
        val history = ChronoUnit.WEEKS.between(firstWeek, currentWeek).toInt()
        if (history < minWeeks) return Result(history, false, null, null, currentWeek, "onvoldoende data ($history weken, minimum $minWeeks)")
        fun window(weeks: Int): Window {
            val w = minOf(weeks, history)
            val start = currentWeek.minusWeeks(w.toLong())
            val inWin = done.filter { !it.first.isBefore(start) }
            val perWeek = (0 until w).map { i ->
                val ws = start.plusWeeks(i.toLong())
                inWin.filter { WeeklyVolume.weekStart(it.first) == ws }
            }
            val counts = perWeek.map { it.size }
            val dist = LinkedHashMap<String, Int>()
            listOf("0", "1", "2", "3", "4+").forEach { dist[it] = 0 }
            counts.forEach { c -> val k = if (c >= 4) "4+" else c.toString(); dist[k] = dist.getValue(k) + 1 }
            val dates = inWin.map { it.first }.sorted()
            val gaps = dates.zipWithNext().map { (a, b) -> Gap(a, b, ChronoUnit.DAYS.between(a, b)) }
            var streak = 0
            var best = 0
            counts.forEach { c -> if (c > 0) { streak++; best = maxOf(best, streak) } else streak = 0 }
            val km = perWeek.map { l -> l.sumOf { it.second } / 1000 }
            return Window(
                weeks = w, runs = inWin.size, runsPerWeekDistribution = dist,
                shareWeeksWithRun = counts.count { it > 0 }.toDouble() / w,
                longestGapDays = gaps.maxOfOrNull { it.days },
                gaps7Plus = gaps.filter { it.days >= 7 },
                longestStreakWeeks = best,
                weeklyDistanceCv = Stats.cv(km),
                medianWeeklyKm = Stats.median(km),
                medianRunsPerWeek = Stats.median(counts.map { it.toDouble() }),
            )
        }
        val shown = minOf(longWeeks, history)
        val weeks = (shown downTo 1).map { back ->
            val ws = currentWeek.minusWeeks(back.toLong())
            val inWeek = done.filter { WeeklyVolume.weekStart(it.first) == ws }
            Week(ws, inWeek.size, inWeek.sumOf { it.second } / 1000)
        }
        return Result(history, true, window(shortWeeks), window(longWeeks), currentWeek, null, weeks)
    }
}
