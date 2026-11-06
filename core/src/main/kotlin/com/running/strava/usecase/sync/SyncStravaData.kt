package com.running.strava.usecase.sync

interface SyncStravaData {
    fun execute(): SyncResult

    /** Like [execute] but looks at every activity summary, not only the last 30 days. */
    fun executeFull(): SyncResult

    data class SyncResult(
        val activitiesFetched: Int,
        val newActivities: Int,
        val streamsFetched: Int,
        val errors: List<String>,
        /** Already-stored activities that were edited on Strava and updated. */
        val updatedActivities: Int = 0,
    )
}
