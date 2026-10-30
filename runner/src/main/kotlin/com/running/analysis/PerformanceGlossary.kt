package com.running.analysis

/** One explained term. Shown behind a "?" next to the term and in the full list at the bottom of the page. */
data class Term(val key: String, val title: String, val text: String)

/** Plain-language explanations for every term on the performance pages. */
object PerformanceGlossary {
    private val list = listOf(
        Term(
            "stabiele-run", "Stabiele run",
            "Een rustige duurloop die betrouwbaar genoeg is om met andere runs te vergelijken. Voorwaarden: geen interval, tempo of wedstrijd; " +
                "gemiddelde hartslag onder 85 % van je max; na de opwarming (eerste 10 minuten) minstens 30 minuten aan een gelijkmatig tempo; " +
                "hooguit 15 hoogtemeter per km; en minstens 90 % hartslagdekking. Alleen zulke runs gebruikt de app voor efficiëntie, drift en duurzaamheid.",
        ),
        Term(
            "ef", "Aerobe efficiëntie (EF)",
            "Hoeveel meter je aflegt per hartslag. 1.25 m betekent: elke hartslag brengt je gemiddeld 1,25 meter vooruit. " +
                "Loop je even snel met een lagere hartslag, of sneller met dezelfde hartslag, dan stijgt de EF. Een stijgende EF over weken wijst op een " +
                "sterkere aerobe basis. De app meet de EF alleen over het stabiele deel van een stabiele run (na de eerste 10 minuten).",
        ),
        Term(
            "zonegroep", "Zonegroep",
            "Runs worden alleen vergeleken met runs met een gelijkaardige gemiddelde hartslag: zone 1-2 (rustig), zone 3 (matig) of zone 4 en hoger (zwaar). " +
                "Bij een hogere hartslag loop je ook sneller, dus een EF uit zone 3 is niet te vergelijken met een EF uit zone 1-2.",
        ),
        Term(
            "periodes", "Periode tegenover periode",
            "De app vergelijkt de laatste X dagen (recent) met de X dagen daarvoor (ervoor). " +
                "'9 ervoor, 4 recent' betekent: 9 stabiele runs in de vorige periode en 4 in de recente periode. " +
                "Pas vanaf 5 runs in elke periode geeft de app een oordeel. Je kunt de lengte van de periodes per onderdeel aanpassen.",
        ),
        Term(
            "oordeel", "Gunstig, stabiel, onzeker",
            "Gunstig of ongunstig: het verschil is groter dan de drempel (3 % voor EF) én groter dan de normale schommeling tussen je runs. " +
                "Stabiel: het verschil is kleiner dan de drempel. Onzeker: er is een verschil, maar je runs schommelen te veel om er zeker van te zijn. " +
                "Onvoldoende data: te weinig runs in een van beide periodes. Alle drempels zijn voorstellen van de app, niet wetenschappelijk vastgelegd.",
        ),
        Term(
            "gap", "GAP (hellingsgecorrigeerd tempo)",
            "GAP staat voor Grade Adjusted Pace: het tempo dat je met dezelfde inspanning op vlak terrein zou lopen. Bergop kost meer energie, " +
                "dus daar is je GAP sneller dan je echte tempo; bergaf omgekeerd. Op vlak terrein zijn tempo en GAP bijna gelijk. " +
                "De app berekent dit zelf uit de hellingsdata (formule van Minetti, 2002); het is niet de GAP die Strava toont.",
        ),
        Term(
            "drift", "Hartslagdrift",
            "Hoeveel hoger je hartslag in de tweede helft van het stabiele deel lag dan in de eerste helft. Alleen berekend als je tempo in beide helften " +
                "gelijk bleef (binnen 5 %); anders zegt een hogere hartslag niets. Een kleine drift is normaal; warmte, vochttekort, vermoeidheid of een " +
                "minder sterke basis maken ze groter. Lager is gunstiger.",
        ),
        Term(
            "ontkoppeling", "Ontkoppeling (aerobic decoupling)",
            "Hoeveel minder meter per hartslag (EF) je in de tweede helft had dan in de eerste helft. Kijkt naar tempo én hartslag samen, dus werkt ook als " +
                "je tempo iets veranderde. Positief = in de tweede helft minder efficiënt. Coaches gebruiken vaak 5 % als vuistregel voor een goed " +
                "opgebouwde duurbasis; die grens is niet wetenschappelijk gevalideerd. Lager is gunstiger.",
        ),
        Term(
            "stroomdata", "Stroomdata (detailmetingen)",
            "De meetreeksen die Strava per run bewaart: elke seconde (of om de paar seconden) de tijd, afstand, hartslag, hoogte en cadans. " +
                "Zonder stroomdata kent de app alleen de totalen (afstand, tijd, gemiddelde hartslag) en kan ze niet zien wat er tijdens de run gebeurde. " +
                "Stroomdata worden apart opgehaald; oudere runs en handmatig ingevoerde runs hebben ze vaak niet.",
        ),
        Term(
            "hartslagdekking", "Hartslagdekking",
            "Welk deel van je bewegingstijd bruikbare hartslagmetingen heeft. Metingen vallen weg bij stilstand, bij gaten van meer dan 30 seconden, " +
                "bij onmogelijke waarden (onder 40 of boven 230 bpm) en als de hartslag lang op één waarde blijft hangen (sensoruitval). " +
                "Onder 90 % gebruikt de app de run niet voor hartslaganalyses.",
        ),
        Term(
            "zones", "Hartslagzones",
            "Vijf hartslagzones, van zone 1 (heel rustig) tot zone 5 (maximaal). De app gebruikt je Garmin-zones als die er zijn, anders een schatting " +
                "op basis van je geschatte maximale hartslag. Je huidige zones worden ook op oude runs toegepast, omdat de app niet weet wanneer je ze wijzigde.",
        ),
        Term(
            "intensiteit", "Makkelijk, matig, zwaar",
            "Een groepering van de tijd in zones: makkelijk = zone 1-2, matig = zone 3, zwaar = zone 4-5. Veel lopers trainen het grootste deel rustig " +
                "en een klein deel zwaar. De app legt geen ideale verdeling op.",
        ),
        Term(
            "mediaan", "Mediaan",
            "De middelste waarde als je alle waarden sorteert. Eén uitschieter (een slechte dag, een haperende hartslagband) verschuift de mediaan nauwelijks, " +
                "een gemiddelde wel. Daarom gebruikt de app vaak de mediaan.",
        ),
        Term(
            "cs", "Kritische snelheid",
            "Een schatting van het hoogste tempo dat je lang kunt volhouden, ongeveer het tempo van een wedstrijd van 30 tot 60 minuten. " +
                "Berekend uit je snelste 1, 3, 5 en 10 km van de gekozen periode, standaard de laatste 90 dagen (stukken van 3 tot 30 minuten). Het is geen lactaatdrempel. " +
                "Liep je die stukken niet voluit, dan ligt je echte kritische snelheid hoger.",
        ),
        Term(
            "d-prime", "D′ (reserve boven kritische snelheid)",
            "Hoeveel meter je sneller dan je kritische snelheid kunt lopen voor je leeg bent, bijvoorbeeld bij een eindsprint of een korte rep. " +
                "Een schatting die sterk schommelt; gebruik ze als grootteorde.",
        ),
        Term(
            "anker", "Anker",
            "Een snelste stuk over een vaste afstand (1, 3, 5 of 10 km) uit de gekozen periode (standaard 90 dagen). De app trekt een rechte lijn door deze punten " +
                "(afstand tegenover tijd); de helling van die lijn is je kritische snelheid. Hoe beter de lijn past (R² dicht bij 1), hoe betrouwbaarder.",
        ),
        Term(
            "duurzaamheid", "Duurzaamheid",
            "Hoe goed je efficiëntie overeind blijft naarmate een lange run vordert. Na de opwarming wordt de run in blokken van 10 minuten verdeeld. " +
                "Per blok berekent de app de EF als % van het eerste blok: 100 % = even efficiënt als in het begin, 95 % = 5 % minder meter per hartslag. " +
                "Hoe dichter bij 100 % na een uur, hoe duurzamer.",
        ),
        Term(
            "lange-run", "Lange run",
            "Een run die op Strava als lange duurloop gemarkeerd is, of die minstens 75 minuten duurt én 1,5 keer langer is dan je gewone run " +
                "(mediaan van de laatste 90 dagen). De app vergelijkt het laatste derde met het eerste derde (na de eerste 10 minuten).",
        ),
        Term(
            "opbouw", "Opbouw",
            "Het herkende schema van een intervaltraining, bv. 4 x 1000 m of 6 x 3:00. Alleen trainingen met dezelfde opbouw worden met elkaar vergeleken. " +
                "'Gemengd' betekent reps van verschillende lengte (bv. een piramide); die worden niet vergeleken.",
        ),
        Term(
            "verval", "Verval",
            "Hoeveel trager je laatste rep was dan je eerste. Positief = je laatste rep was trager (je zakte weg). Negatief = je liep sneller naar het einde toe.",
        ),
        Term(
            "spreiding", "Spreiding",
            "Hoeveel je reptempo's onderling verschilden (variatiecoëfficiënt). Rond 1 % = heel gelijkmatig gelopen; 5 % of meer = grote verschillen tussen de reps.",
        ),
        Term(
            "hr-stijging", "HR-stijging",
            "Gemiddelde hartslag van de laatste rep min die van de eerste rep. Een stijging is normaal: je hart werkt harder naarmate je vermoeider wordt.",
        ),
        Term(
            "hrr", "Hartslagherstel (HRR60)",
            "Hoeveel je hartslag daalt in de 60 seconden na het einde van een rep. Een snellere daling gaat samen met een betere fitheid, maar hangt sterk af " +
                "van wat je in de rust doet; daarom worden stilstaan, wandelen en joggen apart gehouden. Een negatieve waarde betekent dat je hartslag nog steeg " +
                "(bij korte reps loopt de hartslag achter op de inspanning).",
        ),
        Term(
            "schommeling", "Schommeling weekafstand",
            "Hoeveel je wekelijkse kilometers van week tot week verschillen (standaardafwijking gedeeld door het gemiddelde). Rond 20 % = vrij constant; " +
                "50 % of meer = sterk wisselend, bv. door rustweken, pauzes of een wedstrijdperiode.",
        ),
        Term(
            "vermogen", "Loopvermogen",
            "Het vermogen in watt dat je horloge of voetsensor schat. Watt per hartslag: hoeveel vermogen je levert per hartslag (hoger = efficiënter). " +
                "Snelheid per watt: hoeveel snelheid je uit elke watt haalt. Alleen runs waarvan het apparaat zelf vermogen meet, tellen mee.",
        ),
        Term(
            "terrein", "Terreinklasse",
            "Op basis van de hoogtemeters per km volgens Strava: vlak (minder dan 5 m/km), glooiend (5 tot 15 m/km) of heuvelachtig (meer dan 15 m/km).",
        ),
        Term(
            "sessietype", "Sessietype",
            "De app herkent het soort training uit je rondes (laps): rustig, tempo, interval, strides of wedstrijd. Een herkenning, geen zekerheid; " +
                "markeer wedstrijden en lange duurlopen in Strava voor het beste resultaat.",
        ),
        Term(
            "soort", "Berekend, gemeten, app-schatting",
            "Gemeten: rechtstreeks door je horloge of Garmin vastgelegd. Berekend: uit je gemeten data afgeleid met een vaste formule. " +
                "App-schatting: een model of herkenning van de app, met meer onzekerheid (gestreepte rand).",
        ),
    )

    val terms: Map<String, Term> = list.associateBy { it.key }
    val all: List<Term> = list.sortedBy { it.title.lowercase() }
}
