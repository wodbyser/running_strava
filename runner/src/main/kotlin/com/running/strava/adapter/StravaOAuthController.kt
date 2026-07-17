package com.running.strava.adapter

import com.running.config.StravaProperties
import com.running.strava.usecase.exchange.ExchangeStravaCode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
class StravaOAuthController(
    private val properties: StravaProperties,
    private val exchangeStravaCode: ExchangeStravaCode,
) {

    @GetMapping("/auth/strava")
    fun redirectToStrava(): RedirectView {
        val url = buildString {
            append("https://www.strava.com/oauth/authorize")
            append("?client_id=${properties.clientId}")
            append("&response_type=code")
            append("&redirect_uri=${properties.redirectUri}")
            append("&approval_prompt=force")
            append("&scope=activity:read_all")
        }
        return RedirectView(url)
    }

    @GetMapping("/callback")
    fun callback(@RequestParam code: String): String {
        val response = exchangeStravaCode.execute(ExchangeStravaCode.Request(code))
        return "Strava gekoppeld! Athlete ID: ${response.athleteId}. Access token opgeslagen. " +
            "Je kunt nu de data synchroniseren via /sync of /fetch-all."
    }
}
