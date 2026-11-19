package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DataModeTest {
    class MapSettings : SettingsStore {
        /** Keyed `tenant/key`, like one `app_settings` table per database. */
        val values = mutableMapOf<String, String>()
        var reads = 0
        private fun k(key: String) = "${TenantContext.current()}/$key"
        override fun get(key: String): String? = values[k(key)].also { reads++ }
        override fun put(key: String, value: String) { values[k(key)] = value }
        override fun remove(key: String) { values.remove(k(key)) }
    }

    private val registry = TenantRegistry { listOf(Tenant("wout", "running", true), Tenant("anna", "running_u_anna", false)) }.also { it.reload() }
    private val settings = MapSettings()
    private val modes = TenantModes(registry, settings)

    @Test
    fun `derivation matrix`() {
        assertEquals(DataMode.BOTH, DataMode.derive(hasStrava = true, hasGarmin = true))
        assertEquals(DataMode.STRAVA, DataMode.derive(true, false))
        assertEquals(DataMode.GARMIN, DataMode.derive(false, true))
        assertEquals(DataMode.UNSET, DataMode.derive(false, false))
        assertTrue(DataMode.BOTH.usesStrava && DataMode.BOTH.usesGarmin)
        assertFalse(DataMode.STRAVA.usesGarmin)
        assertFalse(DataMode.GARMIN.usesStrava)
    }

    @Test
    fun `a tenant without settings is unset and not onboarded`() {
        assertEquals(DataMode.UNSET, modes.mode("anna"))
        assertFalse(modes.onboardingComplete("anna"))
    }

    @Test
    fun `the mode is written once and cannot be changed afterwards`() {
        modes.complete("anna", DataMode.STRAVA)
        assertEquals(DataMode.STRAVA, modes.mode("anna"))
        assertTrue(modes.onboardingComplete("anna"))
        assertEquals("STRAVA", settings.values["anna/${TenantModes.DATA_MODE}"])
        assertThrows<IllegalStateException> { modes.complete("anna", DataMode.BOTH) }
        assertEquals(DataMode.STRAVA, modes.mode("anna"))
    }

    @Test
    fun `an eviction (reset) reads the tenant again`() {
        modes.complete("anna", DataMode.GARMIN)
        settings.values.clear()
        assertEquals(DataMode.GARMIN, modes.mode("anna"), "cached")
        modes.evict("anna")
        assertEquals(DataMode.UNSET, modes.mode("anna"))
    }

    @Test
    fun `the guard follows the mode, an unknown or missing tenant gets nothing`() {
        val guard = DataModeGuard(modes)
        assertFalse(guard.stravaAllowed("anna") || guard.garminAllowed("anna"), "unset")
        modes.complete("anna", DataMode.GARMIN)
        assertTrue(guard.garminAllowed("anna"))
        assertFalse(guard.stravaAllowed("anna"))
        assertFalse(guard.stravaAllowed(null) || guard.garminAllowed(null))
    }

    @Test
    fun `no Garmin key is handed out outside a mode that uses Garmin, even when one is stored`() {
        val cipher = SecretCipher(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 }))
        val stored = cipher.encrypt("anna-key-0000000001")
        val resolver = CredentialResolver(
            com.running.config.GarminProperties(apiKey = "owner-key"), registry, TenantSettings { stored }, cipher, DataModeGuard(modes),
        )
        assertEquals(null, resolver.garminKey("anna"), "unset")
        modes.complete("anna", DataMode.STRAVA)
        assertEquals(null, resolver.garminKey("anna"), "strava only")
        assertEquals("owner-key", resolver.garminKey("wout"), "the owner is unaffected")
        modes.evict("anna")
        settings.values["anna/${TenantModes.DATA_MODE}"] = "BOTH"
        assertEquals("anna-key-0000000001", resolver.garminKey("anna"))
    }
}
