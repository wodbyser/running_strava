package com.running.strava.domain

import com.running.strava.domain.GoldenLaps.L
import com.running.strava.domain.LapKind.EASY
import com.running.strava.domain.LapKind.FLOAT
import com.running.strava.domain.LapKind.NOISE
import com.running.strava.domain.LapKind.REP
import com.running.strava.domain.LapKind.TEMPO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LapClassifierGoldenTest {

    private fun kinds(laps: List<Lap>, workoutType: Int? = null) = LapClassifier.classify(laps, workoutType)
    private fun session(laps: List<Lap>, workoutType: Int? = null) = SessionClassifier.classify(laps, workoutType)

    @Test
    fun `easy run - nothing detected`() {
        assertTrue(kinds(GoldenLaps.easyRun).all { it == EASY })
        assertEquals(SessionType.STEADY, session(GoldenLaps.easyRun))
    }

    @Test
    fun `long run with natural drift - nothing detected`() {
        assertTrue(kinds(GoldenLaps.longRun).all { it == EASY })
        assertEquals(SessionType.STEADY, session(GoldenLaps.longRun))
    }

    @Test
    fun `tempo run - tempo block, no reps`() {
        assertEquals(listOf(EASY, EASY, TEMPO, TEMPO, TEMPO, TEMPO, TEMPO, EASY), kinds(GoldenLaps.tempo))
        assertEquals(SessionType.TEMPO, session(GoldenLaps.tempo))
    }

    @Test
    fun `fast and float 400s after a short warm-up - floats are floats, not recovery`() {
        val k = kinds(GoldenLaps.floatsShortWarmup)
        assertEquals(listOf(EASY, EASY), k.take(2))
        assertEquals((1..8).flatMap { listOf(REP, FLOAT) }, k.subList(2, 18))
        assertEquals(EASY, k.last())
        assertEquals(SessionType.INTERVAL, session(GoldenLaps.floatsShortWarmup))
    }

    @Test
    fun `pyramid - all five reps detected`() {
        val k = kinds(GoldenLaps.pyramid)
        assertEquals(listOf(EASY, REP, EASY, REP, EASY, REP, EASY, REP, EASY, REP, EASY), k)
        assertEquals(SessionType.INTERVAL, session(GoldenLaps.pyramid))
    }

    @Test
    fun `hill repeats - slow uphill reps are reps, warm-up is not`() {
        val k = kinds(GoldenLaps.hillRepeats)
        assertEquals(EASY, k.first())
        assertEquals((1..8).flatMap { listOf(REP, EASY) }, k.subList(1, 17))
        assertEquals(EASY, k.last())
    }

    @Test
    fun `time-based reps detected`() {
        val k = kinds(GoldenLaps.timeBased)
        assertEquals(6, k.count { it == REP })
        assertEquals(SessionType.INTERVAL, session(GoldenLaps.timeBased))
    }

    @Test
    fun `missing HR does not prevent interval detection`() {
        assertEquals(6, kinds(GoldenLaps.sixBy800NoHr).count { it == REP })
    }

    @Test
    fun `gps glitch lap without HR response is noise, run stays steady`() {
        assertEquals(listOf(EASY, EASY, NOISE, EASY, EASY, EASY), kinds(GoldenLaps.gpsGlitch))
        assertEquals(SessionType.STEADY, session(GoldenLaps.gpsGlitch))
    }

    @Test
    fun `accidental lap press is noise`() {
        assertEquals(listOf(EASY, EASY, NOISE, EASY, EASY), kinds(GoldenLaps.accidentalLap))
        assertEquals(SessionType.STEADY, session(GoldenLaps.accidentalLap))
    }

    @Test
    fun `race is a race, never an interval session`() {
        assertEquals(SessionType.RACE, session(GoldenLaps.race, workoutType = 1))
        assertTrue(kinds(GoldenLaps.race, workoutType = 1).none { it == REP })
        // A race with a fast last lap is still a race
        val kick = GoldenLaps.laps(*(1..9).map { GoldenLaps.p(1000.0, 250, 175) }.toTypedArray(), GoldenLaps.p(1000.0, 215, 185))
        assertEquals(SessionType.RACE, session(kick, workoutType = 1))
        assertTrue(kinds(kick, workoutType = 1).none { it == REP })
    }

    @Test
    fun `progression run is not intervals`() {
        val k = kinds(GoldenLaps.progression)
        assertTrue(k.none { it == REP }, "progression must not produce reps: $k")
        assertEquals(TEMPO, k.last())
        assertEquals(EASY, k.first())
        assertEquals(SessionType.TEMPO, session(GoldenLaps.progression))
    }

    @Test
    fun `single finishing kick is not an interval`() {
        val k = kinds(GoldenLaps.finishingKick)
        assertTrue(k.none { it == REP })
        assertEquals(TEMPO, k.last())
    }

    @Test
    fun `tempo with strides keeps the tempo block and sees strides as short reps`() {
        val k = kinds(GoldenLaps.tempoWithStrides)
        assertEquals(listOf(EASY, EASY, TEMPO, TEMPO, TEMPO, TEMPO, TEMPO), k.take(7))
        assertEquals(4, k.count { it == REP })
        assertEquals(SessionType.TEMPO, session(GoldenLaps.tempoWithStrides))
    }

    @Test
    fun `easy run with strides is a strides session, not intervals`() {
        assertEquals(SessionType.STRIDES, session(GoldenLaps.easyWithStrides))
    }

    @Test
    fun `single or no lap is steady`() {
        assertEquals(SessionType.STEADY, session(GoldenLaps.laps(L(10000.0, 3000))))
        assertEquals(SessionType.STEADY, session(emptyList()))
        assertEquals(SessionType.RACE, session(emptyList(), workoutType = 1))
    }

    @Test
    fun `KNOWN LIMITATION - hill reps with less than 15 percent speed contrast are not detected`() {
        // 5:25 up vs 6:00 down = speed ratio 1.108 < 1.15. Lap data has no per-lap elevation, so the app
        // cannot tell these are hard efforts. This test documents the limitation; it is not a goal.
        val gentle = GoldenLaps.laps(
            GoldenLaps.p(2000.0, 350, 138),
            *(1..8).flatMap { listOf(GoldenLaps.p(300.0, 325, 170), GoldenLaps.p(300.0, 360, 150)) }.toTypedArray(),
        )
        assertTrue(kinds(gentle).none { it == REP })
    }
}
