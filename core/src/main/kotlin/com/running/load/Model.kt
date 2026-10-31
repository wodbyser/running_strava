package com.running.load

import com.running.garmin.recovery.Baseline
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One performance observation: the efficiency of a steady run as a percentage of the owner's median in the same zone group. */
data class PerfPoint(val id: Long, val date: LocalDate, val name: String, val rel: Double)

data class FfFit(val p0: Double, val k1: Double, val k2: Double)

data class FfValidation(
    val evaluated: Int,
    val maeModel: Double?,
    val maeLast28: Double?,
    val maePrevious: Double?,
    val betterBaseline: String?,
    val shareCloser: Double?,
    val stableShare: Double?,
    val k1Range: Pair<Double, Double>?,
    val k2Range: Pair<Double, Double>?,
    val passed: Boolean,
    val reason: String?,
    val firstEvaluated: LocalDate?,
    val lastEvaluated: LocalDate?,
    /** One row per evaluated point, oldest first. */
    val errors: List<FfPointError> = emptyList(),
)

data class FfPointError(val date: LocalDate, val model: Double, val last28: Double, val previous: Double)

data class FfDay(val date: LocalDate, val fatigue: Double, val fitness: Double, val predicted: Double?, val runIn: Boolean)

data class FfResult(
    val runInEnd: LocalDate?,
    val days: List<FfDay>,
    val points: Int,
    val validation: FfValidation,
    val fit: FfFit?,
) {
    /** The performance curve is shown only after validation (design 3.4). There is no form or TSB number in any case. */
    val showCurve: Boolean get() = validation.passed && fit != null
}

/** #37 Banister impulse-response model with fixed time constants 42 and 7 (literature defaults), fitted by least squares. */
object FitnessFatigue {
    const val TAU_FITNESS = 42.0
    const val TAU_FATIGUE = 7.0

    fun fit(points: List<PerfPoint>, fitness: Map<LocalDate, Double>, fatigue: Map<LocalDate, Double>): FfFit? {
        val usable = points.filter { fitness.containsKey(it.date.minusDays(1)) && fatigue.containsKey(it.date.minusDays(1)) }
        val c = LoadStats.ols(
            listOf(usable.map { fitness.getValue(it.date.minusDays(1)) }, usable.map { fatigue.getValue(it.date.minusDays(1)) }),
            usable.map { it.rel },
        ) ?: return null
        return FfFit(c[0], c[1], -c[2])
    }

    fun predict(fit: FfFit, date: LocalDate, fitness: Map<LocalDate, Double>, fatigue: Map<LocalDate, Double>): Double? {
        val f = fitness[date.minusDays(1)] ?: return null
        val a = fatigue[date.minusDays(1)] ?: return null
        return fit.p0 + fit.k1 * f - fit.k2 * a
    }

    /** [daily] has a real 0 on rest days from [firstKnown] on. */
    fun analyze(daily: Map<LocalDate, Double>, firstKnown: LocalDate?, today: LocalDate, points: List<PerfPoint>, cfg: LoadConfig = LoadConfig.DEFAULT): FfResult {
        if (firstKnown == null) return FfResult(null, emptyList(), 0, invalid("geen interne belasting beschikbaar"), null)
        val yesterday = today.minusDays(1)
        val fitness = Ewma.series(daily, firstKnown, yesterday, TAU_FITNESS)
        val fatigue = Ewma.series(daily, firstKnown, yesterday, TAU_FATIGUE)
        val runInEnd = firstKnown.plusDays(cfg.modelRunInDays.toLong())
        val eligible = points.filter { !it.date.isBefore(runInEnd) && !it.date.isAfter(yesterday) }.sortedBy { it.date }

        val errModel = ArrayList<Double>()
        val err28 = ArrayList<Double>()
        val errPrev = ArrayList<Double>()
        val dates = ArrayList<LocalDate>()
        val k1s = ArrayList<Double>()
        val k2s = ArrayList<Double>()
        var first: LocalDate? = null
        var last: LocalDate? = null
        for ((i, p) in eligible.withIndex()) {
            if (p.date.isBefore(runInEnd.plusDays(cfg.modelMinDays.toLong()))) continue
            val earlier = eligible.subList(0, i).filter { it.date.isBefore(p.date) }
            if (earlier.size < cfg.modelMinPoints) continue
            val last28 = earlier.filter { ChronoUnit.DAYS.between(it.date, p.date) <= 28 }.map { it.rel }
            if (last28.size < 3) continue
            val f = fit(earlier, fitness, fatigue) ?: continue
            val pred = predict(f, p.date, fitness, fatigue) ?: continue
            errModel += abs(pred - p.rel)
            err28 += abs(LoadStats.median(last28)!! - p.rel)
            errPrev += abs(earlier.last().rel - p.rel)
            dates += p.date
            k1s += f.k1
            k2s += f.k2
            if (first == null) first = p.date
            last = p.date
        }
        val validation = validate(errModel, err28, errPrev, k1s, k2s, first, last, cfg).copy(errors = dates.indices.map { FfPointError(dates[it], errModel[it], err28[it], errPrev[it]) })
        val finalFit = fit(eligible, fitness, fatigue)
        val days = fitness.keys.map { d ->
            FfDay(d, fatigue.getValue(d), fitness.getValue(d),
                if (validation.passed && finalFit != null) predict(finalFit, d, fitness, fatigue) else null, d.isBefore(runInEnd))
        }
        return FfResult(runInEnd, days, eligible.size, validation, finalFit)
    }

    private fun invalid(reason: String) = FfValidation(0, null, null, null, null, null, null, null, null, false, reason, null, null)

    fun validate(errModel: List<Double>, err28: List<Double>, errPrev: List<Double>, k1s: List<Double>, k2s: List<Double>, first: LocalDate?, last: LocalDate?, cfg: LoadConfig): FfValidation {
        val n = errModel.size
        if (n < MIN_EVALUATED) {
            return FfValidation(n, null, null, null, null, null, null, null, null, false,
                "te weinig voorspelde punten ($n, minimum $MIN_EVALUATED)", first, last)
        }
        val mm = errModel.average()
        val m28 = err28.average()
        val mp = errPrev.average()
        val (name, better, betterErr) = if (m28 <= mp) Triple("de mediaan van de laatste 4 weken", m28, err28) else Triple("de vorige meting", mp, errPrev)
        val closer = errModel.indices.count { errModel[it] < betterErr[it] }.toDouble() / n
        val stable = k1s.indices.count { k1s[it] > 0 && k2s[it] > 0 }.toDouble() / n
        val gain = mm <= (1 - cfg.modelGain) * better
        val stableOk = stable >= cfg.modelMinStable
        val reason = when {
            !gain -> "de fout van het model ($mm) was niet minstens ${(cfg.modelGain * 100).toInt()} % lager dan bij $name ($better)"
            !stableOk -> "de gewichten van het model wisselden te vaak van teken"
            else -> null
        }
        return FfValidation(n, mm, m28, mp, name, closer, stable, k1s.min() to k1s.max(), k2s.min() to k2s.max(), gain && stableOk, reason, first, last)
    }

    const val MIN_EVALUATED = 10
}

// ------------------------------------------------------------------ #15 recovery trend forecast

data class DayForecast(val horizon: Int, val date: LocalDate, val value: Double, val low: Double, val high: Double)

data class ForecastCheck(
    val label: String,
    val unit: String,
    val evaluated: Int,
    val maeModel: Double?,
    val maePersistence: Double?,
    val persistence: Double?,
    val passed: Boolean,
    val reason: String?,
    val current: Double?,
    val forecasts: List<DayForecast>,
)

/** Cautious projection of the 7 day median, 1 to 3 days ahead. Shown only when it beat "same as today" in walk-forward checks. */
object RecoveryForecast {
    const val MAX_HORIZON = 3
    const val EVAL_DAYS = 120
    const val MIN_EVALUATED = 60

    /** 7 day median ending on each day (at least 5 values). */
    fun medians7(series: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val out = LinkedHashMap<LocalDate, Double>()
        var d = from
        while (!d.isAfter(to)) {
            val v = (0 until 7).mapNotNull { series[d.minusDays(it.toLong())] }
            if (v.size >= Baseline.minimumCount(7)) out[d] = Baseline.median(v)
            d = d.plusDays(1)
        }
        return out
    }

    /** Persistence coefficient of the deviation from the baseline, 0..1 (least squares through the origin). */
    fun fitPhi(dev: List<Pair<Double, Double>>): Double? = LoadStats.slopeThroughOrigin(dev.map { it.first }, dev.map { it.second })?.coerceIn(0.0, 1.0)

    fun analyze(label: String, unit: String, series: Map<LocalDate, Double>, today: LocalDate, cfg: LoadConfig = LoadConfig.DEFAULT): ForecastCheck {
        val yesterday = today.minusDays(1)
        val dates = series.keys.filter { !it.isAfter(yesterday) }
        if (dates.isEmpty()) return empty(label, unit, "geen data")
        val first = dates.min()
        val last = dates.max()
        val s7 = medians7(series, first, last)
        val base = HashMap<LocalDate, Double>()
        val dev = HashMap<LocalDate, Double>()
        for ((d, v) in s7) {
            val window = (7..66).mapNotNull { series[d.minusDays(it.toLong())] }
            if (window.size >= Baseline.minimumCount(60)) {
                val b = Baseline.median(window)
                base[d] = b
                dev[d] = v - b
            }
        }
        if (dev.size < cfg.minForecastHistory) return empty(label, unit, "te weinig historie (${dev.size} dagen, minimum ${cfg.minForecastHistory})")

        fun phiAt(t: LocalDate): Double? {
            val pairs = dev.keys.filter { it < t && dev.containsKey(it.plusDays(1)) && it.plusDays(1) <= t }.sorted().takeLast(180)
                .map { dev.getValue(it) to dev.getValue(it.plusDays(1)) }
            return if (pairs.size < 30) null else fitPhi(pairs)
        }

        val errModel = ArrayList<Double>()
        val errPers = ArrayList<Double>()
        val signed = ArrayList<Double>()
        val evalStart = last.minusDays((EVAL_DAYS + MAX_HORIZON).toLong())
        for (t in dev.keys.sorted()) {
            if (t.isBefore(evalStart) || t.isAfter(last.minusDays(MAX_HORIZON.toLong()))) continue
            val phi = phiAt(t) ?: continue
            for (h in 1..MAX_HORIZON) {
                val actual = s7[t.plusDays(h.toLong())] ?: continue
                val pred = base.getValue(t) + Math.pow(phi, h.toDouble()) * dev.getValue(t)
                errModel += abs(actual - pred)
                errPers += abs(actual - s7.getValue(t))
                signed += actual - pred
            }
        }
        if (errModel.size < MIN_EVALUATED) return empty(label, unit, "te weinig voorspelde punten (${errModel.size}, minimum $MIN_EVALUATED)", s7[last])
        val mm = errModel.average()
        val mp = errPers.average()
        val passed = mm <= (1 - cfg.modelGain) * mp
        val reason = if (passed) null else "een voorspelling was in het verleden niet minstens ${(cfg.modelGain * 100).toInt()} % beter dan 'zoals vandaag'"
        val forecasts = if (passed) {
            val phi = phiAt(last.plusDays(1))
            val lo = LoadStats.percentile(signed, 0.10)!!
            val hi = LoadStats.percentile(signed, 0.90)!!
            if (phi == null || !base.containsKey(last)) emptyList() else (1..MAX_HORIZON).map { h ->
                val v = base.getValue(last) + Math.pow(phi, h.toDouble()) * dev.getValue(last)
                DayForecast(h, last.plusDays(h.toLong()), v, v + lo, v + hi)
            }
        } else emptyList()
        return ForecastCheck(label, unit, errModel.size, mm, mp, s7[last], passed, reason, s7[last], forecasts)
    }

    private fun empty(label: String, unit: String, reason: String, current: Double? = null) =
        ForecastCheck(label, unit, 0, null, null, null, false, reason, current, emptyList())
}
