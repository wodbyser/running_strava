package com.running.ai

/** All Dutch texts for the in-app AI card in one place. No provider message ever reaches the user unchanged. */
object AiMessages {

    fun text(failure: AiFailure): String = when (failure) {
        AiFailure.NOT_CONFIGURED -> "AI is niet ingesteld op deze installatie."
        AiFailure.EMPTY_PROMPT -> "De prompt is leeg."
        AiFailure.TOO_LARGE ->
            "Deze prompt is te groot om te versturen. Gebruik de kopieerknop en een externe chatbot."
        AiFailure.MODEL_NOT_FREE ->
            "Dat model staat niet in de lijst met gratis modellen. Kies een model uit de lijst."
        AiFailure.LOCAL_RATE_LIMIT ->
            "Je hebt het maximum aantal AI-vragen per uur bereikt. Probeer het later opnieuw of kopieer de prompt."
        AiFailure.IN_FLIGHT -> "Er loopt al een AI-aanvraag. Wacht tot het antwoord er is."
        AiFailure.PROVIDER_RATE_LIMIT ->
            "Het AI-model is even vol: je gratis limiet of de gedeelde capaciteit van het model is bereikt. " +
                "Kies een ander model in de lijst en vraag het opnieuw, of kopieer de prompt."
        AiFailure.AUTH -> "De AI-sleutel werd geweigerd. Controleer AI_API_KEY."
        AiFailure.TIMEOUT -> "De AI-dienst antwoordde niet op tijd. Probeer het opnieuw."
        AiFailure.MODEL_UNAVAILABLE ->
            "Het gekozen AI-model is niet beschikbaar. Kies een ander model in de lijst."
        AiFailure.EMPTY_ANSWER ->
            "Het model gaf een leeg antwoord. Kies een ander model in de lijst of probeer het opnieuw."
        AiFailure.PROVIDER_ERROR -> "De AI-dienst gaf een fout. Probeer het later opnieuw."
    }
}
