package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.TrainingAssessment
import com.running.strava.analysis.isRun
import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service

/**
 * Loads one training with its laps and the runs around it, and hands them to the pure [TrainingAssessment].
 *
 * The laps of the surrounding runs are needed to recognise earlier hard sessions for the load context. They are
 * loaded in one query for the window that matters instead of for the whole history.
 */
@Service
class TrainingRatingService(private val activityRepository: ActivityRepository) {

    data class Rated(val activity: Activity, val assessment: TrainingAssessment.Result)

    fun assess(activityId: Long): Rated? {
        val activity = activityRepository.findById(activityId) ?: return null
        return assess(activity, activityRepository.findAll().filter { it.isRun() })
    }

    /** Several trainings at once, reading the run history only once. Unknown ids are left out. */
    fun assessMany(activityIds: List<Long>): Map<Long, Rated> {
        val allRuns = activityRepository.findAll().filter { it.isRun() }
        return activityIds.distinct().mapNotNull { id ->
            val activity = allRuns.firstOrNull { it.id == id } ?: activityRepository.findById(id) ?: return@mapNotNull null
            id to assess(activity, allRuns)
        }.toMap()
    }

    private fun assess(activity: Activity, allRuns: List<Activity>): Rated {
        val laps = activityRepository.findLaps(activity.id)
        val date = ActivityTime.localDate(activity)
        val windowStart = date.minusDays((TrainingAssessment.LOAD_DAYS + TrainingAssessment.LOAD_COMPARE_DAYS).toLong())
        val contextRuns = allRuns.filter {
            val d = ActivityTime.localDate(it)
            !d.isBefore(windowStart) && !d.isAfter(date)
        }
        val lapsById = activityRepository.findLapsForActivities(contextRuns.map { it.id })
        val context = contextRuns.map { TrainingAssessment.ContextRun(it, lapsById[it.id].orEmpty()) }
        return Rated(activity, TrainingAssessment.assess(activity, laps, allRuns, context))
    }
}
