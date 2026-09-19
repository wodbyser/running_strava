package com.running.strava.usecase.backfill

interface BackfillLapsData {
    fun execute(): BackfillResult

    data class BackfillResult(
        val checked: Int,
        val updated: Int,
        val skipped: Int,
        val errors: List<String>,
    )
}
