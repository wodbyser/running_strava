package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.DateRange
import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.EfficiencyFactor
import com.running.strava.analysis.EfficiencyFactor.Sample
import com.running.strava.analysis.Format
import com.running.strava.analysis.RUN_TYPES
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * Compares two training periods on "Efficiency Factor" (EF = speed / heart rate, metres per heartbeat), for
 * easy runs and for interval reps separately.
 *
 * EF is a trend PROXY (TrainingPeaks), sensitive to terrain, heat, fatigue and HR-strap quality. Therefore:
 * - EF is pooled as total distance / total heartbeats over items that have BOTH speed and HR;
 * - "easy" = sessions where the app detected no structure (not races, tempo, strides or intervals), road runs
 *   only (no trail, no treadmill);
 * - interval EF uses only reps of >= 30 s from sessions typed INTERVAL;
 * - the verdict needs a minimum number of sessions and a difference larger than the spread between sessions.
 */
@Service
class PeriodComparisonService(
    private val activityRepository: ActivityRepository,
) {

    data class PeriodStats(
        val label: String,
        val from: LocalDate?,
        val till: LocalDate?,
        val activityCount: Int,
        val totalDistanceKm: Double,
        val totalTimeHours: Double,
        /** Easy/steady road runs WITH heart rate (the EF population). */
        val easyRunCount: Int,
        val easyRunsWithoutHr: Int,
        val excludedRaces: Int,
        val excludedTempo: Int,
        val excludedStrides: Int,
        val excludedTrailOrTreadmill: Int,
        val easyAvgPace: String,
        val easyAvgHr: Double?,
        val easyEf: Double?,
        val easyEfSd: Double?,
        /** Interval sessions with HR on the reps. */
        val intervalSessionCount: Int,
        val intervalRepCount: Int,
        val intervalAvgRepSeconds: Double?,
        val intervalAvgPace: String,
        val intervalAvgHr: Double?,
        val intervalEf: Double?,
        val intervalEfSd: Double?,
    ) {
        val easyEfText get() = EfficiencyFactor.format(easyEf)
        val intervalEfText get() = EfficiencyFactor.format(intervalEf)
        val easyAvgHrText get() = Format.hr(easyAvgHr)
        val intervalAvgHrText get() = Format.hr(intervalAvgHr)
    }

    data class MonthlyPoint(
        val month: String,
        val easyEf: Double?,
        val intervalEf: Double?,
        val easyRunCount: Int,
        val intervalRepCount: Int,
        val intervalSessionCount: Int,
    )

    data class ComparisonResult(
        val periodA: PeriodStats,
        val periodB: PeriodStats,
        val easyEfChangePct: Double?,
        val intervalEfChangePct: Double?,
        val easyVerdict: EfVerdict.Result,
        val intervalVerdict: EfVerdict.Result,
        val verdict: String,
        val monthlyTrend: List<MonthlyPoint>,
    )

    /** Per-period classification result, kept internal so the verdict can use per-session values. */
    private data class Classified(
        val stats: PeriodStats,
        val easyPerRunEf: List<Double>,
        val intervalPerSessionEf: List<Double>,
    )

    fun compare(rangeA: DateRange, rangeB: DateRange, labelA: String = "Periode A", labelB: String = "Periode B"): ComparisonResult {
        val allRuns = activityRepository.findAll().filter { it.type in RUN_TYPES }
        val lapsByActivity = activityRepository.findLapsForActivities(allRuns.map { it.id })

        val a = classify(allRuns.filter { rangeA.contains(it) }, lapsByActivity, rangeA, labelA)
        val b = classify(allRuns.filter { rangeB.contains(it) }, lapsByActivity, rangeB, labelB)

        val easy = EfVerdict.evaluate(a.stats.easyEf, b.stats.easyEf, a.easyPerRunEf, b.easyPerRunEf, EfVerdict.MIN_EASY_RUNS)
        val interval = EfVerdict.evaluate(a.stats.intervalEf, b.stats.intervalEf, a.intervalPerSessionEf, b.intervalPerSessionEf, EfVerdict.MIN_INTERVAL_SESSIONS)

        return ComparisonResult(
            periodA = a.stats,
            periodB = b.stats,
            easyEfChangePct = easy.changePct,
            intervalEfChangePct = interval.changePct,
            easyVerdict = easy,
            intervalVerdict = interval,
            verdict = buildVerdict(easy, interval, a.stats, b.stats),
            monthlyTrend = buildMonthlyTrend(allRuns, lapsByActivity),
        )
    }

    fun buildMonthlyTrend(runs: List<Activity>): List<MonthlyPoint> =
        buildMonthlyTrend(runs, activityRepository.findLapsForActivities(runs.map { it.id }))

    private fun buildMonthlyTrend(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>): List<MonthlyPoint> =
        runs.groupBy { YearMonth.from(ActivityTime.localDate(it)) }.toSortedMap().map { (month, monthRuns) ->
            val c = classify(monthRuns, lapsByActivity, DateRange.ALL, month.toString())
            MonthlyPoint(
                month = month.toString(),
                easyEf = c.stats.easyEf,
                intervalEf = c.stats.intervalEf,
                easyRunCount = c.stats.easyRunCount,
                intervalRepCount = c.stats.intervalRepCount,
                intervalSessionCount = c.stats.intervalSessionCount,
            )
        }

    private fun classify(runs: List<Activity>, lapsByActivity: Map<Long, List<Lap>>, range: DateRange, label: String): Classified {
        val types = runs.associateWith { SessionClassifier.classify(lapsByActivity[it.id].orEmpty(), it.workoutType) }

        val steady = runs.filter { types[it] == SessionType.STEADY }
        val roadSteady = steady.filter { it.type == "Run" && !it.isTrainer }
        val easyWithHr = roadSteady.filter { (it.averageHeartrate ?: 0f) > 0f && it.movingTime > 0 && it.distance > 0 }
        val easySamples = easyWithHr.map { Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate!!.toDouble()) }
        val easyPerRun = easySamples.mapNotNull { EfficiencyFactor.pooled(listOf(it)) }

        val intervalSessions = runs.filter { types[it] == SessionType.INTERVAL }
        val repsPerSession = intervalSessions.associateWith { a ->
            val laps = lapsByActivity[a.id].orEmpty()
            val kinds = LapClassifier.classify(laps, a.workoutType)
            laps.filterIndexed { i, lap -> kinds[i] == LapKind.REP && lap.movingTime >= LapClassifier.MIN_REP_SECONDS }
        }
        val repSamplesPerSession = repsPerSession.mapValues { (_, reps) ->
            EfficiencyFactor.usable(reps.map { Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate?.toDouble()) })
        }.filterValues { it.isNotEmpty() }
        val allRepSamples = repSamplesPerSession.values.flatten()
        val intervalPerSession = repSamplesPerSession.values.mapNotNull { EfficiencyFactor.pooled(it) }

        val stats = PeriodStats(
            label = label,
            from = range.from,
            till = range.till,
            activityCount = runs.size,
            totalDistanceKm = runs.sumOf { it.distance.toDouble() } / 1000,
            totalTimeHours = runs.sumOf { it.movingTime.toLong() } / 3600.0,
            easyRunCount = easyWithHr.size,
            easyRunsWithoutHr = roadSteady.size - easyWithHr.size,
            excludedRaces = types.values.count { it == SessionType.RACE },
            excludedTempo = types.values.count { it == SessionType.TEMPO },
            excludedStrides = types.values.count { it == SessionType.STRIDES },
            excludedTrailOrTreadmill = steady.size - roadSteady.size,
            easyAvgPace = Format.pace(EfficiencyFactor.avgSpeed(easySamples)),
            easyAvgHr = EfficiencyFactor.avgHr(easySamples),
            easyEf = EfficiencyFactor.pooled(easySamples),
            easyEfSd = EfVerdict.sd(easyPerRun),
            intervalSessionCount = repSamplesPerSession.size,
            intervalRepCount = allRepSamples.size,
            intervalAvgRepSeconds = if (allRepSamples.isNotEmpty()) allRepSamples.sumOf { it.seconds } / allRepSamples.size else null,
            intervalAvgPace = Format.pace(EfficiencyFactor.avgSpeed(allRepSamples)),
            intervalAvgHr = EfficiencyFactor.avgHr(allRepSamples),
            intervalEf = EfficiencyFactor.pooled(allRepSamples),
            intervalEfSd = EfVerdict.sd(intervalPerSession),
        )
        return Classified(stats, easyPerRun, intervalPerSession)
    }

    private fun describe(kind: String, r: EfVerdict.Result, minN: Int): String {
        val pct = r.changePct?.let { (if (it > 0) "+" else "") + "%.1f".format(it) + "%" }
        return when (r.status) {
            EfVerdict.Status.INSUFFICIENT_DATA ->
                "$kind: onvoldoende data voor een oordeel (${r.nA} vs ${r.nB} sessies met HR; minimum $minN per periode)" +
                    (pct?.let { ", ruwe verandering $it" } ?: "")
            EfVerdict.Status.STABLE -> "$kind: stabiel ($pct, binnen ±${EfVerdict.THRESHOLD_PCT.toInt()}%)"
            EfVerdict.Status.UNCERTAIN -> "$kind: $pct, maar dat verschil valt binnen de spreiding tussen losse sessies, dus geen duidelijke verandering"
            EfVerdict.Status.IMPROVED -> "$kind: indicatie van verbetering ($pct)"
            EfVerdict.Status.DECLINED -> "$kind: indicatie van achteruitgang ($pct)"
        }
    }

    private fun buildVerdict(easy: EfVerdict.Result, interval: EfVerdict.Result, a: PeriodStats, b: PeriodStats): String {
        val parts = mutableListOf(
            describe("Rustige lopen", easy, EfVerdict.MIN_EASY_RUNS),
            describe("Intervallen", interval, EfVerdict.MIN_INTERVAL_SESSIONS),
        )
        val repA = a.intervalAvgRepSeconds
        val repB = b.intervalAvgRepSeconds
        if (repA != null && repB != null && abs(repA - repB) / maxOf(repA, repB) > 0.2) {
            parts += "Let op: de gemiddelde repduur verschilt sterk (${"%.0f".format(repA)} s vs ${"%.0f".format(repB)} s); " +
                "interval-EF hangt af van de replengte, dus die vergelijking is weinig betrouwbaar"
        }
        return "Indicatie van de app (EF is gevoelig voor terrein, warmte, vermoeidheid en HR-meting). " + parts.joinToString(". ") + "."
    }
}
