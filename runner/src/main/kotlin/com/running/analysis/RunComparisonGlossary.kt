package com.running.analysis

/** The terms on the "Trainingen vergelijken" page that need an explanation. Shared terms are reused from the other pages. */
object RunComparisonGlossary {
    const val PAGE = "/compare-runs"

    private val own = listOf(
        Term(
            "meetruis", "Gelijk binnen de meetruis",
            "Een verschil telt pas als het groter is dan wat de meting zelf kan schommelen. Voor tempo is dat 1 % (GPS meet de afstand " +
                "niet tot op de meter), voor meter per hartslag 3 % (dezelfde grens als op Prestaties), voor percentages zoals ontkoppeling, " +
                "temposchommeling en verval 1 procentpunt, en voor hartslagherstel 3 bpm. Ontkoppeling en verval onder 0 % tellen als 0 %. Kleinere verschillen noemt de app 'gelijk': " +
                "dan wint geen enkele run dat onderdeel. In de grafiekjes is die marge de lichte band naast de beste waarde.", PAGE,
        ),
        Term(
            "voorbehoud", "Onder voorbehoud",
            "De runs verschillen in iets dat de betekenis van dat onderdeel verandert, bijvoorbeeld een andere soort training, een andere " +
                "afstand, ander terrein, meer dan 5 °C verschil in temperatuur of een duidelijk andere hartslag. De beste run blijft vermeld, " +
                "want het cijfer klopt, maar het zegt dan minder over je vorm. Bij reps met een andere opbouw of hartslagherstel met een andere " +
                "manier van herstellen geeft de app helemaal geen winnaar.", PAGE,
        ),
        Term(
            "telling", "Hoe de app de beste run kiest",
            "Er is geen totaalscore. Per onderdeel kijkt de app wie het best was; runs binnen de meetruis van de beste delen die plaats. " +
                "De conclusie noemt de run die het vaakst bij de besten was; bij een gelijke stand beslist wie het vaakst alleen de beste was. " +
                "GAP telt niet apart mee, want " +
                "het is dezelfde snelheid, gecorrigeerd voor hellingen. Elk onderdeel weegt even zwaar; of snelheid of hartslag voor jou " +
                "belangrijker is, hangt af van het doel van je training, en dat kent de app niet.", PAGE,
        ),
        Term(
            "temposchommeling", "Temposchommeling",
            "Hoeveel je tempo verschilde tussen blokken van 5 minuten na de opwarming (de variatiecoëfficiënt). Rond 2 tot 4 % is een " +
                "gelijkmatige duurloop; boven 10 % telt een run niet als stabiel. Heuvels, verkeerslichten en bochten verhogen het cijfer ook. " +
                "Bij een intervaltraining horen tempowissels erbij, dus daar vergelijkt de app dit niet.", PAGE,
        ),
    )

    private val shared = listOf("ef", "gap", "ontkoppeling", "drift", "stabiele-run", "opbouw", "spreiding", "verval", "hrr", "terrein", "sessietype", "zones")
        .mapNotNull { PerformanceGlossary.terms[it]?.copy(page = PAGE) } +
        listOfNotNull(TrainingRatingGlossary.terms["uitvoering"]?.copy(page = PAGE))

    val all: List<Term> = (own + shared).sortedBy { it.title.lowercase() }
    val terms: Map<String, Term> = all.associateBy { it.key }
}
