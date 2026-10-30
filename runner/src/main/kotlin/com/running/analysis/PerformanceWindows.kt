package com.running.analysis

/** One adjustable period on the performance page: URL key, input label, unit and allowed range. */
data class WindowSpec(val key: String, val label: String, val unit: String, val min: Int, val max: Int, val default: Int)

/**
 * Every period the performance overview uses, adjustable per section through URL parameters.
 * Defaults are the values the page always used; values outside the range are clamped.
 */
data class PerformanceWindows(
    /** Conditietrend: last N days against the N days before (also the critical speed comparison). */
    val trendDays: Int = 90,
    val efShortDays: Int = 28,
    val efLongDays: Int = 90,
    val zoneWeeks: Int = 26,
    val driftDays: Int = 90,
    /** Critical speed: fastest efforts of the last N days. */
    val csDays: Int = 90,
    val intensityMonths: Int = 18,
    val mixWeeks: Int = 52,
    val consistencyShortWeeks: Int = 12,
    val consistencyLongWeeks: Int = 52,
    val durabilityDays: Int = 90,
    val longRunDays: Int = 90,
    val hrrDays: Int = 90,
    /** Power: last N days against the N days before. */
    val powerDays: Int = 90,
) {
    fun value(key: String): Int = when (key) {
        "trend" -> trendDays
        "short" -> efShortDays
        "long" -> efLongDays
        "zoneWeeks" -> zoneWeeks
        "drift" -> driftDays
        "cs" -> csDays
        "months" -> intensityMonths
        "mixWeeks" -> mixWeeks
        "consShort" -> consistencyShortWeeks
        "consLong" -> consistencyLongWeeks
        "dur" -> durabilityDays
        "lr" -> longRunDays
        "hrr" -> hrrDays
        "power" -> powerDays
        else -> throw IllegalArgumentException("unknown window $key")
    }

    /** Current value per key, for the input fields. */
    val values: Map<String, Int> get() = SPECS.associate { it.key to value(it.key) }

    /** Only the values that differ from the default, so links and forms keep the user's other choices. */
    val changed: Map<String, Int> get() = SPECS.filter { value(it.key) != it.default }.associate { it.key to value(it.key) }

    companion object {
        val DEFAULT = PerformanceWindows()

        val SPECS: List<WindowSpec> = listOf(
            WindowSpec("trend", "Vergelijk de laatste", "dagen", 28, 365, 90),
            WindowSpec("short", "Korte periode", "dagen", 7, 365, 28),
            WindowSpec("long", "Lange periode", "dagen", 7, 365, 90),
            WindowSpec("zoneWeeks", "Toon de laatste", "weken", 4, 156, 26),
            WindowSpec("drift", "Runs van de laatste", "dagen", 14, 730, 90),
            WindowSpec("cs", "Snelste stukken van de laatste", "dagen", 28, 365, 90),
            WindowSpec("months", "Toon de laatste", "maanden", 3, 60, 18),
            WindowSpec("mixWeeks", "Soort trainingen: laatste", "weken", 8, 156, 52),
            WindowSpec("consShort", "Korte periode", "weken", 4, 104, 12),
            WindowSpec("consLong", "Lange periode", "weken", 8, 260, 52),
            WindowSpec("dur", "Samenvatting over de laatste", "dagen", 28, 730, 90),
            WindowSpec("lr", "Samenvatting over de laatste", "dagen", 28, 730, 90),
            WindowSpec("hrr", "Metingen van de laatste", "dagen", 14, 730, 90),
            WindowSpec("power", "Vergelijk de laatste", "dagen", 28, 365, 90),
        )
        val SPEC_BY_KEY: Map<String, WindowSpec> = SPECS.associateBy { it.key }

        /** Reads the windows from request parameters; missing or invalid values fall back to the default. */
        fun from(params: Map<String, String?>): PerformanceWindows {
            fun v(key: String): Int {
                val spec = SPEC_BY_KEY.getValue(key)
                return params[key]?.trim()?.toIntOrNull()?.coerceIn(spec.min, spec.max) ?: spec.default
            }
            val (efS, efL) = v("short").let { s -> v("long").let { l -> minOf(s, l) to maxOf(s, l) } }
            val (cS, cL) = v("consShort").let { s -> v("consLong").let { l -> minOf(s, l) to maxOf(s, l) } }
            return PerformanceWindows(
                trendDays = v("trend"), efShortDays = efS, efLongDays = efL, zoneWeeks = v("zoneWeeks"), driftDays = v("drift"),
                csDays = v("cs"), intensityMonths = v("months"), mixWeeks = v("mixWeeks"),
                consistencyShortWeeks = cS, consistencyLongWeeks = cL, durabilityDays = v("dur"), longRunDays = v("lr"),
                hrrDays = v("hrr"), powerDays = v("power"),
            )
        }
    }
}
