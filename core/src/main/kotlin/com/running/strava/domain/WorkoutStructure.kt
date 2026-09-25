package com.running.strava.domain

import kotlin.math.abs

/**
 * Turns a list of laps into a human-readable workout structure, e.g.
 * `warming-up 2.53 km @ 5:46 /km | 6x 800m @ 3:46 /km ..., herstel 1m30s ... | setpauze 2m30s | 8x 400m ... |
 * cooling-down 2.53 km @ 5:47 /km`.
 *
 * Reps of different length are never averaged together: consecutive reps are grouped into a set as long as
 * they have (roughly) the same distance or the same duration.
 */
object WorkoutStructure {

    private const val SAME_SIZE_TOLERANCE = 0.10

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
        /** Recovery laps between the reps (jog/walk), empty if the set uses floats. */
        val recovery: Block,
        /** Moderate float laps between/after the reps. */
        val floats: Block,
        /** True if reps are time-based (same duration, varying distance) rather than distance-based. */
        val timeBased: Boolean,
    )

    data class Structure(
        val warmup: Block,
        val sets: List<IntervalSet>,
        /** Easy laps between consecutive sets; `setPauses[i]` sits between `sets[i]` and `sets[i + 1]`. */
        val setPauses: List<Block>,
        val cooldown: Block,
    )

    /** Returns null if the activity has no detected interval reps. */
    fun analyze(laps: List<Lap>): Structure? {
        val kinds = LapClassifier.classify(laps)
        val repIdx = laps.indices.filter { kinds[it] == LapKind.REP }
        if (repIdx.isEmpty()) return null

        fun lapsIn(range: IntRange, vararg allowed: LapKind) =
            range.filter { kinds[it] in allowed }.map { laps[it] }

        // Group reps into sets of similar size.
        val repGroups = mutableListOf(mutableListOf(repIdx.first()))
        for (i in repIdx.drop(1)) {
            val current = repGroups.last()
            if (sameSize(laps[current.first()], laps[i])) current.add(i) else repGroups.add(mutableListOf(i))
        }

        val sets = mutableListOf<IntervalSet>()
        val setPauses = mutableListOf<Block>()
        var cursor = 0 // first lap index not yet assigned
        val warmupEnd = repIdx.first() - 1
        val warmup = Block(lapsIn(0..warmupEnd, LapKind.EASY))
        cursor = repIdx.first()

        repGroups.forEachIndexed { g, group ->
            if (g > 0) {
                // Everything between the previous set's end and this set's first rep is a set pause.
                setPauses.add(Block(lapsIn(cursor until group.first(), LapKind.EASY, LapKind.FLOAT)))
            }
            val inner = (group.first()..group.last())
            val recovery = lapsIn(inner, LapKind.EASY).toMutableList()
            val floats = lapsIn(inner, LapKind.FLOAT).toMutableList()
            var end = group.last()

            // Floats directly after the last rep belong to the set (fast/moderate alternation ends on a float).
            while (end + 1 < laps.size && kinds[end + 1] in setOf(LapKind.FLOAT, LapKind.NOISE)) {
                end++
                if (kinds[end] == LapKind.FLOAT) floats.add(laps[end])
            }
            // A jog after the last rep with the same duration as the other recoveries is the last recovery.
            if (floats.isEmpty() && recovery.isNotEmpty() && end + 1 < laps.size && kinds[end + 1] == LapKind.EASY) {
                val typical = recovery.map { it.movingTime }.sorted()[recovery.size / 2]
                val next = laps[end + 1]
                if (abs(next.movingTime - typical) <= typical * 0.15) {
                    recovery.add(next)
                    end++
                }
            }

            val repLaps = group.map { laps[it] }
            sets.add(IntervalSet(
                reps = Block(repLaps),
                recovery = Block(recovery),
                floats = Block(floats),
                timeBased = !sameDistance(repLaps) && sameDuration(repLaps),
            ))
            cursor = end + 1
        }

        val cooldown = Block(lapsIn(cursor until laps.size, LapKind.EASY, LapKind.FLOAT))
        return Structure(warmup, sets, setPauses, cooldown)
    }

    /** One-line summary for AI prompts, or null if the activity has no intervals. */
    fun describe(laps: List<Lap>): String? {
        val s = analyze(laps) ?: return null
        val parts = mutableListOf<String>()
        if (s.warmup.laps.isNotEmpty()) parts.add("warming-up ${km(s.warmup)} @ ${pace(s.warmup.speed)}${hr(s.warmup)}")
        s.sets.forEachIndexed { i, set ->
            if (i > 0) {
                val pause = s.setPauses[i - 1]
                if (pause.laps.isNotEmpty()) parts.add("setpauze ${duration(pause.movingTime)} (${meters(pause.distance)} @ ${pace(pause.speed)})")
            }
            parts.add(describeSet(set))
        }
        if (s.cooldown.laps.isNotEmpty()) parts.add("cooling-down ${km(s.cooldown)} @ ${pace(s.cooldown.speed)}${hr(s.cooldown)}")
        return parts.joinToString(" | ")
    }

    private fun describeSet(set: IntervalSet): String {
        val reps = set.reps
        val n = reps.laps.size
        val size = if (set.timeBased) {
            duration(reps.laps.map { it.movingTime }.sorted()[n / 2])
        } else {
            meters(reps.laps.map { it.distance.toDouble() }.sorted()[n / 2])
        }
        val paces = reps.laps.map { it.averageSpeed.toDouble() }
        val details = listOfNotNull(
            reps.avgHr?.let { "gem. HR %.0f".format(it) },
            reps.maxHr?.let { "max %.0f".format(it) },
            if (n > 1) "reps ${pace(paces.max(), unit = false)}–${pace(paces.min(), unit = false)}" else null,
        )
        val sb = StringBuilder("${n}x $size @ ${pace(reps.speed)}")
        if (details.isNotEmpty()) sb.append(" (${details.joinToString(", ")})")

        if (set.floats.laps.isNotEmpty()) {
            val f = set.floats
            val fn = f.laps.size
            val fSize = meters(f.laps.map { it.distance.toDouble() }.sorted()[fn / 2])
            sb.append(", afgewisseld met ${fn}x $fSize float/matig tempo @ ${pace(f.speed)}${hr(f)}")
        }
        if (set.recovery.laps.isNotEmpty()) {
            val r = set.recovery
            val typical = r.laps.map { it.movingTime }.sorted()[r.laps.size / 2]
            sb.append(", herstel ${duration(typical)} (gem. ${meters(r.distance / r.laps.size)} @ ${pace(r.speed)}${hr(r)})")
        }
        return sb.toString()
    }

    private fun sameSize(a: Lap, b: Lap): Boolean =
        within(a.distance.toDouble(), b.distance.toDouble()) || within(a.movingTime.toDouble(), b.movingTime.toDouble())

    private fun sameDistance(laps: List<Lap>) = laps.all { within(it.distance.toDouble(), laps.first().distance.toDouble()) }
    private fun sameDuration(laps: List<Lap>) = laps.all { within(it.movingTime.toDouble(), laps.first().movingTime.toDouble()) }
    private fun within(a: Double, b: Double) = a > 0 && b > 0 && abs(a - b) / maxOf(a, b) <= SAME_SIZE_TOLERANCE

    fun pace(speedMs: Double, unit: Boolean = true): String {
        if (speedMs <= 0) return "-"
        val s = Math.round(1000 / speedMs).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" + if (unit) " /km" else ""
    }

    private fun km(b: Block) = "%.2f km".format(b.distance / 1000)
    private fun hr(b: Block) = b.avgHr?.let { ", gem. HR %.0f".format(it) } ?: ""
    private fun duration(sec: Int) = if (sec >= 60) "${sec / 60}m${(sec % 60).toString().padStart(2, '0')}s" else "${sec}s"

    /** Rounds to the nearest 10m (e.g. 798.4 -> "800m"). */
    private fun meters(d: Double) = "${(Math.round(d / 10) * 10)}m"
}
