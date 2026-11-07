package com.running.race

import com.running.strava.analysis.performance.CriticalSpeed
import com.running.strava.domain.SessionType
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object R {
    val D0: LocalDate = LocalDate.of(2026, 3, 2)

    fun run(
        id: Long, date: LocalDate, meters: Double, seconds: Int, hr: Double? = null, workoutType: Int? = 0, elev: Double = 3.0,
        type: SessionType = SessionType.STEADY, efforts: Map<Double, Double> = emptyMap(), trainer: Boolean = false,
        halves: Halves? = null, startHour: Int = 9, elapsed: Int = seconds, name: String = "run $id",
    ) = RaceRun(
        id, date, date.atTime(startHour, 0).toEpochSecond(ZoneOffset.UTC), name, meters, seconds, elapsed, hr, workoutType, trainer, elev, type, efforts, halves,
    )

    fun race(id: Long, date: LocalDate, meters: Double, seconds: Int, hr: Double? = null, elev: Double = 3.0, halves: Halves? = null) =
        run(id, date, meters, seconds, hr, 1, elev, SessionType.RACE, halves = halves)
}

class PredictionTest {
    private val asOf = R.D0.plusDays(100)

    private fun anchors(vararg runs: RaceRun) = Anchors.collect(runs.toList(), asOf, 180, 190)

    @Test
    fun `Riegel by hand, 5 km in 20 00 and 10 km in 42 00 give 41 45 for 10 km`() {
        val a = anchors(R.race(1, R.D0, 5000.0, 1200), R.race(2, R.D0.plusDays(7), 10000.0, 2520))
        val p = Prediction.predict(10000.0, a)!!
        val first = 1200 * Math.pow(2.0, 1.06)
        assertEquals((first + 2520) / 2, p.rawSeconds, 1e-6)
        assertEquals(2505.0, p.seconds)
        assertEquals(2505.0, p.lowSeconds)
        assertEquals(2520.0, p.highSeconds)
        assertEquals(2, p.anchors.size)
    }

    @Test
    fun `no prediction from one anchor`() {
        assertNull(Prediction.predict(10000.0, anchors(R.race(1, R.D0, 5000.0, 1200))))
        assertNull(Prediction.predict(10000.0, emptyList()))
    }

    @Test
    fun `median of three anchors is the middle one and of four the mean of the middle two`() {
        val three = anchors(R.race(1, R.D0, 10000.0, 2400), R.race(2, R.D0.plusDays(1), 10000.0, 2500), R.race(3, R.D0.plusDays(2), 10000.0, 2700))
        assertEquals(2500.0, Prediction.predict(10000.0, three)!!.rawSeconds, 1e-9)
        val four = anchors(R.race(1, R.D0, 10000.0, 2400), R.race(2, R.D0.plusDays(1), 10000.0, 2500), R.race(3, R.D0.plusDays(2), 10000.0, 2700), R.race(4, R.D0.plusDays(3), 10000.0, 2800))
        assertEquals(2600.0, Prediction.predict(10000.0, four)!!.rawSeconds, 1e-9)
    }

    @Test
    fun `anchors at ratio 0 39 and 2 51 are excluded, 0 4 and 2 5 are kept`() {
        val a = anchors(R.race(1, R.D0, 10000.0, 2500), R.race(2, R.D0.plusDays(7), 10000.0, 2600))
        assertNull(Prediction.predict(3900.0, a))
        assertNotNull(Prediction.predict(4000.0, a))
        assertNotNull(Prediction.predict(25000.0, a))
        assertNull(Prediction.predict(25100.0, a))
    }

    @Test
    fun `paces increase monotonically with distance for several anchor sets`() {
        val sets = listOf(
            listOf(R.race(1, R.D0, 5000.0, 1200), R.race(2, R.D0.plusDays(5), 10000.0, 2520), R.race(3, R.D0.plusDays(10), 21097.0, 5500)),
            listOf(R.race(1, R.D0, 10000.0, 2700), R.race(2, R.D0.plusDays(5), 21097.0, 5900), R.race(3, R.D0.plusDays(9), 5000.0, 1300)),
            listOf(
                R.run(1, R.D0, 9000.0, 3000, efforts = mapOf(5000.0 to 1500.0, 10000.0 to 3100.0)),
                R.run(2, R.D0.plusDays(3), 21000.0, 7000, efforts = mapOf(5000.0 to 1480.0, 10000.0 to 3050.0, 21097.5 to 6900.0)),
                R.race(3, R.D0.plusDays(8), 10000.0, 3000),
            ),
        )
        sets.forEach { runs ->
            val a = anchors(*runs.toTypedArray())
            val predictions = Prediction.STANDARD.mapNotNull { Prediction.predict(it, a) }
            assertTrue(predictions.size >= 3, "expected predictions for most distances")
            assertTrue(Prediction.paceViolations(predictions).isEmpty(), predictions.map { it.secondsPerKm }.toString())
            predictions.sortedBy { it.targetMeters }.zipWithNext().forEach { (x, y) -> assertTrue(y.secondsPerKm >= x.secondsPerKm) }
        }
    }

    @Test
    fun `interval and tempo sessions never become hard run anchors, a steady hard run does`() {
        val hr = 175.0
        val runs = listOf(
            R.run(1, R.D0, 8000.0, 2400, hr = hr, type = SessionType.INTERVAL),
            R.run(2, R.D0.plusDays(2), 8000.0, 2400, hr = hr, type = SessionType.TEMPO),
            R.run(3, R.D0.plusDays(4), 8000.0, 2400, hr = hr, type = SessionType.STEADY),
            R.run(4, R.D0.plusDays(6), 8000.0, 2400, hr = 120.0, type = SessionType.STEADY),
        )
        val a = Anchors.collect(runs, asOf, 180, 190)
        assertEquals(listOf(3L), a.filter { it.kind == AnchorKind.HARD_RUN }.map { it.activityId })
    }

    @Test
    fun `a marked road race at a low average heart rate is still an anchor and a trail race is not`() {
        val runs = listOf(R.race(1, R.D0, 9800.0, 2900, hr = 154.0), R.race(2, R.D0.plusDays(3), 10000.0, 3300, elev = 22.0))
        val a = Anchors.collect(runs, asOf, 180, 197)
        assertEquals(listOf(1L), a.map { it.activityId })
        assertEquals(AnchorKind.RACE, a.single().kind)
    }

    @Test
    fun `the window is 180 days, day 180 is in and day 181 is out, the evaluated day itself is out`() {
        val day = R.D0.plusDays(300)
        val runs = listOf(
            R.race(1, day.minusDays(180), 5000.0, 1200),
            R.race(2, day.minusDays(181), 5000.0, 1210),
            R.race(3, day, 5000.0, 1190),
        )
        assertEquals(listOf(1L), Anchors.collect(runs, day, 180, null).map { it.activityId })
        assertEquals(setOf(1L, 2L), Anchors.collect(runs, day, 181, null).map { it.activityId }.toSet())
    }

    @Test
    fun `best efforts under 3 5 minutes are not anchors and treadmill runs are skipped`() {
        val fast = R.run(1, R.D0, 6000.0, 1800, efforts = mapOf(1000.0 to 200.0))
        val tread = R.run(2, R.D0.plusDays(1), 6000.0, 1800, efforts = mapOf(5000.0 to 1300.0), trainer = true)
        val ok = R.run(3, R.D0.plusDays(2), 6000.0, 1800, efforts = mapOf(5000.0 to 1350.0))
        val a = Anchors.collect(listOf(fast, tread, ok), asOf, 180, null)
        assertEquals(listOf(3L), a.map { it.activityId })
    }

    @Test
    fun `the marathon prediction carries the fixed warning`() {
        val a = anchors(R.race(1, R.D0, 21097.0, 5400), R.race(2, R.D0.plusDays(20), 21000.0, 5500))
        val p = Prediction.predict(42195.0, a)!!
        assertTrue(p.marathonWarning)
        assertTrue(Prediction.MARATHON_WARNING.contains("marathon"))
        assertFalse(Prediction.predict(21097.5, a)!!.marathonWarning)
    }

    @Test
    fun `critical speed method only for 5 km and shorter, within 3 to 30 minutes and only when the fit passed`() {
        val fit = CriticalSpeed.Fit(4.0, 100.0, 0.99, emptyList(), 3.9 to 4.1)
        val ok = CriticalSpeed.Outcome.Ok(fit)
        assertEquals(1225.0, Prediction.criticalSpeedSeconds(5000.0, ok)!!, 1e-9)
        assertNull(Prediction.criticalSpeedSeconds(5001.0, ok))
        assertNull(Prediction.criticalSpeedSeconds(10000.0, ok))
        assertNull(Prediction.criticalSpeedSeconds(600.0, ok))
        assertNotNull(Prediction.criticalSpeedSeconds(1000.0, ok))
        assertNull(Prediction.criticalSpeedSeconds(5000.0, CriticalSpeed.Outcome.Abstain("test", emptyList())))
        assertNull(Prediction.criticalSpeedSeconds(5000.0, null))
    }

    @Test
    fun `the weekly series uses only anchors dated before each Monday`() {
        val runs = listOf(R.race(1, R.D0.plusDays(1), 5000.0, 1200), R.race(2, R.D0.plusDays(2), 10000.0, 2520))
        val w = Prediction.weekly(runs, R.D0, R.D0.plusWeeks(2), listOf(10000.0), 180, null)
        assertNull(w[0].seconds[10000.0])
        assertNotNull(w[1].seconds[10000.0])
        assertEquals(3, w.size)
    }
}

class WalkForwardTest {
    private fun base() = listOf(
        R.race(1, R.D0, 5000.0, 1200),
        R.race(2, R.D0.plusDays(20), 10000.0, 2520),
        R.race(3, R.D0.plusDays(40), 5000.0, 1190),
        R.race(4, R.D0.plusDays(60), 10000.0, 2480),
    )

    @Test
    fun `a race never enters its own prediction and the first race has none`() {
        val o = Accuracy.outcomes(base(), 180, null)
        assertEquals(4, o.size)
        assertNull(o[0].predictedSeconds)
        assertNull(o[1].predictedSeconds)
        assertNotNull(o[2].predictedSeconds)
        val expected = Prediction.predict(5000.0, Anchors.collect(base(), R.D0.plusDays(40), 180, null))!!.rawSeconds
        assertEquals(expected, o[2].predictedSeconds!!, 1e-9)
        assertEquals((1190 - expected) / expected * 100, o[2].errorPct!!, 1e-9)
    }

    @Test
    fun `adding a later activity changes no earlier prediction`() {
        val before = Accuracy.outcomes(base(), 180, null)
        val later = base() + R.race(9, R.D0.plusDays(90), 5000.0, 900) + R.run(10, R.D0.plusDays(95), 6000.0, 1500, efforts = mapOf(5000.0 to 1000.0))
        val after = Accuracy.outcomes(later, 180, null)
        before.forEachIndexed { i, o ->
            assertEquals(o.predictedSeconds, after[i].predictedSeconds)
            assertEquals(o.errorPct, after[i].errorPct)
        }
        assertEquals(5, after.size)
    }

    @Test
    fun `the baseline is the last earlier race converted with Riegel`() {
        val o = Accuracy.outcomes(base(), 180, null)
        val b = com.running.strava.analysis.RacePredictor.riegel(2520.0, 10000.0, 5000.0)
        assertEquals(b, o[2].baselineSeconds!!, 1e-9)
        assertEquals((1190 - b) / b * 100, o[2].baselineErrorPct!!, 1e-9)
    }

    @Test
    fun `trail races are listed without a prediction`() {
        val o = Accuracy.outcomes(base() + R.race(7, R.D0.plusDays(70), 21000.0, 7000, elev = 30.0), 180, null)
        val trail = o.single { it.trail }
        assertNull(trail.predictedSeconds)
        assertNull(trail.errorPct)
    }

    private fun outcome(err: Double?, base: Double? = null): RaceOutcome =
        RaceOutcome(R.race(1, R.D0, 5000.0, 1200), false, 1000.0, 2, err, 1000.0, base)

    @Test
    fun `four races give no own range, five give the trimmed 10 to 90 percentile range`() {
        val four = Accuracy.summary(listOf(-2.0, -1.0, 0.0, 3.0).map { outcome(it) })
        assertFalse(four.enough)
        assertNull(Accuracy.range(1000.0, four))
        val five = Accuracy.summary(listOf(-2.0, -1.0, 0.0, 3.0, 5.0).map { outcome(it) })
        assertTrue(five.enough)
        assertEquals(0.0, five.medianErrorPct)
        assertEquals(-2.0, five.minErrorPct)
        assertEquals(5.0, five.maxErrorPct)
        // type 7 percentiles: position 0.4 and 3.6 of the sorted errors
        assertEquals(-1.6, five.rangeLowPct!!, 1e-9)
        assertEquals(4.2, five.rangeHighPct!!, 1e-9)
        val (lo, hi) = Accuracy.range(1000.0, five)!!
        assertEquals(984.0, lo, 1e-9)
        assertEquals(1042.0, hi, 1e-9)
    }

    @Test
    fun `one outlier no longer sets the edge of the range, on the owner's eleven race-v2 errors`() {
        val errors = listOf(-18.4, -2.5, -2.1, 1.5, -10.1, -0.4, -7.8, -1.1, -3.2, -10.6, -9.4)
        val s = Accuracy.summary(errors.map { outcome(it) })
        assertEquals(-10.6, s.rangeLowPct!!, 1e-9)
        assertEquals(-0.4, s.rangeHighPct!!, 1e-9)
        val (lo, hi) = Accuracy.range(1160.0, s)!!
        assertEquals(1160.0 * 0.894, lo, 1e-9)
        assertEquals(1160.0, hi, 1e-9)
    }

    @Test
    fun `the range always contains the prediction and trim 0 gives the old min to max range`() {
        val allFast = Accuracy.summary(listOf(-9.0, -8.0, -7.0, -6.0, -5.0).map { outcome(it) })
        val (lo, hi) = Accuracy.range(1000.0, allFast)!!
        assertTrue(lo < 1000.0)
        assertEquals(1000.0, hi, 1e-9)
        val untrimmed = Accuracy.summary(listOf(-2.0, -1.0, 0.0, 3.0, 5.0).map { outcome(it) }, RaceConfig(rangeTrimPct = 0.0))
        assertEquals(980.0 to 1050.0, Accuracy.range(1000.0, untrimmed))
    }

    @Test
    fun `mean absolute errors of the main method and the baseline are compared on the same races`() {
        val s = Accuracy.summary(listOf(outcome(1.0, 4.0), outcome(-3.0, 2.0), outcome(2.0, null)))
        assertEquals(2, s.nCompared)
        assertEquals(2.0, s.mainMaePct!!, 1e-9)
        assertEquals(3.0, s.baselineMaePct!!, 1e-9)
        assertEquals("main", s.closer)
    }
}

class PlanTest {
    @Test
    fun `pace range follows from the time range`() {
        val (a, b) = PacePlan.paceRange(1160.0, 1200.0, 5000.0)
        assertEquals(232.0, a, 1e-9)
        assertEquals(240.0, b, 1e-9)
    }

    @Test
    fun `even split table sums to the target`() {
        val s = PacePlan.even(1200.0, 5000.0)
        assertEquals(5, s.size)
        assertEquals(1200.0, s.last().cumulativeSeconds, 1e-9)
        assertEquals(1200.0, s.sumOf { it.seconds }, 1e-9)
        s.forEach { assertEquals(240.0, it.secondsPerKm, 1e-9) }
        assertEquals("0–1 km", s.first().label)
    }

    @Test
    fun `half marathon splits are per 5 km with a short last part`() {
        val s = PacePlan.even(5400.0, 21097.5)
        assertEquals(5, s.size)
        assertEquals("20–21,1 km", s.last().label)
        assertEquals(5400.0, s.last().cumulativeSeconds, 1e-9)
    }

    @Test
    fun `calm start keeps the total and is 2 percent slower over the first km of a 5 km race`() {
        val s = PacePlan.calmStart(1200.0, 5000.0, 2.0)
        assertEquals(1200.0, s.sumOf { it.seconds }, 1e-9)
        assertEquals(244.8, s[0].seconds, 1e-9)
        assertEquals(238.8, s[1].seconds, 1e-9)
        assertEquals(238.8, s[4].seconds, 1e-9)
    }

    @Test
    fun `calm start over longer races covers the first 3 km`() {
        val s = PacePlan.calmStart(3000.0, 10000.0, 2.0)
        val even = 300.0
        assertEquals(even * 1.02, s[0].secondsPerKm, 1e-9)
        assertEquals(even * 1.02, s[2].secondsPerKm, 1e-9)
        assertTrue(s[3].secondsPerKm < even)
        assertEquals(3000.0, s.last().cumulativeSeconds, 1e-9)
    }

    private fun stream(firstHalfSpeed: Double, secondHalfSpeed: Double, meters: Double = 4000.0): Pair<List<Int>, List<Float>> {
        val time = ArrayList<Int>()
        val dist = ArrayList<Float>()
        var t = 0
        var d = 0.0
        while (d < meters) {
            time += t; dist += d.toFloat()
            d += (if (d < meters / 2) firstHalfSpeed else secondHalfSpeed) * 10
            t += 10
        }
        time += t; dist += meters.toFloat()
        return time to dist
    }

    @Test
    fun `halves from a synthetic stream`() {
        val (t, d) = stream(4.0, 3.2)
        val h = Pacing.halves(t, d)!!
        assertEquals(500.0, h.firstSeconds, 6.0)
        assertEquals(625.0, h.secondSeconds, 12.0)
        assertTrue(h.splitPct > 20 && h.splitPct < 30)
        assertNull(Pacing.halves(listOf(0, 10), listOf(0f, 100f)))
        assertNull(Pacing.halves(null, null))
    }

    @Test
    fun `pacing summary needs 3 races`() {
        assertFalse(Pacing.summary(listOf(Halves(100.0, 102.0), Halves(100.0, 99.0))).enough)
        val s = Pacing.summary(listOf(Halves(100.0, 102.0), Halves(100.0, 99.0), Halves(100.0, 104.0)))
        assertTrue(s.enough)
        assertEquals(2, s.slowerSecondHalf)
        assertEquals(2.0, s.medianSplitPct!!, 1e-9)
    }

    @Test
    fun `specificity counts only stretches of 3 5 minutes or more at goal pace or faster`() {
        // 600 s at 4 m/s (goal 3.9 m/s), then 120 s at 4 m/s, a slow part, then 300 s at 4 m/s
        val time = ArrayList<Int>()
        val dist = ArrayList<Float>()
        var t = 0
        var d = 0.0
        fun seg(seconds: Int, speed: Double) { repeat(seconds) { time += t; dist += d.toFloat(); t += 1; d += speed } }
        seg(600, 4.0); seg(120, 2.0); seg(120, 4.0); seg(120, 2.0); seg(300, 4.0)
        time += t; dist += d.toFloat()
        val s = Specificity.secondsAtOrFaster(time, dist, 3.9, 0.0)
        assertTrue(s in 800.0..930.0, s.toString())
        assertEquals(0.0, Specificity.secondsAtOrFaster(time, dist, 6.0, 0.0))
        assertEquals(0.0, Specificity.secondsAtOrFaster(null, null, 4.0, 3.0))
    }
}

class FeasibilityTest {
    private fun eval(goal: Double, weeks: Int = 5, series: List<Double?> = emptyList()) =
        GoalFeasibility.evaluate(goal, 5000.0, 1200.0, 1180.0, 1230.0, weeks, series)

    @Test
    fun `the three bands at their boundaries`() {
        assertEquals(GoalBand.INSIDE, eval(1180.0).band)
        assertEquals(GoalBand.INSIDE, eval(1230.0).band)
        assertEquals(GoalBand.FASTER, eval(1179.0).band)
        assertEquals(GoalBand.SLOWER, eval(1231.0).band)
        val faster = eval(1121.0)
        assertEquals((1121.0 / 1180.0 - 1) * 100, faster.gapPct!!, 1e-9)
        assertEquals((1121.0 - 1180.0) / 5.0, faster.gapSecondsPerKm!!, 1e-9)
    }

    @Test
    fun `needed change against todays prediction`() {
        assertEquals(-5.0, eval(1140.0).needPct, 1e-9)
        assertEquals(5.0, eval(1260.0).needPct, 1e-9)
    }

    @Test
    fun `history share counted by hand on a synthetic weekly series`() {
        // 30 weeks falling 0.5 % per week: any 5 week window improves 2.48 %, windows needing at most that qualify
        val series: List<Double?> = (0 until 30).map { 1300.0 * Math.pow(0.995, it.toDouble()) }
        val f = GoalFeasibility.evaluate(1170.0, 5000.0, 1200.0, 1180.0, 1230.0, 5, series)
        assertEquals(-2.5, f.needPct, 1e-9)
        assertEquals(25, f.historyWindows)
        // improvement of 5 weeks = 0.995^5 - 1 = -2.4876 %, which is slower than -2.5 %: none qualify
        assertEquals(0, f.historyQualifying)
        val f2 = GoalFeasibility.evaluate(1180.0, 5000.0, 1200.0, 1180.0, 1230.0, 5, series)
        assertEquals(25, f2.historyQualifying)
        assertNull(f2.historyNote)
    }

    @Test
    fun `abstains under 26 weeks of history`() {
        val f = eval(1140.0, series = List<Double?>(25) { 1200.0 })
        assertNull(f.historyWindows)
        assertNotNull(f.historyNote)
        assertTrue(f.historyNote!!.contains("minstens 26"))
    }

    @Test
    fun `no history sentence for a goal slower than the prediction`() {
        assertEquals("Je doel is niet sneller dan je huidige voorspelling.", eval(1260.0).historyNote)
    }

    @Test
    fun `the texts contain no verdict word`() {
        val words = listOf("haalbaar", "veilig", "klaar", "optimaal")
        GoalBand.entries.forEach { b -> words.forEach { assertFalse(b.text.contains(it)) } }
    }
}

class ReadinessTest {
    private fun input(
        now: Double? = 1200.0, before: Double? = 1210.0, better: Int = 2, worse: Int = 0, verdicts: Int = 4, specific: Double? = 1500.0,
        dist: Double? = 5000.0, longest: Double? = null, since: Int? = 20,
    ) = FitnessInput(now, before, 8, better, worse, verdicts, specific, 6, dist, longest, since)

    private fun stance(b: ReadinessBlock, key: String) = b.parts.first { it.key == key }.stance

    @Test
    fun `each component flips its stance alone`() {
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input()), "trend"))
        assertEquals(RStance.AGAINST, stance(RaceReadiness.fitness(input(now = 1230.0)), "trend"))
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input(now = 1222.0)), "trend"))
        assertEquals(RStance.UNKNOWN, stance(RaceReadiness.fitness(input(now = null)), "trend"))
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input()), "condition"))
        assertEquals(RStance.AGAINST, stance(RaceReadiness.fitness(input(better = 0, worse = 2)), "condition"))
        assertEquals(RStance.NEUTRAL, stance(RaceReadiness.fitness(input(better = 1, worse = 1)), "condition"))
        assertEquals(RStance.UNKNOWN, stance(RaceReadiness.fitness(input(verdicts = 0)), "condition"))
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input(specific = 1200.0)), "specific"))
        assertEquals(RStance.AGAINST, stance(RaceReadiness.fitness(input(specific = 1199.0)), "specific"))
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input(since = 60)), "recent"))
        assertEquals(RStance.AGAINST, stance(RaceReadiness.fitness(input(since = 61)), "recent"))
        assertEquals(RStance.AGAINST, stance(RaceReadiness.fitness(input(since = null)), "recent"))
    }

    @Test
    fun `the longest run counts from the half marathon on only`() {
        assertTrue(RaceReadiness.fitness(input(dist = 5000.0)).parts.none { it.key == "longest" })
        val half = RaceReadiness.fitness(input(dist = 21097.5, longest = 15000.0))
        assertEquals(RStance.AGAINST, stance(half, "longest"))
        assertEquals(RStance.FOR, stance(RaceReadiness.fitness(input(dist = 21097.5, longest = 15900.0)), "longest"))
    }

    @Test
    fun `levels follow the count of counter signals and fewer than two components give insufficient`() {
        assertEquals(com.running.load.ReadinessLevel.NONE, RaceReadiness.fitness(input()).level)
        assertEquals(com.running.load.ReadinessLevel.ONE, RaceReadiness.fitness(input(now = 1250.0)).level)
        assertEquals(com.running.load.ReadinessLevel.MANY, RaceReadiness.fitness(input(now = 1250.0, since = null)).level)
        val empty = RaceReadiness.fitness(input(now = null, verdicts = 0, specific = null, since = 10).copy(), hasGoal = false)
        assertEquals(com.running.load.ReadinessLevel.INSUFFICIENT, empty.level)
    }

    @Test
    fun `without a goal only the goal independent components appear`() {
        val b = RaceReadiness.fitness(input(), hasGoal = false)
        assertEquals(listOf("trend", "condition", "recent"), b.parts.map { it.key })
    }

    @Test
    fun `no text contains a verdict word`() {
        val b = RaceReadiness.fitness(input(dist = 42195.0, longest = 20000.0))
        val text = b.parts.joinToString(" ") { it.title + " " + it.detail + " " + it.stance.text }.lowercase()
        listOf("klaar", "veilig", "je loopt", "optimaal").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun `the race week check is visible on day 7 and 0 and hidden on day 8 and after the race`() {
        val goal = LocalDate.of(2026, 12, 5)
        assertTrue(RaceWeek.visible(goal, goal.minusDays(7)))
        assertTrue(RaceWeek.visible(goal, goal))
        assertFalse(RaceWeek.visible(goal, goal.minusDays(8)))
        assertFalse(RaceWeek.visible(goal, goal.plusDays(1)))
        assertEquals(3, RaceWeek.daysToGo(goal, goal.minusDays(3)))
    }

    @Test
    fun `load of the last 7 days against the average week of 28 days`() {
        val today = LocalDate.of(2026, 12, 3)
        val daily = (1..28).associate { today.minusDays(it.toLong()) to 100.0 } + (1..7).associate { today.minusDays(it.toLong()) to 50.0 }
        // 28 days: 7 x 50 + 21 x 100 = 2450, week average 612.5; last 7 = 350
        assertEquals(350.0 / 612.5 * 100, RaceWeek.loadPct(daily, today)!!, 1e-9)
        assertNull(RaceWeek.loadPct(emptyMap(), today))
    }
}

class PredictionCapTest {
    private val asOf = R.D0.plusDays(100)

    private fun anchors(vararg runs: RaceRun) = Anchors.collect(runs.toList(), asOf, 180, 190)

    private fun riegel(seconds: Double, from: Double, to: Double) = seconds * Math.pow(to / from, 1.06)

    // Median of two 10 km road races at 45:00 and 46:00 predicts 45:30 for 10 km, 15 km etc.
    private val slowBase = arrayOf(R.race(1, R.D0, 10000.0, 2700), R.race(2, R.D0.plusDays(1), 10000.0, 2760))

    @Test
    fun `no longer race in the set means no cap`() {
        val p = Prediction.predict(10000.0, anchors(*slowBase))!!
        assertNull(p.cappedBy)
        assertEquals(2730.0, p.rawSeconds, 1e-9)
    }

    @Test
    fun `a race shorter than the target never caps`() {
        val p = Prediction.predict(10000.0, anchors(*slowBase, R.race(3, R.D0.plusDays(2), 5000.0, 1500)))!!
        assertNull(p.cappedBy)
    }

    @Test
    fun `a faster race at the same distance caps the median`() {
        val p = Prediction.predict(10000.0, anchors(R.race(1, R.D0, 10000.0, 2700), R.race(2, R.D0.plusDays(1), 10000.0, 2760), R.race(3, R.D0.plusDays(2), 10000.0, 2400)))!!
        assertEquals(2400.0, p.rawSeconds, 1e-9)
        assertEquals(3L, p.cappedBy!!.activityId)
        assertEquals(2400.0, p.lowSeconds, 1e-9)
    }

    @Test
    fun `a longer race caps through Riegel`() {
        val half = R.race(3, R.D0.plusDays(2), 21097.5, 5400)
        val p = Prediction.predict(10000.0, anchors(*slowBase, half))!!
        val cap = riegel(5400.0, 21097.5, 10000.0)
        assertTrue(cap < 2730.0)
        assertEquals(cap, p.rawSeconds, 1e-6)
        assertEquals(3L, p.cappedBy!!.activityId)
    }

    @Test
    fun `a cap above the median leaves it unchanged`() {
        val p = Prediction.predict(10000.0, anchors(*slowBase, R.race(3, R.D0.plusDays(2), 10000.0, 3000)))!!
        assertNull(p.cappedBy)
        assertEquals(2760.0, p.rawSeconds, 1e-9)
    }

    @Test
    fun `only the most recent race counts`() {
        val older = R.race(3, R.D0.plusDays(2), 10000.0, 2400)
        val newer = R.race(4, R.D0.plusDays(20), 10000.0, 3000)
        val p = Prediction.predict(10000.0, anchors(*slowBase, older, newer))!!
        assertNull(p.cappedBy)
        assertEquals(2730.0, p.rawSeconds, 1e-9)
    }

    @Test
    fun `a race outside the window does not cap`() {
        val old = R.race(3, asOf.minusDays(200), 10000.0, 2400)
        val p = Prediction.predict(10000.0, anchors(*slowBase, old))!!
        assertNull(p.cappedBy)
    }

    @Test
    fun `a trail race is ignored`() {
        val trail = R.race(3, R.D0.plusDays(2), 10000.0, 2400, elev = 40.0)
        val p = Prediction.predict(10000.0, anchors(*slowBase, trail))!!
        assertNull(p.cappedBy)
    }

    @Test
    fun `a best effort or hard run is not a cap`() {
        val effort = R.run(3, R.D0.plusDays(2), 10000.0, 2400, efforts = mapOf(10000.0 to 2400.0))
        val p = Prediction.predict(10000.0, anchors(*slowBase, effort))!!
        assertNull(p.cappedBy)
    }

    @Test
    fun `display rounding happens after the cap`() {
        val p = Prediction.predict(10000.0, anchors(*slowBase, R.race(3, R.D0.plusDays(2), 10000.0, 2410)))!!
        assertEquals(2410.0, p.rawSeconds, 1e-9)
        assertEquals(2415.0, p.seconds, 1e-9)
    }

    @Test
    fun `the capped prediction also feeds the walk-forward accuracy`() {
        val runs = slowBase.toList() + R.race(3, R.D0.plusDays(2), 21097.5, 5400) + R.race(4, R.D0.plusDays(40), 10000.0, 2500)
        val outcome = Accuracy.outcomes(runs, 180, 190).first { it.race.id == 4L }
        assertEquals(riegel(5400.0, 21097.5, 10000.0), outcome.predictedSeconds!!, 1e-6)
    }
}

class PredictionLogTest {
    private fun entry(daysBeforeD0: Long, meters: Double, seconds: Double, low: Double? = null, high: Double? = null) =
        LoggedPrediction(R.D0.minusDays(daysBeforeD0), meters, seconds, low, high, "race-v3", 3, null)

    @Test
    fun `the latest row before the race day counts, scaled with Riegel to the measured distance`() {
        val race = R.race(1, R.D0, 4900.0, 1180)
        val log = listOf(entry(5, 5000.0, 1300.0), entry(1, 5000.0, 1200.0, 1150.0, 1250.0), entry(1, 10000.0, 2500.0))
        val o = PredictionLog.outcomes(listOf(race), log).single()
        val scale = Math.pow(4900.0 / 5000.0, 1.06)
        assertEquals(1200.0 * scale, o.predictedSeconds, 1e-9)
        assertEquals((1180 - 1200.0 * scale) / (1200.0 * scale) * 100, o.errorPct, 1e-9)
        assertEquals(true, o.withinRange)
        assertEquals(1L, o.daysBefore)
    }

    @Test
    fun `a row of the race day itself, a row older than 14 days, another distance and a trail race never count`() {
        val race = R.race(1, R.D0, 5000.0, 1180)
        assertTrue(PredictionLog.outcomes(listOf(race), listOf(entry(0, 5000.0, 1200.0))).isEmpty())
        assertTrue(PredictionLog.outcomes(listOf(race), listOf(entry(15, 5000.0, 1200.0))).isEmpty())
        assertEquals(1, PredictionLog.outcomes(listOf(race), listOf(entry(14, 5000.0, 1200.0))).size)
        assertTrue(PredictionLog.outcomes(listOf(race), listOf(entry(1, 10000.0, 2500.0))).isEmpty())
        val trail = R.race(2, R.D0, 5000.0, 1500, elev = 40.0)
        assertTrue(PredictionLog.outcomes(listOf(trail), listOf(entry(1, 5000.0, 1200.0))).isEmpty())
    }

    @Test
    fun `entries freeze the raw prediction, the shown range, the version and the cap`() {
        val anchors = Anchors.collect(listOf(R.race(1, R.D0, 21097.5, 5400), R.run(2, R.D0.plusDays(5), 6000.0, 1600, efforts = mapOf(5000.0 to 1300.0)),
            R.run(3, R.D0.plusDays(6), 11000.0, 2900, efforts = mapOf(10000.0 to 2700.0))), R.D0.plusDays(10), 180, null)
        val p = Prediction.predict(5000.0, anchors)!!
        val e = PredictionLog.entries(R.D0.plusDays(10), listOf(Triple(p, 1100.0, 1300.0))).single()
        assertEquals(p.rawSeconds, e.seconds)
        assertEquals(1100.0, e.lowSeconds)
        assertEquals(RaceAlgorithm.VERSION, e.algorithmVersion)
        assertEquals(p.cappedBy?.activityId, e.cappedByActivityId)
    }

    @Test
    fun `the summary counts races, the median and mean absolute error, the hits inside the range and the first log day`() {
        val races = listOf(R.race(1, R.D0, 5000.0, 1140), R.race(2, R.D0.plusDays(30), 5000.0, 1260))
        val log = listOf(entry(1, 5000.0, 1200.0, 1100.0, 1250.0), entry(-29, 5000.0, 1200.0, 1100.0, 1250.0))
        val s = PredictionLog.summary(PredictionLog.outcomes(races, log), log)
        assertEquals(2, s.n)
        assertEquals(0.0, s.medianErrorPct!!, 1e-9)
        assertEquals(5.0, s.maePct!!, 1e-9)
        assertEquals(1, s.withinRange)
        assertEquals(R.D0.minusDays(1), s.firstLogDate)
    }
}
