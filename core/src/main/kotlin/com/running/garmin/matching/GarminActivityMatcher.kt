package com.running.garmin.matching

import com.running.garmin.domain.GarminActivityRef
import com.running.strava.domain.Activity
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class MatchTier { MATCHED, REVIEW, UNMATCHED }
enum class Strength { STRONG, WEAK, FAIL }

/** Thresholds are proposals (docs/garmin-data-provenance.md section 3) until the owner approves them from the dry run. */
data class MatchThresholds(
    val startStrongSeconds: Long = 120,
    val startWeakSeconds: Long = 600,
    val distanceStrongRatio: Double = 0.02,
    val distanceWeakRatio: Double = 0.05,
    val elapsedStrongRatio: Double = 0.03,
    val elapsedStrongSeconds: Double = 10.0,
    val elapsedWeakRatio: Double = 0.10,
    val coordinateStrongMeters: Double = 200.0,
)

data class MatchCandidate(
    val stravaActivityId: Long,
    val startDiffSeconds: Long,
    val distanceDiffRatio: Double?,
    val elapsedDiffRatio: Double?,
    val elapsedDiffSeconds: Double?,
    val startPositionDiffMeters: Double?,
    val start: Strength,
    val distance: Strength,
    val elapsed: Strength,
    val position: Strength?,
) {
    val overall: Strength
        get() = listOfNotNull(start, distance, elapsed, position).maxBy { it.ordinal }
}

data class GarminMatchResult(
    val garminActivityId: Long,
    val tier: MatchTier,
    val reasons: List<String>,
    val candidates: List<MatchCandidate>,
    /** Set only for MATCHED. Phase 1 does not store it. */
    val stravaActivityId: Long?,
)

data class Distribution(val count: Int, val min: Double?, val median: Double?, val p90: Double?, val max: Double?)

data class MatchReport(
    val garminActivities: Int,
    val stravaActivities: Int,
    val tiers: Map<MatchTier, Int>,
    val startDiffSeconds: Distribution,
    val distanceDiffPercent: Distribution,
    val elapsedDiffPercent: Distribution,
    val nearestPairs: Int,
    val externalIdPresent: Int,
    val externalIdContainsGarminId: Int,
)

/** Pure and read-only. Never merges an ambiguous match. */
class GarminActivityMatcher(private val thresholds: MatchThresholds = MatchThresholds()) {

    fun match(garmin: List<GarminActivityRef>, strava: List<Activity>): List<GarminMatchResult> {
        val candidatesByGarmin = garmin.associate { g -> g.activityId to candidatesFor(g, strava) }
        val claims = HashMap<Long, MutableSet<Long>>()
        candidatesByGarmin.forEach { (gid, list) -> list.forEach { claims.getOrPut(it.stravaActivityId) { mutableSetOf() }.add(gid) } }
        val stravaById = strava.associateBy { it.id }

        return garmin.map { g ->
            val candidates = candidatesByGarmin.getValue(g.activityId)
            when {
                candidates.isEmpty() -> GarminMatchResult(g.activityId, MatchTier.UNMATCHED, listOf("no compatible candidate within limits"), emptyList(), null)
                candidates.size > 1 -> GarminMatchResult(g.activityId, MatchTier.REVIEW, listOf("${candidates.size} candidates"), candidates, null)
                else -> {
                    val c = candidates.single()
                    val reasons = mutableListOf<String>()
                    if (c.overall != Strength.STRONG) reasons += "a component is only weak"
                    if (claims.getValue(c.stravaActivityId).size > 1) reasons += "Strava activity claimed by several Garmin activities"
                    if (g.isManual || stravaById[c.stravaActivityId]?.isManual == true) reasons += "manual activity"
                    if (reasons.isEmpty()) GarminMatchResult(g.activityId, MatchTier.MATCHED, listOf("all components strong"), candidates, c.stravaActivityId)
                    else GarminMatchResult(g.activityId, MatchTier.REVIEW, reasons, candidates, null)
                }
            }
        }
    }

    fun report(garmin: List<GarminActivityRef>, strava: List<Activity>): MatchReport {
        val results = match(garmin, strava)
        val tiers = MatchTier.entries.associateWith { t -> results.count { it.tier == t } }
        val starts = mutableListOf<Double>()
        val distances = mutableListOf<Double>()
        val elapsed = mutableListOf<Double>()
        var pairs = 0
        var extPresent = 0
        var extContains = 0
        for (g in garmin) {
            val family = family(g.typeKey, garminFamilies) ?: continue
            val nearest = strava
                .filter { family(it.sportType ?: it.type, stravaFamilies) == family }
                .minByOrNull { abs(it.startDate.toEpochSecond() - g.startTimeGmt.epochSecond) }
                ?: continue
            val c = compare(g, nearest)
            if (c.startDiffSeconds > NEAREST_WINDOW_SECONDS) continue
            pairs++
            starts += abs(c.startDiffSeconds).toDouble()
            c.distanceDiffRatio?.let { distances += it * 100 }
            c.elapsedDiffRatio?.let { elapsed += it * 100 }
            if (c.startDiffSeconds <= thresholds.startWeakSeconds && nearest.externalId != null) {
                extPresent++
                if (nearest.externalId!!.contains(g.activityId.toString())) extContains++
            }
        }
        return MatchReport(garmin.size, strava.size, tiers, distribution(starts), distribution(distances), distribution(elapsed), pairs, extPresent, extContains)
    }

    private fun candidatesFor(g: GarminActivityRef, strava: List<Activity>): List<MatchCandidate> {
        val family = family(g.typeKey, garminFamilies) ?: return emptyList()
        return strava
            .filter { family(it.sportType ?: it.type, stravaFamilies) == family }
            .map { compare(g, it) }
            .filter { it.overall != Strength.FAIL }
    }

    private fun compare(g: GarminActivityRef, s: Activity): MatchCandidate {
        val startDiff = abs(s.startDate.toEpochSecond() - g.startTimeGmt.epochSecond)
        val start = when {
            startDiff <= thresholds.startStrongSeconds -> Strength.STRONG
            startDiff <= thresholds.startWeakSeconds -> Strength.WEAK
            else -> Strength.FAIL
        }
        val dist = g.distanceMeters?.let { ratio(it, s.distance.toDouble()) }
        val distance = when {
            dist == null -> Strength.FAIL
            dist <= thresholds.distanceStrongRatio -> Strength.STRONG
            dist <= thresholds.distanceWeakRatio -> Strength.WEAK
            else -> Strength.FAIL
        }
        val garminElapsed = g.elapsedSeconds ?: g.durationSeconds
        val elapsedRatio = garminElapsed?.let { ratio(it, s.elapsedTime.toDouble()) }
        val elapsedSeconds = garminElapsed?.let { abs(it - s.elapsedTime) }
        val elapsedStrength = when {
            elapsedRatio == null || elapsedSeconds == null -> Strength.FAIL
            elapsedRatio <= thresholds.elapsedStrongRatio || elapsedSeconds <= thresholds.elapsedStrongSeconds -> Strength.STRONG
            elapsedRatio <= thresholds.elapsedWeakRatio -> Strength.WEAK
            else -> Strength.FAIL
        }
        val sLat = s.startLatlng?.getOrNull(0)
        val sLon = s.startLatlng?.getOrNull(1)
        val posDiff = if (g.startLatitude != null && g.startLongitude != null && sLat != null && sLon != null)
            haversineMeters(g.startLatitude!!, g.startLongitude!!, sLat, sLon) else null
        val position = posDiff?.let {
            when {
                it <= thresholds.coordinateStrongMeters -> Strength.STRONG
                else -> Strength.WEAK // supporting component only: it can send a pair to review, never reject it alone
            }
        }
        return MatchCandidate(s.id, startDiff, dist, elapsedRatio, elapsedSeconds, posDiff, start, distance, elapsedStrength, position)
    }

    private fun ratio(a: Double, b: Double): Double {
        val larger = max(abs(a), abs(b))
        return if (larger == 0.0) 0.0 else abs(a - b) / larger
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun distribution(values: List<Double>): Distribution {
        if (values.isEmpty()) return Distribution(0, null, null, null, null)
        val sorted = values.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return Distribution(sorted.size, sorted.first(), pct(0.5), pct(0.9), sorted.last())
    }

    private fun family(key: String?, table: Map<String, String>): String? = key?.let { table[it] }

    companion object {
        const val NEAREST_WINDOW_SECONDS = 3_600L

        /** Family table. Garmin keys seen live only (other keys, such as trail or treadmill running, are UNVERIFIED and never match). */
        val garminFamilies = mapOf("running" to "run", "walking" to "walk", "cycling" to "ride", "lap_swimming" to "swim")
        val stravaFamilies = mapOf(
            "Run" to "run", "TrailRun" to "run", "VirtualRun" to "run",
            "Walk" to "walk", "Hike" to "walk",
            "Ride" to "ride", "VirtualRide" to "ride", "EBikeRide" to "ride",
            "Swim" to "swim",
        )
    }
}
