package com.running.analysis

/**
 * Terms for the "Periodes vergelijken" page. Separate from [PerformanceGlossary] because this page pools
 * whole easy runs (not only the stable part) and compares two chosen periods instead of trailing windows.
 */
object CompareGlossary {
    private val list = listOf(
        Term(
            "ef", "Aerobe efficiëntie (EF)",
            "Hoeveel meter je per hartslag aflegt, berekend over de hele rustige loop. 1.25 m betekent: elke hartslag brengt je " +
                "gemiddeld 1,25 meter vooruit. Loop je sneller bij dezelfde hartslag, dan stijgt de EF. Terrein, warmte, " +
                "vermoeidheid en de kwaliteit van je hartslagband beïnvloeden het cijfer.",
        ),
        Term(
            "periodes", "Twee periodes naast elkaar",
            "Periode A is standaard 8 tot 6 maanden geleden en periode B de laatste 2 maanden; je kiest zelf de datums. " +
                "De app vergelijkt rustige weglopen met hartslag en intervalsessies met hartslag uit beide periodes.",
        ),
        Term(
            "oordeel", "Gunstig, stabiel, onzeker",
            "De app geeft pas een oordeel vanaf 5 rustige lopen of 3 intervalsessies met hartslag per periode. Het verschil moet " +
                "groter zijn dan 3 % én groter dan de normale schommeling tussen je trainingen. Onzeker: er is een verschil, maar " +
                "je trainingen schommelen te veel om er zeker van te zijn. Alle drempels zijn voorstellen van de app, niet " +
                "wetenschappelijk vastgelegd.",
        ),
        // Reuse the existing explanation of how the app recognises a session type.
        PerformanceGlossary.terms.getValue("sessietype"),
    )

    val terms: Map<String, Term> = list.associateBy { it.key }
}
