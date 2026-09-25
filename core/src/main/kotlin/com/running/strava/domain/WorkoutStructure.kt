package com.running.strava.domain

import com.running.strava.analysis.Format
import kotlin.math.abs

/**
 * Turns a list of laps into a human-readable workout structure, e.g.
 * `warming-up 2.50 km @ 5:46 /km | 6x 800m @ 3:46 /km ..., herstel 1m30s ... | setpauze 1m00s | 8x 400m ... |
 * cooling-down 2.50 km @ 5:47 /km`.
 *
 * Built on top of [LapClassifier] and therefore an APP HEURISTIC ("automatisch herkend").
 *
 * Reps of different length are never averaged together: consecutive reps are grouped into a set as long as
 * they have (roughly, [SAME_SIZE_TOLERANCE]) the same distance or duration. A run of reps that are all
 * different sizes (pyramid / ladder) is reported as one ladder set instead of one "set" per rep.
 */
object WorkoutStructure {

    const val SAME_SIZE_TOLERANCE = 0.10

    /** The jog after the last rep counts as its recovery when its duration is within this fraction of the others. */
    const val LAST_RECOVERY_TOLERANCE = 0.15

    data class Block(val laps: List<Lap>) {
        val distance: Double get() = laps.sumOf { it.distance.toDouble() }
        val movingTime: Int get() = laps.sumOf { it.movingTime }
        val speed: Double get() = if (movingTime > 0) distance / movingTime else 0.0

        /** Time-weighted average heart rate, or null if no lap has HR. */
        val avgHr: Double?
            get() {
                val withHr = laps.filter { it.averageHeartrate != null }
                val t = withHr.sumOf { it.movingTime }
                return if (t == 0) null else withHr.sumOf { it.averageHeartrate!!.toDouble() * it.movingTime } / t
            }
        val maxHr: Double? get() = laps.mapNotNull { it.maxHeartrate?.toDouble() }.maxOrNull()
    }

    data class IntervalSet(
        val reps: Block,
        /** Recovery laps between the reps (jog/walk). */
        val recovery: Block,
        /** Moderate float laps between/after the reps. */
        val floats: Block,
        /** True if reps are time-based (same duration, varying distance) rather than distance-based. */
        val timeBased: Boolean,
        /** True for a pyramid/ladder: consecutive reps of different sizes. */
        val ladder: Boolean = false,
    )

    enum class Role { WARMUP, COOLDOWN, EASY, TEMPO, SET_PAUSE }

    sealed interface Segment {
        data class Steady(val role: Role, val block: Block) : Segment
        data class Set(val set: IntervalSet) : Segment
    }

    data class Structure(val segments: List<Segment>) {
        val sets: List<IntervalSet> get() = segments.filterIsInstance<Segment.Set>().map { it.set }
        val warmup: Block get() = steady(Role.WARMUP).firstOrNull() ?: Block(emptyList())
        val cooldown: Block get() = steady(Role.COOLDOWN).firstOrNull() ?: Block(emptyList())
        val setPauses: List<Block> get() = steady(Role.SET_PAUSE)
        val tempoBlocks: List<Block> get() = steady(Role.TEMPO)
        private fun steady(role: Role) = segments.filterIsInstance<Segment.Steady>().filter { it.role == role }.map { it.block }
    }

    /** Returns null if the activity has neither interval reps nor a tempo block. */
    fun analyze(laps: List<Lap>, workoutType: Int? = null): Structure? {
        val kinds = LapClassifier.classify(laps, workoutType)
        val repIdx = laps.indices.filter { kinds[it] == LapKind.REP }
        if (repIdx.isEmpty() && kinds.none { it == LapKind.TEMPO }) return null

        // 1. Group reps into sets of similar size, then merge runs of single-rep "sets" into one ladder.
        val sizeGroups = mutableListOf<MutableList<Int>>()
        for (i in repIdx) {
            val current = sizeGroups.lastOrNull()
            if (current != null && sameSize(laps[current.first()], laps[i])) current.add(i) else sizeGroups.add(mutableListOf(i))
        }
        data class Group(val reps: List<Int>, val ladder: Boolean)
        val groups = mutableListOf<Group>()
        var k = 0
        while (k < sizeGroups.size) {
            if (sizeGroups[k].size == 1) {
                var j = k
                while (j + 1 < sizeGroups.size && sizeGroups[j + 1].size == 1) j++
                if (j > k) {
                    groups.add(Group(sizeGroups.subList(k, j + 1).flatten(), ladder = true))
                    k = j + 1
                    continue
                }
            }
            groups.add(Group(sizeGroups[k], ladder = false))
            k++
        }

        // 2. Determine the lap span of every set (reps + recoveries/floats in between + trailing float/recovery).
        data class Span(val start: Int, val end: Int, val set: IntervalSet)
        val spans = mutableListOf<Span>()
        for (g in groups) {
            val first = g.reps.first()
            var end = g.reps.last()
            val inner = first..end
            val recovery = inner.filter { kinds[it] == LapKind.EASY || kinds[it] == LapKind.TEMPO }.map { laps[it] }.toMutableList()
            val floats = inner.filter { kinds[it] == LapKind.FLOAT }.map { laps[it] }.toMutableList()
            while (end + 1 < laps.size && kinds[end + 1] in setOf(LapKind.FLOAT, LapKind.NOISE)) {
                end++
                if (kinds[end] == LapKind.FLOAT) floats.add(laps[end])
            }
            if (floats.isEmpty() && recovery.isNotEmpty() && end + 1 < laps.size && kinds[end + 1] == LapKind.EASY) {
                val typical = recovery.map { it.movingTime }.sorted()[recovery.size / 2]
                val next = laps[end + 1]
                if (abs(next.movingTime - typical) <= typical * LAST_RECOVERY_TOLERANCE) {
                    recovery.add(next)
                    end++
                }
            }
            val repLaps = g.reps.map { laps[it] }
            spans.add(Span(first, end, IntervalSet(
                reps = Block(repLaps),
                recovery = Block(recovery),
                floats = Block(floats),
                timeBased = !g.ladder && isTimeBased(repLaps),
                ladder = g.ladder,
            )))
        }

        // 3. Everything outside the spans becomes steady segments (easy vs tempo), noise is skipped.
        val raw = mutableListOf<Any>() // Segment.Set or Pair<LapKind, MutableList<Lap>>
        var i = 0
        var spanIdx = 0
        while (i < laps.size) {
            val span = spans.getOrNull(spanIdx)
            if (span != null && i == span.start) {
                raw.add(Segment.Set(span.set))
                i = span.end + 1
                spanIdx++
                continue
            }
            if (kinds[i] != LapKind.NOISE) {
                val kind = if (kinds[i] == LapKind.EASY) LapKind.EASY else LapKind.TEMPO
                val last = raw.lastOrNull()
                @Suppress("UNCHECKED_CAST")
                if (last is Pair<*, *> && last.first == kind) (last.second as MutableList<Lap>).add(laps[i])
                else raw.add(kind to mutableListOf(laps[i]))
            }
            i++
        }

        // Warm-up / cool-down labels only make sense around interval sets; around a tempo block or a progression the
        // app cannot tell a warm-up from the first part of the run, so it says "rustig".
        val hasSets = raw.any { it is Segment.Set }
        val segments = raw.mapIndexed { idx, item ->
            if (item is Segment.Set) return@mapIndexed item
            @Suppress("UNCHECKED_CAST")
            val pair = item as Pair<LapKind, List<Lap>>
            val (kind, segLaps) = pair
            val role = when {
                kind == LapKind.TEMPO -> Role.TEMPO
                hasSets && idx == 0 && raw.size > 1 -> Role.WARMUP
                hasSets && idx == raw.lastIndex && raw.size > 1 -> Role.COOLDOWN
                raw.getOrNull(idx - 1) is Segment.Set && raw.getOrNull(idx + 1) is Segment.Set -> Role.SET_PAUSE
                else -> Role.EASY
            }
            Segment.Steady(role, Block(segLaps))
        }
        return Structure(segments)
    }

    /** One-line summary for AI prompts, or null if nothing beyond steady running was detected. */
    fun describe(laps: List<Lap>, workoutType: Int? = null): String? {
        val s = analyze(laps, workoutType) ?: return null
        return s.segments.joinToString(" | ") { seg ->
            when (seg) {
                is Segment.Set -> describeSet(seg.set)
                is Segment.Steady -> {
                    val b = seg.block
                    when (seg.role) {
                        Role.WARMUP -> "warming-up ${km(b)} @ ${Format.pace(b.speed)}${hr(b)}"
                        Role.COOLDOWN -> "cooling-down ${km(b)} @ ${Format.pace(b.speed)}${hr(b)}"
                        Role.EASY -> "rustig ${km(b)} @ ${Format.pace(b.speed)}${hr(b)}"
                        Role.TEMPO -> "tempoblok ${km(b)} in ${duration(b.movingTime)} @ ${Format.pace(b.speed)}${hr(b)}"
                        Role.SET_PAUSE -> "setpauze ${duration(b.movingTime)} (${meters(b.distance)} @ ${Format.pace(b.speed)})"
                    }
                }
            }
        }
    }

    private fun describeSet(set: IntervalSet): String {
        val reps = set.reps
        val n = reps.laps.size
        val size = when {
            set.ladder -> reps.laps.joinToString("-") { meters(it.distance.toDouble()).removeSuffix("m") } + "m"
            set.timeBased -> duration(reps.laps.map { it.movingTime }.sorted()[n / 2])
            else -> meters(reps.laps.map { it.distance.toDouble() }.sorted()[n / 2])
        }
        val paces = reps.laps.map { it.averageSpeed.toDouble() }
        val details = listOfNotNull(
            reps.avgHr?.let { "gem. HR ${Format.hr(it, unit = false)}" },
            reps.maxHr?.let { "max ${Format.hr(it, unit = false)}" },
            if (n > 1) "reps ${Format.pace(paces.max(), unit = false)}–${Format.pace(paces.min(), unit = false)}" else null,
        )
        val sb = StringBuilder(if (set.ladder) "ladder $size @ ${Format.pace(reps.speed)}" else "${n}x $size @ ${Format.pace(reps.speed)}")
        if (details.isNotEmpty()) sb.append(" (${details.joinToString(", ")})")

        if (set.floats.laps.isNotEmpty()) {
            val f = set.floats
            val fn = f.laps.size
            val fSize = meters(f.laps.map { it.distance.toDouble() }.sorted()[fn / 2])
            sb.append(", afgewisseld met ${fn}x $fSize float/matig tempo @ ${Format.pace(f.speed)}${hr(f)}")
        }
        if (set.recovery.laps.isNotEmpty()) {
            val r = set.recovery
            val typical = r.laps.map { it.movingTime }.sorted()[r.laps.size / 2]
            sb.append(", herstel ${duration(typical)} (gem. ${meters(r.distance / r.laps.size)} @ ${Format.pace(r.speed)}${hr(r)})")
        }
        return sb.toString()
    }

    private fun sameSize(a: Lap, b: Lap): Boolean =
        within(a.distance.toDouble(), b.distance.toDouble()) || within(a.movingTime.toDouble(), b.movingTime.toDouble())

    /** Reps are time-based when durations are (nearly) identical and vary less than the distances do. */
    private fun isTimeBased(laps: List<Lap>): Boolean {
        val durSpread = spread(laps.map { it.movingTime.toDouble() })
        val distSpread = spread(laps.map { it.distance.toDouble() })
        return durSpread <= 0.03 && durSpread < distSpread
    }

    private fun spread(v: List<Double>): Double = if (v.isEmpty() || v.max() <= 0) 0.0 else (v.max() - v.min()) / v.max()
    private fun within(a: Double, b: Double) = a > 0 && b > 0 && abs(a - b) / maxOf(a, b) <= SAME_SIZE_TOLERANCE

    /** Kept for callers that need a pace string; delegates to the shared rounded formatter. */
    fun pace(speedMs: Double, unit: Boolean = true): String = Format.pace(speedMs, unit)

    private fun km(b: Block) = "%.2f km".format(b.distance / 1000)
    private fun hr(b: Block) = b.avgHr?.let { ", gem. HR ${Format.hr(it, unit = false)}" } ?: ""
    private fun duration(sec: Int) = if (sec >= 60) "${sec / 60}m${(sec % 60).toString().padStart(2, '0')}s" else "${sec}s"

    /** Rounds to the nearest 10m (e.g. 798.4 -> "800m"). */
    private fun meters(d: Double) = "${(Math.round(d / 10) * 10)}m"
}
