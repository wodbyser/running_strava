package com.running.strava.usecase.backfill

/** Fetches the detailed streams for runs that are stored but have none (e.g. after a rate limit during a full fetch). */
interface BackfillStreamsData {
    fun execute(): BackfillResult

    data class BackfillResult(
        /** Runs without streams before this run. */
        val missing: Int,
        val fetched: Int,
        /** Runs still without streams afterwards (rate limit or errors). */
        val remaining: Int,
        val rateLimited: Boolean,
        val errors: List<String>,
    )
}
