package com.running.garmin.adapter

import com.running.garmin.projection.GarminActivityProjection
import com.running.garmin.spi.GarminRepository
import com.running.strava.spi.ActivityRepository
import org.slf4j.LoggerFactory

/**
 * Writes the stored Garmin activities into `activities` for a GARMIN-only tenant (design §9.4).
 * Idempotent, and it never overwrites a row that is not its own (null source means Strava).
 */
class GarminActivityProjector(
    private val garmin: GarminRepository,
    private val activities: ActivityRepository,
) {
    private val log = LoggerFactory.getLogger(GarminActivityProjector::class.java)

    fun project(): Int {
        var skipped = 0
        val rows = garmin.findAllActivities().mapNotNull { ref ->
            val existing = activities.findById(ref.activityId)
            if (existing != null && existing.source != GarminActivityProjection.SOURCE) {
                skipped++
                null
            } else {
                GarminActivityProjection.project(ref)
            }
        }
        if (rows.isNotEmpty()) activities.saveAll(rows)
        if (skipped > 0) log.warn("Garmin projection skipped {} activities whose id already belongs to another source", skipped)
        return rows.size
    }
}
