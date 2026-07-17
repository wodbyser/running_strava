package com.running.strava.usecase.fetch

interface FetchRemainingData {
    fun execute(): FetchResult

    data class FetchResult(
        val newActivities: Int,
        val streamsFetched: Int,
        val errors: List<String>,
    )
}
