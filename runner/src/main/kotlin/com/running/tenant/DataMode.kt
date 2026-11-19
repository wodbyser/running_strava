package com.running.tenant

import java.util.concurrent.ConcurrentHashMap

/** Which sources feed a tenant (design §9.1). Written once, at the end of onboarding. */
enum class DataMode {
    UNSET, STRAVA, GARMIN, BOTH;

    val usesStrava: Boolean get() = this == STRAVA || this == BOTH
    val usesGarmin: Boolean get() = this == GARMIN || this == BOTH

    companion object {
        fun derive(hasStrava: Boolean, hasGarmin: Boolean): DataMode = when {
            hasStrava && hasGarmin -> BOTH
            hasStrava -> STRAVA
            hasGarmin -> GARMIN
            else -> UNSET
        }
    }
}

/**
 * The mode and onboarding state of every tenant, cached in memory so a request costs no query (design §9.1).
 * The OWNER is always BOTH and never reads `app_settings`: his database has no such row and must not need one.
 */
class TenantModes(private val registry: TenantRegistry, private val settings: SettingsStore) {
    private data class State(val mode: DataMode, val complete: Boolean)

    private val cache = ConcurrentHashMap<String, State>()

    fun mode(tenant: String): DataMode = if (isOwner(tenant)) DataMode.BOTH else state(tenant).mode

    fun onboardingComplete(tenant: String): Boolean = isOwner(tenant) || state(tenant).complete

    fun isOwner(tenant: String): Boolean = tenant == registry.ownerId

    /** Persists the mode once. A tenant that is already onboarded cannot be changed (D11: only a reset does that). */
    fun complete(tenant: String, mode: DataMode) {
        check(!isOwner(tenant)) { "The owner's mode is fixed" }
        require(mode != DataMode.UNSET) { "Nothing to complete" }
        check(!onboardingComplete(tenant)) { "The data mode is already set" }
        TenantContext.asTenant(tenant) {
            settings.put(DATA_MODE, mode.name)
            settings.put(ONBOARDING_COMPLETE, "true")
        }
        cache[tenant] = State(mode, true)
    }

    fun evict(tenant: String) {
        cache.remove(tenant)
    }

    private fun state(tenant: String): State = cache[tenant] ?: TenantContext.asTenant(tenant) {
        val mode = settings.get(DATA_MODE)?.let { runCatching { DataMode.valueOf(it) }.getOrNull() } ?: DataMode.UNSET
        State(mode, settings.get(ONBOARDING_COMPLETE) == "true" && mode != DataMode.UNSET)
    }.also { cache[tenant] = it }

    companion object {
        const val DATA_MODE = "data_mode"
        const val ONBOARDING_COMPLETE = "onboarding_complete"
    }
}

/** Server-side switch for the Strava and Garmin paths (design §9.2), so a hidden button is never the only protection. */
class DataModeGuard(private val modes: TenantModes) {
    fun stravaAllowed(tenant: String?): Boolean = tenant != null && modes.mode(tenant).usesStrava

    fun garminAllowed(tenant: String?): Boolean = tenant != null && modes.mode(tenant).usesGarmin
}

/** A Strava call was attempted for a tenant whose mode does not use Strava. Thrown before any HTTP request is built. */
class ModeMismatchException(message: String) : RuntimeException(message)

/**
 * Flag-on wrapper around the Strava client (design §9.2): a GARMIN-only or not yet onboarded tenant makes no Strava data call,
 * whichever path reached the client. The OAuth code exchange stays open because onboarding needs it before the mode exists.
 */
class ModeGuardedStravaClient(
    private val delegate: com.running.strava.spi.StravaApiClient,
    private val guard: () -> DataModeGuard,
) : com.running.strava.spi.StravaApiClient {
    private fun <T> allowed(call: () -> T): T {
        if (!guard().stravaAllowed(TenantContext.current())) throw ModeMismatchException("This account does not use Strava")
        return call()
    }

    override fun exchangeToken(code: String) = delegate.exchangeToken(code)
    override fun refreshToken(refreshToken: String) = allowed { delegate.refreshToken(refreshToken) }
    override fun getAthleteActivities(token: com.running.strava.domain.StravaToken, page: Int, perPage: Int, after: Long?) =
        allowed { delegate.getAthleteActivities(token, page, perPage, after) }

    override fun getActivity(token: com.running.strava.domain.StravaToken, activityId: Long) = allowed { delegate.getActivity(token, activityId) }
    override fun getActivityStreams(token: com.running.strava.domain.StravaToken, activityId: Long) = allowed { delegate.getActivityStreams(token, activityId) }
}
