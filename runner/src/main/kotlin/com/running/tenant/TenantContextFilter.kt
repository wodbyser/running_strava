package com.running.tenant

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Last filter of the security chain (design §5.4): by now form login, session and remember-me have all run.
 * The tenant comes from the principal only; unauthenticated paths bind nothing and therefore cannot reach a database.
 */
class TenantContextFilter(private val registry: TenantRegistry) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppUserPrincipal
        if (principal == null) {
            chain.doFilter(request, response)
            return
        }
        val tenant = try {
            registry.requireActive(principal.tenantId)
        } catch (e: TenantDisabledException) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        TenantContext.asTenant(tenant.id) { chain.doFilter(request, response) }
    }
}
