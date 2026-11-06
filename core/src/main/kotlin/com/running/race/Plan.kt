package com.running.race

import com.running.load.LoadStats
import kotlin.math.abs

/** First and second half of a race from the stream (same distance, elapsed time). */
data class Halves(val firstSeconds: Double, val secondSeconds: Double) {
    /** Positive: second half slower. */
    val splitPct: Double get() = (secondSeconds / firstSeconds - 1) * 100
}

object Pacing {
    const val MIN_METERS = 1000.0

    fun halves(time: List<Int>?, distance: List<Float>?): Halves? {
        if (time == null || distance == null) return null
        val n = minOf(time.size, distance.size)
        if (n < 2) return null
        val total = (distance[n - 1] - distance[0]).toDouble()
        if (total < MIN_METERS) return null
        val target = distance[0] + total / 2
        var k = 1
        while (k < n && distance[k] < target) k++
        if (k >= n) return null
        val d0 = distance[k - 1].toDouble()
        val d1 = distance[k].toDouble()
        val t0 = time[k - 1].toDouble()
        val t1 = time[k].toDouble()
        val half = if (d1 > d0) t0 + (t1 - t0) * ((target - d0) / (d1 - d0)) else t0
        val first = half - time[0]
        val second = time[n - 1] - half
        return if (first > 0 && second > 0) Halves(first, second) else null
    }

    data class Summary(val n: Int, val slowerSecondHalf: Int, val medianSplitPct: Double?, val enough: Boolean, val needed: Int)

    fun summary(halves: List<Halves>, cfg: RaceConfig = RaceConfig.DEFAULT): Summary {
        val enough = halves.size >= cfg.minPacingRaces
        return Summary(halves.size, halves.count { it.splitPct > 0 }, if (enough) LoadStats.median(halves.map { it.splitPct }) else null, enough, cfg.minPacingRaces)
    }
}

data class Split(val label: String, val fromMeters: Double, val toMeters: Double, val seconds: Double, val cumulativeSeconds: Double) {
    val secondsPerKm: Double get() = seconds / ((toMeters - fromMeters) / 1000)
}

/** #52 and #53. Even plan and a calm start variant with the same total time. */
object PacePlan {
    fun paceRange(lowSeconds: Double, highSeconds: Double, distanceMeters: Double): Pair<Double, Double> =
        lowSeconds / (distanceMeters / 1000) to highSeconds / (distanceMeters / 1000)

    /** Splits per km up to 10 km, per 5 km above. */
    fun stepMeters(distanceMeters: Double) = if (distanceMeters <= 10_000.0 + 1) 1000.0 else 5000.0

    /** First 1 km of a 5 km race, first 3 km of anything longer. */
    fun calmMeters(distanceMeters: Double) = if (distanceMeters <= 5_100.0) 1000.0 else 3000.0

    private fun km(m: Double): String = if (abs(m - Math.round(m / 1000.0) * 1000.0) < 0.5) "${Math.round(m / 1000.0)}" else "%.1f".format(java.util.Locale.ROOT, m / 1000).replace('.', ',')

    private fun build(distanceMeters: Double, timeAt: (Double) -> Double): List<Split> {
        val step = stepMeters(distanceMeters)
        val bounds = generateSequence(0.0) { it + step }.takeWhile { it < distanceMeters - 0.5 }.toList() + distanceMeters
        return bounds.zipWithNext().map { (a, b) -> Split("${km(a)}–${km(b)} km", a, b, timeAt(b) - timeAt(a), timeAt(b)) }
    }

    fun even(totalSeconds: Double, distanceMeters: Double): List<Split> =
        build(distanceMeters) { x -> totalSeconds * x / distanceMeters }

    fun calmStart(totalSeconds: Double, distanceMeters: Double, pct: Double): List<Split> {
        val s = calmMeters(distanceMeters)
        if (s >= distanceMeters) return even(totalSeconds, distanceMeters)
        val p1 = totalSeconds / distanceMeters * (1 + pct / 100)
        val t1 = s * p1
        val p2 = (totalSeconds - t1) / (distanceMeters - s)
        return build(distanceMeters) { x -> if (x <= s) x * p1 else t1 + (x - s) * p2 }
    }
}

/** Time spent at or faster than goal pace in long enough stretches (#51 part 3, #50). Short bursts and strides do not count. */
object Specificity {
    const val SMOOTH_SECONDS = 30
    const val MIN_STRETCH_SECONDS = 210

    fun secondsAtOrFaster(time: List<Int>?, distance: List<Float>?, goalSpeedMs: Double, tolerancePct: Double): Double {
        if (time == null || distance == null || goalSpeedMs <= 0) return 0.0
        val n = minOf(time.size, distance.size)
        if (n < 2) return 0.0
        val limit = goalSpeedMs / (1 + tolerancePct / 100)
        var total = 0.0
        var start = -1
        var j = 0
        fun close(end: Int) {
            if (start >= 0) {
                val len = time[end] - time[start]
                if (len >= MIN_STRETCH_SECONDS) total += len
                start = -1
            }
        }
        for (k in 1 until n) {
            while (j < k - 1 && time[k] - time[j + 1] >= SMOOTH_SECONDS) j++
            val dt = time[k] - time[j]
            val ok = dt >= SMOOTH_SECONDS && (distance[k] - distance[j]) / dt >= limit
            if (ok) { if (start < 0) start = k - 1 } else close(k - 1)
        }
        close(n - 1)
        return total
    }
}

enum class GoalBand(val text: String) {
    FASTER("sneller dan je bandbreedte"),
    INSIDE("binnen je bandbreedte"),
    SLOWER("trager dan je voorspelling"),
}

data class Feasibility(
    val band: GoalBand,
    /** Distance to the range in % (negative = goal faster than the range) and in seconds per km. */
    val gapPct: Double?,
    val gapSecondsPerKm: Double?,
    /** Goal against today's prediction in % (negative = needs to get faster). */
    val needPct: Double,
    val weeksToGo: Int,
    val historyWindows: Int?,
    val historyQualifying: Int?,
    val historyNote: String?,
)

/** #51. No verdict word: a band, the needed change and how often the owner's own history did that. */
object GoalFeasibility {
    fun evaluate(
        goalSeconds: Double, distanceMeters: Double, predictionSeconds: Double, lowSeconds: Double, highSeconds: Double,
        weeksToGo: Int, weeklySeries: List<Double?>, cfg: RaceConfig = RaceConfig.DEFAULT,
    ): Feasibility {
        val km = distanceMeters / 1000
        val (band, gap, gapKm) = when {
            goalSeconds < lowSeconds -> Triple(GoalBand.FASTER, (goalSeconds / lowSeconds - 1) * 100, (goalSeconds - lowSeconds) / km)
            goalSeconds > highSeconds -> Triple(GoalBand.SLOWER, (goalSeconds / highSeconds - 1) * 100, (goalSeconds - highSeconds) / km)
            else -> Triple(GoalBand.INSIDE, null, null)
        }
        val need = (goalSeconds / predictionSeconds - 1) * 100
        val known = weeklySeries.count { it != null }
        var windows: Int? = null
        var qualifying: Int? = null
        val note = when {
            weeksToGo < 1 -> "De wedstrijd is deze week: geen periode meer om te verbeteren."
            need >= 0 -> "Je doel is niet sneller dan je huidige voorspelling."
            known < cfg.minFeasibilityWeeks -> "Te weinig geschiedenis voor een vergelijking (${known} weken met een voorspelling, minstens ${cfg.minFeasibilityWeeks} nodig)."
            else -> {
                val w = weeksToGo
                val pairs = weeklySeries.indices.mapNotNull { i ->
                    val a = weeklySeries[i]
                    val b = weeklySeries.getOrNull(i + w)
                    if (a != null && b != null) (b / a - 1) * 100 else null
                }
                windows = pairs.size
                qualifying = pairs.count { it <= need }
                if (pairs.isEmpty()) "Geen vergelijkbare periodes in je geschiedenis." else null
            }
        }
        return Feasibility(band, gap, gapKm, need, weeksToGo, windows, qualifying, note)
    }
}
