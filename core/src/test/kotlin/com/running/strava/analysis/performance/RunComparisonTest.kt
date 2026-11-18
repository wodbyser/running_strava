package com.running.strava.analysis.performance

import com.running.strava.analysis.TrainingAssessment.RatingLevel
import com.running.strava.analysis.performance.RunComparison.Caution
import com.running.strava.analysis.performance.RunComparison.Check
import com.running.strava.analysis.performance.RunComparison.CompareAspect
import com.running.strava.analysis.performance.RunComparison.Level
import com.running.strava.analysis.performance.RunComparison.Status
import com.running.strava.domain.SessionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunComparisonTest {

    private fun run(
        id: Long,
        distance: Double = 10_000.0,
        seconds: Int = 3000,
        hr: Double? = 150.0,
        type: SessionType = SessionType.STEADY,
        trainer: Boolean = false,
        terrain: TerrainClass = TerrainClass.FLAT,
        temp: Double? = 12.0,
        gap: Double? = null,
        ef: Double? = null,
        efHr: Double? = 150.0,
        decoupling: Double? = null,
        cv: Double? = null,
        interval: IntervalSummary? = null,
        execution: RatingLevel? = null,
    ) = ComparedRun(
        id, type, trainer, distance, seconds, hr, terrain, temp, gap, ef, if (ef != null) efHr else null, decoupling,
        if (decoupling != null) 2400.0 else null, cv, interval, execution,
    )

    private fun reps(signature: String?, speed: Double, cv: Double? = 0.02, fade: Double? = 2.0, hrr: Double? = null, protocol: RecoveryProtocol? = null) =
        IntervalSummary(signature, 6, speed, cv, fade, hrr, protocol)

    @Test
    fun `the fastest run wins speed only when the gap is larger than the GPS noise`() {
        val clear = RunComparison.compare(listOf(run(1, seconds = 3000), run(2, seconds = 2900)))
        val speed = clear.aspect(CompareAspect.SPEED)
        assertEquals(Status.WINNER, speed.status)
        assertEquals(setOf(2L), speed.winners)

        // 3000 s against 2985 s is 0.5 % faster: inside the 1 % noise band, so no winner.
        val tie = RunComparison.compare(listOf(run(1, seconds = 3000), run(2, seconds = 2985))).aspect(CompareAspect.SPEED)
        assertEquals(Status.NO_DIFFERENCE, tie.status)
        assertTrue(tie.winners.isEmpty())
    }

    @Test
    fun `two runs within the noise of the best share the first place`() {
        val r = RunComparison.compare(listOf(run(1, seconds = 3000), run(2, seconds = 2990), run(3, seconds = 3300)))
        val speed = r.aspect(CompareAspect.SPEED)
        assertEquals(Status.SHARED, speed.status)
        assertEquals(setOf(1L, 2L), speed.winners)
        assertEquals(0, r.wins[1L])
        // Speed and whole-run efficiency (same heart rate) are both shared.
        assertEquals(2, r.shared[1L])
    }

    @Test
    fun `lower is better for decoupling and evenness`() {
        val r = RunComparison.compare(listOf(run(1, decoupling = 6.0, cv = 0.08), run(2, decoupling = 2.0, cv = 0.03)))
        assertEquals(setOf(2L), r.aspect(CompareAspect.DECOUPLING).winners)
        assertEquals(setOf(2L), r.aspect(CompareAspect.EVENNESS).winners)
    }

    @Test
    fun `a negative decoupling or a faster last rep counts as no loss, not as better`() {
        val r = RunComparison.compare(listOf(run(1, decoupling = -5.2), run(2, decoupling = -0.1), run(3, decoupling = 2.9)))
        val d = r.aspect(CompareAspect.DECOUPLING)
        assertEquals(Status.SHARED, d.status)
        assertEquals(setOf(1L, 2L), d.winners)

        val f = RunComparison.compare(
            listOf(
                run(1, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.6, fade = -3.0)),
                run(2, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.6, fade = 0.5)),
            ),
        ).aspect(CompareAspect.REP_FADE)
        assertEquals(Status.NO_DIFFERENCE, f.status)
    }

    @Test
    fun `shared first places count when naming the run that was most often among the best`() {
        // 1 wins decoupling alone; 2 shares speed and efficiency with 3 and wins execution alone.
        val r = RunComparison.compare(
            listOf(
                run(1, seconds = 3300, decoupling = 1.0, execution = RatingLevel.MIXED),
                run(2, seconds = 3000, decoupling = 4.0, execution = RatingLevel.SOUND),
                run(3, seconds = 2990, decoupling = 4.0, execution = RatingLevel.MIXED),
            ),
        )
        assertEquals(1, r.wins[1L])
        assertEquals(3, r.firsts(2L))
        assertEquals(2L, r.leader)
    }

    @Test
    fun `steady efficiency is used when two runs have it, otherwise the whole run with a caution`() {
        val steady = RunComparison.compare(listOf(run(1, ef = 0.022), run(2, ef = 0.020), run(3))).aspect(CompareAspect.EFFICIENCY)
        assertFalse(steady.wholeRun)
        assertEquals(setOf(1L, 2L), steady.values.keys)
        assertEquals(setOf(1L), steady.winners)

        val whole = RunComparison.compare(listOf(run(1, ef = 0.022), run(2, hr = 160.0))).aspect(CompareAspect.EFFICIENCY)
        assertTrue(whole.wholeRun)
        assertTrue(Caution.WHOLE_RUN in whole.cautions)
        assertTrue(Caution.HR_LEVEL in whole.cautions) // 150 against 160 bpm
        assertEquals(setOf(1L), whole.winners) // same speed at a lower heart rate
    }

    @Test
    fun `steady efficiency at clearly different heart rates carries a caution`() {
        val a = RunComparison.compare(listOf(run(1, ef = 0.022, efHr = 140.0), run(2, ef = 0.020, efHr = 155.0))).aspect(CompareAspect.EFFICIENCY)
        assertTrue(Caution.HR_LEVEL in a.cautions)
    }

    @Test
    fun `rep speed over different rep structures has no winner`() {
        val r = RunComparison.compare(
            listOf(
                run(1, type = SessionType.INTERVAL, interval = reps("6 x 400 m", 5.0)),
                run(2, type = SessionType.INTERVAL, interval = reps("5 x 1000 m", 4.5)),
            ),
        )
        val repSpeed = r.aspect(CompareAspect.REP_SPEED)
        assertEquals(Status.NOT_COMPARABLE, repSpeed.status)
        assertTrue(repSpeed.winners.isEmpty())
        assertTrue(Caution.REP_STRUCTURE in r.aspect(CompareAspect.REP_EVENNESS).cautions)
    }

    @Test
    fun `rep speed with the same structure has a winner`() {
        val r = RunComparison.compare(
            listOf(
                run(1, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.6)),
                run(2, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.4)),
            ),
        )
        assertEquals(setOf(1L), r.aspect(CompareAspect.REP_SPEED).winners)
    }

    @Test
    fun `heart rate recovery is never compared across recovery protocols`() {
        val r = RunComparison.compare(
            listOf(
                run(1, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.6, hrr = 30.0, protocol = RecoveryProtocol.STANDING)),
                run(2, type = SessionType.INTERVAL, interval = reps("6 x 1000 m", 4.6, hrr = 20.0, protocol = RecoveryProtocol.JOGGING)),
            ),
        )
        assertEquals(Status.NOT_COMPARABLE, r.aspect(CompareAspect.HRR).status)
    }

    @Test
    fun `evenness is not judged for interval sessions`() {
        val r = RunComparison.compare(listOf(run(1, type = SessionType.INTERVAL, cv = 0.2), run(2, cv = 0.03)))
        assertEquals(Status.TOO_FEW, r.aspect(CompareAspect.EVENNESS).status)
    }

    @Test
    fun `comparability flags type, surface, distance and temperature`() {
        val good = RunComparison.compare(listOf(run(1), run(2, distance = 11_000.0))).comparability
        assertEquals(Level.GOOD, good.level)

        val partly = RunComparison.comparability(listOf(run(1), run(2, distance = 15_000.0), run(3, temp = 25.0)))
        assertEquals(false, partly.checks[Check.SIMILAR_DISTANCE])
        assertEquals(false, partly.checks[Check.SIMILAR_TEMPERATURE])
        assertEquals(Level.PARTLY, partly.level)

        val hard = RunComparison.comparability(listOf(run(1), run(2, trainer = true)))
        assertEquals(Level.HARD, hard.level)
        assertNull(RunComparison.comparability(listOf(run(1, temp = null), run(2, temp = null))).checks[Check.SIMILAR_TEMPERATURE])
    }

    @Test
    fun `speed between different kinds of training carries a caution but keeps its winner`() {
        val speed = RunComparison.compare(listOf(run(1, type = SessionType.INTERVAL, seconds = 2700), run(2))).aspect(CompareAspect.SPEED)
        assertEquals(setOf(1L), speed.winners)
        assertTrue(Caution.SESSION_TYPE in speed.cautions)
    }

    @Test
    fun `the leader is the run with strictly the most outright wins and GAP does not count twice`() {
        val r = RunComparison.compare(
            listOf(
                run(1, seconds = 2800, gap = 3.6, decoupling = 2.0, execution = RatingLevel.SOUND),
                run(2, seconds = 3000, gap = 3.3, decoupling = 6.0, execution = RatingLevel.MIXED),
            ),
        )
        // Speed, whole-run efficiency (faster at the same heart rate), decoupling and execution; GAP is shown but not counted.
        assertEquals(4, r.wins[1L])
        assertEquals(1L, r.leader)
        assertEquals(4, r.compared)

        val even = RunComparison.compare(
            listOf(
                run(1, seconds = 2800, decoupling = 6.0, execution = RatingLevel.OFF),
                run(2, seconds = 3000, decoupling = 2.0, execution = RatingLevel.SOUND),
            ),
        )
        assertEquals(2, even.wins[1L])
        assertEquals(2, even.wins[2L])
        assertNull(even.leader)
    }

    @Test
    fun `an aspect with one value is too few and gives no win`() {
        val r = RunComparison.compare(listOf(run(1, decoupling = 2.0), run(2)))
        assertEquals(Status.TOO_FEW, r.aspect(CompareAspect.DECOUPLING).status)
        assertEquals(Status.NO_DIFFERENCE, r.aspect(CompareAspect.SPEED).status)
        assertEquals(0, r.wins.values.sum())
        assertNull(r.leader)
    }

    @Test
    fun `curves give pace and heart rate per block and skip pauses`() {
        // 2 km at 4 m/s, a 5-minute stop, then 1 km at 3 m/s.
        val rows = (1..500).map { Synth.Row(1, 4.0, 150) } + listOf(Synth.Row(300, 0.0, 120)) + (1..333).map { Synth.Row(1, 3.0, 160) }
        val points = RunCurves.byDistance(Synth.fromRows(rows), 500.0)
        assertEquals(6, points.size)
        assertEquals(250.0, points[0].paceSecondsPerKm!!, 0.5)
        assertEquals(150.0, points[0].hr!!, 1.0)
        assertEquals(333.3, points[5].paceSecondsPerKm!!, 1.0)
        assertEquals(160.0, points[5].hr!!, 1.0)
        assertEquals(0.5, points[0].km, 1e-9)
    }
}
