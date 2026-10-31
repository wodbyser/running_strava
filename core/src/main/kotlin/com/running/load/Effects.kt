package com.running.load

import com.running.garmin.recovery.Baseline
import com.running.garmin.recovery.BaselineStatus
import com.running.strava.analysis.WeeklyVolume
import java.time.LocalDate
import java.util.Random

// ------------------------------------------------------------------ #14 deload effectiveness

data class DeloadChange(val metric: ResponseMetric, val before: Double?, val during: Double?, val change: Double?, val changeZ: Double?)

data class DeloadWeek(
    val weekStart: LocalDate,
    val trimp: Double,
    val priorMean: Double,
    val pctOfPrior: Double,
    val changes: List<DeloadChange>,
    val efBefore: Double?,
    val efBeforeN: Int,
    val efAfter: Double?,
    val efAfterN: Int,
) {
    val efChange: Double? get() = if (efBefore != null && efAfter != null) efAfter - efBefore else null
}

data class DeloadSummary(val n: Int, val medianChange: Map<ResponseMetric, Double?>, val medianEfChange: Double?, val efN: Int)

data class DeloadReport(val weeks: List<DeloadWeek>, val summary: DeloadSummary?, val needed: Int, val thresholdPct: Int)

object Deload {
    val METRICS = listOf(ResponseMetric.HRV, ResponseMetric.RESTING_HR, ResponseMetric.SLEEP, ResponseMetric.BODY_BATTERY)
    const val EF_MIN_PER_SIDE = 3

    /**
     * A complete week at most [pct] of the mean of the 3 weeks before, while each of those 3 weeks was at least 80 % of the
     * 4 week level (so a break after illness is not a deload). A week with no run is a break, not a deload (that is #47).
     */
    fun detect(weeks: List<WeekLoad>, pct: Double): List<Triple<WeekLoad, Double, Double>> {
        val byStart = weeks.filter { it.complete && it.knownDays == 7 && it.trimp != null }.associateBy { it.weekStart }
        return byStart.values.sortedBy { it.weekStart }.mapNotNull { w ->
            if (w.runs == 0) return@mapNotNull null
            val prior = (1..4).map { byStart[w.weekStart.minusWeeks(it.toLong())]?.trimp ?: return@mapNotNull null }
            val chronic = prior.average()
            val prior3 = prior.take(3)
            if (chronic <= 0 || prior3.any { it < 0.80 * chronic }) return@mapNotNull null
            val priorMean = prior3.average()
            if (w.trimp!! <= pct * priorMean) Triple(w, priorMean, w.trimp / priorMean * 100) else null
        }
    }

    fun analyze(weeks: List<WeekLoad>, series: RecoverySeries, points: List<PerfPoint>, w: LoadWindows, cfg: LoadConfig): DeloadReport {
        val rows = detect(weeks, w.deloadPct / 100.0).map { (wk, priorMean, pctOf) ->
            val lastDays = (0..2).map { wk.weekStart.plusDays(6L - it) }
            val priorDays = lastDays.map { it.minusWeeks(1) }
            val changes = METRICS.map { m ->
                val s = series.of(m)
                val during = LoadStats.median(lastDays.mapNotNull { s[it] }.takeIf { it.size >= 2 } ?: emptyList())
                val before = LoadStats.median(priorDays.mapNotNull { s[it] }.takeIf { it.size >= 2 } ?: emptyList())
                val sd = Baseline.compute(s, priorDays.min(), w.normDays, null).takeIf { it.status == BaselineStatus.OK }?.robustSd
                val ch = if (during != null && before != null) during - before else null
                DeloadChange(m, before, during, ch, if (ch != null && sd != null) ch / sd else null)
            }
            val before = points.filter { !it.date.isBefore(wk.weekStart.minusDays(28)) && it.date.isBefore(wk.weekStart) }.map { it.rel }
            val after = points.filter { it.date.isAfter(wk.weekStart.plusDays(6)) && !it.date.isAfter(wk.weekStart.plusDays(20)) }.map { it.rel }
            DeloadWeek(wk.weekStart, wk.trimp!!, priorMean, pctOf, changes,
                if (before.size >= EF_MIN_PER_SIDE) LoadStats.median(before) else null, before.size,
                if (after.size >= EF_MIN_PER_SIDE) LoadStats.median(after) else null, after.size)
        }
        val summary = if (rows.size >= cfg.minDeloadSummary) {
            val ef = rows.mapNotNull { it.efChange }
            DeloadSummary(rows.size, METRICS.associateWith { m -> LoadStats.median(rows.mapNotNull { r -> r.changes.first { it.metric == m }.changeZ }) }, LoadStats.median(ef), ef.size)
        } else null
        return DeloadReport(rows, summary, cfg.minDeloadSummary, w.deloadPct)
    }
}

// ------------------------------------------------------------------ #31 fatigue-adjusted performance

data class FatigueGroup(val label: String, val n: Int, val median: Double?, val low: Double?, val high: Double?, val verdict: Verdict)

data class FatigueAdjustedReport(
    val n: Int,
    val enough: Boolean,
    val byLoad: List<FatigueGroup>,
    val byReadiness: List<FatigueGroup>,
    val tertiles: Pair<Double, Double>?,
    val reason: String?,
)

/** Efficiency of a steady run against the owner's own rolling 90 day median before it, in percent (needs 5 earlier runs). */
object RelativeEf {
    const val DAYS = 90
    const val MIN_PRIOR = 5

    fun series(points: List<PerfPoint>): List<Pair<PerfPoint, Double>> {
        val sorted = points.sortedBy { it.date }
        return sorted.mapNotNull { p ->
            val prior = sorted.filter { it.date.isBefore(p.date) && !it.date.isBefore(p.date.minusDays(DAYS.toLong())) }.map { it.rel }
            if (prior.size < MIN_PRIOR) return@mapNotNull null
            val med = LoadStats.median(prior)!!
            if (med <= 0) null else p to (p.rel / med - 1) * 100
        }
    }
}

object FatigueAdjusted {
    private data class Obs(val relDiff: Double, val loadRatio: Double?, val deviating: Int?)

    /**
     * Efficiency of each steady run against the owner's rolling 90 day median (removes slow fitness change), grouped by the load of the
     * 3 days before and by the recovery state of that morning. [deviating] is the number of recovery components outside the own norm.
     */
    fun analyze(points: List<PerfPoint>, daily: Map<LocalDate, Double>, deviating: Map<LocalDate, Int?>, cfg: LoadConfig): FatigueAdjustedReport {
        val obs = RelativeEf.series(points).map { (p, pct) ->
            val last3 = (1..3).map { daily[p.date.minusDays(it.toLong())] }
            val chronic = Rolling.window(daily, p.date.minusDays(1), 28)
            val ratio = if (last3.all { it != null } && chronic.perWeek != null && chronic.perWeek > 0) last3.sumOf { it!! } / (chronic.perWeek / 7 * 3) else null
            Obs(pct, ratio, deviating[p.date])
        }
        if (obs.size < cfg.minFatigueRuns) {
            return FatigueAdjustedReport(obs.size, false, emptyList(), emptyList(), null, "te weinig stabiele runs met een eigen niveau (${obs.size}, minimum ${cfg.minFatigueRuns})")
        }
        fun group(label: String, list: List<Obs>): FatigueGroup {
            val ci = LoadStats.bootstrapMedian(list.map { it.relDiff }, cfg.resamples, cfg.seed)
            return FatigueGroup(label, list.size, ci?.median, ci?.low, ci?.high, ResponseStats.verdict(list.size, ci, cfg.minFatigueGroup))
        }
        val ratios = obs.mapNotNull { it.loadRatio }
        val t1 = LoadStats.percentile(ratios, 1.0 / 3)
        val t2 = LoadStats.percentile(ratios, 2.0 / 3)
        val byLoad = if (t1 != null && t2 != null) listOf(
            group("Lage belasting in de 3 dagen ervoor", obs.filter { it.loadRatio != null && it.loadRatio <= t1 }),
            group("Gewone belasting", obs.filter { it.loadRatio != null && it.loadRatio > t1 && it.loadRatio <= t2 }),
            group("Hoge belasting", obs.filter { it.loadRatio != null && it.loadRatio > t2 }),
        ) else emptyList()
        val byReadiness = listOf(
            group("Alle herstelwaarden binnen je norm", obs.filter { it.deviating == 0 }),
            group("1 herstelwaarde wijkt af", obs.filter { it.deviating == 1 }),
            group("2 of meer herstelwaarden wijken af", obs.filter { (it.deviating ?: 0) >= 2 }),
        )
        return FatigueAdjustedReport(obs.size, true, byLoad, byReadiness, if (t1 != null && t2 != null) t1 to t2 else null, null)
    }
}

// ------------------------------------------------------------------ #43 workout effectiveness and #44 frequency

data class Block(
    val start: LocalDate,
    val sessions: Int,
    val counts: Map<SessionClass, Int>,
    val trimp: Double?,
    val efMedian: Double?,
    val efN: Int,
    val nextChange: Double?,
)

data class ClassEffect(val cls: SessionClass, val n: Int, val rho: Double?, val pValue: Double?, val loadRho: Double?, val confounded: Boolean, val verdict: String)

data class BlockReport(val blocks: List<Block>, val effects: List<ClassEffect>, val usable: Int, val needed: Int, val enough: Boolean)

object BlockEffect {
    val CLASSES = listOf(SessionClass.INTERVAL, SessionClass.TEMPO, SessionClass.LONG)
    const val BLOCK_WEEKS = 4

    /** Blocks of 4 Monday-aligned weeks, counted back from the last complete week. */
    fun blocks(sessions: List<LoadSession>, weeks: List<WeekLoad>, points: List<PerfPoint>): List<Block> {
        val complete = weeks.filter { it.complete }.map { it.weekStart }.sorted()
        if (complete.isEmpty()) return emptyList()
        val lastWeek = complete.last()
        val firstWeek = complete.first()
        val starts = generateSequence(lastWeek.minusWeeks(BLOCK_WEEKS - 1L)) { it.minusWeeks(BLOCK_WEEKS.toLong()) }.takeWhile { !it.isBefore(firstWeek) }.toList().reversed()
        val weekMap = weeks.associateBy { it.weekStart }
        val base = starts.map { s ->
            val end = s.plusWeeks(BLOCK_WEEKS.toLong())
            val runs = sessions.filter { it.isRun && !it.date.isBefore(s) && it.date.isBefore(end) }
            val ws = (0 until BLOCK_WEEKS).map { weekMap[s.plusWeeks(it.toLong())] }
            val trimp = if (ws.all { it?.trimp != null && it.knownDays == 7 }) ws.sumOf { it!!.trimp!! } else null
            val ef = points.filter { !it.date.isBefore(s) && it.date.isBefore(end) }.map { it.rel }
            Triple(s, runs, Triple(trimp, ef.takeIf { it.size >= 3 }?.let { LoadStats.median(it) }, ef.size))
        }
        return base.mapIndexed { i, (s, runs, t) ->
            val next = base.getOrNull(i + 1)?.third?.second
            Block(s, runs.size, CLASSES.associateWith { c -> runs.count { it.cls == c } }, t.first, t.second, t.third, if (t.second != null && next != null) next - t.second!! else null)
        }
    }

    fun analyze(blocks: List<Block>, cfg: LoadConfig): BlockReport {
        val usable = blocks.filter { it.nextChange != null && it.sessions > 0 }
        val effects = CLASSES.map { c ->
            val rows = usable.filter { it.trimp != null }
            if (rows.size < cfg.minBlocks) return@map ClassEffect(c, rows.size, null, null, null, false, "onvoldoende data")
            val share = rows.map { it.counts.getValue(c).toDouble() / it.sessions }
            val perm = LoadStats.spearmanPermutation(share, rows.map { it.nextChange!! }, cfg.resamples, cfg.seed)
            val loadRho = LoadStats.spearman(share, rows.map { it.trimp!! })
            val confounded = loadRho != null && kotlin.math.abs(loadRho) >= 0.5
            ClassEffect(c, rows.size, perm?.statistic, perm?.pValue, loadRho, confounded, if (perm != null && perm.pValue < 0.05) "verband gevonden" else "geen duidelijk verband")
        }
        return BlockReport(blocks, effects, usable.size, cfg.minBlocks, usable.count { it.trimp != null } >= cfg.minBlocks)
    }
}

data class FrequencyGroup(val label: String, val weeks: Int, val medianHrvZ: Double?, val medianRestingHrZ: Double?, val medianTrimp: Double?, val medianKm: Double?, val enough: Boolean)

data class FrequencyReport(val groups: List<FrequencyGroup>, val weeks: Int, val frequencyTracksVolume: Boolean, val reason: String?)

object Frequency {
    private data class W(val runs: Int, val hrvZ: Double?, val rhrZ: Double?, val trimp: Double?, val km: Double)

    fun analyze(weeks: List<WeekLoad>, series: RecoverySeries, w: LoadWindows, cfg: LoadConfig): FrequencyReport {
        val rows = weeks.filter { it.complete }.mapNotNull { wk ->
            val next = wk.weekStart.plusWeeks(1)
            fun z(s: Map<LocalDate, Double>): Double? {
                val v = (0..6).mapNotNull { MorningDeviation.after(s, next, it, w.normDays)?.z }
                return if (v.size >= 4) LoadStats.median(v) else null
            }
            W(wk.runs, z(series.hrv), z(series.restingHr), wk.trimp, wk.km)
        }
        if (rows.isEmpty()) return FrequencyReport(emptyList(), 0, false, "geen volledige weken")
        // groups: 1 to 2, 3, 4, 5 or more; merged upward while a group has too few weeks
        var bands = listOf(1..2, 3..3, 4..4, 5..99).map { it.first to it.last }
        fun count(b: Pair<Int, Int>) = rows.count { it.runs in b.first..b.second }
        var guard = 0
        while (bands.size > 1 && bands.any { count(it) < cfg.minWeeksFrequency } && guard++ < 10) {
            val i = bands.indexOfFirst { count(it) < cfg.minWeeksFrequency }
            val j = if (i + 1 < bands.size) i else i - 1
            bands = bands.take(j) + listOf(bands[j].first to bands[j + 1].second) + bands.drop(j + 2)
        }
        val groups = bands.map { b ->
            val g = rows.filter { it.runs in b.first..b.second }
            val label = when { b.second >= 99 -> "${b.first} of meer runs per week"; b.first == b.second -> "${b.first} runs per week"; else -> "${b.first} tot ${b.second} runs per week" }
            FrequencyGroup(label, g.size, LoadStats.median(g.mapNotNull { it.hrvZ }), LoadStats.median(g.mapNotNull { it.rhrZ }),
                LoadStats.median(g.mapNotNull { it.trimp }), LoadStats.median(g.map { it.km }), g.size >= cfg.minWeeksFrequency)
        }
        val rho = LoadStats.spearman(rows.map { it.runs.toDouble() }, rows.map { it.km })
        return FrequencyReport(groups, rows.size, rho != null && rho >= 0.6, null)
    }
}

// ------------------------------------------------------------------ #47 detraining

data class Episode(
    val start: LocalDate,
    val end: LocalDate,
    val days: Int,
    val efBefore: Double?,
    val efBeforeN: Int,
    val efAfter: Double?,
    val efAfterN: Int,
    val restingHrBefore: Double?,
    val restingHrEnd: Double?,
    val hrvBefore: Double?,
    val hrvEnd: Double?,
) {
    val efChange: Double? get() = if (efBefore != null && efAfter != null) efAfter - efBefore else null
    val usable: Boolean get() = efChange != null
}

data class DetrainingReport(val episodes: List<Episode>, val usable: Int, val summaryEfChange: Double?, val needed: Int, val minDays: Int)

object Detraining {
    /** Spans of at least [minDays] without a run, or of consecutive complete weeks below 30 % of the 4 week level before them. */
    fun spans(days: List<DayLoad>, weeks: List<WeekLoad>, minDays: Int): List<Pair<LocalDate, LocalDate>> {
        val byStart = weeks.associateBy { it.weekStart }
        val low = HashSet<LocalDate>()
        for (wk in weeks) {
            if (!wk.complete || wk.trimp == null) continue
            val prior = (1..4).mapNotNull { byStart[wk.weekStart.minusWeeks(it.toLong())]?.trimp }
            if (prior.size >= 3 && prior.average() > 0 && wk.trimp < 0.30 * prior.average()) for (i in 0..6) low += wk.weekStart.plusDays(i.toLong())
        }
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        fun spansOf(set: Set<LocalDate>): List<Pair<LocalDate, LocalDate>> {
            val s = set.sorted()
            val r = ArrayList<Pair<LocalDate, LocalDate>>()
            var start: LocalDate? = null
            var prev: LocalDate? = null
            for (d in s) {
                if (start == null) start = d else if (d != prev!!.plusDays(1)) { r += start to prev; start = d }
                prev = d
            }
            if (start != null) r += start to prev!!
            return r.filter { java.time.temporal.ChronoUnit.DAYS.between(it.first, it.second) + 1 >= minDays }
        }
        out += spansOf(days.filter { it.runCount == 0 }.map { it.date }.toSet())
        out += spansOf(low)
        // merge overlaps
        val merged = ArrayList<Pair<LocalDate, LocalDate>>()
        for (sp in out.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && !sp.first.isAfter(last.second.plusDays(1))) merged[merged.size - 1] = last.first to maxOf(last.second, sp.second) else merged += sp
        }
        return merged
    }

    fun analyze(days: List<DayLoad>, weeks: List<WeekLoad>, series: RecoverySeries, points: List<PerfPoint>, w: LoadWindows, cfg: LoadConfig): DetrainingReport {
        val episodes = spans(days, weeks, w.detrainingDays).map { (s, e) ->
            val before = points.filter { !it.date.isBefore(s.minusDays(28)) && it.date.isBefore(s) }.map { it.rel }
            val after = points.filter { it.date.isAfter(e) && !it.date.isAfter(e.plusDays(28)) }.map { it.rel }
            fun med(map: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): Double? {
                val v = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.mapNotNull { map[it] }.toList()
                return if (v.size >= 4) LoadStats.median(v) else null
            }
            Episode(s, e, (java.time.temporal.ChronoUnit.DAYS.between(s, e) + 1).toInt(),
                if (before.size >= 3) LoadStats.median(before) else null, before.size,
                if (after.size >= 3) LoadStats.median(after) else null, after.size,
                med(series.restingHr, s.minusDays(7), s.minusDays(1)), med(series.restingHr, e.minusDays(6), e),
                med(series.hrv, s.minusDays(7), s.minusDays(1)), med(series.hrv, e.minusDays(6), e))
        }
        val usable = episodes.filter { it.usable }
        return DetrainingReport(episodes, usable.size, if (usable.size >= cfg.minDetrainingSummary) LoadStats.median(usable.map { it.efChange!! }) else null, cfg.minDetrainingSummary, w.detrainingDays)
    }
}

// ------------------------------------------------------------------ #48 adaptation lag

data class LagCorrelation(val lagWeeks: Int, val correlation: Double?, val pairs: Int)

data class AdaptationLagReport(
    val weeksUsed: Int,
    val enough: Boolean,
    val lags: List<LagCorrelation>,
    val bestLag: Int?,
    val bestCorrelation: Double?,
    val threshold: Double?,
    val found: Boolean,
    val reason: String?,
)

object AdaptationLag {
    const val MAX_LAG = 8
    const val BLOCK = 4
    const val MIN_PAIRS = 20

    private fun corrAt(load: List<Double?>, ef: List<Double?>, lag: Int): LagCorrelation {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (t in load.indices) {
            val u = t + lag
            if (u >= ef.size) break
            val a = load[t]
            val b = ef[u]
            if (a != null && b != null) { xs += a; ys += b }
        }
        return LagCorrelation(lag, if (xs.size >= MIN_PAIRS) LoadStats.pearson(xs, ys) else null, xs.size)
    }

    fun analyze(weeks: List<WeekLoad>, points: List<PerfPoint>, runInEnd: LocalDate?, cfg: LoadConfig): AdaptationLagReport {
        val list = weeks.filter { it.complete && it.knownDays == 7 && it.trimp != null && (runInEnd == null || !it.weekStart.isBefore(runInEnd)) }.sortedBy { it.weekStart }
        val used = list.size
        if (used < cfg.minAdaptationWeeks) return AdaptationLagReport(used, false, emptyList(), null, null, null, false, "te weinig volledige weken ($used, minimum ${cfg.minAdaptationWeeks})")
        val trimp = list.map { it.trimp!! }
        val m4 = trimp.indices.map { t -> if (t >= 3) trimp.subList(t - 3, t + 1).average() else null }
        val loadChange = m4.indices.map { t -> if (t >= 1 && m4[t] != null && m4[t - 1] != null) m4[t]!! - m4[t - 1]!! else null }
        val ef4 = list.indices.map { t ->
            val from = list[maxOf(0, t - 3)].weekStart
            val to = list[t].weekStart.plusDays(6)
            val v = points.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.map { it.rel }
            if (v.size >= 3 && t >= 3) LoadStats.median(v) else null
        }
        val efChange = ef4.indices.map { t -> if (t >= 1 && ef4[t] != null && ef4[t - 1] != null) ef4[t]!! - ef4[t - 1]!! else null }
        val lags = (0..MAX_LAG).map { corrAt(loadChange, efChange, it) }
        val best = lags.filter { it.correlation != null }.maxByOrNull { kotlin.math.abs(it.correlation!!) }
        if (best == null) return AdaptationLagReport(used, true, lags, null, null, null, false, "te weinig gepaarde weken per vertraging (minimum $MIN_PAIRS)")
        val rnd = Random(cfg.seed)
        val blocks = efChange.indices.chunked(BLOCK).map { idx -> idx.map { efChange[it] } }.toMutableList()
        val stats = DoubleArray(cfg.resamples)
        for (r in 0 until cfg.resamples) {
            blocks.shuffle(rnd)
            val shuffled = blocks.flatten()
            stats[r] = (0..MAX_LAG).mapNotNull { corrAt(loadChange, shuffled, it).correlation }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        }
        stats.sort()
        val threshold = stats[(0.95 * (stats.size - 1)).toInt()]
        val found = kotlin.math.abs(best.correlation!!) > threshold
        return AdaptationLagReport(used, true, lags, if (found) best.lagWeeks else null, best.correlation, threshold, found, if (found) null else "geen meetbare vertraging gevonden")
    }
}
