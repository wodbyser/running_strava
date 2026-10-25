package com.running.garmin.domain

import java.time.Instant
import java.time.LocalDate

enum class GarminErrorCategory { NOT_CONFIGURED, AUTH, NETWORK, TIMEOUT, RATE_LIMIT, SERVER, CLIENT, PARSE }

/** Never carries a key, token, response body or health value. */
class GarminApiException(
    val category: GarminErrorCategory,
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val retryable: Boolean
        get() = category in RETRYABLE

    companion object {
        val RETRYABLE = setOf(
            GarminErrorCategory.NETWORK,
            GarminErrorCategory.TIMEOUT,
            GarminErrorCategory.RATE_LIMIT,
            GarminErrorCategory.SERVER,
        )
    }
}

enum class GarminRunKind { SYNC, BACKFILL }

data class GarminCallFailure(
    val endpoint: String,
    val from: LocalDate?,
    val to: LocalDate?,
    val category: GarminErrorCategory,
)

data class GarminSyncResult(
    val kind: GarminRunKind,
    val from: LocalDate,
    val to: LocalDate,
    val startedAt: Instant,
    val finishedAt: Instant,
    val recordsPerEndpoint: Map<String, Int>,
    val nullFieldsPerEndpoint: Map<String, Int>,
    val failures: List<GarminCallFailure>,
    val aborted: Boolean,
) {
    val success: Boolean get() = failures.isEmpty() && !aborted
    val authFailed: Boolean get() = failures.any { it.category == GarminErrorCategory.AUTH }
}

/** Persistent record of the last run (table `garmin_sync_status`, row id 1). */
data class GarminSyncStatus(
    val lastRunKind: GarminRunKind?,
    val lastRunStartedAt: Instant?,
    val lastRunFinishedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastSuccessDate: LocalDate?,
    val lastErrorCategory: GarminErrorCategory?,
    val lastErrorDetail: String?,
    val recordsPerEndpoint: Map<String, Int>,
    val nullFieldsPerEndpoint: Map<String, Int>,
    val failedCalls: Int,
) {
    companion object {
        val EMPTY = GarminSyncStatus(null, null, null, null, null, null, null, emptyMap(), emptyMap(), 0)
    }
}
