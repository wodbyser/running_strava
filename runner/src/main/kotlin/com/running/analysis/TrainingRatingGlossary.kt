package com.running.analysis

/** The terms on the training assessment page that really need an explanation. */
object TrainingRatingGlossary {
    private const val PAGE = "/rate-training"

    private val list = listOf(
        Term(
            "gelijkmatigheid", "Gelijkmatigheid",
            "Hoeveel de reps (bij een intervaltraining) of de kilometers (bij een duurloop) in tempo van elkaar verschilden, als percentage van het " +
                "gemiddelde tempo. Bij reps noemt de app tot 3 % gelijkmatig, 3 tot 6 % een aandachtspunt en boven 6 % wisselend. " +
                "Bij heuvelachtig terrein, reps korter dan 60 seconden, minder dan drie reps of een ladder geeft de app geen oordeel: " +
                "dan zegt het tempo per ronde te weinig over de uitvoering.", PAGE,
        ),
        Term(
            "ontkoppeling", "Ontkoppeling",
            "Hoeveel minder meter per hartslag je in de tweede helft van een blok liep dan in de eerste helft. De app vergelijkt alleen als je tempo " +
                "in beide helften binnen 5 % bleef; anders zegt een hogere hartslag niets over je efficiëntie. Coaches gebruiken 5 % als vuistregel " +
                "voor een goed opgebouwde duurbasis en 10 % als duidelijke daling; die grenzen zijn niet wetenschappelijk gevalideerd. " +
                "Warmte, terrein, wind en een korte warming-up beïnvloeden het cijfer ook.", PAGE,
        ),
        Term(
            "herstel", "Herstel tussen de reps",
            "De rustige ronde(s) tussen de reps. De app vergelijkt het tempo van de pauze met het tempo van de reps: minder dan 3 % trager is " +
                "nauwelijks rust, 3 tot 10 % is matig en meer dan 10 % is duidelijk rustiger. Loopt de pauze bewust matig (een float, zoals bij " +
                "cruise-intervallen), dan hoort dat bij de training en is het geen fout. Herkent de app geen aparte pauzerondes, dan was dit " +
                "eerder een aaneengesloten snel blok dan een intervaltraining.", PAGE,
        ),
        Term(
            "max-hartslag", "Max-hartslag (schatting)",
            "De app schat je maximale hartslag op het 95e percentiel van je hoogste hartslag per run van de laatste 12 maanden. Dat is geen " +
                "max-HR-test: je echte maximum kan hoger liggen, dus een percentage van je max is een schatting en geen meting. " +
                "Zonder betrouwbare schatting toont de app geen percentage.", PAGE,
        ),
        Term(
            "pacing", "Pacing (positieve en negatieve split)",
            "Het verschil tussen de eerste en de tweede helft van een wedstrijd. Positief betekent dat de tweede helft trager was (een positieve " +
                "split), negatief dat ze sneller was (een negatieve split). Een gelijkmatige of licht versnellende wedstrijd is meestal de " +
                "snelste manier om te lopen, maar of dat lukte hangt ook af van het parcours en je strategie.", PAGE,
        ),
        Term(
            "uitvoering", "Uitvoering, geen schema",
            "Deze beoordeling kijkt alleen naar hoe je de training uitvoerde: gelijkmatigheid, verval, hartslag en herstel. Of de training nuttig " +
                "was voor je ontwikkeling hangt af van je doel en je schema, en die kent de app niet. Je gevoel (RPE), het weer, je slaap, pijn of " +
                "klachten, je schoenen en je voeding staan niet in de data en wegen zwaarder dan elk cijfer op deze pagina.", PAGE,
        ),
        Term(
            "sessietype", "Soort training",
            "De app herkent het soort training uit je rondes (tempo per ronde, met hartslag als controle), niet uit de naam die je in Strava gaf. " +
                "Dat is een heuristiek: heuvels, een loopband, korte reps of GPS-fouten kunnen de herkenning verstoren. Markeer wedstrijden in " +
                "Strava voor het beste resultaat.", PAGE,
        ),
    )

    val terms: Map<String, Term> = list.associateBy { it.key }
    val all: List<Term> = list.sortedBy { it.title.lowercase() }
}
