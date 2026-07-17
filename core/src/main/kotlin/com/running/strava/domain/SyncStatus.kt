package com.running.strava.domain

import java.time.ZonedDateTime

data class SyncStatus(
    val lastSyncAt: ZonedDateTime?,
    val lastActivityId: Long?,
    val activitiesCount: Int,
    val isSyncing: Boolean,
)
