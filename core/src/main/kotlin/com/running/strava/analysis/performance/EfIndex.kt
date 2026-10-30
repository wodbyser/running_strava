package com.running.strava.analysis.performance

import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.EfficiencyFactor
import java.time.LocalDate
import java.time.YearMonth

enum class Stratum(val text: String) {
    ZONE_1_2("zone 1-2"),
    ZONE_3("zone 3"),
    ZONE_4_PLUS("zone 4 en hoger"),
    ;

    companion object {
        fun of(avgHr: Double, set: HrZoneSet): Stratum = when (set.zoneOf(Math.round(avgHr).toInt())) {
            1, 2 -> ZONE_1_2
            3 -> ZONE_3
            else -> ZONE_4_PLUS
        }
    }
}

/** Result of the steady window of one run (#16). Distances and times are those of the window W. */
data class EfRun(
    val activityId: Long,
    val date: LocalDate,
    val distanceMeters: Double,
    val seconds: Double,
    val avgHr: Double,
    val ef: Double,
    val efGap: Double?,
    val tempC: Double?,
    val elevationPerKm: Double,
) {
    val avgSpeed: Double get() = distanceMeters / seconds
    val windowMinutes: Double get() = seconds / 60
}

data class EfTrend(
    val stratum: Stratum,
    val windowDays: Int,
    val nA: Int,
    val nB: Int,
    val pooledA: Double?,
    val pooledB: Double?,
    val medianEfA: Double?,
    val medianEfB: Double?,
    val medianSpeedA: Double?,
    val medianSpeedB: Double?,
    val medianHrA: Double?,
    val medianHrB: Double?,
    val tempA: Double?,
    val tempB: Double?,
    val medianElevA: Double?,
    val medianElevB: Double?,
    val changePct: Double?,
    val status: EfVerdict.Status,
    /** The verdict was limited to UNCERTAIN because the temperature differed too much between the windows. */
    val tempCapped: Boolean,
) {
    val direction: Direction get() = when (status) {
        EfVerdict.Status.IMPROVED -> Direction.HIGHER
        EfVerdict.Status.DECLINED -> Direction.LOWER
        EfVerdict.Status.STABLE -> Direction.STABLE
        EfVerdict.Status.UNCERTAIN -> Direction.UNCERTAIN
        EfVerdict.Status.INSUFFICIENT_DATA -> Direction.INSUFFICIENT
    }
}

data class EfMonth(val month: String, val stratum: Stratum, val medianEf: Double?, val runs: Int)

/** #16 aerobic efficiency index and #17 trends: EF of the steady window, compared inside one HR stratum only. */
object EfIndex {

    private fun pooled(runs: List<EfRun>) =
        EfficiencyFactor.pooled(runs.map { EfficiencyFactor.Sample(it.distanceMeters, it.seconds, it.avgHr) })

    /** Runs of [stratum] whose date is in (today - [fromDaysBack], today - [toDaysBack]]. */
    private fun window(runs: List<Pair<EfRun, Stratum>>, stratum: Stratum, today: LocalDate, fromDaysBack: Long, toDaysBack: Long) =
        runs.filter { (r, s) -> s == stratum && r.date.isAfter(today.minusDays(fromDaysBack)) && !r.date.isAfter(today.minusDays(toDaysBack)) }
            .map { it.first }

    fun trend(
        runs: List<Pair<EfRun, Stratum>>,
        stratum: Stratum,
        today: LocalDate,
        windowDays: Int,
        config: PerformanceConfig = PerformanceConfig.DEFAULT,
    ): EfTrend {
        val a = window(runs, stratum, today, windowDays * 2L, windowDays.toLong())
        val b = window(runs, stratum, today, windowDays.toLong(), 0)
        val verdict = EfVerdict.evaluate(pooled(a), pooled(b), a.map { it.ef }, b.map { it.ef }, config.minRunsTrend)
        val tA = Stats.median(a.mapNotNull { it.tempC })
        val tB = Stats.median(b.mapNotNull { it.tempC })
        val capped = tA != null && tB != null && kotlin.math.abs(tA - tB) > config.maxTempGapC &&
            (verdict.status == EfVerdict.Status.IMPROVED || verdict.status == EfVerdict.Status.DECLINED)
        return EfTrend(
            stratum = stratum, windowDays = windowDays, nA = a.size, nB = b.size,
            pooledA = pooled(a), pooledB = pooled(b),
            medianEfA = Stats.median(a.map { it.ef }), medianEfB = Stats.median(b.map { it.ef }),
            medianSpeedA = Stats.median(a.map { it.avgSpeed }), medianSpeedB = Stats.median(b.map { it.avgSpeed }),
            medianHrA = Stats.median(a.map { it.avgHr }), medianHrB = Stats.median(b.map { it.avgHr }),
            tempA = tA, tempB = tB,
            medianElevA = Stats.median(a.map { it.elevationPerKm }), medianElevB = Stats.median(b.map { it.elevationPerKm }),
            changePct = verdict.changePct,
            status = if (capped) EfVerdict.Status.UNCERTAIN else verdict.status,
            tempCapped = capped,
        )
    }

    fun monthly(runs: List<Pair<EfRun, Stratum>>): List<EfMonth> =
        runs.groupBy { YearMonth.from(it.first.date) to it.second }
            .toSortedMap(compareBy({ it.first }, { it.second.ordinal }))
            .map { (key, list) -> EfMonth(key.first.toString(), key.second, Stats.median(list.map { it.first.ef }), list.size) }
}

/** #21: four slow indicators side by side. A way of presenting, not a score: no number, no weights (rule 6). */
object FitnessView {
    enum class Favourable { WHEN_HIGHER, WHEN_LOWER }

    data class Indicator(
        val key: String,
        val title: String,
        val direction: Direction,
        val favourable: Favourable,
        val meaning: String,
        val nA: Int,
        val nB: Int,
        val detail: String,
    ) {
        /** True favourable, false unfavourable, null when there is no direction. */
        val isFavourable: Boolean? get() = when (direction) {
            Direction.HIGHER -> favourable == Favourable.WHEN_HIGHER
            Direction.LOWER -> favourable == Favourable.WHEN_LOWER
            else -> null
        }
    }

    data class Summary(
        val indicators: List<Indicator>,
        val favourable: Int,
        val unfavourable: Int,
        val stable: Int,
        val uncertain: Int,
        val insufficient: Int,
        val hasSummary: Boolean,
        val text: String,
    )

    const val MIN_INDICATORS = 2

    fun summarize(indicators: List<Indicator>): Summary {
        val fav = indicators.count { it.isFavourable == true }
        val unfav = indicators.count { it.isFavourable == false }
        val stable = indicators.count { it.direction == Direction.STABLE }
        val uncertain = indicators.count { it.direction == Direction.UNCERTAIN }
        val insufficient = indicators.count { it.direction == Direction.INSUFFICIENT }
        val withVerdict = indicators.size - insufficient
        val has = withVerdict >= MIN_INDICATORS
        val text = if (has) {
            "Van ${indicators.size} indicatoren: $fav in gunstige richting, $unfav in ongunstige richting, $stable stabiel, " +
                "$uncertain onzeker, $insufficient onvoldoende data."
        } else {
            "Onvoldoende data voor een samenvatting (minstens $MIN_INDICATORS indicatoren met een oordeel nodig, nu $withVerdict)."
        }
        return Summary(indicators, fav, unfav, stable, uncertain, insufficient, has, text)
    }
}
