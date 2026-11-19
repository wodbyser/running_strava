package com.running.tenant

import com.running.tenant.web.OnboardingGate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class OwnerModeTest {
    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false)) }.also { it.reload() }
    private val settings = DataModeTest.MapSettings()
    private val modes = TenantModes(registry, settings)
    private val gate = OnboardingGate(modes)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun login(principal: AppUserPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
    }

    private fun pass(path: String): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path).apply { servletPath = path }
        val response = MockHttpServletResponse()
        val allowed = gate.preHandle(request, response, Any())
        assertEquals(response.redirectedUrl == null, allowed)
        return response
    }

    @Test
    fun `the owner is BOTH without any app_settings row and is never sent to setup`() {
        login(AppUserPrincipal("wout", "x", "OWNER", "wout", "running", true))
        assertEquals(DataMode.BOTH, modes.mode("wout"))
        assertTrue(modes.onboardingComplete("wout"))
        for (path in listOf("/", "/activities", "/load", "/race", "/setup")) assertNull(pass(path).redirectedUrl, path)
        assertEquals(0, settings.reads, "no query for the owner")
    }

    @Test
    fun `a tenant that is not onboarded is sent to setup from every page except the wizard and the Strava round trip`() {
        login(AppUserPrincipal("anna", "x", "USER", "anna", "running_u_anna", true))
        for (path in listOf("/", "/activities", "/load", "/garmin/status")) assertEquals("/setup", pass(path).redirectedUrl, path)
        for (path in listOf("/setup", "/setup/garmin", "/auth/strava", "/callback")) assertNull(pass(path).redirectedUrl, path)
        modes.complete("anna", DataMode.STRAVA)
        assertNull(pass("/").redirectedUrl)
    }

    @Test
    fun `an unauthenticated request is left to the security chain`() {
        assertFalse(pass("/").redirectedUrl != null)
    }
}
