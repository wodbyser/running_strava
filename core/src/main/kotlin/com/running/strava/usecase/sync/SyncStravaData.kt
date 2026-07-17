package com.running.strava.usecase.sync

interface SyncStravaData {
    fun execute(): SyncResult

    data class SyncResult(
        val activitiesFetched: Int,
        val newActivities: Int,
        val streamsFetched: Int,
        val errors: List<String>,
    )
}
