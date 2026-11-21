package com.running.strava.spi

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.SyncStatus
import java.time.ZonedDateTime

interface ActivityRepository {
    fun save(activity: Activity)
    fun saveAll(activities: List<Activity>)
    fun saveStreams(activityId: Long, streams: ActivityStream)
    fun saveLaps(activityId: Long, laps: List<Lap>)
    fun findById(id: Long): Activity?
    fun findStreams(activityId: Long): ActivityStream?

    /** Ids of activities that have stored streams. Adapters should override this with a query that skips the stream data. */
    fun findIdsWithStreams(): Set<Long> = findAllIds().filter { findStreams(it) != null }.toSet()

    /**
     * Ids whose Strava detail call has not completed successfully. Adapters should override this with a query.
     * Legacy rows (no marker yet) that already have streams are assumed complete and excluded, so introducing this
     * column does not trigger a refetch of the whole history. New rows are marked only after a successful detail call.
     */
    fun findIdsMissingDetail(): Set<Long> = emptySet()
    fun markDetailFetched(activityId: Long, at: ZonedDateTime) {}
    fun findLaps(activityId: Long): List<Lap>
    fun findLapsForActivities(activityIds: Collection<Long>): Map<Long, List<Lap>>
    fun findAll(after: ZonedDateTime? = null): List<Activity>
    fun findAllIds(): Set<Long>
    fun findLatestActivityTimestamp(): ZonedDateTime?
    fun getSyncStatus(): SyncStatus
    fun updateSyncStatus(lastActivityId: Long?, lastSyncAt: ZonedDateTime)
}
