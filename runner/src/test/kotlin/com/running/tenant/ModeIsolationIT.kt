package com.running.tenant

import com.running.analysis.Fixtures
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.domain.GarminActivityPage
import com.running.garmin.domain.GarminActivityRef
import com.running.garmin.domain.GarminSource
import com.running.garmin.scheduler.GarminSyncScheduler
import com.running.garmin.spi.GarminApiClient
import com.running.strava.scheduler.StravaSyncScheduler
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.tenant.db.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Design §9.2 / Phase 5: a GARMIN-only tenant makes zero Strava calls, a STRAVA-only tenant zero Garmin calls (throwing-style
 * mocks, verifyNoInteractions), every page renders in every mode, and the Garmin projection feeds the GARMIN tenant only.
 */
class ModeIsolationIT @Autowired constructor(
    private val mvc: MockMvc,
    private val provisioner: TenantProvisioner,
    private val users: AppUserRepository,
    private val registry: TenantRegistry,
    private val modes: TenantModes,
    private val settings: AppSettings,
    private val cipher: SecretCipher,
    private val activities: ActivityRepository,
    private val coordinator: GarminSyncCoordinator,
    private val stravaScheduler: StravaSyncScheduler,
    private val garminScheduler: GarminSyncScheduler,
) : MultiUserIT() {
    @MockitoBean lateinit var strava: StravaApiClient
    @MockitoBean lateinit var garmin: GarminApiClient

    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    private fun tenant(name: String, mode: DataMode): MockHttpSession {
        if (registry.find(name) == null) {
            provisioner.provision(name)
            users.updatePasswordHash(users.findByUsername(name)!!.id, encoder.encode("$name-test-pw"))
        }
        if (!modes.onboardingComplete(name)) {
            if (mode.usesGarmin) TenantContext.asTenant(name) { settings.put(CredentialResolver.GARMIN_KEY, cipher.encrypt("FAKE-KEY-FOR-$name")) }
            modes.complete(name, mode)
        }
        val result = mvc.perform(post("/login").param("username", name).param("password", "$name-test-pw")).andReturn()
        assertEquals(302, result.response.status)
        return result.request.getSession(false) as MockHttpSession
    }

    private fun ref(id: Long) = GarminActivityRef(
        id, Instant.now().minusSeconds(86_400), "running", 17, 10000.0, 3000.0, 3000.0, 3000.0, 150.0, 170.0, 3.0, 0.0, 100.0,
        "AEROBIC_BASE", "GARMIN", false, null, null, GarminSource("test", null, "test"),
    )

    private fun fetch(session: MockHttpSession, url: String): Int {
        val first = mvc.perform(get(url).session(session)).andReturn().response
        if (first.status != 302) return first.status
        val target = first.redirectedUrl!!
        assertTrue(target.startsWith("/unavailable"), "$url redirected to $target")
        val panel = mvc.perform(get(target).session(session)).andReturn().response
        assertTrue(panel.contentAsString.contains("Niet beschikbaar voor jouw account"), url)
        return panel.status
    }

    private val pages = listOf(
        "/", "/activities", "/pbs", "/recovery", "/performance", "/load", "/load/response", "/race", "/race/history", "/ai",
        "/next-training", "/rate-training", "/compare", "/compare-runs", "/coach", "/settings",
    )

    @Test
    fun `garmin only renders every page, makes no Strava call and shows the panel for Strava-only pages`() {
        val session = tenant("mi_garmin", DataMode.GARMIN)
        TenantContext.asTenant("mi_garmin") { activities.save(Fixtures.run(900, ZonedDateTime.now().minusDays(1), 8000.0, 2400).copy(source = "GARMIN")) }
        for (url in pages + "/activity/900") assertEquals(200, fetch(session, url), url)
        assertEquals("/unavailable?page=performance", mvc.perform(get("/performance").session(session)).andReturn().response.redirectedUrl)
        assertTrue(mvc.perform(get("/performance").session(session)).andReturn().response.status == 302)
        assertTrue(mvc.perform(get("/recovery").session(session)).andReturn().response.status == 200)

        for (url in listOf("/sync", "/fetch-all", "/fetch-remaining", "/backfill-streams", "/backfill-laps", "/coach/sync", "/race/strava-recheck")) {
            val r = mvc.perform(post(url).session(session)).andReturn().response
            assertEquals(302, r.status, url)
        }
        assertEquals(409, mvc.perform(get("/auth/strava").session(session)).andReturn().response.status)
        stravaScheduler.syncNewActivities()
        verifyNoInteractions(strava)
    }

    @Test
    fun `strava only renders every page, shows the Garmin panel on recovery and makes no Garmin call`() {
        val session = tenant("mi_strava", DataMode.STRAVA)
        TenantContext.asTenant("mi_strava") { activities.save(Fixtures.run(901, ZonedDateTime.now().minusDays(1), 8000.0, 2400)) }
        for (url in pages + "/activity/901") assertEquals(200, fetch(session, url), url)
        assertEquals("/unavailable?page=recovery", mvc.perform(get("/recovery").session(session)).andReturn().response.redirectedUrl)
        val panel = mvc.perform(get("/unavailable?page=recovery").session(session)).andReturn().response.contentAsString
        assertTrue(panel.contains("Deze analyse heeft Garmin-data nodig."))

        mvc.perform(post("/recovery/garmin-update").session(session))
        mvc.perform(post("/garmin/sync").session(session))
        mvc.perform(post("/garmin/backfill").session(session))
        garminScheduler.syncGarmin()
        assertEquals(com.running.garmin.adapter.GarminStartOutcome.NOT_CONFIGURED, TenantContext.asTenant("mi_strava") { coordinator.startSync(async = false) })
        verifyNoInteractions(garmin)
    }

    @Test
    fun `garmin sync projects activities into a garmin only tenant, never into a BOTH tenant`() {
        tenant("mi_proj", DataMode.GARMIN)
        tenant("mi_both", DataMode.BOTH)
        `when`(garmin.searchActivities(anyInt(), anyInt(), any(), any())).thenAnswer { inv ->
            if (inv.getArgument<Int>(0) == 0) GarminActivityPage(listOf(ref(5001)), 1) else GarminActivityPage(emptyList(), 0)
        }

        TenantContext.asTenant("mi_proj") { assertEquals(com.running.garmin.adapter.GarminStartOutcome.STARTED, coordinator.startSync(async = false)) }
        val projected = TenantContext.asTenant("mi_proj") { activities.findById(5001) }
        assertNotNull(projected)
        assertEquals("GARMIN", projected!!.source)
        assertEquals(10000f, projected.distance)

        TenantContext.asTenant("mi_both") { assertEquals(com.running.garmin.adapter.GarminStartOutcome.STARTED, coordinator.startSync(async = false)) }
        assertNull(TenantContext.asTenant("mi_both") { activities.findById(5001) }, "BOTH keeps Strava as the backbone")
        assertNull(TenantContext.asTenant("wout") { activities.findById(5001) }, "never the owner's database")
    }

    @Test
    fun `the owner keeps every page and every Strava route`() {
        val result = mvc.perform(post("/login").param("username", "wout").param("password", "owner-test-pw")).andReturn()
        val owner = result.request.getSession(false) as MockHttpSession
        for (url in pages - "/settings") assertEquals(200, mvc.perform(get(url).session(owner)).andReturn().response.status, url)
    }
}
