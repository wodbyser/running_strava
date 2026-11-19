package com.running.tenant

import com.running.strava.adapter.BackfillGate
import com.running.strava.adapter.StravaRateBudget
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.RateLimitExceededException
import com.running.strava.domain.StravaToken
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.usecase.backfill.BackfillLapsData
import com.running.strava.usecase.backfill.BackfillStreamsData
import com.running.strava.usecase.fetch.FetchRemainingData
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Stops a tenant backfill (as if Strava answered 429) once the app's shared read budget is ~70% used (design §10.4). */
class BudgetGatedStravaClient(private val delegate: StravaApiClient, private val budget: StravaRateBudget) : StravaApiClient {
    private fun <T> gated(call: () -> T): T {
        if (!budget.tenantBackfillAllowed()) throw RateLimitExceededException("Strava read budget reserved for the owner's sync")
        return call()
    }

    override fun exchangeToken(code: String) = delegate.exchangeToken(code)
    override fun refreshToken(refreshToken: String) = delegate.refreshToken(refreshToken)
    override fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long?): List<Activity> =
        gated { delegate.getAthleteActivities(token, page, perPage, after) }

    override fun getActivity(token: StravaToken, activityId: Long): Activity = gated { delegate.getActivity(token, activityId) }
    override fun getActivityStreams(token: StravaToken, activityId: Long): ActivityStream = gated { delegate.getActivityStreams(token, activityId) }
}

enum class BackfillPhase { IDLE, QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class BackfillProgress(val phase: BackfillPhase, val activities: Int, val runs: Int, val runsWithDetail: Int)

/**
 * First historical Strava fetch of a non-owner tenant, off the request thread (design §10.4).
 * One thread and one gate for the whole app, so two tenants never backfill at the same time and the owner's 06:00
 * sync keeps its share of the budget. Stops for the day when the budget is used up; the nightly job continues it.
 * Never used for the OWNER.
 */
class TenantBackfill(
    private val gate: BackfillGate,
    private val budget: StravaRateBudget,
    private val fetch: FetchRemainingData,
    private val streams: BackfillStreamsData,
    private val laps: BackfillLapsData,
    private val activities: ActivityRepository,
    private val settings: SettingsStore,
    private val modes: TenantModes,
    private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(TenantBackfill::class.java)
    private val phases = ConcurrentHashMap<String, BackfillPhase>()

    /** Unfinished first fetch of the current tenant. The owner has none and costs no query. */
    fun pending(): Boolean {
        val tenant = TenantContext.current() ?: return false
        if (modes.isOwner(tenant)) return false
        return settings.get(PENDING) == "true"
    }

    /** Marks the first fetch as needed and queues it. */
    fun start() {
        refuseOwner()
        settings.put(PENDING, "true")
        enqueue()
    }

    fun enqueue() {
        val tenant = refuseOwner()
        var started = false
        phases.compute(tenant) { _, previous ->
            if (previous == BackfillPhase.QUEUED || previous == BackfillPhase.RUNNING) {
                previous
            } else {
                started = true
                BackfillPhase.QUEUED
            }
        }
        if (!started) return
        executor.execute { TenantContext.asTenant(tenant) { run(tenant) } }
    }

    fun phase(): BackfillPhase {
        val tenant = TenantContext.currentOrFail()
        return phases[tenant] ?: if (pending()) BackfillPhase.PAUSED else BackfillPhase.DONE
    }

    fun progress(): BackfillProgress {
        val all = activities.findAll()
        val runs = all.filter { it.type in RUN_TYPES && !it.isManual }
        val withStreams = activities.findIdsWithStreams()
        return BackfillProgress(phase(), all.size, runs.size, runs.count { it.id in withStreams })
    }

    private fun refuseOwner(): String {
        val tenant = TenantContext.currentOrFail()
        check(!modes.isOwner(tenant)) { "The owner never uses the tenant backfill" }
        return tenant
    }

    private fun run(tenant: String) {
        try {
            if (!modes.mode(tenant).usesStrava) {
                phases[tenant] = BackfillPhase.IDLE
                return
            }
            phases[tenant] = BackfillPhase.RUNNING
            val ran = gate.runIfFree { phases[tenant] = execute(tenant) }
            if (!ran) phases[tenant] = BackfillPhase.PAUSED
        } catch (e: Exception) {
            phases[tenant] = BackfillPhase.FAILED
            log.warn("Backfill of tenant '{}' failed: {}", tenant, e.javaClass.simpleName)
        }
    }

    private fun execute(tenant: String): BackfillPhase {
        if (!budget.tenantBackfillAllowed()) return BackfillPhase.PAUSED
        val fetched = fetch.execute()
        val missing = streams.execute()
        val lapResult = laps.execute()
        log.info(
            "Backfill of tenant '{}': {} new, {} streams, {} runs still without streams",
            tenant, fetched.newActivities, missing.fetched, missing.remaining,
        )
        val limited = !budget.tenantBackfillAllowed()
        val clean = fetched.errors.isEmpty() && lapResult.errors.isEmpty() && missing.errors.isEmpty() && missing.remaining == 0
        return when {
            clean -> {
                settings.put(PENDING, "false")
                BackfillPhase.DONE
            }
            limited -> BackfillPhase.PAUSED
            else -> BackfillPhase.FAILED
        }
    }

    companion object {
        const val PENDING = "backfill.pending"
        private val RUN_TYPES = setOf("Run", "TrailRun", "VirtualRun")
    }
}
