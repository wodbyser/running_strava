package com.running.race

import com.running.load.ReadinessLevel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class RStance(val text: String) {
    FOR("pleit ervoor"),
    AGAINST("pleit ertegen"),
    NEUTRAL("neutraal"),
    UNKNOWN("onbekend"),
}

data class RPart(val key: String, val title: String, val stance: RStance, val detail: String)

/** A readiness block: components and a level from the count of counter-signals. No number (spec rule 6). */
data class ReadinessBlock(val parts: List<RPart>, val level: ReadinessLevel, val withData: Int)

data class FitnessInput(
    val predictionNow: Double?,
    val predictionBefore: Double?,
    val trendWeeks: Int,
    val indicatorsBetter: Int,
    val indicatorsWorse: Int,
    val indicatorsWithVerdict: Int,
    /** Null without a goal with a target time. */
    val specificSeconds: Double?,
    val specificWeeks: Int,
    val goalDistanceMeters: Double?,
    val longestRunMeters: Double?,
    /** Days since the most recent race or anchor within 0.4 to 2.5 x of the goal distance, null when none. */
    val daysSinceAnchor: Int?,
)

/** #50. Fitness on the long term; the short-term recovery block is the Phase 4 "Wedstrijdtempo" readiness, shown separately. */
object RaceReadiness {
    const val TREND_LIMIT_PCT = 1.0

    fun fitness(i: FitnessInput, cfg: RaceConfig = RaceConfig.DEFAULT, hasGoal: Boolean = true): ReadinessBlock {
        val parts = ArrayList<RPart>()

        val now = i.predictionNow
        val before = i.predictionBefore
        parts += if (now == null || before == null) {
            RPart("trend", "Voorspelling tegenover ${i.trendWeeks} weken geleden", RStance.UNKNOWN, "geen voorspelling nu of toen")
        } else {
            val change = (now / before - 1) * 100
            val text = "%+.1f %%".format(java.util.Locale.ROOT, change).replace('.', ',')
            if (change <= TREND_LIMIT_PCT) RPart("trend", "Voorspelling tegenover ${i.trendWeeks} weken geleden", RStance.FOR, "$text (sneller of gelijk, binnen ${TREND_LIMIT_PCT.toInt()} %)")
            else RPart("trend", "Voorspelling tegenover ${i.trendWeeks} weken geleden", RStance.AGAINST, "$text (trager dan ${TREND_LIMIT_PCT.toInt()} %)")
        }

        parts += when {
            i.indicatorsWithVerdict == 0 -> RPart("condition", "Conditietrend", RStance.UNKNOWN, "nog geen indicator met een oordeel")
            i.indicatorsBetter >= 2 && i.indicatorsWorse == 0 -> RPart("condition", "Conditietrend", RStance.FOR, "${i.indicatorsBetter} indicatoren beter, geen slechter")
            i.indicatorsWorse >= 2 -> RPart("condition", "Conditietrend", RStance.AGAINST, "${i.indicatorsWorse} indicatoren slechter")
            i.indicatorsBetter == 0 && i.indicatorsWorse == 0 -> RPart("condition", "Conditietrend", RStance.NEUTRAL, "geen duidelijke verandering (stabiel of onzeker)")
            else -> RPart("condition", "Conditietrend", RStance.NEUTRAL, "gemengd beeld (${i.indicatorsBetter} beter, ${i.indicatorsWorse} slechter)")
        }

        if (hasGoal) {
            val minutes = i.specificSeconds?.let { it / 60 }
            parts += when {
                minutes == null -> RPart("specific", "Lopen op doeltempo (laatste ${i.specificWeeks} weken)", RStance.UNKNOWN, "geen doeltijd ingevuld")
                minutes >= cfg.specificityMinutes -> RPart("specific", "Lopen op doeltempo (laatste ${i.specificWeeks} weken)", RStance.FOR, "${Math.round(minutes)} minuten, minstens ${cfg.specificityMinutes}")
                else -> RPart("specific", "Lopen op doeltempo (laatste ${i.specificWeeks} weken)", RStance.AGAINST, "${Math.round(minutes)} minuten, onder de ${cfg.specificityMinutes}")
            }
            val dist = i.goalDistanceMeters
            if (dist != null && dist >= 21_000.0) {
                val longest = i.longestRunMeters
                parts += when {
                    longest == null -> RPart("longest", "Langste run (laatste ${i.specificWeeks} weken)", RStance.UNKNOWN, "geen run in deze periode")
                    longest >= dist * cfg.longestRunShare -> RPart("longest", "Langste run (laatste ${i.specificWeeks} weken)", RStance.FOR, "%.1f km, minstens %d %% van de wedstrijd".format(java.util.Locale.ROOT, longest / 1000, Math.round(cfg.longestRunShare * 100)).replace('.', ','))
                    else -> RPart("longest", "Langste run (laatste ${i.specificWeeks} weken)", RStance.AGAINST, "%.1f km, onder %d %% van de wedstrijd".format(java.util.Locale.ROOT, longest / 1000, Math.round(cfg.longestRunShare * 100)).replace('.', ','))
                }
            }
        }

        val d = i.daysSinceAnchor
        parts += if (d == null) RPart("recent", "Recente wedstrijd of inspanning", RStance.AGAINST, "geen wedstrijd of inspanning in de buurt van deze afstand")
        else if (d <= cfg.recentAnchorDays) RPart("recent", "Recente wedstrijd of inspanning", RStance.FOR, "$d dagen geleden, binnen ${cfg.recentAnchorDays} dagen")
        else RPart("recent", "Recente wedstrijd of inspanning", RStance.AGAINST, "$d dagen geleden, ouder dan ${cfg.recentAnchorDays} dagen")

        val withData = parts.count { it.stance != RStance.UNKNOWN }
        val against = parts.count { it.stance == RStance.AGAINST }
        val level = when {
            withData < 2 -> ReadinessLevel.INSUFFICIENT
            against == 0 -> ReadinessLevel.NONE
            against == 1 -> ReadinessLevel.ONE
            else -> ReadinessLevel.MANY
        }
        return ReadinessBlock(parts, level, withData)
    }
}

/** #55. Only visible from 7 days before a goal race to the race day itself. */
object RaceWeek {
    fun daysToGo(goalDate: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, goalDate)

    fun visible(goalDate: LocalDate, today: LocalDate, checkDays: Int = 7): Boolean = daysToGo(goalDate, today) in 0..checkDays.toLong()

    /** Last 7 days of load as a percentage of the average week of the 28 days ending yesterday (plain percentage, no zone). */
    fun loadPct(daily: Map<LocalDate, Double>, today: LocalDate, minKnownShare: Double = 0.70): Double? {
        val yesterday = today.minusDays(1)
        val last7 = com.running.load.Rolling.window(daily, yesterday, 7, minKnownShare)
        val chronic = com.running.load.Rolling.window(daily, yesterday, 28, minKnownShare)
        val a = last7.sum ?: return null
        val c = chronic.perWeek ?: return null
        return if (c <= 0) null else a / c * 100
    }
}
