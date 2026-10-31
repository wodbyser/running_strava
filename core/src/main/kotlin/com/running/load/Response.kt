package com.running.load

import com.running.garmin.recovery.Baseline
import com.running.garmin.recovery.BaselineStatus
import java.time.LocalDate
import kotlin.math.abs

enum class ResponseMetric(val label: String, val unit: String) {
    HRV("HRV", "ms"),
    RESTING_HR("Rusthartslag", "bpm"),
    SLEEP("Slaapduur", "min"),
    BODY_BATTERY("Body Battery bij opstaan", "punten"),
}

/** Garmin morning values by their own `calendarDate` (HRV, resting HR and sleep belong to the wake-up date). */
data class RecoverySeries(
    val hrv: Map<LocalDate, Double>,
    val restingHr: Map<LocalDate, Double>,
    val sleepMinutes: Map<LocalDate, Double>,
    val bodyBatteryWake: Map<LocalDate, Double>,
) {
    fun of(m: ResponseMetric): Map<LocalDate, Double> = when (m) {
        ResponseMetric.HRV -> hrv
        ResponseMetric.RESTING_HR -> restingHr
        ResponseMetric.SLEEP -> sleepMinutes
        ResponseMetric.BODY_BATTERY -> bodyBatteryWake
    }

    companion object {
        val EMPTY = RecoverySeries(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

enum class Verdict(val text: String) {
    DIFFERENT("duidelijk verschil"),
    NOT_CLEAR("geen duidelijk verschil"),
    INSUFFICIENT("onvoldoende data"),
}

/** Statistic for one morning after a session group. `z` is in own-norm SD units, `diff` in the metric's unit. */
data class MorningStat(
    val morning: Int,
    val n: Int,
    val medianZ: Double?,
    val lowZ: Double?,
    val highZ: Double?,
    val medianDiff: Double?,
    val verdict: Verdict,
)

data class MetricResponse(val metric: ResponseMetric, val mornings: List<MorningStat>)

object ResponseStats {
    /** The shared group rule (design 2.3): a difference only when n is enough and the bootstrap interval excludes 0. */
    fun verdict(n: Int, interval: LoadStats.Interval?, minN: Int): Verdict = when {
        n < minN || interval == null -> Verdict.INSUFFICIENT
        interval.excludesZero -> Verdict.DIFFERENT
        else -> Verdict.NOT_CLEAR
    }

    fun summarize(z: List<Double>, diff: List<Double>, morning: Int, minN: Int, cfg: LoadConfig): MorningStat {
        val ci = LoadStats.bootstrapMedian(z, cfg.resamples, cfg.seed)
        return MorningStat(morning, z.size, ci?.median, ci?.low, ci?.high, LoadStats.median(diff), verdict(z.size, ci, minN))
    }
}

/** Deviation of one morning from the 28 day norm that ends BEFORE the reference day (so the aftermath never moves its own reference). */
object MorningDeviation {
    data class Value(val z: Double, val diff: Double)

    fun after(series: Map<LocalDate, Double>, reference: LocalDate, morning: Int, normDays: Int): Value? {
        val v = series[reference.plusDays(morning.toLong())] ?: return null
        val b = Baseline.compute(series, reference, normDays, v)
        if (b.status != BaselineStatus.OK || b.deviation == null || b.median == null) return null
        return Value(b.deviation, v - b.median)
    }
}

/** One day with its hardest session, for the isolation rule. */
data class DaySession(val date: LocalDate, val session: LoadSession, val hard: Boolean, val others: Int)

/** Isolation (design 2.4): no other hard session in the days before and in the follow-up window after. */
object Isolation {
    fun daySessions(sessions: List<LoadSession>, hardIds: Set<Long>): List<DaySession> =
        sessions.groupBy { it.date }.map { (d, list) ->
            val top = list.maxByOrNull { it.cls.severity }!!
            DaySession(d, top, list.any { it.id in hardIds }, list.size - 1)
        }.sortedBy { it.date }

    /** Hard days other than [day] within [before] days before and [after] days after. */
    fun isIsolated(day: DaySession, all: List<DaySession>, before: Int, after: Int): Boolean =
        all.none { o -> o.hard && o.date != day.date && !o.date.isBefore(day.date.minusDays(before.toLong())) && !o.date.isAfter(day.date.plusDays(after.toLong())) }

    /** Easy runs inside the follow-up window, for the "2 rustige runs in het venster" note. */
    fun easyInWindow(day: DaySession, all: List<DaySession>, after: Int): Int =
        all.count { o -> !o.hard && o.session.isRun && o.date.isAfter(day.date) && !o.date.isAfter(day.date.plusDays(after.toLong())) }
}

data class ClassResponse(
    val cls: SessionClass,
    val sessions: Int,
    val isolated: Int,
    val metrics: List<MetricResponse>,
    val medianSleepMinutes: Double?,
    val medianEasyInWindow: Double?,
    val enough: Boolean,
)

/** #10 outcome per class. */
sealed interface RecoveryTime {
    data class Insufficient(val n: Int, val needed: Int) : RecoveryTime
    data object NoDeviation : RecoveryTime
    data class Mornings(val mornings: Int) : RecoveryTime
    data class Longer(val horizon: Int) : RecoveryTime
}

data class ClassRecovery(val cls: SessionClass, val time: RecoveryTime, val n: Int)

/** #5 training response, #10 recovery time. Descriptive only, never a prescription. */
class ResponseAnalyzer(
    private val series: RecoverySeries,
    private val sessions: List<LoadSession>,
    private val cfg: LoadConfig = LoadConfig.DEFAULT,
    private val w: LoadWindows = LoadWindows.DEFAULT,
) {
    val hardIds: Set<Long> = HardSessions.ids(sessions, cfg)
    val days: List<DaySession> = Isolation.daySessions(sessions, hardIds)

    fun isolated(cls: SessionClass, followUp: Int): List<DaySession> =
        days.filter { it.session.cls == cls && Isolation.isIsolated(it, days, w.isolationDays, followUp) }

    fun metrics(group: List<DaySession>, mornings: Int, metrics: List<ResponseMetric> = ResponseMetric.entries, minN: Int = cfg.minIsolated): List<MetricResponse> =
        metricsForDates(group.map { it.date }, mornings, metrics, minN)

    /**
     * Reference for "nothing happened" (design 3.8): days without a run and without a hard session in the isolation window
     * before and the follow-up window after, from [from] to [to].
     */
    fun restReference(from: LocalDate, to: LocalDate, mornings: Int): List<MetricResponse> {
        val runDays = sessions.filter { it.isRun }.map { it.date }.toSet()
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter { d ->
            d !in runDays && days.none { o -> o.hard && !o.date.isBefore(d.minusDays(w.isolationDays.toLong())) && !o.date.isAfter(d.plusDays(mornings.toLong())) }
        }.toList()
        return metricsForDates(dates, mornings, ResponseMetric.entries, cfg.minIsolated)
    }

    private fun metricsForDates(dates: List<LocalDate>, mornings: Int, metrics: List<ResponseMetric>, minN: Int): List<MetricResponse> =
        metrics.map { m ->
            val s = series.of(m)
            MetricResponse(m, (1..mornings).map { k ->
                val v = dates.mapNotNull { MorningDeviation.after(s, it, k, w.normDays) }
                ResponseStats.summarize(v.map { it.z }, v.map { it.diff }, k, minN, cfg)
            })
        }

    fun byClass(classes: List<SessionClass> = listOf(SessionClass.EASY, SessionClass.LONG, SessionClass.TEMPO, SessionClass.INTERVAL, SessionClass.RACE)): List<ClassResponse> =
        classes.map { cls ->
            val all = days.filter { it.session.cls == cls }
            val iso = isolated(cls, w.followUpMornings)
            ClassResponse(
                cls, all.size, iso.size, metrics(iso, w.followUpMornings),
                LoadStats.median(iso.mapNotNull { series.sleepMinutes[it.date.plusDays(1)] }),
                LoadStats.median(iso.map { Isolation.easyInWindow(it, days, w.followUpMornings).toDouble() }),
                iso.size >= cfg.minIsolated,
            )
        }

    fun recoveryTimes(classes: List<SessionClass> = listOf(SessionClass.EASY, SessionClass.LONG, SessionClass.TEMPO, SessionClass.INTERVAL, SessionClass.RACE)): List<ClassRecovery> =
        classes.map { cls -> ClassRecovery(cls, recoveryTime(cls), isolated(cls, w.recoveryHorizon).size) }

    fun recoveryTime(cls: SessionClass): RecoveryTime {
        val iso = isolated(cls, w.recoveryHorizon)
        if (iso.size < cfg.minIsolated) return RecoveryTime.Insufficient(iso.size, cfg.minIsolated)
        var bestN = 0
        val perMorning = (1..w.recoveryHorizon).map { k ->
            val both = iso.mapNotNull { d ->
                val h = MorningDeviation.after(series.hrv, d.date, k, w.normDays)
                val r = MorningDeviation.after(series.restingHr, d.date, k, w.normDays)
                if (h != null && r != null) h.z to r.z else null
            }
            bestN = maxOf(bestN, both.size)
            if (both.size < cfg.minIsolated) null else {
                val h = LoadStats.bootstrapMedian(both.map { it.first }, cfg.resamples, cfg.seed)!!
                val r = LoadStats.bootstrapMedian(both.map { it.second }, cfg.resamples, cfg.seed)!!
                fun ok(i: LoadStats.Interval) = !i.excludesZero && abs(i.median) < 0.5
                ok(h) && ok(r)
            }
        }
        for (k in 1 until w.recoveryHorizon) {
            if (perMorning[k - 1] == true && perMorning[k] == true) return if (k == 1) RecoveryTime.NoDeviation else RecoveryTime.Mornings(k)
        }
        return if (perMorning.all { it == null }) RecoveryTime.Insufficient(bestN, cfg.minIsolated) else RecoveryTime.Longer(w.recoveryHorizon)
    }
}

// ------------------------------------------------------------------ #13 rest day effectiveness

enum class DayKind(val label: String) {
    REST("Rustdag (geen run)"),
    EASY("Rustige run"),
    HARD("Zware training"),
    SECOND_REST("Tweede rustdag op rij"),
}

data class RestChange(val metric: ResponseMetric, val n: Int, val medianChange: Double?, val low: Double?, val high: Double?, val verdict: Verdict)

data class RestGroup(
    val kind: DayKind,
    val days: Int,
    val withOtherActivity: Int,
    val changes: List<RestChange>,
    val medianSleepMinutes: Double?,
)

object RestDays {
    val METRICS = listOf(ResponseMetric.HRV, ResponseMetric.RESTING_HR, ResponseMetric.BODY_BATTERY)

    /**
     * A rest day (DECISION, owner 2026-10-31) is a day without a run; walks and other sports are allowed and flagged.
     * Change = morning D+1 minus morning D, so it covers the night after day D.
     */
    fun analyze(sessions: List<LoadSession>, series: RecoverySeries, from: LocalDate, to: LocalDate, cfg: LoadConfig = LoadConfig.DEFAULT): List<RestGroup> {
        val hard = HardSessions.ids(sessions, cfg)
        val byDay = sessions.groupBy { it.date }
        fun hasRun(d: LocalDate) = byDay[d].orEmpty().any { it.isRun }
        fun kind(d: LocalDate): DayKind? {
            val s = byDay[d].orEmpty()
            return when {
                s.none { it.isRun } -> DayKind.REST
                s.any { it.id in hard } -> DayKind.HARD
                else -> DayKind.EASY
            }
        }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val groups = linkedMapOf<DayKind, MutableList<LocalDate>>()
        for (d in days) {
            val k = kind(d) ?: continue
            groups.getOrPut(k) { mutableListOf() } += d
            if (k == DayKind.REST && !hasRun(d.minusDays(1)) && d.minusDays(1) >= from) groups.getOrPut(DayKind.SECOND_REST) { mutableListOf() } += d
        }
        return DayKind.entries.map { k ->
            val list = groups[k].orEmpty()
            val changes = METRICS.map { m ->
                val s = series.of(m)
                val values = list.mapNotNull { d ->
                    val a = s[d]
                    val b = s[d.plusDays(1)]
                    if (a != null && b != null) b - a else null
                }
                val ci = LoadStats.bootstrapMedian(values, cfg.resamples, cfg.seed)
                RestChange(m, values.size, ci?.median, ci?.low, ci?.high, ResponseStats.verdict(values.size, ci, cfg.minRestDays))
            }
            RestGroup(k, list.size, list.count { d -> byDay[d].orEmpty().any { !it.isRun } },
                changes, LoadStats.median(list.mapNotNull { series.sleepMinutes[it.plusDays(1)] }))
        }
    }
}

// ------------------------------------------------------------------ #45 long run duration

data class DurationGroup(
    val label: String,
    val fromMinutes: Int,
    val toMinutes: Int?,
    val runs: Int,
    val isolated: Int,
    val metrics: List<MetricResponse>,
    val efChangePct: Double?,
    val efN: Int,
    val enough: Boolean,
)

object LongRunDuration {
    val BOUNDS = listOf(75 to 90, 90 to 105, 105 to 120, 120 to null)

    fun label(from: Int, to: Int?) = if (to == null) "meer dan $from min" else "$from tot $to min"

    /** [efChange] maps a long-run id to the EF change (last against first third, %) from Phase 3. */
    fun analyze(analyzer: ResponseAnalyzer, efChange: Map<Long, Double>, cfg: LoadConfig, followUp: Int, isolationDays: Int): List<DurationGroup> =
        BOUNDS.map { (lo, hi) ->
            val all = analyzer.days.filter { it.session.cls == SessionClass.LONG && it.session.minutes >= lo && (hi == null || it.session.minutes < hi) }
            val iso = all.filter { Isolation.isIsolated(it, analyzer.days, isolationDays, followUp) }
            val ef = iso.mapNotNull { efChange[it.session.id] }
            DurationGroup(label(lo, hi), lo, hi, all.size, iso.size, analyzer.metrics(iso, followUp, ResponseMetric.entries, cfg.minLongRunsGroup), LoadStats.median(ef), ef.size, iso.size >= cfg.minLongRunsGroup)
        }
}
