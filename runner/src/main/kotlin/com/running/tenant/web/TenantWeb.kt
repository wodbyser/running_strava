package com.running.tenant.web

import com.running.tenant.AppUserPrincipal
import com.running.tenant.AttemptLimiter
import com.running.tenant.ClientIp
import com.running.tenant.DataMode
import com.running.tenant.TenantModes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.server.ResponseStatusException

internal fun currentPrincipal(): AppUserPrincipal =
    SecurityContextHolder.getContext().authentication?.principal as? AppUserPrincipal
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

/** Path inside the application, the same in a servlet container and in MockMvc (where `servletPath` is empty). */
internal fun HttpServletRequest.appPath(): String = requestURI.orEmpty().removePrefix(contextPath.orEmpty())

/** The setup, settings and backfill pages are never for the OWNER (invariant 3 and 10): they answer as if they did not exist. */
internal fun tenantUser(modes: TenantModes): AppUserPrincipal {
    val principal = currentPrincipal()
    if (principal.role == "OWNER" || modes.isOwner(principal.tenantId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return principal
}

/** While a tenant has not finished onboarding every page except the wizard sends them to `/setup` (design §9.1, UNSET). */
class OnboardingGate(private val modes: TenantModes) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppUserPrincipal ?: return true
        if (principal.role == "OWNER") return true
        if (modes.onboardingComplete(principal.tenantId)) return true
        val path = request.appPath()
        if (path == "/setup" || path.startsWith("/setup/") || path == "/auth/strava" || path == "/callback") return true
        response.sendRedirect("/setup")
        return false
    }
}

/** Failed logins per (IP, username), never per username alone: a stranger must not be able to lock the owner out (design §6.4). */
class LoginAttemptFilter(private val limiter: AttemptLimiter) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !(request.method == "POST" && request.appPath() == "/login")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = ClientIp.of(request) + "|" + request.getParameter("username").orEmpty().trim().lowercase().take(64)
        if (limiter.blocked(key)) {
            response.sendRedirect("/login?locked")
            return
        }
        chain.doFilter(request, response)
        if (response.getHeader("Location").orEmpty().contains("error")) limiter.record(key) else limiter.clear(key)
    }
}

/** Tiny answers for the shared layout. Always a bean (like the key banner) and false without a database read when the flag is off. */
@Component("tenantUi")
class TenantUi(private val modes: org.springframework.beans.factory.ObjectProvider<TenantModes>) {
    fun showSettings(): Boolean {
        val known = modes.ifAvailable ?: return false
        val tenant = com.running.tenant.TenantContext.current() ?: return false
        return !known.isOwner(tenant)
    }
}

internal fun DataMode.label(): String = when (this) {
    DataMode.STRAVA -> "Alleen Strava"
    DataMode.GARMIN -> "Alleen Garmin"
    DataMode.BOTH -> "Strava en Garmin"
    DataMode.UNSET -> "Nog niet ingesteld"
}
