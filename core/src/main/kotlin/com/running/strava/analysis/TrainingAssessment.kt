package com.running.strava.analysis

import com.running.strava.analysis.performance.TerrainClass
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.domain.WorkoutStructure
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Execution assessment of ONE training, from the summary and the laps.
 *
 * What this object does NOT do: it cannot know the goal of the session, how it felt (RPE), the weather, sleep,
 * illness, shoes or fuelling. It therefore judges the EXECUTION of the session, not whether the session was useful
 * for a training plan. The page says that explicitly.
 *
 * Every judgement here is an APP HEURISTIC built on lap averages (no GPS/HR streams), so the page labels it
 * "app-schatting". The thresholds below are coaching rules of thumb, not validated science; they are documented
 * per constant so the method section can explain them.
 */
object TrainingAssessment {

    // ---------------------------------------------------------------- thresholds (rules of thumb, documented in the UI)

    /** Interval reps: spread in pace between the fastest and slowest rep, as % of the mean pace. */
    const val REP_SPREAD_GOOD_PCT = 3.0
    const val REP_SPREAD_ATTENTION_PCT = 6.0

    /** Pace trend across the reps: second half of the reps slower than the first half, in %. */
    const val REP_FADE_ATTENTION_PCT = 3.0
    const val REP_FADE_BAD_PCT = 6.0

    /** Tempo/steady pace drift between the two halves. */
    const val PACE_FADE_ATTENTION_PCT = 3.0
    const val PACE_FADE_BAD_PCT = 6.0

    /** Aerobic decoupling (EF drop between the halves), the well-known coaching rule of thumb (not validated as a test). */
    const val DECOUPLING_ATTENTION_PCT = 5.0
    const val DECOUPLING_BAD_PCT = 10.0

    /** Recovery laps that are less than this % slower than the reps are no real recovery. */
    const val RECOVERY_ATTENTION_RATIO = 1.10
    const val RECOVERY_BAD_RATIO = 1.03

    /** Reps shorter than this are dominated by GPS noise and HR lag; no consistency verdict. */
    const val SHORT_REP_SECONDS = 60

    /** The halves of a session are comparable only when the pace stayed within this band. */
    const val HALVES_SPEED_BAND = 0.05

    /** Below this many comparable reps, the spread says too little for a verdict. */
    const val MIN_REPS_FOR_CONSISTENCY = 3

    /** Tempo blocks shorter than this get no decoupling (HR lag at the start dominates). */
    const val MIN_TEMPO_SECONDS = 8 * 60

    /** Steady runs shorter than this get no decoupling (the halves are too short to mean anything). */
    const val MIN_STEADY_SECONDS = 20 * 60

    /** Warm-up that is left out of the decoupling halves of a steady run (standard). */
    const val STEADY_WARMUP_SECONDS = 10 * 60

    /** A run shorter than this is reported as very short; no claim about training effect. */
    const val VERY_SHORT_SECONDS = 15 * 60

    const val LOAD_DAYS = 7
    const val LOAD_COMPARE_DAYS = 28

    // ---------------------------------------------------------------- result model

    /** Visual/interpretive tone of one aspect. UNKNOWN = not enough data for a verdict, not "bad". */
    enum class RatingTone { GOOD, ATTENTION, BAD, UNKNOWN }

    /** Overall execution level. There is deliberately no numeric score: that would suggest a precision the data lacks. */
    enum class RatingLevel { SOUND, MIXED, OFF, INSUFFICIENT }

    enum class DataConfidence { HIGH, MEDIUM, LOW }

    /** Reasons why a judgement is missing or weak. The page turns these into plain Dutch. */
    enum class Caveat {
        NO_LAPS,
        NO_HR,
        TREADMILL,
        MANUAL_ENTRY,
        SHORT_REPS,
        HILLY,
        FEW_REPS,
        LADDER,
        TIME_BASED,
        VERY_SHORT,
        NO_MAX_HR,
        LAP_AVERAGES_ONLY,
        NO_HISTORY,
    }

    data class ContextRun(val activity: Activity, val laps: List<Lap> = emptyList())

    data class Rep(
        val number: Int,
        val distanceMeters: Double,
        val seconds: Int,
        val paceSecondsPerKm: Double,
        val avgHr: Double?,
        val maxHr: Double?,
    )

    /** The two halves of a block, with pace/HR/EF. */
    data class Halves(
        val firstPace: Double?,
        val secondPace: Double?,
        val fadePct: Double?,
        val firstHr: Double?,
        val secondHr: Double?,
        val hrDriftBpm: Double?,
        val decouplingPct: Double?,
        val comparable: Boolean,
    )

    data class Interval(
        val repCount: Int,
        val distanceMeters: Double?,
        val seconds: Double?,
        val timeBased: Boolean,
        val ladder: Boolean,
        val paceSecondsPerKm: Double?,
        val paceMin: Double?,
        val paceMax: Double?,
        val spreadPct: Double?,
        val consistency: RatingTone,
        val halves: Halves,
        val avgHr: Double?,
        val minHr: Double?,
        val maxHr: Double?,
        val recoveryLaps: Int,
        /** True when the laps between the reps are moderate floats, not a jog: the set is a cruise interval, not full recovery. */
        val recoveryIsFloat: Boolean,
        val recoverySeconds: Double?,
        val recoveryMeters: Double?,
        val recoveryPace: Double?,
        val recoveryRatio: Double?,
        val recoveryTone: RatingTone,
        val warmupMeters: Double,
        val cooldownMeters: Double,
        val reps: List<Rep>,
    )

    data class Tempo(
        val blockCount: Int,
        val laps: Int,
        val seconds: Int,
        val meters: Double,
        val paceSecondsPerKm: Double?,
        val avgHr: Double?,
        val halves: Halves,
        val warmupMeters: Double,
        val cooldownMeters: Double,
    )

    data class Steady(
        val substantialLaps: Int,
        val paceMin: Double?,
        val paceMax: Double?,
        val spreadPct: Double?,
        val evenness: RatingTone,
        val halves: Halves,
        val warmupMeters: Double,
    )

    data class Race(
        val halves: Halves,
        val firstHalfPace: Double?,
        val secondHalfPace: Double?,
        val pacing: RatingTone,
    )

    data class Load(
        val runsLast7: Int,
        val kmLast7: Double,
        val kmPrev28: Double,
        val kmAverage7: Double?,
        val changePct: Double?,
        val hardSessions7: Int,
        val hardSessionLabels: List<String>,
        val longestKm28: Double?,
        val daysSinceHard: Long?,
        val daysSinceAnyRun: Long?,
    )

    data class Result(
        val sessionType: SessionType,
        val level: RatingLevel,
        val confidence: DataConfidence,
        val distanceMeters: Double,
        val movingSeconds: Int,
        val paceSecondsPerKm: Double,
        val avgHr: Double?,
        val maxHr: Double?,
        val elevationPerKm: Double,
        val lapCount: Int,
        val hasHr: Boolean,
        val maxHrEstimate: Int?,
        val avgHrPctMax: Double?,
        val interval: Interval?,
        val tempo: Tempo?,
        val steady: Steady?,
        val race: Race?,
        val load: Load,
        val caveats: List<Caveat>,
        val structureText: String?,
    )

    /**
     * [activity] is the run to judge; [laps] its laps. [allRuns] is used only for the max-HR estimate and the
     * load context; [contextRuns] holds the runs of the last [LOAD_COMPARE_DAYS] + [LOAD_DAYS] days with their laps,
     * needed to recognise other hard sessions.
     */
    fun assess(
        activity: Activity,
        laps: List<Lap>,
        allRuns: List<Activity>,
        contextRuns: List<ContextRun>,
    ): Result {
        val elevationPerKm = TerrainClass.elevationPerKm(activity)
        val sessionType = if (laps.size > 1 || activity.workoutType == 1) {
            SessionClassifier.classify(laps, activity.workoutType, elevationPerKm)
        } else {
            SessionType.STEADY
        }
        val maxHrEstimate = HrZones.estimateMaxHrForRuns(allRuns.filter { it.isRun() }, activity.startDate)
        val avgHrPctMax = activity.averageHeartrate
            ?.takeIf { maxHrEstimate != null && maxHrEstimate!! > 0 }
            ?.let { it.toDouble() / maxHrEstimate!! * 100 }

        val structure = if (laps.size > 1) WorkoutStructure.analyze(laps, activity.workoutType, elevationPerKm) else null
        val caveats = mutableListOf<Caveat>()
        if (laps.size <= 1) caveats += Caveat.NO_LAPS
        if (activity.averageHeartrate == null) caveats += Caveat.NO_HR
        if (activity.isTrainer) caveats += Caveat.TREADMILL
        if (activity.isManual) caveats += Caveat.MANUAL_ENTRY
        if (activity.movingTime < VERY_SHORT_SECONDS) caveats += Caveat.VERY_SHORT
        if (laps.size > 1) caveats += Caveat.LAP_AVERAGES_ONLY
        if (maxHrEstimate == null) caveats += Caveat.NO_MAX_HR
        if (elevationPerKm > LapClassifier.HILLY_ELEVATION_PER_KM) caveats += Caveat.HILLY
        if (contextRuns.isEmpty()) caveats += Caveat.NO_HISTORY

        val interval = if (sessionType == SessionType.INTERVAL) {
            interval(laps, activity, elevationPerKm, structure, maxHrEstimate, caveats)
        } else {
            null
        }
        val tempo = if (sessionType == SessionType.TEMPO) {
            tempo(laps, activity, elevationPerKm, structure)
        } else {
            null
        }
        val steady = if (sessionType == SessionType.STEADY || sessionType == SessionType.STRIDES) {
            steady(laps, activity, elevationPerKm)
        } else {
            null
        }
        val race = if (sessionType == SessionType.RACE) race(laps, activity) else null

        val level = level(sessionType, interval, tempo, steady, race, laps.size, activity, caveats)
        val confidence = when {
            activity.isTrainer || activity.isManual || (laps.size <= 1 && activity.averageHeartrate == null) -> DataConfidence.LOW
            laps.size > 4 && activity.averageHeartrate != null -> DataConfidence.HIGH
            else -> DataConfidence.MEDIUM
        }
        val load = loadContext(activity, contextRuns)

        return Result(
            sessionType = sessionType,
            level = level,
            confidence = confidence,
            distanceMeters = activity.distance.toDouble(),
            movingSeconds = activity.movingTime,
            paceSecondsPerKm = 1000.0 / activity.speedMs(),
            avgHr = activity.averageHeartrate?.toDouble(),
            maxHr = activity.maxHeartrate?.toDouble(),
            elevationPerKm = elevationPerKm,
            lapCount = laps.size,
            hasHr = activity.averageHeartrate != null,
            maxHrEstimate = maxHrEstimate,
            avgHrPctMax = avgHrPctMax,
            interval = interval,
            tempo = tempo,
            steady = steady,
            race = race,
            load = load,
            caveats = caveats.distinct(),
            structureText = structure?.let { WorkoutStructure.describe(laps, activity.workoutType, elevationPerKm) },
        )
    }

    // ---------------------------------------------------------------- interval

    private fun interval(
        laps: List<Lap>,
        activity: Activity,
        elevationPerKm: Double,
        structure: WorkoutStructure.Structure?,
        maxHrEstimate: Int?,
        caveats: MutableList<Caveat>,
    ): Interval? {
        val sets = structure?.sets.orEmpty()
        val primary = sets.maxByOrNull { it.reps.laps.size } ?: return null
        val repLaps = primary.reps.laps
        if (repLaps.isEmpty()) return null
        if (repLaps.any { it.movingTime < SHORT_REP_SECONDS }) caveats += Caveat.SHORT_REPS
        if (primary.ladder) caveats += Caveat.LADDER
        if (primary.timeBased) caveats += Caveat.TIME_BASED
        if (repLaps.size < MIN_REPS_FOR_CONSISTENCY) caveats += Caveat.FEW_REPS

        val paces = repLaps.map { 1000.0 / it.speedMs() }
        val mean = paces.average()
        val min = paces.min()
        val max = paces.max()
        val spread = if (mean > 0) (max - min) / mean * 100 else null
        val rows = if (primary.timeBased) repLaps.map { it.movingTime.toDouble() } else repLaps.map { it.distance.toDouble() }
        val sameSize = rows.all { it > 0 } && (rows.max() / rows.min()) <= 1.0 + WorkoutStructure.SAME_SIZE_TOLERANCE + 1e-9
        val consistency = when {
            spread == null || !sameSize || primary.ladder || repLaps.size < MIN_REPS_FOR_CONSISTENCY -> RatingTone.UNKNOWN
            repLaps.any { it.movingTime < SHORT_REP_SECONDS } -> RatingTone.UNKNOWN
            elevationPerKm > LapClassifier.HILLY_ELEVATION_PER_KM -> RatingTone.UNKNOWN
            spread <= REP_SPREAD_GOOD_PCT -> RatingTone.GOOD
            spread <= REP_SPREAD_ATTENTION_PCT -> RatingTone.ATTENTION
            else -> RatingTone.BAD
        }

        val halves = halves(repLaps)
        val hrLaps = repLaps.filter { it.averageHeartrate != null }
        val avgHr = EfficiencyFactor.avgHr(hrLaps.map { EfficiencyFactor.Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate?.toDouble()) })
        val minHr = hrLaps.mapNotNull { it.averageHeartrate?.toDouble() }.minOrNull()
        val maxHr = hrLaps.mapNotNull { it.averageHeartrate?.toDouble() }.maxOrNull()

        // Floats are the laps between reps of a cruise interval. They are reported as the break between reps, but
        // labelled as floats on the page: a float is moderate on purpose, so a fast float is not a mistake.
        val recoveryIsFloat = primary.recovery.laps.isEmpty() && primary.floats.laps.isNotEmpty()
        val recovery = if (recoveryIsFloat) primary.floats else primary.recovery
        val recoveryCount = recovery.laps.size
        val recoveryPace = if (recovery.movingTime > 0 && recovery.distance > 0) 1000.0 / recovery.speed else null
        val recoveryRatio = recoveryPace?.takeIf { mean > 0 }?.let { it / mean }
        val recoveryTone = when {
            recoveryRatio == null || recoveryCount == 0 -> RatingTone.UNKNOWN
            recoveryRatio <= RECOVERY_BAD_RATIO -> RatingTone.BAD
            recoveryRatio <= RECOVERY_ATTENTION_RATIO -> RatingTone.ATTENTION
            else -> RatingTone.GOOD
        }

        return Interval(
            repCount = repLaps.size,
            distanceMeters = if (primary.timeBased) null else rows.average(),
            seconds = if (primary.timeBased) rows.average() else null,
            timeBased = primary.timeBased,
            ladder = primary.ladder,
            paceSecondsPerKm = mean.takeIf { it > 0 },
            paceMin = min,
            paceMax = max,
            spreadPct = spread,
            consistency = consistency,
            halves = halves,
            avgHr = avgHr,
            minHr = minHr,
            maxHr = maxHr,
            recoveryLaps = recoveryCount,
            recoveryIsFloat = recoveryIsFloat,
            recoverySeconds = if (recoveryCount > 0) recovery.movingTime.toDouble() / recoveryCount else null,
            recoveryMeters = if (recoveryCount > 0) recovery.distance / recoveryCount else null,
            recoveryPace = recoveryPace,
            recoveryRatio = recoveryRatio,
            recoveryTone = recoveryTone,
            warmupMeters = structure?.warmup?.distance ?: 0.0,
            cooldownMeters = structure?.cooldown?.distance ?: 0.0,
            reps = repLaps.mapIndexed { i, lap ->
                Rep(
                    number = i + 1,
                    distanceMeters = lap.distance.toDouble(),
                    seconds = lap.movingTime,
                    paceSecondsPerKm = 1000.0 / lap.speedMs(),
                    avgHr = lap.averageHeartrate?.toDouble(),
                    maxHr = lap.maxHeartrate?.toDouble(),
                )
            },
        )
    }

    // ---------------------------------------------------------------- tempo

    private fun tempo(
        laps: List<Lap>,
        activity: Activity,
        elevationPerKm: Double,
        structure: WorkoutStructure.Structure?,
    ): Tempo? {
        val blocks = structure?.tempoBlocks.orEmpty()
        val kinds = LapClassifier.classify(laps, activity.workoutType, elevationPerKm)
        val validLaps = laps.indices
            .filter { kinds.getOrNull(it) != LapKind.NOISE }
            .map { laps[it] }
            .toSet()
        val used = blocks.flatMap { it.laps }
            .filter { it in validLaps }
            .distinctBy { it.lapIndex }
            .sortedBy { it.lapIndex }
        if (used.isEmpty()) return null
        val distance = used.sumOf { it.distance.toDouble() }
        val seconds = used.sumOf { it.movingTime }
        val pace = if (seconds > 0 && distance > 0) seconds / (distance / 1000.0) else null
        val halves = halves(used)
        return Tempo(
            blockCount = blocks.size,
            laps = used.size,
            seconds = seconds,
            meters = distance,
            paceSecondsPerKm = pace,
            avgHr = EfficiencyFactor.avgHr(used.map { EfficiencyFactor.Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate?.toDouble()) }),
            halves = halves,
            warmupMeters = structure?.warmup?.distance ?: 0.0,
            cooldownMeters = structure?.cooldown?.distance ?: 0.0,
        )
    }

    // ---------------------------------------------------------------- steady / strides

    private fun steady(laps: List<Lap>, activity: Activity, elevationPerKm: Double): Steady? {
        val kinds = if (laps.size > 1) LapClassifier.classify(laps, activity.workoutType, elevationPerKm) else emptyList()
        val valid = laps.indices.filter { kinds.getOrNull(it) != LapKind.NOISE }.map { laps[it] }
            .filter { it.movingTime >= 60 && it.distance >= 200 }
        if (valid.size < 2) return null
        val paces = valid.map { 1000.0 / it.speedMs() }
        val mean = paces.average()
        val spread = if (mean > 0) (paces.max() - paces.min()) / mean * 100 else null
        val evenness = when {
            spread == null || valid.size < 3 -> RatingTone.UNKNOWN
            elevationPerKm > LapClassifier.HILLY_ELEVATION_PER_KM -> RatingTone.UNKNOWN
            spread <= 8.0 -> RatingTone.GOOD
            spread <= 15.0 -> RatingTone.ATTENTION
            else -> RatingTone.BAD
        }
        // Decoupling ignores the warm-up; evenness above uses the whole run.
        val warmup = warmupLaps(valid, STEADY_WARMUP_SECONDS).let { drop ->
            if (drop >= valid.size) emptyList() else valid.drop(drop)
        }
        val window = if (warmup.size >= 2 && warmup.sumOf { it.movingTime } >= MIN_STEADY_SECONDS) warmup else valid
        // On a hilly run the pace difference between the halves is terrain, not effort: decoupling would be noise.
        val h = if (elevationPerKm > LapClassifier.HILLY_ELEVATION_PER_KM) {
            Halves(null, null, null, null, null, null, null, false)
        } else {
            halves(window)
        }
        return Steady(
            substantialLaps = valid.size,
            paceMin = paces.min(),
            paceMax = paces.max(),
            spreadPct = spread,
            evenness = evenness,
            halves = h,
            warmupMeters = valid.take(warmupLaps(valid, STEADY_WARMUP_SECONDS)).sumOf { it.distance.toDouble() },
        )
    }

    // ---------------------------------------------------------------- race

    private fun race(laps: List<Lap>, activity: Activity): Race? {
        val kinds = if (laps.size > 1) LapClassifier.classify(laps, activity.workoutType, TerrainClass.elevationPerKm(activity)) else emptyList()
        val valid = laps.indices.filter { kinds.getOrNull(it) != LapKind.NOISE }.map { laps[it] }
        if (valid.size < 2) return null
        val h = halves(valid)
        // For a race the pace split is the point: it does not need comparable halves (that rule protects decoupling).
        val pacing = when {
            h.fadePct == null -> RatingTone.UNKNOWN
            h.fadePct <= 2.0 -> RatingTone.GOOD // even or a negative split
            h.fadePct <= 5.0 -> RatingTone.ATTENTION
            else -> RatingTone.BAD
        }
        return Race(h, h.firstPace, h.secondPace, pacing)
    }

    // ---------------------------------------------------------------- halves, level, load

    /**
     * Splits [laps] by moving time and compares pace, HR and EF (metres per heartbeat). Positive fade = slower
     * second half. Decoupling is only meaningful when the pace stayed within ±5 %.
     */
    fun halves(laps: List<Lap>): Halves {
        if (laps.size < 2) return Halves(null, null, null, null, null, null, null, false)
        val total = laps.sumOf { it.movingTime }
        if (total <= 0) return Halves(null, null, null, null, null, null, null, false)
        var acc = 0
        val first = mutableListOf<Lap>()
        val second = mutableListOf<Lap>()
        for (lap in laps.sortedBy { it.lapIndex }) {
            if (acc < total / 2.0) first += lap else second += lap
            acc += lap.movingTime
        }
        fun pace(ls: List<Lap>): Double? {
            val d = ls.sumOf { it.distance.toDouble() }
            val t = ls.sumOf { it.movingTime }
            return if (d > 0 && t > 0) t / (d / 1000.0) else null
        }
        fun hr(ls: List<Lap>): Double? = EfficiencyFactor.avgHr(ls.map {
            EfficiencyFactor.Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate?.toDouble())
        })
        fun ef(ls: List<Lap>): Double? = EfficiencyFactor.pooled(ls.map {
            EfficiencyFactor.Sample(it.distance.toDouble(), it.movingTime.toDouble(), it.averageHeartrate?.toDouble())
        })
        val p1 = pace(first)
        val p2 = pace(second)
        val h1 = hr(first)
        val h2 = hr(second)
        val e1 = ef(first)
        val e2 = ef(second)
        val fade = if (p1 != null && p2 != null && p1 > 0) (p2 - p1) / p1 * 100 else null
        val speedRatio = if (p1 != null && p2 != null && p1 > 0 && p2 > 0) p1 / p2 else null
        val comparable = speedRatio != null && abs(speedRatio - 1.0) <= HALVES_SPEED_BAND
        val decoupling = if (e1 != null && e2 != null && e1 > 0) (e1 - e2) / e1 * 100 else null
        return Halves(p1, p2, fade, h1, h2, if (h1 != null && h2 != null) h2 - h1 else null, decoupling, comparable)
    }

    /** Number of leading laps that together cover the warm-up, capped so at least two laps remain. */
    private fun warmupLaps(laps: List<Lap>, seconds: Int): Int {
        var acc = 0
        var n = 0
        for (lap in laps) {
            if (acc >= seconds) break
            acc += lap.movingTime
            n++
        }
        return n.coerceAtMost(maxOf(0, laps.size - 2))
    }

    private fun level(
        type: SessionType,
        interval: Interval?,
        tempo: Tempo?,
        steady: Steady?,
        race: Race?,
        lapCount: Int,
        activity: Activity,
        caveats: List<Caveat>,
    ): RatingLevel {
        if (activity.isTrainer || activity.isManual) return RatingLevel.INSUFFICIENT
        if (lapCount <= 1 && activity.averageHeartrate == null) return RatingLevel.INSUFFICIENT
        val tones = when (type) {
            SessionType.INTERVAL -> listOfNotNull(interval?.consistency, interval?.recoveryTone, interval?.halves?.let { paceFadeTone(it.fadePct) })
            SessionType.TEMPO -> tempo?.let { listOfNotNull(paceFadeTone(it.halves.fadePct), decouplingTone(it.halves)) }.orEmpty()
            SessionType.RACE -> listOfNotNull(race?.pacing)
            SessionType.STEADY, SessionType.STRIDES -> steady?.let { listOfNotNull(it.evenness, decouplingTone(it.halves)) }.orEmpty()
        }.filter { it != RatingTone.UNKNOWN }
        return when {
            // No single aspect could be judged (no laps, no HR, or every aspect is outside what the data can support,
            // e.g. a hilly auto-lapped run): no level, instead of a green level that would suggest a verdict.
            tones.isEmpty() -> RatingLevel.INSUFFICIENT
            tones.contains(RatingTone.BAD) -> RatingLevel.OFF
            tones.contains(RatingTone.ATTENTION) -> RatingLevel.MIXED
            else -> RatingLevel.SOUND
        }
    }

    private fun paceFadeTone(fadePct: Double?): RatingTone = when {
        fadePct == null -> RatingTone.UNKNOWN
        fadePct > PACE_FADE_BAD_PCT -> RatingTone.BAD
        fadePct > PACE_FADE_ATTENTION_PCT -> RatingTone.ATTENTION
        else -> RatingTone.GOOD
    }

    private fun decouplingTone(h: Halves): RatingTone = when {
        h.decouplingPct == null || !h.comparable -> RatingTone.UNKNOWN
        h.decouplingPct > DECOUPLING_BAD_PCT -> RatingTone.BAD
        h.decouplingPct > DECOUPLING_ATTENTION_PCT -> RatingTone.ATTENTION
        else -> RatingTone.GOOD
    }

    private fun loadContext(activity: Activity, context: List<ContextRun>): Load {
        val date = ActivityTime.localDate(activity)
        val runs = context.filter { it.activity.isRun() && !it.activity.isManual }
        val last7 = runs.filter { !ActivityTime.localDate(it.activity).isBefore(date.minusDays(LOAD_DAYS - 1L)) && !ActivityTime.localDate(it.activity).isAfter(date) }
        val prev28 = runs.filter { !ActivityTime.localDate(it.activity).isBefore(date.minusDays(LOAD_DAYS + LOAD_COMPARE_DAYS - 1L)) && ActivityTime.localDate(it.activity).isBefore(date.minusDays(LOAD_DAYS - 1L)) }
        val km7 = last7.sumOf { it.activity.distance.toDouble() } / 1000
        val km28 = prev28.sumOf { it.activity.distance.toDouble() } / 1000
        val avg7 = if (prev28.isEmpty()) null else km28 / (LOAD_COMPARE_DAYS / LOAD_DAYS.toDouble())
        val change = if (avg7 != null && avg7 > 0) (km7 - avg7) / avg7 * 100 else null

        fun typeOf(run: ContextRun): SessionType =
            if (run.laps.size > 1 || run.activity.workoutType == 1) {
                SessionClassifier.classify(run.laps, run.activity.workoutType, TerrainClass.elevationPerKm(run.activity))
            } else {
                SessionType.STEADY
            }
        val hardTypes = setOf(SessionType.INTERVAL, SessionType.TEMPO, SessionType.RACE)
        val hardLast7 = last7.filter { typeOf(it) in hardTypes }
        val labels = hardLast7.map { typeOf(it) }
        val previous = context.filter { it.activity.startDate.isBefore(activity.startDate) && typeOf(it) in hardTypes }
            .maxByOrNull { it.activity.startDate }
        val previousAny = context.filter { it.activity.startDate.isBefore(activity.startDate) }.maxByOrNull { it.activity.startDate }
        val longest28 = runs.filter {
            !ActivityTime.localDate(it.activity).isBefore(date.minusDays(27)) && !ActivityTime.localDate(it.activity).isAfter(date)
        }.maxOfOrNull { it.activity.distance.toDouble() }?.div(1000)

        return Load(
            runsLast7 = last7.size,
            kmLast7 = km7,
            kmPrev28 = km28,
            kmAverage7 = avg7,
            changePct = change,
            hardSessions7 = hardLast7.size,
            hardSessionLabels = labels.map { it.name },
            longestKm28 = longest28,
            daysSinceHard = previous?.let { ChronoUnit.DAYS.between(ActivityTime.localDate(it.activity), date) },
            daysSinceAnyRun = previousAny?.let { ChronoUnit.DAYS.between(ActivityTime.localDate(it.activity), date) },
        )
    }

}
