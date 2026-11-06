package com.running.analysis

import com.running.race.RaceWindows

object RaceText {
    const val INTRO = "Welke tijd kan ik nu lopen, is mijn doel realistisch en hoe loop ik het best? Alles komt uit je eigen wedstrijden en trainingen, met altijd " +
        "de ankers en een bandbreedte erbij. Klik op een ? voor uitleg bij een begrip."
    const val INTRO_HISTORY = "Wat gebeurde er bij je vorige wedstrijden? Wat voorspelde de app, wat liep je echt, en welke gegevens liggen ernaast? " +
        "Dit zijn er maar enkele, dus de pagina somt ze op en trekt pas conclusies vanaf een minimum aantal wedstrijden."
    val SAFETY = RecoveryText.SAFETY
    const val PREDICTION_NOTE = com.running.race.Prediction.NOTE
    const val ASSOCIATION = "Verband, geen oorzaak."
    const val COURSE_NOTE = "Parcours en wind zitten niet in dit plan."
    const val CALM_NOTE = "De rustige start is een keuze van de app: onderzoek ondersteunt een gelijkmatig tempo of een iets rustigere start bij loopwedstrijden. De 2 % is geen bewezen waarde."
    const val CANDIDATE_NOTE = "Ruwe zoekregel op de naam, een hoge gemiddelde hartslag of een snel tempo. Jij beslist of het een wedstrijd was. Markeer ze op Strava als wedstrijd; markeringen op activiteiten ouder dan 30 dagen komen pas binnen na 'Markeringen opnieuw ophalen'."
}

/** Every adjustable period of the race pages, with the default shown as the current value (docs/ui-guidelines.md section 6). */
object RaceWindowSettings {
    val SPECS: List<WindowSpec> = listOf(
        WindowSpec("anchor", "Ankers uit de laatste", "dagen", 30, 730, 180),
        WindowSpec("chart", "Grafiek toont de laatste", "weken", 12, 156, 52),
        WindowSpec("trend", "Vergelijk met", "weken geleden", 2, 26, 8),
        WindowSpec("specific", "Doeltempo en langste run: laatste", "weken", 2, 26, 6),
        WindowSpec("weekcheck", "Wedstrijdweek vanaf", "dagen ervoor", 3, 14, 7),
        WindowSpec("taper7", "Taper, korte periode", "dagen", 3, 14, 7),
        WindowSpec("taper14", "Taper, lange periode", "dagen", 7, 28, 14),
        WindowSpec("retro", "Voorbereiding: laatste", "weken voor de wedstrijd", 4, 26, 8),
    )
    val SPEC_BY_KEY: Map<String, WindowSpec> = SPECS.associateBy { it.key }

    fun value(w: RaceWindows, key: String): Int = when (key) {
        "anchor" -> w.anchorDays
        "chart" -> w.chartWeeks
        "trend" -> w.trendWeeks
        "specific" -> w.specificWeeks
        "weekcheck" -> w.weekCheckDays
        "taper7" -> w.taperShortDays
        "taper14" -> w.taperLongDays
        "retro" -> w.retroWeeks
        else -> throw IllegalArgumentException("unknown window $key")
    }

    fun values(w: RaceWindows): Map<String, Int> = SPECS.associate { it.key to value(w, it.key) }

    fun changed(w: RaceWindows): Map<String, Int> = SPECS.filter { value(w, it.key) != it.default }.associate { it.key to value(w, it.key) }

    fun from(params: Map<String, String?>): RaceWindows {
        fun v(key: String): Int {
            val spec = SPEC_BY_KEY.getValue(key)
            return params[key]?.trim()?.toIntOrNull()?.coerceIn(spec.min, spec.max) ?: spec.default
        }
        return RaceWindows(
            anchorDays = v("anchor"), chartWeeks = v("chart"), trendWeeks = v("trend"), specificWeeks = v("specific"), weekCheckDays = v("weekcheck"),
            taperShortDays = v("taper7"), taperLongDays = maxOf(v("taper14"), v("taper7")), retroWeeks = v("retro"),
        )
    }
}

object RaceGlossary {
    private const val P = "/race"
    private const val H = "/race/history"

    private val list = listOf(
        Term(
            "riegel", "Riegel-voorspelling",
            "Een bekende formule: tijd op de nieuwe afstand = je tijd × (nieuwe afstand ÷ afstand van het anker)^1,06. Ze gaat uit van een volle inspanning en van gelijke training voor elke afstand. " +
                "De app rekent dit uit voor elk anker en neemt de middelste waarde. Voor de marathon is de formule vaak te optimistisch.",
            P,
        ),
        Term(
            "anker", "Anker",
            "Een inspanning waarop een voorspelling steunt: een wedstrijd, het snelste stuk van een standaardafstand (1, 5, 10 km, halve marathon, marathon) binnen een run, of een harde, gelijkmatige run. " +
                "Intervalreeksen zijn nooit een anker. Trailwedstrijden ook niet. Alleen ankers van de gekozen periode tellen, en voor een wedstrijd alleen ankers van vóór die dag.",
            P,
        ),
        Term(
            "bandbreedte", "Bandbreedte",
            "Het bereik waarbinnen je tijd waarschijnlijk valt. Met genoeg eigen wedstrijden is dat de kleinste en de grootste afwijking van je eerdere voorspellingen. " +
                "Met te weinig wedstrijden is het enkel de spreiding tussen je ankers, en dat is een zwakkere maat.",
            P,
        ),
        Term(
            "kritische-snelheid", "Kritische snelheid (tweede methode)",
            "Een model uit je snelste inspanningen van 3 tot 30 minuten. De app toont het als tweede getal naast Riegel, alleen voor 5 km en korter, en alleen als het model past. De twee worden nooit gemiddeld.",
            P,
        ),
        Term(
            "negatieve-split", "Negatieve split",
            "De tweede helft van een wedstrijd sneller lopen dan de eerste. 'Positief' betekent de tweede helft trager. De app toont hoe jij vroeger liep.",
            P,
        ),
        Term(
            "taper", "Taper",
            "Minder trainen in de dagen voor een wedstrijd. De app toont je belasting van de laatste dagen als percentage van je gemiddelde week daarvoor. Er is geen 'beste taper'.",
            H,
        ),
        Term(
            "belasting", "Belasting",
            "Een getal voor hoe zwaar een training was, uit je hartslag (zie de pagina Belasting). Alleen vergelijken met je eigen cijfers is zinvol.",
            H,
        ),
        Term(
            "eigen-norm", "Eigen norm",
            "Wat gewoon is voor jou: de middelste waarde van je eigen laatste weken. Een waarde buiten je norm is niet goed of slecht, alleen anders dan je gewoonte.",
            P,
        ),
    )

    val terms: Map<String, Term> = list.associateBy { it.key }
    fun forPage(page: String): List<Term> = list.filter { it.page == page || it.key == "eigen-norm" && page == H }.sortedBy { it.title.lowercase() }
}
