package com.running.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "garmin")
data class GarminProperties(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val baseUrl: String = "https://personal.connectapi.garmin.com",
    val storeRawPayloads: Boolean = true,
    val timeout: Duration = Duration.ofSeconds(30),
    val activityPageSize: Int = 100,
    val sync: Sync = Sync(),
    val backfill: Backfill = Backfill(),
    val retry: Retry = Retry(),
    val recovery: Recovery = Recovery(),
    val performance: Performance = Performance(),
) {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "garmin.timeout must be greater than 0" }
        require(backfill.defaultDays >= 1) { "garmin.backfill.default-days must be at least 1" }
        require(recovery.sleepTargetMinutes in 1..1440) { "garmin.recovery.sleep-target-minutes must be between 1 and 1440" }
        require(retry.maxAttempts >= 1 && retry.delays.isNotEmpty()) { "garmin.retry needs max-attempts >= 1 and at least one delay" }
    }

    data class Sync(val cron: String = "0 30 6 * * *", val overlapDays: Int = 3)

    data class Backfill(
        val defaultDays: Int = 730,
        val chunkDays: Int = 7,
        val pause: Duration = Duration.ofSeconds(2),
    )

    /** Phase 3 thresholds: PROPOSAL values from the design, UNVERIFIED. Defaults live in [PerformanceConfig]. */
    data class Performance(
        val zoneSet: String = "garmin",
        val warmupMinutes: Int = 10,
        val minSteadyWindowMinutes: Int = 30,
        val speedCvLimit: Double = 0.10,
        val elevationLimitMPerKm: Double = 15.0,
        val minRunsTrend: Int = 5,
        val minRunsDrift: Int = 5,
        val minRunsDurability: Int = 5,
        val minLongRunsList: Int = 3,
        val minLongRunsTrend: Int = 5,
        val minIntervalSessions: Int = 3,
        val minHrrMeasurements: Int = 10,
        val minPowerRuns: Int = 5,
        val minConsistencyWeeks: Int = 8,
    ) {
        fun toConfig() = com.running.strava.analysis.performance.PerformanceConfig(
            warmupMinutes = warmupMinutes, minSteadyWindowMinutes = minSteadyWindowMinutes, speedCvLimit = speedCvLimit,
            elevationLimitMPerKm = elevationLimitMPerKm, minRunsTrend = minRunsTrend, minRunsDrift = minRunsDrift,
            minRunsDurability = minRunsDurability, minLongRunsList = minLongRunsList, minLongRunsTrend = minLongRunsTrend,
            minIntervalSessions = minIntervalSessions, minHrrMeasurements = minHrrMeasurements, minPowerRuns = minPowerRuns,
            minConsistencyWeeks = minConsistencyWeeks,
        )
    }

    /** Owner decision 2026-10-25: target 7 h 30 total sleep per night. */
    data class Recovery(val sleepTargetMinutes: Int = 450)

    data class Retry(
        val maxAttempts: Int = 3,
        val delays: List<Duration> = listOf(Duration.ofSeconds(1), Duration.ofSeconds(4)),
    )

    val configured: Boolean get() = apiKey.isNotBlank()

    /** The key must never reach a log line. */
    override fun toString(): String = "GarminProperties(enabled=$enabled, baseUrl=$baseUrl, apiKeyPresent=$configured)"
}
