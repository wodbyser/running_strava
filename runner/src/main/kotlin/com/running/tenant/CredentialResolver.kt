package com.running.tenant

import com.running.config.GarminProperties
import org.slf4j.LoggerFactory

/** Per-tenant settings stored in the tenant's own database. The real store (`app_settings`) arrives in Phase 4. */
fun interface TenantSettings {
    /** Reads from the database of the tenant bound to the current thread. */
    fun get(key: String): String?
}

/** Phase 3 stand-in: no tenant has stored credentials yet. */
class NoTenantSettings : TenantSettings {
    override fun get(key: String): String? = null
}

/**
 * Who may use which Garmin key (design §7.3).
 * Flag off (no registry): the env key, exactly as before.
 * Flag on: the OWNER uses the env key (never read from his database), anyone else only a stored, decryptable key,
 * and no tenant at all means no key (never a fallback to the owner's key).
 */
class CredentialResolver(
    private val properties: GarminProperties,
    private val registry: TenantRegistry? = null,
    private val settings: TenantSettings? = null,
    private val cipher: SecretCipher? = null,
) {
    private val log = LoggerFactory.getLogger(CredentialResolver::class.java)

    fun garminKey(tenant: String?): String? {
        if (registry == null) return properties.apiKey.takeIf { it.isNotBlank() }
        if (tenant == null) return null
        if (tenant == registry.ownerId) return properties.apiKey.takeIf { it.isNotBlank() }
        val stored = TenantContext.asTenant(tenant) { settings?.get(GARMIN_KEY) } ?: return null
        val plain = cipher?.decrypt(stored)
        if (plain == null) log.warn("Garmin key of tenant '{}' cannot be read (wrong master key or damaged value)", tenant)
        return plain?.takeIf { it.isNotBlank() }
    }

    fun garminConfigured(): Boolean = garminKey(TenantContext.current()) != null

    companion object {
        const val GARMIN_KEY = "garmin.api_key"
    }
}
