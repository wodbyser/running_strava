package com.running.garmin.recovery

import java.time.LocalDate

/**
 * One day of a metric with the norm that applied on that day. `median`/`sd` are the own norm: median and robust SD of the
 * N (default 28) days before that day, so the "within own norm" band is median ± 1 SD (same band as [Baseline.band]).
 *
 * HRV carries Garmin's balanced range of that day instead (`bandLow`/`bandHigh`): the green band of the chart and the
 * colour of the table cell then mean exactly the same as the HRV conclusion, which uses Garmin's range (owner decision
 * 2026-11-15). Every other metric leaves both null and uses the own norm band.
 *
 * `side` says which side counts as deviating: resting heart rate is one-sided, so a value below the norm stays green.
 */
data class NormPoint(
    val date: LocalDate,
    val value: Double?,
    val median: Double?,
    val sd: Double?,
    val bandLow: Double? = null,
    val bandHigh: Double? = null,
    val side: NormSide = NormSide.BOTH_SIDES,
)

/** Daily series of the five recovery metrics, each with its rolling own norm. For charts; no verdicts. */
data class DailySeries(
    val hrv: List<NormPoint>,
    val restingHr: List<NormPoint>,
    val sleepMinutes: List<NormPoint>,
    val bodyBatteryWake: List<NormPoint>,
    val stressAvg: List<NormPoint>,
) {
    companion object {
        val EMPTY = DailySeries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object DailyNorms {
    /** Same series as the analyses use. Stress of [today] is left out (the day is not complete). */
    fun series(inputs: RecoveryInputs, from: LocalDate, to: LocalDate, today: LocalDate, normDays: Int = Baseline.PRIMARY_WINDOW): DailySeries {
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        fun points(series: Map<LocalDate, Double>, side: NormSide = NormSide.BOTH_SIDES, band: (LocalDate) -> Pair<Double, Double>? = { null }) = days.map { d ->
            val b = Baseline.compute(series, d, normDays)
            val g = band(d)
            NormPoint(d, series[d], b.median, b.robustSd, g?.first, g?.second, side)
        }
        val garminRanges = inputs.hrv.mapNotNull { d ->
            val low = d.baselineBalancedLow ?: return@mapNotNull null
            val upper = d.baselineBalancedUpper ?: return@mapNotNull null
            if (low <= 0 || upper <= low) null else d.calendarDate to (low.toDouble() to upper.toDouble())
        }.toMap()
        return DailySeries(
            hrv = points(inputs.hrv.toSeries { it.lastNightAvg }) { garminRanges[it] },
            restingHr = points(inputs.restingHr.toSeries { it.restingHr }, NormSide.HIGHER_ONLY),
            sleepMinutes = points(inputs.sleep.toSeries { it.sleepTimeSeconds?.let { s -> s / 60.0 } }),
            bodyBatteryWake = points(BodyBatteryAnalysis.wakeLevels(inputs.bodyBatterySamples, inputs.sleep).mapValues { it.value.toDouble() }),
            stressAvg = points(inputs.stress.filter { it.calendarDate != today }.toSeries { it.avgLevel }),
        )
    }
}
