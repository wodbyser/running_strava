package com.running.strava.analysis.performance

import com.running.garmin.domain.GarminHeartRateZones
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.domain.SessionType
import java.time.LocalDate
import java.time.YearMonth

enum class ZoneSource(val text: String) {
    GARMIN("Garmin-instelling (laatst gesynchroniseerd)"),
    APP("[app-schatting] % van geschatte max-HR"),
}

/**
 * Five HR zones as floors. The floor value belongs to the higher zone (as in [HrZones]); anything below the zone 1
 * floor is counted in zone 1; zone 5 has no upper bound. The two sources are never mixed in one view.
 */
data class HrZoneSet(val source: ZoneSource, val floors: List<Int>) {
    init { require(floors.size == 5 && floors.zipWithNext().all { (a, b) -> a < b }) { "floors must be 5 increasing values" } }

    fun zoneOf(hr: Int): Int {
        for (z in 5 downTo 2) if (hr >= floors[z - 1]) return z
        return 1
    }

    companion object {
        /** Garmin `RUNNING` zones when complete, else the `DEFAULT` row, else null. */
        fun garmin(rows: List<GarminHeartRateZones>): HrZoneSet? {
            val row = rows.firstOrNull { it.sport.equals("RUNNING", true) && complete(it) }
                ?: rows.firstOrNull { it.sport.equals("DEFAULT", true) && complete(it) }
                ?: return null
            val f = listOf(row.zone1Floor!!, row.zone2Floor!!, row.zone3Floor!!, row.zone4Floor!!, row.zone5Floor!!)
            return if (f.zipWithNext().all { (a, b) -> a < b }) HrZoneSet(ZoneSource.GARMIN, f) else null
        }

        fun app(maxHr: Int): HrZoneSet = HrZoneSet(ZoneSource.APP, HrZones.pctMax(maxHr).map { it.minBpm })

        private fun complete(r: GarminHeartRateZones) =
            r.zone1Floor != null && r.zone2Floor != null && r.zone3Floor != null && r.zone4Floor != null && r.zone5Floor != null
    }
}

/** Time per bpm for one run, valid samples only. Small, so it can be cached and re-binned with any zone set. */
typealias HrHistogram = Map<Int, Double>

data class ZoneRun(
    val date: LocalDate,
    val histogram: HrHistogram,
    val sessionType: SessionType,
    val distanceMeters: Double,
)

data class PeriodZones(
    val label: String,
    val runs: Int,
    val minutes: List<Double>,
    val totalMinutes: Double,
    val easyPct: Double,
    val moderatePct: Double,
    val hardPct: Double,
    val fewRuns: Boolean,
)

data class SessionMix(
    val label: String,
    val runs: Int,
    val counts: Map<SessionType, Int>,
    val distanceShare: Map<SessionType, Double>,
    val fewRuns: Boolean,
)

/** #24 zone distribution and #40 intensity distribution. */
object ZoneDistribution {
    const val FEW_RUNS = 3

    /** Valid time per bpm. Weights by `dt`, so the sample interval does not matter. */
    fun histogram(samples: List<Sample>): HrHistogram = samples.groupBy { it.hr }.mapValues { (_, v) -> v.sumOf { it.dt } }

    fun seconds(histogram: HrHistogram, set: HrZoneSet): List<Double> {
        val out = DoubleArray(5)
        histogram.forEach { (bpm, sec) -> out[set.zoneOf(bpm) - 1] += sec }
        return out.toList()
    }

    private fun period(label: String, runs: List<ZoneRun>, set: HrZoneSet): PeriodZones {
        val total = DoubleArray(5)
        runs.forEach { r -> seconds(r.histogram, set).forEachIndexed { i, s -> total[i] += s } }
        val sum = total.sum()
        fun pct(v: Double) = if (sum > 0) v / sum * 100 else 0.0
        return PeriodZones(
            label = label, runs = runs.size, minutes = total.map { it / 60 }, totalMinutes = sum / 60,
            easyPct = pct(total[0] + total[1]), moderatePct = pct(total[2]), hardPct = pct(total[3] + total[4]),
            fewRuns = runs.size < FEW_RUNS,
        )
    }

    fun byWeek(runs: List<ZoneRun>, set: HrZoneSet): List<PeriodZones> =
        runs.groupBy { WeeklyVolume.weekStart(it.date) }.toSortedMap().map { (w, r) -> period(w.toString(), r, set) }

    fun byMonth(runs: List<ZoneRun>, set: HrZoneSet): List<PeriodZones> =
        runs.groupBy { YearMonth.from(it.date) }.toSortedMap().map { (m, r) -> period(m.toString(), r, set) }

    private fun mix(label: String, runs: List<ZoneRun>): SessionMix {
        val counts = SessionType.entries.associateWith { t -> runs.count { it.sessionType == t } }
        val total = runs.sumOf { it.distanceMeters }
        val share = SessionType.entries.associateWith { t ->
            if (total > 0) runs.filter { it.sessionType == t }.sumOf { it.distanceMeters } / total * 100 else 0.0
        }
        return SessionMix(label, runs.size, counts, share, runs.size < FEW_RUNS)
    }

    fun sessionMixByWeek(runs: List<ZoneRun>): List<SessionMix> =
        runs.groupBy { WeeklyVolume.weekStart(it.date) }.toSortedMap().map { (w, r) -> mix(w.toString(), r) }

    /** Blocks of 4 weeks counted back from the week of [lastWeek]; label is the first Monday of the block. */
    fun sessionMixBy4Weeks(runs: List<ZoneRun>, lastWeek: LocalDate): List<SessionMix> {
        val end = WeeklyVolume.weekStart(lastWeek)
        return runs.filter { WeeklyVolume.weekStart(it.date) <= end }.groupBy { r ->
            val weeksBack = java.time.temporal.ChronoUnit.WEEKS.between(WeeklyVolume.weekStart(r.date), end)
            end.minusWeeks((weeksBack / 4) * 4 + 3)
        }.toSortedMap().map { (start, r) -> mix(start.toString(), r) }
    }
}
