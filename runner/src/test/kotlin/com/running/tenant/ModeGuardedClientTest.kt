package com.running.tenant

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.StravaToken
import com.running.strava.spi.StravaApiClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The throwing stub of design §9.2: the delegate fails the test if it is ever reached. */
class ModeGuardedClientTest {
    private var reached = 0
    private val stub = object : StravaApiClient {
        override fun exchangeToken(code: String): StravaToken { reached++; return StravaToken("a", "r", 0, 1) }
        override fun refreshToken(refreshToken: String): StravaToken = fail()
        override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?): List<Activity> = fail()
        override fun getActivity(token: StravaToken, activityId: Long): Activity = fail()
        override fun getActivityStreams(token: StravaToken, activityId: Long): ActivityStream = fail()
        private fun <T> fail(): T { reached++; throw AssertionError("Strava was called") }
    }

    private val registry = TenantRegistry {
        listOf(Tenant("wout", "running", true), Tenant("gina", "running_u_gina", false), Tenant("new", "running_u_new", false))
    }.also { it.reload() }
    private val modes = TenantModes(registry, DataModeTest.MapSettings()).also { it.complete("gina", DataMode.GARMIN) }
    private val client = ModeGuardedStravaClient(stub) { DataModeGuard(modes) }
    private val token = StravaToken("a", "r", 0, 1)

    private fun calls(tenant: String?, block: () -> Unit) {
        if (tenant == null) block() else TenantContext.asTenant(tenant, block)
    }

    @Test
    fun `garmin only, not onboarded and unknown tenants never reach Strava`() {
        for (tenant in listOf("gina", "new", null)) {
            calls(tenant) {
                assertThrows<ModeMismatchException> { client.getAthleteActivities(token, 1, 10) }
                assertThrows<ModeMismatchException> { client.getActivity(token, 1) }
                assertThrows<ModeMismatchException> { client.getActivityStreams(token, 1) }
                assertThrows<ModeMismatchException> { client.refreshToken("r") }
            }
        }
        assertEquals(0, reached)
    }

    @Test
    fun `the code exchange stays open so onboarding can link Strava`() {
        TenantContext.asTenant("new") { client.exchangeToken("code") }
        assertEquals(1, reached)
    }

    @Test
    fun `a strava tenant and the owner pass through`() {
        modes.complete("new", DataMode.STRAVA)
        for (tenant in listOf("new", "wout")) {
            TenantContext.asTenant(tenant) { assertThrows<AssertionError> { client.getActivity(token, 1) } }
        }
        assertEquals(2, reached, "both reached the delegate")
    }
}
