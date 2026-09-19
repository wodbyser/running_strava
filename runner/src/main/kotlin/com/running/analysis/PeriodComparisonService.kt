package com.running.analysis

import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.YearMonth
import java.time.ZonedDateTime

/**
 * Compares two arbitrary training periods (e.g. "6-8 months ago" vs "last 2 months") to estimate
 * whether running level (aerobic efficiency) has improved, both for easy/steady runs and for
 * interval work specifically.
 *
 * The core metric is "Efficiency Factor" (EF) = average speed (m/s) / average heart rate (bpm).
 * A higher EF means you cover more distance per heartbeat, i.e. better aerobic fitness/economy at
 * a comparable effort. This is a well known proxy metric (popularized by TrainingPeaks) and avoids
 * needing lactate/VO2max testing. It is sensitive to terrain, heat and fatigue, so it should be read
 * as a trend indicator rather than an absolute truth.
 */
@Service
class PeriodComparisonService(
    private val activityRepository: ActivityRepository,
) {

    private val runTypes = listOf("Run", "TrailRun", "VirtualRun")

    data class PeriodStats(
        val label: String,
        val from: ZonedDateTime,
        val till: ZonedDateTime,
        val activityCount: Int,
        val totalDistanceKm: Double,
        val totalTimeHours: Double,
        val easyRunCount: Int,
        val easyAvgPace: String,
        val easyAvgHr: Double?,
        val easyEf: Double?,
        val intervalSessionCount: Int,
        val intervalRepCount: Int,
        val intervalAvgPace: String,
        val intervalAvgHr: Double?,
        val intervalEf: Double?,
    )

    data class MonthlyPoint(
        val month: String,
        val easyEf: Double?,
        val intervalEf: Double?,
        val easyRunCount: Int,
        val intervalRepCount: Int,
    )

    data class ComparisonResult(
        val periodA: PeriodStats,
        val periodB: PeriodStats,
        val easyEfChangePct: Double?,
        val intervalEfChangePct: Double?,
        val easyPaceAtRefHr: Pair<String, String>?,
        val intervalPaceAtRefHr: Pair<String, String>?,
        val verdict: String,
        val monthlyTrend: List<MonthlyPoint>,
    )

    fun compare(
        fromA: ZonedDateTime,
        tillA: ZonedDateTime,
        fromB: ZonedDateTime,
        tillB: ZonedDateTime,
        labelA: String = "Periode A",
        labelB: String = "Periode B",
    ): ComparisonResult {
        val allRuns = activityRepository.findAll().filter { it.type in runTypes }

        val periodA = buildStats(allRuns, fromA, tillA, labelA)
        val periodB = buildStats(allRuns, fromB, tillB, labelB)

        val easyChange = pctChange(periodA.easyEf, periodB.easyEf)
        val intervalChange = pctChange(periodA.intervalEf, periodB.intervalEf)

        val easyPaceAtRef = refHrPaceComparison(periodA.easyEf, periodB.easyEf)
        val intervalPaceAtRef = refHrPaceComparison(periodA.intervalEf, periodB.intervalEf)

        val verdict = buildVerdict(easyChange, intervalChange)

        val monthlyTrend = buildMonthlyTrend(allRuns)

        return ComparisonResult(
            periodA = periodA,
            periodB = periodB,
            easyEfChangePct = easyChange,
            intervalEfChangePct = intervalChange,
            easyPaceAtRefHr = easyPaceAtRef,
            intervalPaceAtRefHr = intervalPaceAtRef,
            verdict = verdict,
            monthlyTrend = monthlyTrend,
        )
    }

    private fun buildStats(allRuns: List<Activity>, from: ZonedDateTime, till: ZonedDateTime, label: String): PeriodStats {
        val runs = allRuns.filter { !it.startDate.isBefore(from) && it.startDate.isBefore(till) }

        val totalDistanceKm = runs.sumOf { it.distance.toDouble() } / 1000
        val totalTimeHours = runs.sumOf { it.movingTime.toLong() } / 3600.0

        val lapsByActivity = runs.associate { it.id to activityRepository.findLaps(it.id) }

        val intervalActivities = runs.filter { hasIntervalLaps(lapsByActivity[it.id] ?: emptyList()) }
        val easyActivities = runs.filter { it !in intervalActivities }

        val easyAvgSpeed = easyActivities.mapNotNull { it.averageSpeed.takeIf { s -> s > 0 } }.average().takeIf { !it.isNaN() }
        val easyAvgHr = easyActivities.mapNotNull { it.averageHeartrate }.average().takeIf { !it.isNaN() }
        val easyEf = if (easyAvgSpeed != null && easyAvgHr != null && easyAvgHr > 0) easyAvgSpeed / easyAvgHr else null

        val intervalLaps = intervalActivities.flatMap { activity ->
            val laps = lapsByActivity[activity.id] ?: emptyList()
            classifyLaps(laps).filter { it.second }.map { it.first }
        }
        val intervalAvgSpeed = intervalLaps.mapNotNull { it.averageSpeed.takeIf { s -> s > 0 } }.average().takeIf { !it.isNaN() }
        val intervalAvgHr = intervalLaps.mapNotNull { it.averageHeartrate }.average().takeIf { !it.isNaN() }
        val intervalEf = if (intervalAvgSpeed != null && intervalAvgHr != null && intervalAvgHr > 0) intervalAvgSpeed / intervalAvgHr else null

        return PeriodStats(
            label = label,
            from = from,
            till = till,
            activityCount = runs.size,
            totalDistanceKm = totalDistanceKm,
            totalTimeHours = totalTimeHours,
            easyRunCount = easyActivities.size,
            easyAvgPace = formatPaceFromSpeed(easyAvgSpeed),
            easyAvgHr = easyAvgHr,
            easyEf = easyEf,
            intervalSessionCount = intervalActivities.size,
            intervalRepCount = intervalLaps.size,
            intervalAvgPace = formatPaceFromSpeed(intervalAvgSpeed),
            intervalAvgHr = intervalAvgHr,
            intervalEf = intervalEf,
        )
    }

    fun buildMonthlyTrend(allRuns: List<Activity>): List<MonthlyPoint> {
        val byMonth = allRuns.groupBy { YearMonth.from(it.startDate) }.toSortedMap()

        return byMonth.map { (month, runs) ->
            val lapsByActivity = runs.associate { it.id to activityRepository.findLaps(it.id) }
            val intervalActivities = runs.filter { hasIntervalLaps(lapsByActivity[it.id] ?: emptyList()) }
            val easyActivities = runs.filter { it !in intervalActivities }

            val easyAvgSpeed = easyActivities.mapNotNull { it.averageSpeed.takeIf { s -> s > 0 } }.average().takeIf { !it.isNaN() }
            val easyAvgHr = easyActivities.mapNotNull { it.averageHeartrate }.average().takeIf { !it.isNaN() }
            val easyEf = if (easyAvgSpeed != null && easyAvgHr != null && easyAvgHr > 0) easyAvgSpeed / easyAvgHr else null

            val intervalLaps = intervalActivities.flatMap { activity ->
                val laps = lapsByActivity[activity.id] ?: emptyList()
                classifyLaps(laps).filter { it.second }.map { it.first }
            }
            val intervalAvgSpeed = intervalLaps.mapNotNull { it.averageSpeed.takeIf { s -> s > 0 } }.average().takeIf { !it.isNaN() }
            val intervalAvgHr = intervalLaps.mapNotNull { it.averageHeartrate }.average().takeIf { !it.isNaN() }
            val intervalEf = if (intervalAvgSpeed != null && intervalAvgHr != null && intervalAvgHr > 0) intervalAvgSpeed / intervalAvgHr else null

            MonthlyPoint(
                month = month.toString(),
                easyEf = easyEf,
                intervalEf = intervalEf,
                easyRunCount = easyActivities.size,
                intervalRepCount = intervalLaps.size,
            )
        }
    }

    /** Same heuristic used on the activity detail page: 1-D 2-means clustering on lap speed to
     * separate fast interval reps from easy/recovery laps. Returns pairs of (lap, isInterval). */
    private fun classifyLaps(laps: List<Lap>): List<Pair<Lap, Boolean>> {
        val flags = LapClassifier.classifyIntervals(laps)
        return laps.zip(flags)
    }

    private fun hasIntervalLaps(laps: List<Lap>): Boolean {
        return LapClassifier.hasIntervals(laps)
    }

    private fun pctChange(oldValue: Double?, newValue: Double?): Double? {
        if (oldValue == null || newValue == null || oldValue == 0.0) return null
        return ((newValue - oldValue) / oldValue) * 100
    }

    /** Normalizes both periods' EF to a shared reference heart rate so the resulting paces are
     * directly comparable ("what pace could I run at the same effort in both periods"). */
    private fun refHrPaceComparison(efA: Double?, efB: Double?): Pair<String, String>? {
        if (efA == null || efB == null) return null
        val refHr = 150.0
        val speedA = efA * refHr
        val speedB = efB * refHr
        return formatPaceFromSpeed(speedA) to formatPaceFromSpeed(speedB)
    }

    private fun buildVerdict(easyChangePct: Double?, intervalChangePct: Double?): String {
        val parts = mutableListOf<String>()
        if (easyChangePct != null) {
            parts.add(when {
                easyChangePct > 3 -> "je aerobe efficiëntie bij rustige lopen is verbeterd (+${"%.1f".format(easyChangePct)}%)"
                easyChangePct < -3 -> "je aerobe efficiëntie bij rustige lopen is gedaald (${"%.1f".format(easyChangePct)}%)"
                else -> "je aerobe efficiëntie bij rustige lopen is stabiel gebleven (${"%.1f".format(easyChangePct)}%)"
            })
        }
        if (intervalChangePct != null) {
            parts.add(when {
                intervalChangePct > 3 -> "je intervalefficiëntie is verbeterd (+${"%.1f".format(intervalChangePct)}%)"
                intervalChangePct < -3 -> "je intervalefficiëntie is gedaald (${"%.1f".format(intervalChangePct)}%)"
                else -> "je intervalefficiëntie is stabiel gebleven (${"%.1f".format(intervalChangePct)}%)"
            })
        }
        if (parts.isEmpty()) return "Onvoldoende data (HR + pace) in een van beide periodes om te vergelijken."
        return "Vergeleken met de eerste periode " + parts.joinToString(" en ") + "."
    }

    private fun formatPaceFromSpeed(speedMs: Double?): String {
        if (speedMs == null || speedMs <= 0 || speedMs.isNaN()) return "-"
        val paceSeconds = (1000 / speedMs).toInt()
        return "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')} /km"
    }
}
