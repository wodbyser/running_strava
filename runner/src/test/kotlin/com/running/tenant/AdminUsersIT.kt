package com.running.tenant

import com.running.analysis.Fixtures
import com.running.strava.spi.ActivityRepository
import com.running.tenant.db.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.sql.DriverManager
import java.time.ZonedDateTime

/** `/admin/users` (design §6.2): OWNER only, counts instead of content, disable without deleting, single-use invites. */
class AdminUsersIT @Autowired constructor(
    private val mvc: MockMvc,
    private val users: AppUserRepository,
    private val registry: TenantRegistry,
    private val activities: ActivityRepository,
    private val modes: TenantModes,
) : MultiUserIT() {
    private val password = "a-long-test-password"

    private fun login(user: String, pw: String): MockHttpSession {
        val result = mvc.perform(post("/login").param("username", user).param("password", pw)).andReturn()
        assertEquals(302, result.response.status)
        return result.request.getSession(false) as MockHttpSession
    }

    private fun owner() = login("wout", "owner-test-pw")

    private fun newInvite(owner: MockHttpSession): String {
        val html = mvc.perform(post("/admin/users/invites").session(owner)).andReturn().response.contentAsString
        return Regex("<code>([^<]+)</code>").find(html)!!.groupValues[1]
    }

    private fun register(name: String, code: String, ip: String): MvcResult = mvc.perform(
        post("/register").header("X-Forwarded-For", ip).param("code", code).param("username", name).param("password", password).param("password2", password),
    ).andReturn()

    private fun databases(like: String): List<String> =
        DriverManager.getConnection(TestPostgres.container.jdbcUrl, TestPostgres.container.username, TestPostgres.container.password).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE '$like'").use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun signUpWithInvite(name: String, ip: String): MockHttpSession {
        val code = newInvite(owner())
        val result = register(name, code, ip)
        assertEquals(302, result.response.status, result.response.contentAsString)
        return result.request.getSession(false) as MockHttpSession
    }

    @Test
    fun `only the owner reaches the admin pages`() {
        assertEquals(302, mvc.perform(get("/admin/users")).andReturn().response.status, "anonymous goes to the login")
        val session = signUpWithInvite("ad_plain", "198.51.100.21")
        modes.complete("ad_plain", DataMode.STRAVA)
        assertEquals(403, mvc.perform(get("/admin/users").session(session)).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/admin/users/invites").session(session)).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/admin/users/wout/disable").session(session)).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/admin/users/ad_plain/disable").session(session)).andReturn().response.status)
        assertEquals("ACTIVE", users.findByUsername("ad_plain")!!.status)
        assertEquals(200, mvc.perform(get("/admin/users").session(owner())).andReturn().response.status)
    }

    @Test
    fun `the list shows counts and masked data but nothing of the training itself`() {
        signUpWithInvite("ad_list", "198.51.100.22")
        modes.complete("ad_list", DataMode.STRAVA)
        TenantContext.asTenant("ad_list") { activities.save(Fixtures.run(4242, ZonedDateTime.now().minusDays(1), 5000.0, 1500).copy(name = "Geheime-ochtendrun")) }
        val html = mvc.perform(get("/admin/users").session(owner())).andReturn().response.contentAsString
        assertTrue(html.contains("ad_list") && html.contains("Alleen Strava"))
        assertFalse(html.contains("Geheime-ochtendrun"))
        val row = html.substringAfter("<strong>ad_list</strong>").substringBefore("</tr>")
        assertTrue(Regex("""mono">\s*1\s*<""").containsMatchIn(row) || row.contains(">1<"), row)
        assertTrue(html.contains("wout") && html.contains("beheerder"))
    }

    @Test
    fun `disabling blocks login and keeps the database, enabling brings it back, the owner cannot be disabled`() {
        val session = signUpWithInvite("ad_off", "198.51.100.23")
        modes.complete("ad_off", DataMode.STRAVA)
        val admin = owner()
        assertEquals(302, mvc.perform(post("/admin/users/ad_off/disable").session(admin)).andReturn().response.status)
        assertEquals("DISABLED", users.findByUsername("ad_off")!!.status)
        assertEquals(null, registry.find("ad_off"))
        assertEquals(403, mvc.perform(get("/activities").session(session)).andReturn().response.status, "an open session is stopped too")
        assertTrue(mvc.perform(post("/login").param("username", "ad_off").param("password", password)).andReturn().response.redirectedUrl!!.contains("error"))
        assertEquals(listOf("running_u_ad_off"), databases("running_u_ad_off"), "nothing was dropped or renamed")

        assertEquals(403, mvc.perform(post("/admin/users/wout/disable").session(admin)).andReturn().response.status)
        assertEquals("ACTIVE", users.findByUsername("wout")!!.status)
        assertEquals(403, mvc.perform(post("/admin/users/nobody/disable").session(admin)).andReturn().response.status)

        assertEquals(302, mvc.perform(post("/admin/users/ad_off/enable").session(admin)).andReturn().response.status)
        assertNotNull(registry.find("ad_off"))
        assertEquals(200, mvc.perform(get("/activities").session(login("ad_off", password))).andReturn().response.status)
    }

    @Test
    fun `a single-use invite works exactly once and the page never shows it again`() {
        val admin = owner()
        val code = newInvite(admin)
        assertTrue(code.length >= 16)
        val list = mvc.perform(get("/admin/users").session(admin)).andReturn().response.contentAsString
        assertFalse(list.contains(code), "the code is shown once, in the response that created it")

        assertEquals(302, register("ad_once1", code, "198.51.100.24").response.status)
        assertEquals(403, register("ad_once2", code, "198.51.100.25").response.status, "second use is refused")
        assertEquals(null, users.findByUsername("ad_once2"))
        val after = mvc.perform(get("/admin/users").session(admin)).andReturn().response.contentAsString
        assertTrue(after.contains("Gebruikt op") && after.contains("ad_once1"))
    }

    @Test
    fun `a refused signup does not burn the code and a revoked code is dead`() {
        val admin = owner()
        val code = newInvite(admin)
        assertEquals(400, register("Bad Name!", code, "198.51.100.26").response.status)
        assertEquals(302, register("ad_keep", code, "198.51.100.27").response.status, "the code survived the invalid attempt")

        val revoked = newInvite(admin)
        val id = users.listInvites().first { it.usedAt == null && it.expiresAt != null }.shortId
        assertEquals(302, mvc.perform(post("/admin/users/invites/$id/revoke").session(admin)).andReturn().response.status)
        assertEquals(403, register("ad_dead", revoked, "198.51.100.28").response.status)
        assertEquals(null, users.findByUsername("ad_dead"))
    }

    @Test
    fun `a wrong or too short code is refused`() {
        assertEquals(403, register("ad_wrong", "short", "198.51.100.29").response.status)
        assertEquals(403, register("ad_wrong", "x".repeat(24), "198.51.100.29").response.status)
    }
}
