package com.running.load

import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.SustainedDirection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Random

class FitnessFatigueTest {
    private val first = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun loads(days: Int, seed: Long = 1): Map<LocalDate, Double> {
        val r = Random(seed)
        return (0 until days).associate { first.plusDays(it.toLong()) to (if (it % 7 == 3) 0.0 else 40.0 + r.nextInt(120)) }
    }

    /** Performance generated exactly from the model: p = 100 + 0.05 F - 0.2 A. */
    private fun modelPoints(daily: Map<LocalDate, Double>, days: Int, step: Int, noise: Double = 0.0, seed: Long = 7): List<PerfPoint> {
        val end = first.plusDays(days.toLong())
        val f = Ewma.series(daily, first, end, FitnessFatigue.TAU_FITNESS)
        val a = Ewma.series(daily, first, end, FitnessFatigue.TAU_FATIGUE)
        val r = Random(seed)
        return (cfg.modelRunInDays until days step step).map { i ->
            val d = first.plusDays(i.toLong())
            PerfPoint(i.toLong(), d, "run", 100 + 0.05 * f.getValue(d.minusDays(1)) - 0.2 * a.getValue(d.minusDays(1)) + noise * r.nextGaussian())
        }
    }

    @Test
    fun `known impulse response coefficients are recovered`() {
        val daily = loads(700)
        val pts = modelPoints(daily, 700, 3)
        val f = Ewma.series(daily, first, first.plusDays(700), FitnessFatigue.TAU_FITNESS)
        val a = Ewma.series(daily, first, first.plusDays(700), FitnessFatigue.TAU_FATIGUE)
        val fit = FitnessFatigue.fit(pts, f, a)!!
        assertEquals(100.0, fit.p0, 1e-6)
        assertEquals(0.05, fit.k1, 1e-9)
        assertEquals(0.2, fit.k2, 1e-9)
    }

    @Test
    fun `a model that predicts well beats both naive forecasts and the curve is shown`() {
        val daily = loads(900)
        val pts = modelPoints(daily, 900, 2)
        val r = FitnessFatigue.analyze(daily, first, first.plusDays(900), pts, cfg)
        assertTrue(r.validation.evaluated >= FitnessFatigue.MIN_EVALUATED)
        assertTrue(r.validation.passed, r.validation.reason ?: "")
        assertTrue(r.showCurve)
        assertEquals(1.0, r.validation.stableShare!!, 1e-9)
        assertTrue(r.days.any { it.predicted != null })
    }

    @Test
    fun `pure noise is not predicted better than the naive forecast so the curve is hidden`() {
        val daily = loads(900)
        val r = Random(3)
        val pts = (cfg.modelRunInDays until 900 step 2).map { PerfPoint(it.toLong(), first.plusDays(it.toLong()), "run", 100 + r.nextGaussian() * 3) }
        val res = FitnessFatigue.analyze(daily, first, first.plusDays(900), pts, cfg)
        assertFalse(res.validation.passed)
        assertFalse(res.showCurve)
        assertNotNull(res.validation.reason)
        // the load lines remain available as plain summaries
        assertTrue(res.days.isNotEmpty())
        assertTrue(res.days.all { it.predicted == null })
    }

    @Test
    fun `adding a later point changes no earlier prediction`() {
        val daily = loads(900)
        val pts = modelPoints(daily, 880, 2, noise = 0.5)
        val base = FitnessFatigue.analyze(daily, first, first.plusDays(900), pts, cfg).validation
        val extra = pts + PerfPoint(999, first.plusDays(890), "late", 500.0)
        val more = FitnessFatigue.analyze(daily, first, first.plusDays(900), extra, cfg).validation
        assertEquals(base.errors.size + 1, more.errors.size)
        assertEquals(base.errors, more.errors.take(base.errors.size))
    }

    @Test
    fun `the run-in period is not evaluated`() {
        val daily = loads(900)
        val pts = modelPoints(daily, 900, 2)
        val r = FitnessFatigue.analyze(daily, first, first.plusDays(900), pts, cfg)
        val earliest = first.plusDays((cfg.modelRunInDays + cfg.modelMinDays).toLong())
        assertTrue(r.validation.errors.all { !it.date.isBefore(earliest) })
        assertTrue(r.days.filter { it.date.isBefore(r.runInEnd) }.all { it.runIn })
    }

    @Test
    fun `the 10 percent rule boundary`() {
        val k = List(10) { 1.0 }
        fun v(model: Double) = FitnessFatigue.validate(List(10) { model }, List(10) { 1.0 }, List(10) { 1.5 }, k, k, null, null, cfg)
        assertTrue(v(0.89).passed)
        assertFalse(v(0.91).passed)
        assertEquals("de mediaan van de laatste 4 weken", v(0.5).betterBaseline)
    }

    @Test
    fun `unstable weights hide the model even when the error is lower`() {
        val ok = List(10) { 1.0 }
        val flip = List(10) { if (it < 4) -1.0 else 1.0 }
        val r = FitnessFatigue.validate(List(10) { 0.5 }, List(10) { 1.0 }, List(10) { 1.0 }, flip, ok, null, null, cfg)
        assertFalse(r.passed)
        assertEquals(0.6, r.stableShare!!, 1e-9)
    }

    @Test
    fun `too few evaluated points abstain`() {
        val r = FitnessFatigue.validate(List(5) { 0.1 }, List(5) { 1.0 }, List(5) { 1.0 }, List(5) { 1.0 }, List(5) { 1.0 }, null, null, cfg)
        assertFalse(r.passed)
        assertTrue(r.reason!!.contains("te weinig"))
    }

    @Test
    fun `there is no form or TSB number anywhere in the output`() {
        val names = (FfDay::class.java.declaredFields + FfResult::class.java.declaredFields + FfValidation::class.java.declaredFields).map { it.name.lowercase() }
        assertTrue(names.none { it.contains("tsb") || it.contains("form") || it.contains("risk") || it.contains("zone") })
    }
}

class RecoveryForecastTest {
    private val today = LocalDate.of(2026, 10, 31)

    @Test
    fun `AR(1) recovers a known coefficient`() {
        val dev = generateSequence(10.0) { it * 0.8 }.take(10).toList()
        val phi = RecoveryForecast.fitPhi(dev.zipWithNext())!!
        assertEquals(0.8, phi, 1e-12)
    }

    @Test
    fun `7 day median needs 5 values`() {
        val d = LocalDate.of(2026, 3, 10)
        val s = (0 until 7).associate { d.minusDays(it.toLong()) to it.toDouble() }.toMutableMap()
        assertEquals(3.0, RecoveryForecast.medians7(s, d, d)[d])
        s.remove(d); s.remove(d.minusDays(1)); s.remove(d.minusDays(2))
        assertNull(RecoveryForecast.medians7(s, d, d)[d]) // 4 of 7
    }

    @Test
    fun `too little history abstains`() {
        val s = T.series(today.minusDays(100), 90) { 50.0 }
        val r = RecoveryForecast.analyze("HRV", "ms", s, today)
        assertFalse(r.passed)
        assertTrue(r.forecasts.isEmpty())
        assertTrue(r.reason!!.contains("te weinig historie"))
    }

    @Test
    fun `a noise series is not forecast better than persistence`() {
        val rnd = Random(5)
        val s = T.series(today.minusDays(400), 399) { 50.0 + rnd.nextGaussian() * 4 }
        val r = RecoveryForecast.analyze("HRV", "ms", s, today)
        assertFalse(r.passed)
        assertTrue(r.forecasts.isEmpty())
        assertNotNull(r.reason)
    }

    @Test
    fun `when the check passes the forecast is a range of 3 days`() {
        val rnd = Random(5)
        val s = T.series(today.minusDays(400), 399) { 50.0 + rnd.nextGaussian() * 4 }
        val r = RecoveryForecast.analyze("HRV", "ms", s, today, LoadConfig(modelGain = -10.0))
        assertTrue(r.passed)
        assertEquals(3, r.forecasts.size)
        r.forecasts.forEach { assertTrue(it.low <= it.value && it.value <= it.high) }
        assertEquals(today.plusDays(1), r.forecasts.last().date) // 3 days after the last day with data (2 days ago)
    }
}

class SignalsTest {
    private fun input(
        hrv: SustainedDirection? = SustainedDirection.NONE,
        rhr: SustainedDirection? = SustainedDirection.NONE,
        sleep: DeviationBand? = DeviationBand.WITHIN,
        ef: Double? = 0.0,
        efN: Int = 5,
        load: Double? = 100.0,
        loadP90: Double? = 200.0,
        mono: Double? = 1.0,
        monoP90: Double? = 2.0,
    ) = SignalInput(hrv, rhr, sleep, ef, efN, load, loadP90, mono, monoP90)

    @Test
    fun `no signal is active in a calm state`() {
        val s = FatigueSignals.evaluate(input())
        assertEquals(0, s.active)
        assertEquals(6, s.withData)
    }

    @Test
    fun `each signal alone`() {
        assertEquals(listOf(SignalKey.HRV), FatigueSignals.evaluate(input(hrv = SustainedDirection.LOWER)).signals.filter { it.active == true }.map { it.key })
        assertEquals(listOf(SignalKey.RESTING_HR), FatigueSignals.evaluate(input(rhr = SustainedDirection.HIGHER)).signals.filter { it.active == true }.map { it.key })
        assertEquals(listOf(SignalKey.SLEEP), FatigueSignals.evaluate(input(sleep = DeviationBand.DEVIATING)).signals.filter { it.active == true }.map { it.key })
        assertEquals(listOf(SignalKey.EFFICIENCY), FatigueSignals.evaluate(input(ef = -4.0)).signals.filter { it.active == true }.map { it.key })
        assertEquals(listOf(SignalKey.LOAD), FatigueSignals.evaluate(input(load = 250.0)).signals.filter { it.active == true }.map { it.key })
        assertEquals(listOf(SignalKey.MONOTONY), FatigueSignals.evaluate(input(mono = 2.5)).signals.filter { it.active == true }.map { it.key })
    }

    @Test
    fun `the wrong direction does not count`() {
        assertEquals(0, FatigueSignals.evaluate(input(hrv = SustainedDirection.HIGHER, rhr = SustainedDirection.LOWER)).active)
    }

    @Test
    fun `signals without data are not counted as inactive`() {
        val s = FatigueSignals.evaluate(input(hrv = SustainedDirection.INSUFFICIENT, ef = -5.0, efN = 2, load = null, mono = null))
        assertEquals(0, s.active)
        assertEquals(2, s.withData)
        assertNull(s.signals.first { it.key == SignalKey.EFFICIENCY }.active)
    }

    @Test
    fun `efficiency boundary is more than 3 percent lower`() {
        assertEquals(0, FatigueSignals.evaluate(input(ef = -3.0)).active)
        assertEquals(1, FatigueSignals.evaluate(input(ef = -3.01)).active)
    }

    @Test
    fun `base rate counts days with at least k signals`() {
        assertEquals(mapOf(1 to 3, 2 to 2, 3 to 1, 4 to 0, 5 to 0, 6 to 0), FatigueSignals.baseRate(listOf(0, 0, 1, 2, 3)))
    }

    @Test
    fun `no score field exists`() {
        val names = (SignalSet::class.java.declaredFields + Signal::class.java.declaredFields).map { it.name.lowercase() }
        assertTrue(names.none { it.contains("score") || it.contains("probab") })
    }
}

class SessionReadinessTest {
    private fun base(
        deviating: Int? = 0,
        strong: Boolean = false,
        sinceHard: Int? = 10,
        hardTime: RecoveryTime? = RecoveryTime.Mornings(2),
        sinceLong: Int? = 10,
        longTime: RecoveryTime? = RecoveryTime.Mornings(3),
        load: OwnLevel = OwnLevel.NORMAL,
        sleepShort: Double? = 0.0,
        signals: Int? = 0,
    ) = ReadinessInput(deviating, strong, sinceHard, hardTime, sinceLong, longTime, load, sleepShort, signals)

    private fun level(i: ReadinessInput, t: ReadinessType) = SessionReadiness.evaluate(i).first { it.type == t }

    @Test
    fun `everything fine gives no counter signals for every type`() {
        SessionReadiness.evaluate(base()).forEach { assertEquals(ReadinessLevel.NONE, it.level, it.type.name) }
    }

    @Test
    fun `two deviating recovery values count against the hard types but a single deviation does not stop easy running`() {
        val i = base(deviating = 2)
        assertEquals(ReadinessLevel.ONE, level(i, ReadinessType.INTERVAL).level)
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.EASY).level)
        assertEquals(ReadinessLevel.ONE, level(base(strong = true, deviating = 1), ReadinessType.EASY).level)
    }

    @Test
    fun `a recent hard session counts against hard types only, with the own recovery time`() {
        val i = base(sinceHard = 1) // own time is 2 mornings
        assertEquals(ReadinessLevel.ONE, level(i, ReadinessType.TEMPO).level)
        assertEquals(ReadinessLevel.ONE, level(i, ReadinessType.RACE).level)
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.EASY).level)
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.LONG).level)
        assertEquals(ReadinessLevel.NONE, level(base(sinceHard = 2), ReadinessType.TEMPO).level)
    }

    @Test
    fun `the fixed fallback is used and said so when the own recovery time is unknown`() {
        val t = level(base(sinceHard = 1, hardTime = RecoveryTime.Insufficient(3, 8)), ReadinessType.INTERVAL)
        val part = t.parts.first { it.key == "hard" }
        assertEquals(Stance.AGAINST, part.stance)
        assertTrue(part.detail.contains("vaste regel van 2 dagen"))
        val ok = level(base(sinceHard = 2, hardTime = null), ReadinessType.INTERVAL).parts.first { it.key == "hard" }
        assertEquals(Stance.FOR, ok.stance)
    }

    @Test
    fun `the last long run decides for the long run`() {
        assertEquals(ReadinessLevel.ONE, level(base(sinceLong = 2), ReadinessType.LONG).level) // own time 3
        assertEquals(ReadinessLevel.NONE, level(base(sinceLong = 3), ReadinessType.LONG).level)
        assertEquals(ReadinessLevel.NONE, level(base(sinceLong = 2), ReadinessType.TEMPO).level)
    }

    @Test
    fun `high load counts against everything except easy running`() {
        val i = base(load = OwnLevel.HIGH)
        ReadinessType.entries.filter { it != ReadinessType.EASY }.forEach { assertEquals(ReadinessLevel.ONE, level(i, it).level, it.name) }
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.EASY).level)
    }

    @Test
    fun `sleep more than 60 minutes under the target counts against hard types and the long run`() {
        val i = base(sleepShort = 61.0)
        assertEquals(ReadinessLevel.ONE, level(i, ReadinessType.LONG).level)
        assertEquals(ReadinessLevel.ONE, level(i, ReadinessType.INTERVAL).level)
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.EASY).level)
        assertEquals(ReadinessLevel.NONE, level(base(sleepShort = 60.0), ReadinessType.INTERVAL).level)
    }

    @Test
    fun `3 active signals count against every type`() {
        ReadinessType.entries.forEach { assertEquals(ReadinessLevel.ONE, level(base(signals = 3), it).level, it.name) }
    }

    @Test
    fun `several counter signals give the top level`() {
        val i = base(deviating = 3, sinceHard = 1, load = OwnLevel.HIGH, signals = 4)
        val t = level(i, ReadinessType.INTERVAL)
        assertEquals(ReadinessLevel.MANY, t.level)
        assertEquals(4, t.parts.count { it.stance == Stance.AGAINST })
    }

    @Test
    fun `fewer than 2 components with data gives insufficient data`() {
        val i = base(deviating = null, signals = null)
        assertEquals(ReadinessLevel.INSUFFICIENT, level(i, ReadinessType.EASY).level)
        assertEquals(ReadinessLevel.NONE, level(i, ReadinessType.TEMPO).level) // hard, load and sleep still have data
        val nothing = base(deviating = null, signals = null, sinceHard = null, load = OwnLevel.UNKNOWN, sleepShort = null)
        assertEquals(ReadinessLevel.INSUFFICIENT, level(nothing, ReadinessType.TEMPO).level)
    }

    @Test
    fun `no text says safe or ready`() {
        val all = SessionReadiness.evaluate(base(deviating = 2, sinceHard = 1)).flatMap { listOf(it.level.text) + it.parts.map { p -> p.detail + p.title + p.stance.text } }
        all.forEach {
            val l = it.lowercase()
            assertFalse(l.contains("veilig") || l.contains("klaar om") || l.contains("overtraind") || l.contains("blessurerisico") || l.contains("optimaal"), it)
        }
    }
}
