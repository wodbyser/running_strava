package com.running.tenant

import com.running.analysis.Fixtures
import com.running.strava.spi.ActivityRepository
import com.running.tenant.db.AppUserRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.ZonedDateTime

class IsolationIT @Autowired constructor(
    private val mvc: MockMvc,
    private val provisioner: TenantProvisioner,
    private val users: AppUserRepository,
    private val registry: TenantRegistry,
    private val activities: ActivityRepository,
    private val modes: TenantModes,
) : MultiUserIT() {

    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @BeforeEach
    fun setUp() {
        if (registry.find("anna") == null) {
            provisioner.provision("anna")
            users.updatePasswordHash(users.findByUsername("anna")!!.id, encoder.encode("anna-test-pw"))
        }
        if (!modes.onboardingComplete("anna")) modes.complete("anna", DataMode.STRAVA)
        val start = ZonedDateTime.now().minusDays(2)
        TenantContext.asTenant("wout") { activities.save(Fixtures.run(777, start, 10000.0, 3000).copy(name = "Alleen-van-wout")) }
        TenantContext.asTenant("anna") { activities.save(Fixtures.run(777, start, 5000.0, 1800).copy(name = "Alleen-van-anna")) }
    }

    private fun login(user: String, password: String): MockHttpSession {
        val result = mvc.perform(post("/login").param("username", user).param("password", password)).andReturn()
        assertEquals(302, result.response.status)
        return result.request.getSession(false) as MockHttpSession
    }

    private fun page(session: MockHttpSession, url: String) = mvc.perform(get(url).session(session)).andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `same activity id in two tenants never shows the other one's data`() {
        val wout = login("wout", "owner-test-pw")
        val anna = login("anna", "anna-test-pw")
        for (url in listOf("/activities", "/activity/777")) {
            val w = page(wout, url)
            val a = page(anna, url)
            assertTrue(w.contains("Alleen-van-wout"), url)
            assertFalse(w.contains("Alleen-van-anna"), url)
            assertTrue(a.contains("Alleen-van-anna"), url)
            assertFalse(a.contains("Alleen-van-wout"), url)
        }
        for (url in listOf("/", "/performance", "/load", "/race", "/recovery")) {
            val a = page(anna, url)
            assertFalse(a.contains("Alleen-van-wout"), url)
        }
    }

    @Test
    fun `tenant caches stay apart for the same activity id`() {
        val wout = login("wout", "owner-test-pw")
        val anna = login("anna", "anna-test-pw")
        page(wout, "/performance")
        page(wout, "/load")
        val a = page(anna, "/performance")
        assertFalse(a.contains("Alleen-van-wout"))
    }

    @Test
    fun `login page needs no tenant and an anonymous request is sent to login`() {
        mvc.perform(get("/login")).andExpect(status().isOk)
        mvc.perform(get("/activities")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `a request authenticated only by the remember-me cookie still gets its tenant`() {
        val result = mvc.perform(post("/login").param("username", "anna").param("password", "anna-test-pw").param("remember-me", "on")).andReturn()
        val cookie: Cookie = checkNotNull(result.response.getCookie("remember-me")) { "no remember-me cookie" }
        val body = mvc.perform(get("/activities").cookie(cookie)).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(body.contains("Alleen-van-anna"))
        assertFalse(body.contains("Alleen-van-wout"))
    }

    @Test
    fun `a tenant that is no longer active is refused`() {
        val anna = login("anna", "anna-test-pw")
        registry.remove("anna")
        try {
            mvc.perform(get("/activities").session(anna)).andExpect(status().isForbidden)
        } finally {
            registry.add(Tenant("anna", "running_u_anna", false))
        }
    }
}
