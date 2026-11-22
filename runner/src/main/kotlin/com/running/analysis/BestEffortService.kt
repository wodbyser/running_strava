package com.running.analysis

import com.running.tenant.TenantKey
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.Format
import com.running.strava.analysis.isRun
import com.running.strava.domain.Activity
import com.running.strava.spi.ActivityRepository
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Best efforts (fastest segment over an exact distance) per activity.
 *
 * Primary source: the app's own calculation on the stored distance/time streams ([BestEffortFinder]).
 * Fallback / cross-check: Strava's own best efforts, when they were stored (only for activities fetched with
 * details after the `best_efforts_json` column was added).
 *
 * Only results of an activity that already has streams are cached. A stream that is not downloaded yet can arrive
 * later (backfill, sync), so a missing stream is never remembered as "no best efforts" — that would keep the record
 * list empty until the cache is evicted.
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

    /** One activity's own stream results: the best-effort times and whether a usable stream exists at all. */
    private data class Own(val efforts: Map<Double, Double>, val usable: Boolean)

    private val streamCache = ConcurrentHashMap<TenantKey<Long>, Own>()

    /**
     * Own best-effort times (seconds) per standard distance, from streams; empty map if no usable streams.
     * A result is only cached when the stream exists: an activity whose stream is still to be downloaded is looked up
     * again on the next call.
     */
    fun streamEfforts(activityId: Long): Map<Double, Double> = own(activityId).efforts

    private fun own(activityId: Long): Own {
        val key = TenantKey.of(activityId)
        streamCache[key]?.let { return it }
        val s = activityRepository.findStreams(activityId)
        val time = s?.time
        val distance = s?.distance
        if (time == null || distance == null) return Own(emptyMap(), false)
        val efforts = BestEffortFinder.STANDARD_DISTANCES.mapNotNull { (_, meters) ->
            BestEffortFinder.fastest(time, distance, meters)?.let { meters to it.seconds }
        }.toMap()
        return Own(efforts, true).also { streamCache[key] = it }
    }

    /** Strava best effort (elapsed time) for a distance, matched within 1 %. */
    private fun stravaEffort(a: Activity, meters: Double): Double? =
        a.bestEfforts?.filter { abs(it.distance - meters) <= meters * 0.01 && it.elapsedTime > 0 }
            ?.minOfOrNull { it.elapsedTime.toDouble() }

    fun effort(a: Activity, meters: Double): Effort? = effort(a, meters, streamEfforts(a.id))

    private fun effort(a: Activity, meters: Double, ownEfforts: Map<Double, Double>): Effort? {
        val streamSeconds = ownEfforts[meters]
        val strava = stravaEffort(a, meters)
        return when {
            streamSeconds != null -> Effort(a, meters, streamSeconds, Source.STREAM, strava)
            strava != null -> Effort(a, meters, strava, Source.STRAVA, strava)
            else -> null
        }
    }

    /** Treadmill (trainer) distances come from a foot pod / belt estimate and are not trusted for records. */
    private fun eligible(a: Activity) = a.isRun() && !a.isTrainer

    fun personalRecords(runs: List<Activity>): PersonalRecords {
        val eligible = runs.filter { eligible(it) }
        // One stream lookup per activity for the whole page: a missing stream is not cached, so this keeps the retry
        // cheap without asking the database nine times (once per standard distance) for the same activity.
        val streams = eligible.associate { it.id to own(it.id) }
        val withStreams = streams.values.count { it.usable }
        val stravaOnly = eligible.count { !streams.getValue(it.id).usable && !it.bestEfforts.isNullOrEmpty() }

        val records = BestEffortFinder.STANDARD_DISTANCES.map { (label, meters) ->
            val best = eligible.mapNotNull { effort(it, meters, streams.getValue(it.id).efforts) }.minByOrNull { it.seconds }
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
                    date = PerfDates.day(ActivityTime.localDate(best.activity)),
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

    /** Forgets everything cached for one tenant (reset, delete). */
    fun evict(tenant: String) {
        streamCache.keys.removeIf { it.tenant == tenant }
    }
}
