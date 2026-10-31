package com.running.analysis

import com.running.load.SessionClass

/** Plain-language explanations for the hard terms on the load pages. Easy words are not explained (docs/ui-guidelines.md). */
object LoadGlossary {
    private const val P = "/load"
    private const val R = "/load/response"

    private val list = listOf(
        Term(
            "belasting", "Belasting (TRIMP)",
            "Een getal voor hoe zwaar een training voor je lichaam was. De app rekent het uit met de hartslag van elke seconde (de formule van Banister): " +
                "hoe dichter je hartslag bij je maximum ligt en hoe langer dat duurt, hoe meer punten. 60 minuten rustig lopen is ongeveer 70 punten, " +
                "60 minuten bijna op je maximum meer dan 200. De punten zijn een schatting van de app en hebben geen eenheid. " +
                "Alleen vergelijken met je eigen cijfers is zinvol, niet met die van iemand anders.",
            P,
        ),
        Term(
            "eigen-norm", "Eigen norm",
            "Wat gewoon is voor jou: de middelste waarde van je eigen laatste weken, met een marge eromheen. Een week is 'hoog voor jou' als die duidelijk boven " +
                "wat je de laatste maanden gewoon was ligt. Dat zegt niets over goed of slecht, alleen over afstand tot je eigen gewoonte.",
            P,
        ),
        Term(
            "week-vergelijking", "7 dagen tegen je gemiddelde week",
            "De belasting van de laatste 7 dagen, vergeleken met je gemiddelde week van de laatste 4 weken. Het is een gewone vergelijking. " +
                "De app toont geen 'veilige zone': er is geen bewezen grens waarboven training gevaarlijk wordt, en de bekende verhouding tussen korte en lange belasting " +
                "voorspelt blessures niet betrouwbaar.",
            P,
        ),
        Term(
            "monotonie", "Monotonie en belastingsdruk",
            "Monotonie zegt hoe gelijkmatig je week was: het gemiddelde van je dagelijkse belasting gedeeld door de spreiding ervan. Elke dag evenveel trainen geeft een hoog getal, " +
                "harde en rustige dagen afwisselen een laag getal. Belastingsdruk is de weekbelasting maal de monotonie. " +
                "De app vergelijkt alleen met je eigen weken, niet met grenzen uit de literatuur.",
            P,
        ),
        Term(
            "tegensignaal", "Tegensignaal",
            "Iets in je gegevens dat pleit tegen een bepaald soort training vandaag, bijvoorbeeld herstelwaarden die afwijken of een harde training van gisteren. " +
                "Geen tegensignalen betekent niet dat je klaar bent om te trainen: pijn, ziekte en je eigen gevoel wegen zwaarder.",
            P,
        ),
        Term(
            "vermoeidheidssignalen", "Vermoeidheidssignalen",
            "Zes losse controles die elk een eigen regel hebben: HRV aanhoudend lager, rusthartslag aanhoudend hoger, slaaptekort, lagere efficiëntie, hoge belasting en hoge monotonie (de regels staan in de tabel). Ze worden niet tot één score samengevoegd. " +
                "Een signaal is 'actief' als jouw cijfers van vandaag afwijken van je eigen norm; 0 actief betekent dat niets afwijkt. Een signaal zonder data telt niet mee. " +
                "De app telt hoeveel er tegelijk actief zijn en laat zien hoe vaak dat de afgelopen 12 maanden voorkwam. Dat is geen kans op iets.",
            P,
        ),
        Term(
            "model", "Fitheid-vermoeidheidsmodel",
            "Een model uit de sportwetenschap met een eenvoudig idee: elke training doet twee dingen. Op lange termijn (weken) maakt ze je fitter, op korte termijn (dagen) maakt ze je moe. " +
                "Fit zijn en moe zijn werken tegen elkaar: je loopt het best als je veel opgebouwd hebt en weinig moe bent. " +
                "De app probeert dus uit je belasting te voorspellen hoe efficiënt je loopt (meter per hartslag). " +
                "De app toont de curve alleen als het model je efficiëntie in het verleden duidelijk beter voorspelde dan de simpele gok 'zoals de laatste 4 weken'. " +
                "Een getal voor 'vorm' toont de app nooit.",
            P,
        ),
        Term(
            "korte-lange", "Korte- en lange-termijnbelasting",
            "Twee voortschrijdende gemiddelden van je dagelijkse belasting: één die snel reageert (ongeveer 7 dagen) en één die traag reageert (ongeveer 6 weken). " +
                "Ze zijn de invoer van het model en worden hier alleen als gewone samenvatting getoond.",
            P,
        ),
        Term(
            "referentie", "Hartslagreferentie",
            "De vaste maximum- en rusthartslag waarmee elke training wordt omgerekend. De app gebruikt de huidige instelling van je Garmin voor al je runs. " +
                "Zo blijven oude trainingen vergelijkbaar, ook als je instelling verandert. Hoe de Garmin die waarden bepaalt is niet bekend.",
            P,
        ),
        Term(
            "marge", "Onzekerheidsmarge",
            "Een verschil is maar echt als het groter is dan het toeval. De app berekent daarom een marge (een zogeheten bootstrap-interval, 95 %). " +
                "Zit het getal 0 in die marge, dan is er geen duidelijk verschil. Hoe minder trainingen, hoe breder de marge.",
            R,
        ),
        Term(
            "losstaand", "Losstaande training",
            "Een training zonder andere zware training in de dagen ervoor en erna. Alleen zulke trainingen gebruikt de app voor reactie en hersteltijd, " +
                "omdat een tweede zware training het effect van de eerste zou vervuilen.",
            R,
        ),
        Term(
            "hersteltijd", "Hersteltijd (jouw eigen)",
            "Na hoeveel ochtenden je HRV en rusthartslag na zo'n training gemiddeld weer binnen je eigen norm zijn. " +
                "Dit is jouw eigen patroon uit je geschiedenis, niet de 'hersteltijd' van Garmin en geen voorschrift.",
            R,
        ),
        Term(
            "rustweek", "Rustweek",
            "Een volledige week met hooguit 70 % van de belasting van de 3 weken ervoor, nadat die weken normaal waren. Een week zonder run telt niet: dat is een onderbreking. " +
                "De app herkent rustweken automatisch; controleer of ze overeenkomen met wat jij weet.",
            R,
        ),
        Term(
            "onderbreking", "Onderbreking",
            "Minstens 14 dagen zonder run, of twee weken na elkaar met minder dan 30 % van je gewone belasting. De oorzaak (blessure, ziekte, vakantie) kent de app niet.",
            R,
        ),
        Term(
            "eigen-niveau", "Efficiëntie tegenover je eigen niveau",
            "De efficiëntie (meter per hartslag) van een stabiele run als percentage verschil met je mediaan van de 90 dagen ervoor: +2 % betekent 2 % efficiënter dan gewoonlijk, -2 % betekent 2 % minder efficiënt. Zo verdwijnt de trage verandering van je conditie en blijft het effect van die dag over.",
            R,
        ),
        Term(
            "verband", "Verband, geen oorzaak",
            "Dat twee dingen samen voorkomen betekent niet dat het ene het andere veroorzaakt. Slaap, ziekte, stress, weer en andere trainingen spelen mee. " +
                "De app toont wat je geschiedenis laat zien, geen advies over hoeveel je moet trainen.",
            R,
        ),
    )

    val terms: Map<String, Term> = list.associateBy { it.key }
    fun forPage(page: String): List<Term> = list.filter { it.page == page }.sortedBy { it.title.lowercase() }
}

object LoadText {
    const val INTRO = "Hoeveel heb je getraind, hoe zwaar was dat voor jou en hoe staat je lichaam er nu voor? Deze pagina zet je belasting, je herstel en je " +
        "vermoeidheidssignalen naast elkaar, steeds vergeleken met je eigen geschiedenis. Klik op een ? voor uitleg bij een begrip."
    const val INTRO_RESPONSE = "Hoe reageerde je lichaam in de ochtenden na een training, een rustdag of een rustweek? Alles komt uit je eigen geschiedenis, met altijd het " +
        "aantal trainingen erbij. Waar er te weinig data is, zegt de pagina dat. De pagina wordt opnieuw berekend elke keer je ze opent of herlaadt, " +
        "met de data van dat moment: nieuwe runs en Garmin-data komen binnen via de dagelijkse synchronisatie (06:00) of je eigen synchronisatie."
    val SAFETY = RecoveryText.SAFETY
    const val ASSOCIATION = "Verband, geen oorzaak. Slaap, ziekte, stress, warmte en andere trainingen beïnvloeden deze waarden."
    const val COLOUR_LEGEND = "Kleur betekent afstand tot je eigen norm, niet goed of slecht."

    fun className(c: SessionClass) = c.label
}
