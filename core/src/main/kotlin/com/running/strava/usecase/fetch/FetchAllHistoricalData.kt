package com.running.strava.usecase.fetch

interface FetchAllHistoricalData {
    fun execute(): FetchResult

    data class FetchResult(
        val activitiesFetched: Int,
        val errors: List<String>,
    )
}
