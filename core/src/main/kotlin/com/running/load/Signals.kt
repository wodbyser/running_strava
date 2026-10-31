package com.running.load

import com.running.garmin.recovery.Baseline
import com.running.garmin.recovery.BaselineStatus
import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.SustainedDirection
import java.time.LocalDate
import kotlin.math.abs

// ------------------------------------------------------------------ #11 fatigue signals

enum class SignalKey(val label: String, val rule: String) {
    HRV("HRV aanhoudend lager", "minstens 5 van de laatste 7 dagen onder je eigen norm"),
    RESTING_HR("Rusthartslag aanhoudend hoger", "minstens 5 van de laatste 7 dagen boven je eigen norm"),
    SLEEP("Slaaptekort", "gemiddeld meer dan 20 minuten per nacht onder je slaapdoel (laatste 7 nachten)"),
    EFFICIENCY("Efficiëntie lager", "stabiele runs van de laatste 14 dagen gemiddeld meer dan 3 % onder je eigen niveau (minstens 3 runs)"),
    LOAD("Belasting hoog voor jou", "belasting van de laatste 7 dagen boven je eigen 90e percentiel van de laatste 26 weken"),
    MONOTONY("Monotonie hoog voor jou", "monotonie van de laatste volledige week boven je eigen 90e percentiel van de laatste 26 weken"),
}

data class SignalInput(
    val hrvSustained: SustainedDirection?,
    val restingHrSustained: SustainedDirection?,
    val sleepBand: DeviationBand?,
    val efMedianPct: Double?,
    val efN: Int,
    val load7: Double?,
    val load7P90: Double?,
    val monotony: Double?,
    val monotonyP90: Double?,
)

/** `active` is null when the signal has no data (never counted as inactive). */
data class Signal(val key: SignalKey, val active: Boolean?)

data class SignalSet(val signals: List<Signal>, val active: Int, val withData: Int)

object FatigueSignals {
    const val EF_LIMIT_PCT = -3.0
    const val EF_MIN_RUNS = 3

    fun evaluate(i: SignalInput): SignalSet {
        fun sustained(d: SustainedDirection?, wanted: SustainedDirection): Boolean? = when (d) {
            null, SustainedDirection.INSUFFICIENT -> null
            else -> d == wanted
        }
        val signals = listOf(
            Signal(SignalKey.HRV, sustained(i.hrvSustained, SustainedDirection.LOWER)),
            Signal(SignalKey.RESTING_HR, sustained(i.restingHrSustained, SustainedDirection.HIGHER)),
            Signal(SignalKey.SLEEP, i.sleepBand?.let { it != DeviationBand.WITHIN }),
            Signal(SignalKey.EFFICIENCY, if (i.efMedianPct != null && i.efN >= EF_MIN_RUNS) i.efMedianPct < EF_LIMIT_PCT else null),
            Signal(SignalKey.LOAD, if (i.load7 != null && i.load7P90 != null) i.load7 > i.load7P90 else null),
            Signal(SignalKey.MONOTONY, if (i.monotony != null && i.monotonyP90 != null) i.monotony > i.monotonyP90 else null),
        )
        return SignalSet(signals, signals.count { it.active == true }, signals.count { it.active != null })
    }

    /** How many days of the history had at least k active signals (k = 1..6). A measure of how unusual a state is, not a probability. */
    fun baseRate(counts: List<Int>): Map<Int, Int> = (1..SignalKey.entries.size).associateWith { k -> counts.count { it >= k } }
}

// ------------------------------------------------------------------ #81 session-type readiness

enum class ReadinessType(val label: String) {
    EASY("Rustig"),
    LONG("Lange duurloop"),
    TEMPO("Tempo"),
    INTERVAL("Interval"),
    RACE("Wedstrijdtempo"),
}

enum class Stance(val text: String) {
    AGAINST("pleit ertegen"),
    FOR("pleit ervoor"),
    UNKNOWN("onbekend"),
}

enum class ReadinessLevel(val text: String) {
    NONE("Geen tegensignalen"),
    ONE("Eén tegensignaal"),
    MANY("Meerdere tegensignalen"),
    INSUFFICIENT("Onvoldoende data"),
}

data class ReadinessPart(val key: String, val title: String, val stance: Stance, val detail: String)

data class TypeReadiness(val type: ReadinessType, val level: ReadinessLevel, val parts: List<ReadinessPart>, val withData: Int)

data class ReadinessInput(
    /** Phase 2 summary: components outside the own norm, or null when the summary is not available. */
    val deviatingComponents: Int?,
    val anyStrongDeviation: Boolean,
    val daysSinceHard: Int?,
    val recoveryAfterHardClass: RecoveryTime?,
    val daysSinceLong: Int?,
    val recoveryAfterLong: RecoveryTime?,
    val load7Level: OwnLevel,
    /** Minutes the last night was under the owner's sleep target, or null when unknown. */
    val sleepShortfallMinutes: Double?,
    val activeSignals: Int?,
)

object SessionReadiness {
    const val FALLBACK_HARD_DAYS = 2
    const val FALLBACK_LONG_DAYS = 3
    const val SLEEP_LIMIT_MINUTES = 60.0
    const val SIGNAL_LIMIT = 3

    /** Mornings after which the owner's own recovery after a session is back within norm; null means "use the fixed fallback". */
    fun requiredDays(t: RecoveryTime?): Int? = when (t) {
        is RecoveryTime.Mornings -> t.mornings
        RecoveryTime.NoDeviation -> 1
        is RecoveryTime.Longer -> t.horizon
        is RecoveryTime.Insufficient, null -> null
    }

    private fun recovery(type: ReadinessType, i: ReadinessInput): ReadinessPart {
        val strongOnly = type == ReadinessType.EASY
        val title = "Herstel vandaag"
        val dev = i.deviatingComponents
        return when {
            dev == null -> ReadinessPart("recovery", title, Stance.UNKNOWN, "te weinig herstelwaarden om een samenvatting te geven")
            strongOnly && i.anyStrongDeviation -> ReadinessPart("recovery", title, Stance.AGAINST, "minstens één herstelwaarde wijkt sterk af van je norm")
            strongOnly -> ReadinessPart("recovery", title, Stance.FOR, "geen herstelwaarde wijkt sterk af (bij rustig lopen telt alleen een sterke afwijking)")
            dev >= 2 || i.anyStrongDeviation -> ReadinessPart("recovery", title, Stance.AGAINST, if (i.anyStrongDeviation) "minstens één herstelwaarde wijkt sterk af van je norm" else "$dev herstelwaarden wijken af van je norm")
            else -> ReadinessPart("recovery", title, Stance.FOR, if (dev == 0) "alle herstelwaarden binnen je norm" else "1 herstelwaarde wijkt af van je norm")
        }
    }

    private fun lastSession(key: String, title: String, daysSince: Int?, recovery: RecoveryTime?, fallback: Int, noun: String): ReadinessPart {
        if (daysSince == null) return ReadinessPart(key, title, Stance.UNKNOWN, "geen $noun gevonden in je data")
        val own = requiredDays(recovery)
        val need = own ?: fallback
        val source = if (own != null) "jouw eigen hersteltijd ($need ${if (need == 1) "ochtend" else "ochtenden"})"
        else "de vaste regel van $fallback dagen (je eigen hersteltijd is nog niet te bepalen)"
        val ago = if (daysSince == 0) "vandaag" else "$daysSince ${if (daysSince == 1) "dag" else "dagen"} geleden"
        return if (daysSince < need) ReadinessPart(key, title, Stance.AGAINST, "$noun $ago, korter dan $source")
        else ReadinessPart(key, title, Stance.FOR, "$noun $ago, niet korter dan $source")
    }

    private fun parts(type: ReadinessType, i: ReadinessInput): List<ReadinessPart> {
        val load = when (i.load7Level) {
            OwnLevel.HIGH -> ReadinessPart("load", "Belasting laatste 7 dagen", Stance.AGAINST, "hoog voor jou")
            OwnLevel.UNKNOWN -> ReadinessPart("load", "Belasting laatste 7 dagen", Stance.UNKNOWN, "te weinig historie om met je eigen norm te vergelijken")
            else -> ReadinessPart("load", "Belasting laatste 7 dagen", Stance.FOR, i.load7Level.text)
        }
        val short = i.sleepShortfallMinutes
        val sleep = when {
            short == null -> ReadinessPart("sleep", "Slaap laatste nacht", Stance.UNKNOWN, "geen slaapmeting van vannacht")
            short > SLEEP_LIMIT_MINUTES -> ReadinessPart("sleep", "Slaap laatste nacht", Stance.AGAINST, "${Math.round(short)} minuten onder je slaapdoel")
            else -> ReadinessPart("sleep", "Slaap laatste nacht", Stance.FOR, "niet meer dan ${SLEEP_LIMIT_MINUTES.toInt()} minuten onder je slaapdoel")
        }
        val active = i.activeSignals
        val signals = when {
            active == null -> ReadinessPart("signals", "Vermoeidheidssignalen", Stance.UNKNOWN, "te weinig data voor de signalen")
            active >= SIGNAL_LIMIT -> ReadinessPart("signals", "Vermoeidheidssignalen", Stance.AGAINST, "$active van 6 signalen actief")
            else -> ReadinessPart("signals", "Vermoeidheidssignalen", Stance.FOR, "$active van 6 signalen actief")
        }
        return when (type) {
            ReadinessType.EASY -> listOf(recovery(type, i), signals)
            ReadinessType.LONG -> listOf(recovery(type, i), lastSession("long", "Laatste lange duurloop", i.daysSinceLong, i.recoveryAfterLong, FALLBACK_LONG_DAYS, "lange duurloop"), load, sleep, signals)
            else -> listOf(recovery(type, i), lastSession("hard", "Laatste harde sessie", i.daysSinceHard, i.recoveryAfterHardClass, FALLBACK_HARD_DAYS, "harde sessie"), load, sleep, signals)
        }
    }

    fun evaluate(i: ReadinessInput): List<TypeReadiness> = ReadinessType.entries.map { t ->
        val p = parts(t, i)
        val withData = p.count { it.stance != Stance.UNKNOWN }
        val against = p.count { it.stance == Stance.AGAINST }
        val level = when {
            withData < 2 -> ReadinessLevel.INSUFFICIENT
            against == 0 -> ReadinessLevel.NONE
            against == 1 -> ReadinessLevel.ONE
            else -> ReadinessLevel.MANY
        }
        TypeReadiness(t, level, p, withData)
    }
}

/** Count of recovery values outside the own norm per morning (own norm only; used for history, #31 and #42). */
object DailyDeviation {
    data class Day(val withData: Int, val deviating: Int, val strong: Boolean) {
        val available: Boolean get() = withData >= 3
    }

    fun forDay(series: RecoverySeries, date: LocalDate, normDays: Int): Day {
        var withData = 0
        var deviating = 0
        var strong = false
        for (m in ResponseMetric.entries) {
            val s = series.of(m)
            val v = s[date] ?: continue
            val b = Baseline.compute(s, date, normDays, v)
            if (b.status != BaselineStatus.OK || b.deviation == null) continue
            withData++
            if (abs(b.deviation) >= 1.0) deviating++
            if (abs(b.deviation) >= 2.0) strong = true
        }
        return Day(withData, deviating, strong)
    }
}
