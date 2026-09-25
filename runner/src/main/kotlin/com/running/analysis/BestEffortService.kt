package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.Format
import com.running.strava.analysis.isRun
import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Best efforts (fastest segment over an exact distance) per activity.
 *
 * Primary source: the app's own calculation on the stored distance/time streams ([BestEffortFinder]).
 * Fallback / cross-check: Strava's own best efforts, when they were stored (only for activities fetched with
 * details after the `best_efforts_json` column was added).
 *
 * Streams never change for a given activity, so results are cached in memory per activity id.
 */
@Service
class BestEffortService(
    private val activityRepository: ActivityRepository,
) {
    enum class Source { STREAM, STRAVA }

    data class Effort(
        val activity: Activity,
        val targetMeters: Double,
        val seconds: Double,
        val source: Source,
        /** Strava's own time for the same distance, when available (cross-check). */
        val stravaSeconds: Double?,
    )

    data class PersonalRecord(
        val label: String,
        val time: String,
        val pace: String,
        val date: String,
        val name: String,
        val activityId: Long?,
        val source: String,
        val crossCheck: String?,
    )

    data class PersonalRecords(
        val records: List<PersonalRecord>,
        val runsConsidered: Int,
        val runsWithStreams: Int,
        val runsWithStravaEffortsOnly: Int,
        val treadmillExcluded: Int,
    )

    private val streamCache = ConcurrentHashMap<Long, Map<Double, Double>>()

    /** Own best-effort times (seconds) per standard distance, from streams; empty map if no usable streams. */
    fun streamEfforts(activityId: Long): Map<Double, Double> = streamCache.getOrPut(activityId) {
        val s = activityRepository.findStreams(activityId)
        if (s?.time == null || s.distance == null) emptyMap()
        else BestEffortFinder.STANDARD_DISTANCES.mapNotNull { (_, d) ->
            BestEffortFinder.fastest(s.time, s.distance, d)?.let { d to it.seconds }
        }.toMap()
    }

    /** Strava best effort (elapsed time) for a distance, matched within 1 %. */
    private fun stravaEffort(a: Activity, meters: Double): Double? =
        a.bestEfforts?.filter { abs(it.distance - meters) <= meters * 0.01 && it.elapsedTime > 0 }
            ?.minOfOrNull { it.elapsedTime.toDouble() }

    fun effort(a: Activity, meters: Double): Effort? {
        val own = streamEfforts(a.id)[meters]
        val strava = stravaEffort(a, meters)
        return when {
            own != null -> Effort(a, meters, own, Source.STREAM, strava)
            strava != null -> Effort(a, meters, strava, Source.STRAVA, strava)
            else -> null
        }
    }

    /** Treadmill (trainer) distances come from a foot pod / belt estimate and are not trusted for records. */
    private fun eligible(a: Activity) = a.isRun() && !a.isTrainer

    fun personalRecords(runs: List<Activity>): PersonalRecords {
        val eligible = runs.filter { eligible(it) }
        val withStreams = eligible.count { hasUsableStreams(it.id) }
        val stravaOnly = eligible.count { !hasUsableStreams(it.id) && !it.bestEfforts.isNullOrEmpty() }
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val records = BestEffortFinder.STANDARD_DISTANCES.map { (label, meters) ->
            val best = eligible.mapNotNull { effort(it, meters) }.minByOrNull { it.seconds }
            if (best == null) {
                PersonalRecord(label, "-", "-", "-", "-", null, "-", null)
            } else {
                val cross = best.stravaSeconds?.let { sv ->
                    if (best.source == Source.STREAM) "Strava: ${Format.duration(sv)}" else null
                }
                PersonalRecord(
                    label = label,
                    time = Format.duration(best.seconds),
                    pace = Format.paceFromSeconds(best.seconds / (meters / 1000)),
                    date = ActivityTime.local(best.activity).format(fmt),
                    name = best.activity.name,
                    activityId = best.activity.id,
                    source = if (best.source == Source.STREAM) "berekend uit GPS-stream" else "Strava best effort",
                    crossCheck = cross,
                )
            }
        }
        return PersonalRecords(
            records = records,
            runsConsidered = eligible.size,
            runsWithStreams = withStreams,
            runsWithStravaEffortsOnly = stravaOnly,
            treadmillExcluded = runs.count { it.isRun() && it.isTrainer },
        )
    }

    private val usableStreamCache = ConcurrentHashMap<Long, Boolean>()

    private fun hasUsableStreams(activityId: Long): Boolean = usableStreamCache.getOrPut(activityId) {
        streamEfforts(activityId).isNotEmpty() || activityRepository.findStreams(activityId)?.let { it.time != null && it.distance != null } == true
    }
}
