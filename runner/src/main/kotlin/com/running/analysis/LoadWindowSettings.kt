package com.running.analysis

import com.running.load.LoadWindows

/** Every adjustable period of the load pages, with the default shown as the current value (docs/ui-guidelines.md section 6). */
object LoadWindowSettings {
    val SPECS: List<WindowSpec> = listOf(
        WindowSpec("acute", "Korte periode", "dagen", 3, 28, 7),
        WindowSpec("chronic", "Gemiddelde week over de laatste", "dagen", 14, 120, 28),
        WindowSpec("chart", "Grafieken tonen de laatste", "dagen", 60, 730, 180),
        WindowSpec("band", "Je eigen norm uit de laatste", "weken", 8, 104, 26),
        WindowSpec("follow", "Ochtenden na een training", "ochtenden", 1, 5, 3),
        WindowSpec("isolate", "Geen andere zware training in de", "dagen ervoor", 0, 7, 2),
        WindowSpec("horizon", "Hersteltijd zoeken tot", "ochtenden", 3, 10, 5),
        WindowSpec("deload", "Rustweek: hooguit", "% van de weken ervoor", 40, 95, 70),
        WindowSpec("detrain", "Onderbreking vanaf", "dagen", 7, 60, 14),
        WindowSpec("efdays", "Efficiëntiesignaal over de laatste", "dagen", 7, 60, 14),
        WindowSpec("norm", "Herstelnorm uit de laatste", "dagen", 14, 90, 28),
    )
    val SPEC_BY_KEY: Map<String, WindowSpec> = SPECS.associateBy { it.key }

    fun value(w: LoadWindows, key: String): Int = when (key) {
        "acute" -> w.acuteDays
        "chronic" -> w.chronicDays
        "chart" -> w.chartDays
        "band" -> w.bandWeeks
        "follow" -> w.followUpMornings
        "isolate" -> w.isolationDays
        "horizon" -> w.recoveryHorizon
        "deload" -> w.deloadPct
        "detrain" -> w.detrainingDays
        "efdays" -> w.efSignalDays
        "norm" -> w.normDays
        else -> throw IllegalArgumentException("unknown window $key")
    }

    fun values(w: LoadWindows): Map<String, Int> = SPECS.associate { it.key to value(w, it.key) }

    /** Only the values that differ from the default, so links and forms keep the user's other choices. */
    fun changed(w: LoadWindows): Map<String, Int> = SPECS.filter { value(w, it.key) != it.default }.associate { it.key to value(w, it.key) }

    fun from(params: Map<String, String?>): LoadWindows {
        fun v(key: String): Int {
            val spec = SPEC_BY_KEY.getValue(key)
            return params[key]?.trim()?.toIntOrNull()?.coerceIn(spec.min, spec.max) ?: spec.default
        }
        return LoadWindows(
            acuteDays = v("acute"), chronicDays = maxOf(v("chronic"), v("acute")), chartDays = v("chart"), bandWeeks = v("band"),
            followUpMornings = v("follow"), isolationDays = v("isolate"), recoveryHorizon = maxOf(v("horizon"), v("follow") + 1),
            deloadPct = v("deload"), detrainingDays = v("detrain"), efSignalDays = v("efdays"), normDays = v("norm"),
        )
    }
}
