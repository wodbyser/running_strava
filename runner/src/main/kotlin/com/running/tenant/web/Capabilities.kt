package com.running.tenant.web

import com.running.tenant.DataMode
import com.running.tenant.TenantContext
import com.running.tenant.TenantModes
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

enum class Level { FULL, PARTIAL, NOT_AVAILABLE }

/** One page of the honest capability matrix (design §9.5, docs/multi-user-capabilities.md). `BOTH` is always FULL. */
data class PageCapability(val path: String, val title: String, val strava: Level, val garmin: Level, val stravaNote: String? = null, val garminNote: String? = null) {
    fun level(mode: DataMode): Level = when (mode) {
        DataMode.STRAVA -> strava
        DataMode.GARMIN -> garmin
        else -> Level.FULL
    }

    fun note(mode: DataMode): String? = when (mode) {
        DataMode.STRAVA -> stravaNote
        DataMode.GARMIN -> garminNote
        else -> null
    }
}

object CapabilityMatrix {
    const val NEEDS_GARMIN = "Deze analyse heeft Garmin-data nodig."
    const val NEEDS_STRAVA = "Deze analyse heeft Strava-data nodig (seconde-data per training)."
    private const val GARMIN_NO_STREAMS = "Gedeeltelijk beschikbaar: je Garmin-koppeling levert geen rondes of seconde-data per training, dus alles wat daarop rust ontbreekt."

    val pages = listOf(
        PageCapability("/", "Dashboard", Level.FULL, Level.FULL),
        PageCapability("/activities", "Activiteiten", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/activity/", "Activiteit", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/pbs", "Persoonlijke records", Level.FULL, Level.PARTIAL, garminNote = "Gedeeltelijk beschikbaar: records over een deel van een training hebben seconde-data nodig. Alleen hele trainingen tellen."),
        PageCapability("/recovery", "Herstel", Level.NOT_AVAILABLE, Level.FULL),
        PageCapability("/performance", "Prestaties", Level.FULL, Level.NOT_AVAILABLE),
        PageCapability("/load", "Belasting", Level.PARTIAL, Level.FULL, stravaNote = "Gedeeltelijk beschikbaar: zonder Garmin berekent de app de belasting uit je hartslag per training, zonder Garmin-trainingseffect."),
        PageCapability("/race", "Wedstrijd", Level.FULL, Level.PARTIAL, garminNote = "Gedeeltelijk beschikbaar: de voorspelling werkt op wedstrijdtijden; tempoplan en specificiteit hebben seconde-data nodig."),
        PageCapability("/ai", "AI-analyse", Level.FULL, Level.PARTIAL, garminNote = "Gedeeltelijk beschikbaar: de prompt bevat alleen wat Garmin levert (geen rondes, geen hoogtemeters)."),
        PageCapability("/next-training", "Volgende training", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/rate-training", "Training beoordelen", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/compare", "Perioden vergelijken", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/compare-runs", "Trainingen vergelijken", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
        PageCapability("/coach", "Profiel", Level.FULL, Level.PARTIAL, garminNote = GARMIN_NO_STREAMS),
    )

    /** Longest matching entry; "/" only matches itself. JSON endpoints and POSTs are not part of the matrix. */
    fun forPath(path: String): PageCapability? {
        if (path == "/") return pages.first()
        return pages.drop(1).filter { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }.maxByOrNull { it.path.length }
    }

    fun message(mode: DataMode): String = if (mode == DataMode.STRAVA) NEEDS_GARMIN else NEEDS_STRAVA
}

/** Which sources the tenant of this request uses. Always a bean: without the flag everything answers like BOTH, without a query. */
@Component("tenantCaps")
class TenantCapabilities(private val modes: ObjectProvider<TenantModes>) {
    fun mode(): DataMode {
        val known = modes.ifAvailable ?: return DataMode.BOTH
        val tenant = TenantContext.current() ?: return DataMode.UNSET
        return known.mode(tenant)
    }

    fun stravaUsed(): Boolean = mode().usesStrava

    fun garminOnly(): Boolean = mode() == DataMode.GARMIN

    /** False only for a page the matrix marks NOT_AVAILABLE for this tenant's mode, so the menu never leads to a dead end. */
    fun available(path: String): Boolean = CapabilityMatrix.forPath(path)?.level(mode()) != Level.NOT_AVAILABLE

    /** The sources named in the footer. */
    fun sourceText(): String = when (mode()) {
        DataMode.STRAVA -> "Strava-gegevens"
        DataMode.GARMIN -> "Garmin-gegevens"
        else -> "Strava- en Garmin-gegevens"
    }
}

/** Sends a tenant to the shared "not available" panel, or hands a partial-page note to the layout. */
class CapabilityInterceptor(private val modes: TenantModes) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val (page, mode) = lookup(request) ?: return true
        if (page.level(mode) != Level.NOT_AVAILABLE) return true
        response.sendRedirect("/unavailable?page=" + page.path.trim('/').ifEmpty { "dashboard" })
        return false
    }

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        val (page, mode) = lookup(request) ?: return
        if (modelAndView == null || modelAndView.viewName?.startsWith("redirect:") == true) return
        if (page.level(mode) == Level.PARTIAL) page.note(mode)?.let { modelAndView.addObject("capabilityNote", it) }
    }

    private fun lookup(request: HttpServletRequest): Pair<PageCapability, DataMode>? {
        if (request.method != "GET") return null
        val principal = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.principal as? com.running.tenant.AppUserPrincipal ?: return null
        if (principal.role == "OWNER" || modes.isOwner(principal.tenantId)) return null
        val path = request.appPath()
        if (path.endsWith("/data") || path.endsWith("/prompt") || path.endsWith("/download") || path.endsWith("/export")) return null
        val page = CapabilityMatrix.forPath(path) ?: return null
        return page to modes.mode(principal.tenantId)
    }
}

@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class UnavailableController(private val modes: TenantModes) {
    @GetMapping("/unavailable")
    fun unavailable(@RequestParam(required = false) page: String?, model: Model): String {
        val principal = currentPrincipal()
        val mode = modes.mode(principal.tenantId)
        val known = CapabilityMatrix.forPath("/" + page.orEmpty().trim('/').takeUnless { it == "dashboard" }.orEmpty())
        model.addAttribute("title", known?.title ?: "Niet beschikbaar")
        model.addAttribute("pageTitle", known?.title ?: "Deze pagina")
        model.addAttribute("reason", CapabilityMatrix.message(mode))
        model.addAttribute("sourceLabel", if (mode == DataMode.STRAVA) "alleen Strava" else "alleen Garmin")
        return "unavailable"
    }
}
