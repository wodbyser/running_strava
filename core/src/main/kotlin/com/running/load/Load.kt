package com.running.load

import com.running.strava.analysis.WeeklyVolume
import com.running.strava.domain.ActivityStream
import java.time.LocalDate
import kotlin.math.exp

object LoadAlgorithm {
    const val VERSION = "load-v1"
}

/** All thresholds are PROPOSALS (design section 2.5), UNVERIFIED, and configurable via `garmin.load.*`. */
data class LoadConfig(
    /** Banister coefficient, 1.92 for men (DECISION P4-2). */
    val trimpB: Double = 1.92,
    val minHrCoverage: Double = 0.80,
    val includeOtherSports: Boolean = true,
    val hardPercentile: Double = 0.90,
    val hardLookbackDays: Int = 180,
    val minKnownShare: Double = 0.70,
    val minWeeksBefore: Int = 4,
    val minIsolated: Int = 8,
    val minRestDays: Int = 15,
    val minDeloadSummary: Int = 3,
    val minForecastHistory: Int = 120,
    val minFatigueRuns: Int = 30,
    val minFatigueGroup: Int = 8,
    val minBlocks: Int = 12,
    val minWeeksFrequency: Int = 6,
    val minLongRunsGroup: Int = 5,
    val minDetrainingSummary: Int = 3,
    val minAdaptationWeeks: Int = 52,
    val modelMinDays: Int = 180,
    val modelMinPoints: Int = 30,
    val modelRunInDays: Int = 126,
    val modelGain: Double = 0.10,
    val modelMinStable: Double = 0.80,
    val resamples: Int = 2000,
    val seed: Long = 42,
) {
    companion object {
        val DEFAULT = LoadConfig()
    }
}

/** Every adjustable period (design section 4.3). Minimum counts scale through the 70 % rule. */
data class LoadWindows(
    val acuteDays: Int = 7,
    val chronicDays: Int = 28,
    val chartDays: Int = 180,
    val bandWeeks: Int = 26,
    val followUpMornings: Int = 3,
    val isolationDays: Int = 2,
    val recoveryHorizon: Int = 5,
    val deloadPct: Int = 70,
    val detrainingDays: Int = 14,
    val efSignalDays: Int = 14,
    val normDays: Int = 28,
) {
    companion object {
        val DEFAULT = LoadWindows()
    }
}

/** How a session's internal load was obtained. */
enum class TrimpSource(val text: String) {
    STREAM("uit de hartslagstroom"),
    AVERAGE("geschat uit gemiddelde hartslag"),
    NONE("zonder hartslag"),
}

/** Reference heart rates for TRIMP, one fixed pair for the whole history (P4-3). */
data class HrReference(val maxHr: Int, val restHr: Int, val source: String) {
    init { require(maxHr > restHr) { "maxHr must be above restHr" } }

    fun intensity(hr: Double): Double = ((hr - restHr) / (maxHr - restHr)).coerceIn(0.0, 1.0)
}

/** Banister's training impulse (#33). Pure. */
object Trimp {
    const val MAX_GAP_SECONDS = 30
    const val MIN_HR = 40
    const val MAX_HR = 230

    data class Result(val trimp: Double?, val source: TrimpSource, val coverage: Double)

    /** TRIMP per minute at relative intensity [x]: `x * 0.64 * e^(b x)`. */
    fun perMinute(x: Double, b: Double): Double = x * 0.64 * exp(b * x)

    /**
     * Sum over valid samples. A sample covers the interval before its time stamp. Gaps over 30 s are not interpolated,
     * heart rates outside 40 to 230 are dropped. Standing still counts (heart rate during a stop is still load).
     */
    fun fromStream(stream: ActivityStream?, movingSeconds: Double, ref: HrReference, b: Double): Pair<Double, Double>? {
        val time = stream?.time ?: return null
        val hr = stream.heartrate ?: return null
        val n = minOf(time.size, hr.size)
        if (n < 2) return null
        var sum = 0.0
        var valid = 0.0
        for (k in 1 until n) {
            val dt = (time[k] - time[k - 1]).toDouble()
            if (dt <= 0 || dt > MAX_GAP_SECONDS) continue
            if (hr[k] < MIN_HR || hr[k] > MAX_HR) continue
            sum += dt / 60.0 * perMinute(ref.intensity(hr[k].toDouble()), b)
            valid += dt
        }
        val coverage = if (movingSeconds > 0) minOf(1.0, valid / movingSeconds) else 0.0
        return sum to coverage
    }

    fun fromAverage(avgHr: Double?, movingSeconds: Int, ref: HrReference, b: Double): Double? {
        if (avgHr == null || avgHr <= 0 || movingSeconds <= 0) return null
        return movingSeconds / 60.0 * perMinute(ref.intensity(avgHr), b)
    }

    fun compute(stream: ActivityStream?, avgHr: Double?, movingSeconds: Int, ref: HrReference, cfg: LoadConfig = LoadConfig.DEFAULT): Result {
        val s = fromStream(stream, movingSeconds.toDouble(), ref, cfg.trimpB)
        if (s != null && s.second >= cfg.minHrCoverage) return Result(s.first, TrimpSource.STREAM, s.second)
        val avg = fromAverage(avgHr, movingSeconds, ref, cfg.trimpB)
        return if (avg != null) Result(avg, TrimpSource.AVERAGE, s?.second ?: 0.0) else Result(null, TrimpSource.NONE, s?.second ?: 0.0)
    }
}

/** Session classes (design section 2.2). Order of [severity] decides the class of a day with several sessions. */
enum class SessionClass(val label: String, val severity: Int) {
    EASY("Rustig", 1),
    LONG("Lange duurloop", 3),
    TEMPO("Tempo", 4),
    INTERVAL("Interval", 5),
    RACE("Wedstrijd", 6),
    OTHER("Andere sport", 0),
    ;

    val hardByClass: Boolean get() = this == TEMPO || this == INTERVAL || this == RACE
}

/** One activity reduced to what the load analysis needs. No raw samples, so it is cheap to cache. */
data class LoadSession(
    val id: Long,
    val date: LocalDate,
    val name: String,
    val sport: String,
    val isRun: Boolean,
    val cls: SessionClass,
    val movingSeconds: Int,
    val distanceMeters: Double,
    val elevationGain: Double,
    val flatEquivalentMeters: Double?,
    val hardSeconds: Double?,
    val trimp: Double?,
    val trimpSource: TrimpSource,
    val kilojoules: Double?,
    val garminLoad: Double? = null,
) {
    val minutes: Double get() = movingSeconds / 60.0
}

/** One calendar day. `trimp` is null when unknown (before the first heart rate activity, or only sessions without heart rate). */
data class DayLoad(
    val date: LocalDate,
    val trimp: Double?,
    val runTrimp: Double,
    val otherTrimp: Double,
    val runKm: Double,
    val runSeconds: Int,
    val runCount: Int,
    val otherCount: Int,
    val withoutHrCount: Int,
    val hardSeconds: Double,
    val cls: SessionClass?,
    val multiple: Boolean,
    val sessions: List<LoadSession>,
)

object DailyLoad {
    /** Date of the first session with a known internal load, or null. */
    fun firstKnown(sessions: List<LoadSession>): LocalDate? = sessions.filter { it.trimp != null }.minOfOrNull { it.date }

    /** Day series over [from]..[to] (inclusive). Rest days are a real 0, days before [firstKnown] are unknown. */
    fun build(sessions: List<LoadSession>, firstKnown: LocalDate?, from: LocalDate, to: LocalDate): List<DayLoad> {
        val byDay = sessions.groupBy { it.date }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val s = byDay[d].orEmpty()
            val known = s.mapNotNull { it.trimp }
            val trimp = when {
                firstKnown == null || d.isBefore(firstKnown) -> null
                s.isEmpty() -> 0.0
                known.isEmpty() -> null
                else -> known.sum()
            }
            val runs = s.filter { it.isRun }
            DayLoad(
                date = d, trimp = trimp,
                runTrimp = runs.sumOf { it.trimp ?: 0.0 },
                otherTrimp = s.filter { !it.isRun }.sumOf { it.trimp ?: 0.0 },
                runKm = runs.sumOf { it.distanceMeters } / 1000.0,
                runSeconds = runs.sumOf { it.movingSeconds },
                runCount = runs.size, otherCount = s.size - runs.size,
                withoutHrCount = s.count { it.trimp == null },
                hardSeconds = s.sumOf { it.hardSeconds ?: 0.0 },
                cls = s.maxByOrNull { it.cls.severity }?.cls,
                multiple = s.size > 1,
                sessions = s,
            )
        }.toList()
    }

    fun trimpMap(days: List<DayLoad>): Map<LocalDate, Double> = days.mapNotNull { d -> d.trimp?.let { d.date to it } }.toMap()
}

/** Hard sessions (design section 2.2): class Tempo, Interval or Wedstrijd, or a load above the own 90th percentile of the previous 180 days. */
object HardSessions {
    fun ids(sessions: List<LoadSession>, cfg: LoadConfig = LoadConfig.DEFAULT): Set<Long> {
        val sorted = sessions.sortedBy { it.date }
        val out = HashSet<Long>()
        for (s in sorted) {
            if (s.cls.hardByClass) { out += s.id; continue }
            val t = s.trimp ?: continue
            val prev = sorted.filter { it.trimp != null && it.date.isBefore(s.date) && !it.date.isBefore(s.date.minusDays(cfg.hardLookbackDays.toLong())) }.map { it.trimp!! }
            val p90 = LoadStats.percentile(prev, cfg.hardPercentile)
            if (prev.size >= MIN_HISTORY && p90 != null && t > p90) out += s.id
        }
        return out
    }

    const val MIN_HISTORY = 10
}

/** Result of a rolling window ending the day before [end]'s successor: `sum` of the known days. */
data class WindowSum(val days: Int, val knownDays: Int, val sum: Double?, val perWeek: Double?) {
    val ok: Boolean get() = sum != null
}

/** Rolling windows (#35 acute, #36 chronic). Today is never part of a window. */
object Rolling {
    /** Sum of the daily values of the [days] days ending on [end] (inclusive). Needs at least 70 % known days. */
    fun window(daily: Map<LocalDate, Double>, end: LocalDate, days: Int, minKnownShare: Double = 0.70): WindowSum {
        val known = (0 until days).count { daily.containsKey(end.minusDays(it.toLong())) }
        if (known.toDouble() / days < minKnownShare) return WindowSum(days, known, null, null)
        val sum = (0 until days).sumOf { daily[end.minusDays(it.toLong())] ?: 0.0 }
        return WindowSum(days, known, sum, sum / days * 7)
    }

    /** Acute against chronic as a plain percentage difference of the weekly amount. Null when either is unavailable or zero. */
    fun differencePct(acute: WindowSum, chronic: WindowSum): Double? {
        val a = acute.perWeek ?: return null
        val c = chronic.perWeek ?: return null
        return if (c <= 0) null else (a / c - 1) * 100
    }
}

/** Exponentially weighted average of daily load with time constant [tau] days (model section, #37). */
object Ewma {
    /** Value at the END of each day from [from] to [to]. Days without a value count as 0 only from [firstKnown] on. */
    fun series(daily: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, tau: Double): Map<LocalDate, Double> {
        val alpha = 1 - exp(-1 / tau)
        var v = 0.0
        val out = LinkedHashMap<LocalDate, Double>()
        var d = from
        while (!d.isAfter(to)) {
            v += ((daily[d] ?: 0.0) - v) * alpha
            out[d] = v
            d = d.plusDays(1)
        }
        return out
    }
}

/** Own band: where a value lies against the owner's own history (design 2.3), never a risk zone. */
enum class OwnLevel(val text: String) { LOW("laag voor jou"), NORMAL("gewoon voor jou"), HIGH("hoog voor jou"), UNKNOWN("onbekend") }

object OwnBand {
    data class Result(val level: OwnLevel, val median: Double?, val sd: Double?, val deviation: Double?, val n: Int)

    /** Needs at least [minN] history values. Uses the same median and robust SD as the recovery baselines. */
    fun evaluate(history: List<Double>, value: Double?, minN: Int): Result {
        if (value == null || history.size < minN) return Result(OwnLevel.UNKNOWN, null, null, null, history.size)
        val med = com.running.garmin.recovery.Baseline.median(history)
        val sd = com.running.garmin.recovery.Baseline.robustSd(history, med)
        val z = (value - med) / sd
        val level = when {
            z >= 1.0 -> OwnLevel.HIGH
            z <= -1.0 -> OwnLevel.LOW
            else -> OwnLevel.NORMAL
        }
        return Result(level, med, sd, z, history.size)
    }
}

data class WeekLoad(
    val weekStart: LocalDate,
    val complete: Boolean,
    val trimp: Double?,
    val knownDays: Int,
    val km: Double,
    val seconds: Int,
    val flatKm: Double?,
    val hardMinutes: Double,
    val runs: Int,
    val monotony: Double?,
    val strain: Double?,
    val monotonyNote: String?,
    val other: Double,
    /** Average power x duration of the runs with a power meter (device estimate, no trend); null when no run has one. */
    val kilojoules: Double? = null,
)

/** Weekly roll-ups, monotony (#38) and strain (#39). */
object Weekly {
    fun monotony(daily: List<Double>): Pair<Double?, String?> {
        if (daily.size != 7) return null to "geen volledige week"
        val sd = LoadStats.populationSd(daily)!!
        if (sd < 1e-9) return null to "geen variatie"
        return daily.average() / sd to null
    }

    /** Weeks (Monday to Sunday) from the week of [from] to the week of [to]. A week is complete when it ended before [today]. */
    fun build(days: List<DayLoad>, today: LocalDate): List<WeekLoad> {
        if (days.isEmpty()) return emptyList()
        return days.groupBy { WeeklyVolume.weekStart(it.date) }.toSortedMap().map { (start, list) ->
            val complete = list.size == 7 && !start.plusDays(6).isAfter(today.minusDays(1))
            val known = list.mapNotNull { it.trimp }
            val allKnown = known.size == 7
            val (m, note) = if (complete && allKnown) monotony(known) else null to (if (!complete) "week niet af" else "niet elke dag bekend")
            val trimp = if (known.isEmpty()) null else known.sum()
            val flat = list.flatMap { it.sessions }.filter { it.isRun }
            WeekLoad(
                weekStart = start, complete = complete, trimp = trimp, knownDays = known.size,
                km = list.sumOf { it.runKm }, seconds = list.sumOf { it.runSeconds },
                flatKm = if (flat.isNotEmpty() && flat.all { it.flatEquivalentMeters != null }) flat.sumOf { it.flatEquivalentMeters!! } / 1000.0 else if (flat.isEmpty()) 0.0 else null,
                hardMinutes = list.sumOf { it.hardSeconds } / 60.0, runs = list.sumOf { it.runCount },
                monotony = m, strain = if (m != null && trimp != null) trimp * m else null, monotonyNote = note,
                other = list.sumOf { it.otherTrimp },
                kilojoules = list.flatMap { it.sessions }.filter { it.isRun }.mapNotNull { it.kilojoules }.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }
}

/** #41 week-over-week change, ranked against the owner's own changes (the 10 % rule is not used). */
data class ProgressionRow(
    val weekStart: LocalDate,
    val trimp: Double?,
    val changePct: Double?,
    val vsMean4Pct: Double?,
    /** Share of the owner's own earlier changes (last 52 weeks) that were at most this one, 0..1. */
    val rank: Double?,
    val afterBreak: Boolean,
    val enough: Boolean,
)

object Progression {
    fun rows(weeks: List<WeekLoad>, cfg: LoadConfig = LoadConfig.DEFAULT): List<ProgressionRow> {
        val complete = weeks.filter { it.complete && it.trimp != null && it.knownDays == 7 }
        val byStart = complete.associateBy { it.weekStart }
        val changes = ArrayList<Pair<LocalDate, Double>>()
        return complete.map { w ->
            val prev = byStart[w.weekStart.minusWeeks(1)]
            val before = (1..cfg.minWeeksBefore).mapNotNull { byStart[w.weekStart.minusWeeks(it.toLong())]?.trimp }
            val enough = before.size == cfg.minWeeksBefore
            val change = if (prev?.trimp != null && prev.trimp > 0) (w.trimp!! / prev.trimp - 1) * 100 else null
            val vsMean = if (enough && before.average() > 0) (w.trimp!! / before.average() - 1) * 100 else null
            val history = changes.filter { it.first.isAfter(w.weekStart.minusWeeks(53)) }.map { it.second }
            val rank = if (change != null && history.size >= MIN_RANK_HISTORY) LoadStats.percentRank(history, change) else null
            val prevPrevMean = (2..5).mapNotNull { byStart[w.weekStart.minusWeeks(it.toLong())]?.trimp }
            val afterBreak = prev?.trimp != null && prevPrevMean.size >= 3 && prevPrevMean.average() > 0 && prev.trimp < 0.30 * prevPrevMean.average()
            if (change != null) changes += w.weekStart to change
            ProgressionRow(w.weekStart, w.trimp, change, vsMean, rank, afterBreak, enough)
        }
    }

    const val MIN_RANK_HISTORY = 8
}
