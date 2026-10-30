package com.running.analysis

import com.running.garmin.recovery.RecoveryWindows

/** The adjustable periods of the Herstel page: URL keys, input labels and allowed ranges. Defaults = [RecoveryWindows.DEFAULT]. */
object RecoveryWindowSettings {
    private val d = RecoveryWindows.DEFAULT

    val SPECS: List<WindowSpec> = listOf(
        WindowSpec("norm", "Eigen norm: mediaan van de laatste", "dagen", 14, 90, d.normDays),
        WindowSpec("chart", "Toon de laatste", "dagen", 14, 365, d.chartDays),
        WindowSpec("trend", "Trend: laatste", "dagen", 3, 28, d.trendRecentDays),
        WindowSpec("trendBase", "tegenover de", "dagen ervoor", 28, 180, d.trendBaselineDays),
        WindowSpec("debt", "Slaaptekort over de laatste", "nachten", 3, 28, d.debtNights),
        WindowSpec("reg", "Regelmaat over de laatste", "nachten", 7, 60, d.regularityNights),
        WindowSpec("table", "Toon de laatste", "dagen", 3, 60, d.tableDays),
    )
    val SPEC_BY_KEY: Map<String, WindowSpec> = SPECS.associateBy { it.key }

    fun from(params: Map<String, String?>): RecoveryWindows {
        fun v(key: String): Int {
            val spec = SPEC_BY_KEY.getValue(key)
            return params[key]?.trim()?.toIntOrNull()?.coerceIn(spec.min, spec.max) ?: spec.default
        }
        return RecoveryWindows(
            normDays = v("norm"), trendRecentDays = v("trend"), trendBaselineDays = v("trendBase"),
            debtNights = v("debt"), regularityNights = v("reg"), chartDays = v("chart"), tableDays = v("table"),
        )
    }

    fun values(w: RecoveryWindows): Map<String, Int> = mapOf(
        "norm" to w.normDays, "chart" to w.chartDays, "trend" to w.trendRecentDays, "trendBase" to w.trendBaselineDays,
        "debt" to w.debtNights, "reg" to w.regularityNights, "table" to w.tableDays,
    )

    /** Only the values that differ from the default, so forms and links keep the user's other choices. */
    fun changed(w: RecoveryWindows): Map<String, Int> = values(w).filter { (k, v) -> v != SPEC_BY_KEY.getValue(k).default }
}
