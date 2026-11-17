package com.running.strava.analysis

import com.running.strava.analysis.TrainingAssessment.Caveat
import com.running.strava.analysis.TrainingAssessment.RatingLevel
import com.running.strava.analysis.TrainingAssessment.RatingTone
import com.running.strava.domain.Activity
import com.running.strava.domain.GoldenLaps
import com.running.strava.domain.GoldenLaps.L
import com.running.strava.domain.Lap
import com.running.strava.domain.SessionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** The expected values follow from the hand calculations in the comments; the fixtures are the golden lap dataset. */
class TrainingAssessmentTest {

    private val day = ZonedDateTime.parse("2026-09-01T08:00:00Z")

    private fun activity(
        laps: List<Lap>,
        hr: Double? = 150.0,
        workoutType: Int? = null,
        trainer: Boolean = false,
        name: String = "testrun",
        start: ZonedDateTime = day,
        elevationPerKm: Double = 0.0,
    ): Activity {
        val distance = laps.sumOf { it.distance.toDouble() }
        val seconds = laps.sumOf { it.movingTime }
        val withHr = laps.filter { it.averageHeartrate != null }
        val avgHr = if (withHr.isEmpty()) null else withHr.sumOf { it.averageHeartrate!!.toDouble() * it.movingTime } / withHr.sumOf { it.movingTime }
        return Activity(
            id = 1, name = name, distance = distance.toFloat(), movingTime = seconds, elapsedTime = seconds,
            totalElevationGain = (distance / 1000.0 * elevationPerKm).toFloat(), type = "Run", sportType = "Run", startDate = start,
            timezone = "(GMT+01:00) Europe/Brussels", averageSpeed = (distance / seconds).toFloat(), maxSpeed = 0f,
            averageHeartrate = (avgHr ?: hr)?.toFloat(), maxHeartrate = withHr.maxOfOrNull { it.maxHeartrate ?: 0f } ?: hr?.plus(20)?.toFloat(),
            averageCadence = 84f, averageWatts = null, maxWatts = null, weightedAverageWatts = null, kilojoules = null,
            deviceWatts = null, description = null, calories = null, sufferScore = null, hasHeartrate = avgHr != null || hr != null,
            elevHigh = null, elevLow = null, gearId = null, startLatlng = null, endLatlng = null, isTrainer = trainer,
            isCommute = false, isManual = false, isFlagged = false, workoutType = workoutType, originalStartDate = null,
            laps = null, splits = null, bestEfforts = null,
        )
    }

    private fun assess(
        laps: List<Lap>,
        hr: Double? = 150.0,
        context: List<Activity> = emptyList(),
        workoutType: Int? = null,
        elevationPerKm: Double = 0.0,
    ): TrainingAssessment.Result {
        val a = activity(laps, hr, workoutType = workoutType, elevationPerKm = elevationPerKm)
        return TrainingAssessment.assess(a, laps, listOf(a) + context, context.map { TrainingAssessment.ContextRun(it) })
    }

    // ---------------------------------------------------------------- interval

    @Test
    fun `even interval reps are judged as sound, with a calm recovery`() {
        val r = assess(GoldenLaps.floatsShortWarmup)
        assertEquals(SessionType.INTERVAL, r.sessionType)
        val i = r.interval!!
        assertEquals(8, i.repCount)
        assertEquals(400.0, i.distanceMeters!!, 1.0)
        // All reps are identical 400 m at 230 s/km: spread 0.
        assertEquals(0.0, i.spreadPct!!, 0.01)
        assertEquals(RatingTone.GOOD, i.consistency)
        // The 400 m between reps run at 285 s/km: 285/230 = 1.239, clearly slower than the reps.
        // The lap classifier calls this a float (a cruise interval), not a full jog.
        assertEquals(1.239, i.recoveryRatio!!, 0.01)
        assertTrue(i.recoveryIsFloat)
        assertEquals(RatingTone.GOOD, i.recoveryTone)
        assertEquals(RatingLevel.SOUND, r.level)
    }

    @Test
    fun `uneven interval reps are flagged, but a short rep gets no consistency verdict`() {
        val uneven = GoldenLaps.laps(
            GoldenLaps.p(1000.0, 350),
            *(1..6).flatMap { i ->
                listOf(GoldenLaps.p(800.0, 225 + i * 5), L(200.0, 90, 150))
            }.toTypedArray(),
            GoldenLaps.p(1000.0, 350),
        )
        val r = assess(uneven)
        val i = r.interval!!
        // 6 reps from 230 to 255 s/km: mean 242.5, spread (255-230)/242.5 = 10.3 %.
        assertEquals(10.3, i.spreadPct!!, 0.1)
        assertEquals(RatingTone.BAD, i.consistency)
        assertEquals(RatingLevel.OFF, r.level)

        val short = GoldenLaps.laps(
            GoldenLaps.p(1000.0, 350),
            *(1..8).flatMap { listOf(GoldenLaps.p(200.0, 210), L(200.0, 90, 150)) }.toTypedArray(),
            GoldenLaps.p(1000.0, 350),
        )
        val s = assess(short)
        assertEquals(RatingTone.UNKNOWN, s.interval!!.consistency)
        assertTrue(s.caveats.contains(Caveat.SHORT_REPS))
    }

    @Test
    fun `reps that get slower are a pace fade, not just a spread`() {
        // 6x 800 m from 4:00 to 4:30 with a full jog between: the second half is clearly slower.
        val laps = GoldenLaps.laps(
            GoldenLaps.p(2000.0, 350),
            *(1..6).flatMap { i -> listOf(GoldenLaps.p(800.0, 240 + i * 6), GoldenLaps.p(400.0, 360)) }.toTypedArray(),
            GoldenLaps.p(1000.0, 350),
        )
        val r = assess(laps)
        val i = r.interval!!
        assertEquals(6, i.repCount)
        assertTrue(i.halves.fadePct!! > TrainingAssessment.REP_FADE_ATTENTION_PCT)
        assertEquals(RatingLevel.OFF, r.level)
    }

    // ---------------------------------------------------------------- tempo, steady, race

    @Test
    fun `tempo block with stable pace and rising HR stays within the decoupling rule`() {
        val r = assess(GoldenLaps.tempo)
        assertEquals(SessionType.TEMPO, r.sessionType)
        val t = r.tempo!!
        assertEquals(5.0, t.meters / 1000, 0.01)
        assertNotNull(t.halves.decouplingPct)
        // HR rises 165 -> 173 bpm over an even pace: (3.7037/166.65 - 3.7037/171.8) / (3.7037/166.65) = 3.0 %.
        assertEquals(3.0, t.halves.decouplingPct!!, 0.2)
        assertEquals(RatingLevel.SOUND, r.level)
    }

    @Test
    fun `an even easy run is sound, a hilly auto-lapped run gets no verdict`() {
        val easy = assess(GoldenLaps.easyRun)
        assertEquals(SessionType.STEADY, easy.sessionType)
        assertEquals(RatingTone.GOOD, easy.steady!!.evenness)
        assertEquals(RatingLevel.SOUND, easy.level)

        val hilly = assess(GoldenLaps.hillyTrailAutoLap, elevationPerKm = 33.0)
        assertTrue(hilly.caveats.contains(Caveat.HILLY))
        assertEquals(RatingTone.UNKNOWN, hilly.steady!!.evenness)
        assertEquals(RatingLevel.INSUFFICIENT, hilly.level)
    }

    @Test
    fun `an evenly paced race is sound and a positive split is an attention point`() {
        val even = assess(GoldenLaps.race, workoutType = 1)
        assertEquals(SessionType.RACE, even.sessionType)
        assertEquals(RatingLevel.SOUND, even.level)

        val fading = GoldenLaps.laps(*(1..10).map { GoldenLaps.p(1000.0, 240 + it * 3, 175) }.toTypedArray())
        val r = assess(fading, workoutType = 1)
        // First half 243-255 (mean 249), second half 258-270 (mean 264): 15/249 = 6.0 % slower.
        assertEquals(6.0, r.race!!.halves.fadePct!!, 0.2)
        assertEquals(RatingTone.BAD, r.race!!.pacing)
        assertEquals(RatingLevel.OFF, r.level)
    }

    // ---------------------------------------------------------------- missing data and load

    @Test
    fun `a run without laps or heart rate gets no level and says why`() {
        val a = activity(emptyList(), hr = null)
        val r = TrainingAssessment.assess(a, emptyList(), listOf(a), emptyList())
        assertEquals(RatingLevel.INSUFFICIENT, r.level)
        assertTrue(r.caveats.contains(Caveat.NO_LAPS))
        assertTrue(r.caveats.contains(Caveat.NO_HR))
        assertNull(r.interval)
    }

    @Test
    fun `a treadmill run is never judged`() {
        val a = activity(GoldenLaps.tempo, trainer = true)
        val r = TrainingAssessment.assess(a, GoldenLaps.tempo, listOf(a), emptyList())
        assertEquals(RatingLevel.INSUFFICIENT, r.level)
        assertTrue(r.caveats.contains(Caveat.TREADMILL))
    }

    @Test
    fun `load context compares the last 7 days with the 28 days before`() {
        val target = activity(GoldenLaps.tempo, start = day)
        fun run(daysAgo: Long, km: Double) = activity(emptyList(), hr = 145.0, start = day.minusDays(daysAgo))
            .copy(distance = (km * 1000).toFloat())
        // Last 7 days (incl. the target): 5 + 5 + 8 (the tempo run is 8 km) = 18 km.
        // Previous 28 days: 4 runs of 10 km = 40 km -> 10 km per 7 days -> +80 %.
        val context = listOf(
            TrainingAssessment.ContextRun(target, GoldenLaps.tempo),
            TrainingAssessment.ContextRun(run(2, 5.0)),
            TrainingAssessment.ContextRun(run(5, 5.0)),
            TrainingAssessment.ContextRun(run(10, 10.0)),
            TrainingAssessment.ContextRun(run(17, 10.0)),
            TrainingAssessment.ContextRun(run(24, 10.0)),
            TrainingAssessment.ContextRun(run(31, 10.0)),
        )
        val r = TrainingAssessment.assess(target, GoldenLaps.tempo, context.map { it.activity }, context)
        assertEquals(3, r.load.runsLast7)
        assertEquals(18.0, r.load.kmLast7, 0.1)
        assertEquals(40.0, r.load.kmPrev28, 0.1)
        assertEquals(10.0, r.load.kmAverage7!!, 0.1)
        assertEquals(80.0, r.load.changePct!!, 0.1)
        assertEquals(1, r.load.hardSessions7) // the tempo run itself
        // The other context runs have no laps, so they are not recognised as hard: there is no earlier hard session.
        assertNull(r.load.daysSinceHard)
    }
}
