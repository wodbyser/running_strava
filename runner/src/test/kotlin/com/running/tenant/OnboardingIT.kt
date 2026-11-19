package com.running.tenant

import com.running.garmin.adapter.GarminKeyCheck
import com.running.garmin.adapter.GarminTokenProvider
import com.running.strava.domain.StravaToken
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.tenant.db.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.client.RestClientResponseException
import java.sql.DriverManager

/** Signup, wizard, Strava state, mode guards, reset and the owner's safety, end to end against a throwaway PostgreSQL. */
@ExtendWith(OutputCaptureExtension::class)
class OnboardingIT @Autowired constructor(
    private val mvc: MockMvc,
    private val users: AppUserRepository,
    private val modes: TenantModes,
    private val settings: AppSettings,
    private val tokens: StravaTokenRepository,
    private val backfill: TenantBackfill,
) : MultiUserIT() {
    @MockitoBean lateinit var garmin: GarminTokenProvider
    @MockitoBean lateinit var strava: StravaApiClient

    private val inviteCode = "test-invite-code-0123456789"
    private val password = "a-long-test-password"

    private fun register(name: String, ip: String, code: String = inviteCode): MvcResult = mvc.perform(
        post("/register").header("X-Forwarded-For", ip)
            .param("code", code).param("username", name).param("password", password).param("password2", password),
    ).andReturn()

    private fun signUp(name: String, ip: String): MockHttpSession {
        val result = register(name, ip)
        assertEquals(302, result.response.status, result.response.contentAsString)
        assertEquals("/setup", result.response.redirectedUrl)
        return result.request.getSession(false) as MockHttpSession
    }

    private fun status(session: MockHttpSession, url: String) = mvc.perform(get(url).session(session)).andReturn().response

    private fun database(like: String): List<String> =
        DriverManager.getConnection(TestPostgres.container.jdbcUrl, TestPostgres.container.username, TestPostgres.container.password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE '$like' ORDER BY datname").use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    @Test
    fun `a wrong invite code creates nothing`() {
        val result = register("ob_wrong", "203.0.113.10", "not-the-code-at-all-123")
        assertEquals(403, result.response.status)
        assertNull(users.findByUsername("ob_wrong"))
        assertTrue(database("running_u_ob_wrong").isEmpty())
    }

    @Test
    fun `signup is limited to five tries per address and a bad password or name is refused`() {
        assertEquals(400, mvc.perform(post("/register").header("X-Forwarded-For", "203.0.113.11").param("code", inviteCode).param("username", "ob_short").param("password", "").param("password2", "")).andReturn().response.status)
        assertEquals(400, mvc.perform(post("/register").header("X-Forwarded-For", "203.0.113.11").param("code", inviteCode).param("username", "Bad Name!").param("password", password).param("password2", password)).andReturn().response.status)
        repeat(3) { register("ob_limit$it", "203.0.113.11", "wrong-code-wrong-code-1") }
        assertEquals(429, register("ob_limit9", "203.0.113.11").response.status)
        assertNull(users.findByUsername("ob_limit9"))
        assertEquals(302, register("ob_other", "203.0.113.12").response.status, "another address is not limited")
    }

    @Test
    fun `a new user has his own empty database, is logged in and is sent to setup until onboarded`() {
        val session = signUp("ob_new", "203.0.113.13")
        assertEquals(listOf("running_u_ob_new"), database("running_u_ob_new"))
        assertEquals("/setup", status(session, "/activities").redirectedUrl)
        assertEquals("/setup", status(session, "/").redirectedUrl)
        assertEquals(200, status(session, "/setup").status)
        assertEquals("/setup", status(session, "/settings").redirectedUrl)
        assertTrue(mvc.perform(post("/register").header("X-Forwarded-For", "203.0.113.13").param("code", inviteCode).param("username", "ob_new").param("password", password).param("password2", password)).andReturn().response.status == 409)
    }

    @Test
    fun `the owner never sees the wizard, settings or a reset`() {
        val result = mvc.perform(post("/login").param("username", "wout").param("password", "owner-test-pw")).andReturn()
        val owner = result.request.getSession(false) as MockHttpSession
        assertEquals(200, status(owner, "/").status)
        assertEquals(404, status(owner, "/setup").status)
        assertEquals(404, status(owner, "/settings").status)
        assertEquals(404, status(owner, "/backfill").status)
        assertEquals(403, mvc.perform(post("/settings/reset").session(owner).param("confirm", "wout")).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/settings/reset").session(owner).param("confirm", "wout")).andReturn().response.status)
        assertEquals(DataMode.BOTH, modes.mode("wout"))
        assertTrue(database("running").contains("running"), "the owner's database is still there")
    }

    @Test
    fun `garmin only user, key is tested then stored encrypted, mode is fixed, reset archives`(output: CapturedOutput) {
        val key = "FAKE-GARMIN-KEY-9f8e7d6c5b4a"
        val session = signUp("ob_garmin", "203.0.113.14")
        `when`(garmin.verify(key)).thenReturn(GarminKeyCheck.REJECTED)
        mvc.perform(post("/setup/garmin").session(session).param("key", key))
        assertNull(TenantContext.asTenant("ob_garmin") { settings.get("garmin.api_key") }, "a rejected key is not stored")

        `when`(garmin.verify(key)).thenReturn(GarminKeyCheck.OK)
        mvc.perform(post("/setup/garmin").session(session).param("key", key))
        val stored = TenantContext.asTenant("ob_garmin") { settings.get("garmin.api_key") }
        assertTrue(stored!!.startsWith("enc:v1:") && !stored.contains(key))
        val page = status(session, "/setup").contentAsString
        assertTrue(page.contains("b4a") && !page.contains(key), "only the last 4 show")

        mvc.perform(post("/setup/finish").session(session))
        assertEquals(DataMode.GARMIN, modes.mode("ob_garmin"))
        assertEquals("/", status(session, "/setup").redirectedUrl)
        assertEquals(200, status(session, "/").status)

        assertEquals(HttpStatus.CONFLICT.value(), status(session, "/auth/strava").status, "Strava cannot be added afterwards")
        mvc.perform(post("/settings/garmin").session(session).param("key", key))
        assertEquals(200, status(session, "/settings").status)

        assertEquals("/settings", mvc.perform(post("/settings/reset").session(session).param("confirm", "someone-else")).andReturn().response.redirectedUrl)
        assertEquals(DataMode.GARMIN, modes.mode("ob_garmin"))
        assertEquals("/setup", mvc.perform(post("/settings/reset").session(session).param("confirm", "ob_garmin")).andReturn().response.redirectedUrl)
        val databases = database("running_u_ob_garmin%")
        assertEquals(2, databases.size, "fresh database plus the archive: $databases")
        assertTrue(databases.any { it.startsWith("running_u_ob_garmin_old_") })
        assertEquals(DataMode.UNSET, modes.mode("ob_garmin"))
        assertNull(TenantContext.asTenant("ob_garmin") { settings.get("garmin.api_key") }, "the fresh database holds no key")
        assertEquals("/setup", status(session, "/").redirectedUrl)

        for (secret in listOf(key, inviteCode, password)) assertFalse(output.all.contains(secret), "a secret reached the log")
    }

    @Test
    fun `strava only user, the signed state is single use and tied to tenant and session`() {
        val session = signUp("ob_strava", "203.0.113.15")
        val redirect = status(session, "/auth/strava").redirectedUrl!!
        assertTrue(redirect.startsWith("https://www.strava.com/oauth/authorize") && redirect.contains("&state="))
        val state = redirect.substringAfter("&state=")
        val token = StravaToken("access-x", "refresh-x", System.currentTimeMillis() / 1000 + 36000, 4242)
        `when`(strava.exchangeToken("good-code")).thenReturn(token)

        val ok = mvc.perform(get("/callback").session(session).param("code", "good-code").param("state", state)).andReturn()
        assertEquals("/setup", ok.response.redirectedUrl)
        assertEquals(4242, TenantContext.asTenant("ob_strava") { tokens.get() }?.athleteId)

        TenantContext.asTenant("ob_strava") { tokens.delete() }
        val replay = mvc.perform(get("/callback").session(session).param("code", "good-code").param("state", state)).andReturn()
        assertEquals("/setup", replay.response.redirectedUrl)
        assertNull(TenantContext.asTenant("ob_strava") { tokens.get() }, "a replayed state links nothing")

        val other = signUp("ob_strava2", "203.0.113.16")
        val foreign = status(other, "/auth/strava").redirectedUrl!!.substringAfter("&state=")
        mvc.perform(get("/callback").session(session).param("code", "good-code").param("state", foreign))
        assertNull(TenantContext.asTenant("ob_strava") { tokens.get() }, "another tenant's state links nothing")
        mvc.perform(get("/callback").session(session).param("code", "good-code"))
        assertNull(TenantContext.asTenant("ob_strava") { tokens.get() }, "no state, no link")
    }

    @Test
    fun `athlete limit gets a clear Dutch message and strava then backfill then dashboard`() {
        val session = signUp("ob_flow", "203.0.113.17")
        fun freshState() = status(session, "/auth/strava").redirectedUrl!!.substringAfter("&state=")
        `when`(strava.exchangeToken("limit-code")).thenThrow(
            RestClientResponseException("403", 403, "Forbidden", null, """{"message":"Limit of connected athletes exceeded"}""".toByteArray(), null),
        )
        val limited = mvc.perform(get("/callback").session(session).param("code", "limit-code").param("state", freshState())).andReturn()
        assertEquals("/setup", limited.response.redirectedUrl)
        assertTrue(limited.flashMap!!["flashMessage"].toString().contains("maximum"))
        assertNull(TenantContext.asTenant("ob_flow") { tokens.get() })

        `when`(strava.exchangeToken("good")).thenReturn(StravaToken("a", "r", System.currentTimeMillis() / 1000 + 36000, 77))
        mvc.perform(get("/callback").session(session).param("code", "good").param("state", freshState()))
        assertNotNull(TenantContext.asTenant("ob_flow") { tokens.get() })

        assertEquals("/backfill", mvc.perform(post("/setup/finish").session(session)).andReturn().response.redirectedUrl)
        assertEquals(DataMode.STRAVA, modes.mode("ob_flow"))
        var phase = BackfillPhase.QUEUED
        repeat(100) {
            if (phase != BackfillPhase.DONE) {
                Thread.sleep(100)
                phase = TenantContext.asTenant("ob_flow") { backfill.phase() }
            }
        }
        assertEquals(BackfillPhase.DONE, phase)
        assertTrue(status(session, "/backfill").contentAsString.contains("Klaar"))
        assertEquals(200, status(session, "/").status)
        assertEquals(409, mvc.perform(post("/settings/garmin").session(session).param("key", "whatever")).andReturn().response.status)
    }

    @Test
    fun `ten failed logins lock that address and name only`() {
        signUp("ob_lock", "203.0.113.18")
        fun attempt(ip: String, name: String, pw: String) =
            mvc.perform(post("/login").header("X-Forwarded-For", ip).param("username", name).param("password", pw)).andReturn().response.redirectedUrl
        repeat(10) { assertEquals("/login?error", attempt("198.18.0.1", "ob_lock", "wrong-$it")) }
        assertEquals("/login?locked", attempt("198.18.0.1", "ob_lock", password), "even the right password is refused while locked")
        assertEquals("/", attempt("198.18.0.1", "wout", "owner-test-pw"), "the owner is not locked out by a stranger's tries on another name")
    }
}
