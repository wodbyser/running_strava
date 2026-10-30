package com.running.garmin.recovery

import java.time.LocalDate

/**
 * One day of a metric with the own norm that applied on that day: median and robust SD of the N (default 28) days before it.
 * The "within own norm" band of that day is median ± 1 SD (same band as [Baseline.band]).
 */
data class NormPoint(val date: LocalDate, val value: Double?, val median: Double?, val sd: Double?)

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
        fun points(series: Map<LocalDate, Double>) = days.map { d ->
            val b = Baseline.compute(series, d, normDays)
            NormPoint(d, series[d], b.median, b.robustSd)
        }
        return DailySeries(
            hrv = points(inputs.hrv.toSeries { it.lastNightAvg }),
            restingHr = points(inputs.restingHr.toSeries { it.restingHr }),
            sleepMinutes = points(inputs.sleep.toSeries { it.sleepTimeSeconds?.let { s -> s / 60.0 } }),
            bodyBatteryWake = points(BodyBatteryAnalysis.wakeLevels(inputs.bodyBatterySamples, inputs.sleep).mapValues { it.value.toDouble() }),
            stressAvg = points(inputs.stress.filter { it.calendarDate != today }.toSeries { it.avgLevel }),
        )
    }
}
