package com.running.strava.spi

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.SyncStatus
import java.time.ZonedDateTime

interface ActivityRepository {
    fun save(activity: Activity)
    fun saveAll(activities: List<Activity>)
    fun saveStreams(activityId: Long, streams: ActivityStream)
    fun findById(id: Long): Activity?
    fun findStreams(activityId: Long): ActivityStream?
    fun findAll(after: ZonedDateTime? = null): List<Activity>
    fun findAllIds(): Set<Long>
    fun findLatestActivityTimestamp(): ZonedDateTime?
    fun getSyncStatus(): SyncStatus
    fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime)
}
