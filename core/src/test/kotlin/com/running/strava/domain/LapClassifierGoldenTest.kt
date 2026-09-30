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

    private val gentleHills = { hr: Boolean ->
        GoldenLaps.laps(
            GoldenLaps.p(2000.0, 350, if (hr) 138 else null),
            *(1..8).flatMap {
                listOf(GoldenLaps.p(300.0, 325, if (hr) 170 else null), GoldenLaps.p(300.0, 360, if (hr) 150 else null))
            }.toTypedArray(),
        )
    }

    @Test
    fun `hill reps with low speed contrast are detected when HR confirms the effort`() {
        // 5:25 up vs 6:00 down = speed ratio 1.108 < 1.15, but rep HR 170 vs 150/138 (+20 bpm).
        val k = kinds(gentleHills(true))
        assertEquals(EASY, k.first())
        assertEquals((1..8).flatMap { listOf(REP, EASY) }, k.drop(1))
        assertEquals(SessionType.INTERVAL, session(gentleHills(true)))
    }

    @Test
    fun `low-contrast hill reps without HR - only detected when marked as workout on Strava`() {
        assertTrue(kinds(gentleHills(false)).none { it == REP })
        assertEquals(8, kinds(gentleHills(false), workoutType = 3).count { it == REP })
    }

    @Test
    fun `rolling hills with lower HR on the fast laps are not intervals`() {
        // Auto-lap 1 km on rolling terrain: downhill km 5:40 (HR 140), uphill km 6:20 (HR 152). Speed ratio 1.118.
        val rolling = GoldenLaps.laps(*(1..10).map { if (it % 2 == 0) GoldenLaps.p(1000.0, 340, 140) else GoldenLaps.p(1000.0, 380, 152) }.toTypedArray())
        assertEquals(SessionType.STEADY, session(rolling))
        assertTrue(kinds(rolling).none { it == REP })
    }

    private val downhillLongRunNoHr = GoldenLaps.laps(
        *listOf(360, 355, 358, 300, 365, 370, 358, 300, 362, 366, 360, 355).map { GoldenLaps.p(1000.0, it, null) }.toTypedArray(),
    )

    @Test
    fun `strava long run without HR - downhill km are not intervals or tempo`() {
        val k = kinds(downhillLongRunNoHr, workoutType = 2)
        assertTrue(k.none { it == REP || it == TEMPO }, "$k")
        assertEquals(SessionType.STEADY, session(downhillLongRunNoHr, workoutType = 2))
    }

    @Test
    fun `strava long run with HR - fast km without HR response are not reps`() {
        val laps = GoldenLaps.laps(
            *listOf(360, 355, 358, 300, 365, 370, 358, 300, 362, 366, 360, 355).mapIndexed { i, pace ->
                GoldenLaps.p(1000.0, pace, if (pace == 300) 146 else 142 + i % 3)
            }.toTypedArray(),
        )
        assertTrue(kinds(laps, workoutType = 2).none { it == REP })
    }

    /** 2 km WU, 3x (2 km @ 4:30 auto-lapped into 2x 1 km, 400 m jog in 2:30), 1 km CD. */
    private val autoLappedCruise = GoldenLaps.laps(
        GoldenLaps.p(1000.0, 345, 135), GoldenLaps.p(1000.0, 345, 140),
        *(1..3).flatMap { listOf(GoldenLaps.p(1000.0, 270, 165), GoldenLaps.p(1000.0, 270, 170), L(400.0, 150, 150)) }.toTypedArray(),
        GoldenLaps.p(1000.0, 350, 150),
    )

    @Test
    fun `auto-lapped 3x 2 km is three 2000 m reps, not six 1000 m reps`() {
        val reps = LapClassifier.realReps(autoLappedCruise, null)
        assertEquals(3, reps.size)
        reps.forEach {
            assertEquals(2000.0, it.distance.toDouble(), 1e-6)
            assertEquals(540, it.movingTime)
            assertEquals(167.5, it.averageHeartrate!!.toDouble(), 1e-6) // (165·270 + 170·270) / 540
        }
        val text = WorkoutStructure.describe(autoLappedCruise)!!
        assertTrue(text.contains("3x 2000m @ 4:30"), text)
        assertTrue(text.contains("herstel 2m30s"), text)
        assertEquals(SessionType.INTERVAL, session(autoLappedCruise))
    }

    /** Standing rest lap: ~15 m, 5 s moving but [elapsed] s on the clock. */
    private fun rest() = GoldenLaps.L(15.0, 5, 150)

    private fun withRests(rows: List<GoldenLaps.L>, restElapsed: Map<Int, Int>): List<Lap> =
        GoldenLaps.laps(*rows.toTypedArray()).mapIndexed { i, l -> restElapsed[i]?.let { l.copy(elapsedTime = it) } ?: l }

    private fun standingRest400s(restElapsed: Int): List<Lap> {
        val rows = mutableListOf(GoldenLaps.p(2000.0, 345, 135))
        repeat(8) { rows += GoldenLaps.p(400.0, 200, 170); rows += rest() }
        rows += GoldenLaps.p(2000.0, 350, 140)
        return withRests(rows, rows.indices.filter { rows[it].d == 15.0 }.associateWith { restElapsed })
    }

    @Test
    fun `8x400 with standing rest laps - reps detected, each rest separates reps`() {
        val laps = standingRest400s(restElapsed = 90)
        val k = kinds(laps)
        assertEquals(8, k.count { it == REP }, "$k")
        assertEquals(8, LapClassifier.repEfforts(laps, k).size)
        assertEquals(8, LapClassifier.realReps(laps, null).size)
        assertTrue(LapClassifier.realReps(laps, null).all { it.distance == 400f })
        assertEquals(SessionType.INTERVAL, session(laps))
    }

    @Test
    fun `short stub lap inside a rep (double-press) does not split it`() {
        // 3x (1 km + 3 s stub + 1 km) with 400 m jog recoveries: 3 reps of 2 km.
        val rows = mutableListOf(GoldenLaps.p(2000.0, 345, 135))
        repeat(3) {
            rows += GoldenLaps.p(1000.0, 270, 168); rows += GoldenLaps.L(5.0, 3, 168); rows += GoldenLaps.p(1000.0, 270, 168)
            rows += GoldenLaps.p(400.0, 375, 150)
        }
        rows += GoldenLaps.p(1000.0, 350, 150)
        val laps = GoldenLaps.laps(*rows.toTypedArray())
        assertTrue(laps.filter { it.distance == 5f }.none { LapClassifier.isRestBreak(it) })
        val reps = LapClassifier.realReps(laps, null)
        assertEquals(3, reps.size)
        assertTrue(reps.all { it.distance == 2000f && it.movingTime == 540 })
    }

    @Test
    fun `first tempo km with HR lag is still tempo`() {
        // Real session: 2 km warm-up, 6 km tempo, stop, 2 km cool-down. HR in the first tempo km (147) is still rising
        // and below the easy-lap median inflated by the cool-down, but the lap belongs to the tempo block.
        val laps = GoldenLaps.laps(
            L(1000.0, 349, 119), L(1000.0, 338, 121),
            L(1000.0, 265, 147), L(1000.0, 268, 167), L(1000.0, 250, 178), L(1000.0, 253, 181), L(1000.0, 249, 187),
            L(1000.0, 247, 190),
            L(136.0, 90, 174), L(1000.0, 369, 153), L(1000.0, 352, 158), L(14.0, 7, 161),
        )
        assertEquals(
            listOf(EASY, EASY, TEMPO, TEMPO, TEMPO, TEMPO, TEMPO, TEMPO, EASY, EASY, EASY, NOISE),
            kinds(laps),
        )
        assertEquals(SessionType.TEMPO, session(laps))
    }

    @Test
    fun `HR-lag promotion is not transitive`() {
        // Fast stretch without HR response except one lap: only its direct neighbours are promoted.
        val laps = GoldenLaps.laps(
            GoldenLaps.p(1000.0, 360, 140), GoldenLaps.p(1000.0, 360, 140), GoldenLaps.p(1000.0, 360, 141),
            GoldenLaps.p(1000.0, 300, 141), GoldenLaps.p(1000.0, 300, 141), GoldenLaps.p(1000.0, 300, 160),
            GoldenLaps.p(1000.0, 300, 141), GoldenLaps.p(1000.0, 300, 141),
            GoldenLaps.p(1000.0, 360, 140), GoldenLaps.p(1000.0, 360, 140),
        )
        assertEquals(listOf(EASY, EASY, EASY, EASY, TEMPO, TEMPO, TEMPO, EASY, EASY, EASY), kinds(laps))
    }
}
