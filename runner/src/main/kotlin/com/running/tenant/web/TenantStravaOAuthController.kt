package com.running.tenant.web

import com.running.config.StravaProperties
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.tenant.DataMode
import com.running.tenant.OAuthState
import com.running.tenant.StateCheck
import com.running.tenant.TenantModes
import com.running.tenant.db.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView

/**
 * Flag-on replacement of [com.running.strava.adapter.StravaOAuthController]: the OAuth round trip carries a signed,
 * single-use `state` bound to tenant and session, and the token lands in the tenant's own database (design §14 Phase 4).
 */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class TenantStravaOAuthController(
    private val properties: StravaProperties,
    private val state: OAuthState,
    private val modes: TenantModes,
    private val strava: StravaApiClient,
    private val tokens: StravaTokenRepository,
    private val activities: ActivityRepository,
    private val users: AppUserRepository,
) {
    @GetMapping("/auth/strava")
    fun redirectToStrava(request: HttpServletRequest, response: HttpServletResponse, model: Model): Any {
        val user = currentPrincipal()
        if (modes.mode(user.tenantId) == DataMode.GARMIN) return conflict(response, model, GARMIN_ONLY)
        val signed = state.create(user.tenantId, request.getSession(true).id)
        val url = buildString {
            append("https://www.strava.com/oauth/authorize")
            append("?client_id=${properties.clientId}")
            append("&response_type=code")
            append("&redirect_uri=${properties.redirectUri}")
            append("&approval_prompt=force")
            append("&scope=activity:read_all")
            append("&state=$signed")
        }
        return RedirectView(url)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(name = "state", required = false) stateParam: String?,
        @RequestParam(required = false) error: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
        ra: RedirectAttributes,
    ): String {
        val user = currentPrincipal()
        val tenant = user.tenantId
        val target = if (modes.onboardingComplete(tenant)) (if (modes.isOwner(tenant)) "redirect:/" else "redirect:/settings") else "redirect:/setup"
        fun message(ok: Boolean, text: String): String {
            ra.addFlashAttribute("flashMessage", text)
            ra.addFlashAttribute("flashType", if (ok) "success" else "error")
            return target
        }
        val sessionId = request.getSession(false)?.id
        if (sessionId == null || state.verify(stateParam, tenant, sessionId) != StateCheck.VALID) {
            users.audit(user.username, "STRAVA_STATE_REJECTED")
            return message(false, "De Strava-koppeling kon niet worden gecontroleerd (verlopen of al gebruikt). Probeer opnieuw.")
        }
        if (modes.mode(tenant) == DataMode.GARMIN) return conflict(response, model, GARMIN_ONLY)
        if (error != null || code.isNullOrBlank()) {
            return message(false, if (error == "access_denied") "Je hebt de koppeling op Strava geweigerd." else "Strava heeft de koppeling niet doorgegeven. Probeer opnieuw.")
        }
        val token = try {
            strava.exchangeToken(code)
        } catch (e: RestClientResponseException) {
            users.audit(user.username, "STRAVA_LINK_FAILED", "HTTP ${e.statusCode.value()}")
            return message(false, if (isAthleteLimit(e)) ATHLETE_LIMIT else GENERIC_FAILURE)
        } catch (e: Exception) {
            users.audit(user.username, "STRAVA_LINK_FAILED", e.javaClass.simpleName)
            return message(false, GENERIC_FAILURE)
        }
        val existing = tokens.get()
        if (existing != null && existing.athleteId != token.athleteId && activities.findAllIds().isNotEmpty()) {
            users.audit(user.username, "STRAVA_OTHER_ATHLETE")
            return message(false, "Dit is een ander Strava-account dan het gekoppelde. Wil je van account wisselen, begin dan opnieuw via Instellingen.")
        }
        tokens.save(token)
        users.audit(user.username, "STRAVA_LINKED")
        return message(true, "Strava is gekoppeld.")
    }

    private fun isAthleteLimit(e: RestClientResponseException) =
        e.responseBodyAsString.contains("limit", ignoreCase = true) && e.responseBodyAsString.contains("athlete", ignoreCase = true)

    private companion object {
        const val GARMIN_ONLY = "Je account gebruikt alleen Garmin. Strava erbij nemen kan alleen door helemaal opnieuw te beginnen."
        const val ATHLETE_LIMIT = "Het maximum aantal Strava-gebruikers van deze app is bereikt. Vraag de beheerder om ruimte te maken."
        const val GENERIC_FAILURE = "Strava koppelen is niet gelukt. Probeer het opnieuw."
    }
}
