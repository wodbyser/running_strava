package com.running.load

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StatisticsTest {
    @Test
    fun `percentile and median by hand`() {
        assertEquals(3.0, LoadStats.median(listOf(5.0, 1.0, 3.0)))
        assertEquals(2.5, LoadStats.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(3.8, LoadStats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.7)!!, 1e-9)
        assertNull(LoadStats.percentile(emptyList(), 0.5))
    }

    @Test
    fun `ranks share the mean rank for ties and spearman is rank correlation`() {
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), LoadStats.ranks(listOf(1.0, 5.0, 5.0, 9.0)))
        assertEquals(1.0, LoadStats.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 200.0, 201.0, 9000.0))!!, 1e-9)
        assertEquals(-1.0, LoadStats.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(4.0, 3.0, 2.0, 1.0))!!, 1e-9)
    }

    @Test
    fun `bootstrap is reproducible with the fixed seed`() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        assertEquals(LoadStats.bootstrapMedian(v), LoadStats.bootstrapMedian(v))
    }

    @Test
    fun `interval excluding 0 versus including 0`() {
        val negative = List(10) { -1.0 - it * 0.01 }
        assertTrue(LoadStats.bootstrapMedian(negative)!!.excludesZero)
        val mixed = listOf(-1.0, 1.0, -0.5, 0.5, -1.0, 1.0, -0.2, 0.2)
        assertFalse(LoadStats.bootstrapMedian(mixed)!!.excludesZero)
    }

    @Test
    fun `slope through the origin and OLS recover known coefficients`() {
        assertEquals(0.8, LoadStats.slopeThroughOrigin(listOf(10.0, 5.0, 2.0), listOf(8.0, 4.0, 1.6))!!, 1e-12)
        val x1 = (1..20).map { it.toDouble() }
        val x2 = (1..20).map { (it * it % 7).toDouble() }
        val y = x1.indices.map { 3.0 + 2.0 * x1[it] - 0.5 * x2[it] }
        val c = LoadStats.ols(listOf(x1, x2), y)!!
        assertEquals(3.0, c[0], 1e-6)
        assertEquals(2.0, c[1], 1e-6)
        assertEquals(-0.5, c[2], 1e-6)
    }

    @Test
    fun `permutation test is reproducible and small for a perfect relation`() {
        val x = (1..12).map { it.toDouble() }
        val a = LoadStats.spearmanPermutation(x, x)!!
        assertEquals(1.0, a.statistic, 1e-9)
        assertTrue(a.pValue < 0.01)
        assertEquals(a, LoadStats.spearmanPermutation(x, x))
    }
}

class ResponseTest {
    private val start = T.D0
    private val cfg = LoadConfig.DEFAULT
    private val days = 400

    private fun flat(value: Double) = T.series(start, days) { value }

    /** Constant series with a deviation on the mornings after the given session days. */
    private fun withDeviation(base: Double, sessionDays: List<LocalDate>, offsets: Map<Int, Double>): Map<LocalDate, Double> {
        val m = flat(base).toMutableMap()
        for (s in sessionDays) for ((k, dv) in offsets) m[s.plusDays(k.toLong())] = base + dv
        return m
    }

    private fun tempoSessions(n: Int, spacing: Int = 10, first: Int = 40) =
        (0 until n).map { T.session(it.toLong() + 1, start.plusDays((first + it * spacing).toLong()), SessionClass.TEMPO, 120.0) }

    private fun analyzer(sessions: List<LoadSession>, hrv: Map<LocalDate, Double>, rhr: Map<LocalDate, Double> = flat(50.0), w: LoadWindows = LoadWindows.DEFAULT) =
        ResponseAnalyzer(RecoverySeries(hrv, rhr, flat(450.0), flat(80.0)), sessions, cfg, w)

    @Test
    fun `the baseline ends before the session day`() {
        val d = start.plusDays(50)
        val s = flat(50.0).toMutableMap()
        s[d.plusDays(1)] = 40.0
        s[d.plusDays(2)] = 40.0
        // every change of the days from D on is ignored by the reference
        val v = MorningDeviation.after(s, d, 1, 28)!!
        assertEquals(-10.0, v.z, 1e-9) // SD floor 1
        assertEquals(-10.0, v.diff, 1e-9)
        // with the reference moved to D + 1 the outlier is part of the baseline of the next day instead
        assertNotNull(MorningDeviation.after(s, d.plusDays(1), 1, 28))
    }

    @Test
    fun `a hard session inside the window makes both sessions not isolated`() {
        val a = T.session(1, start.plusDays(40), SessionClass.TEMPO)
        val b = T.session(2, start.plusDays(42), SessionClass.INTERVAL)
        val an = analyzer(listOf(a, b), flat(50.0))
        assertEquals(0, an.isolated(SessionClass.TEMPO, 3).size)
        assertEquals(0, an.isolated(SessionClass.INTERVAL, 3).size)
        // far enough apart (more than 3 days after, more than 2 days before)
        val c = T.session(3, start.plusDays(50), SessionClass.INTERVAL)
        val an2 = analyzer(listOf(a, c), flat(50.0))
        assertEquals(1, an2.isolated(SessionClass.TEMPO, 3).size)
        assertEquals(1, an2.isolated(SessionClass.INTERVAL, 3).size)
    }

    @Test
    fun `easy sessions inside the window are allowed and counted`() {
        val a = T.session(1, start.plusDays(40), SessionClass.TEMPO)
        val e = T.session(2, start.plusDays(42), SessionClass.EASY, 40.0)
        val an = analyzer(listOf(a, e), flat(50.0))
        val iso = an.isolated(SessionClass.TEMPO, 3)
        assertEquals(1, iso.size)
        assertEquals(1, Isolation.easyInWindow(iso.single(), an.days, 3))
    }

    @Test
    fun `8 isolated sessions give a verdict and 7 abstain`() {
        for ((n, expected) in listOf(8 to Verdict.DIFFERENT, 7 to Verdict.INSUFFICIENT)) {
            val sessions = tempoSessions(n)
            val hrv = withDeviation(50.0, sessions.map { it.date }, mapOf(1 to -10.0))
            val r = analyzer(sessions, hrv).byClass().first { it.cls == SessionClass.TEMPO }
            assertEquals(n, r.isolated)
            val m1 = r.metrics.first { it.metric == ResponseMetric.HRV }.mornings.first()
            assertEquals(expected, m1.verdict, "n=$n")
            assertEquals(n, m1.n)
            assertEquals(-10.0, m1.medianZ!!, 1e-9)
            assertEquals(-10.0, m1.medianDiff!!, 1e-9)
            assertEquals(n >= 8, r.enough)
        }
    }

    @Test
    fun `no difference when the interval includes 0`() {
        val sessions = tempoSessions(8)
        val s = flat(50.0).toMutableMap()
        sessions.forEachIndexed { i, x -> s[x.date.plusDays(1)] = 50.0 + if (i % 2 == 0) 5.0 else -5.0 }
        val m1 = analyzer(sessions, s).byClass().first { it.cls == SessionClass.TEMPO }.metrics.first { it.metric == ResponseMetric.HRV }.mornings.first()
        assertEquals(Verdict.NOT_CLEAR, m1.verdict)
    }

    @Test
    fun `recovery time is the first stable morning inside the own norm`() {
        val sessions = tempoSessions(8)
        val dates = sessions.map { it.date }
        val hrv = withDeviation(50.0, dates, mapOf(1 to -10.0))
        val rhr = withDeviation(50.0, dates, mapOf(1 to 8.0))
        val t = analyzer(sessions, hrv, rhr).recoveryTime(SessionClass.TEMPO)
        assertEquals(RecoveryTime.Mornings(2), t)
    }

    @Test
    fun `no measurable deviation when morning 1 is already within the norm`() {
        val sessions = tempoSessions(8)
        assertEquals(RecoveryTime.NoDeviation, analyzer(sessions, flat(50.0)).recoveryTime(SessionClass.TEMPO))
    }

    @Test
    fun `longer than the horizon when the deviation lasts`() {
        val sessions = tempoSessions(8, spacing = 12)
        val dates = sessions.map { it.date }
        val long = (1..5).associateWith { -10.0 }
        val hrv = withDeviation(50.0, dates, long)
        val rhr = withDeviation(50.0, dates, (1..5).associateWith { 8.0 })
        assertEquals(RecoveryTime.Longer(5), analyzer(sessions, hrv, rhr).recoveryTime(SessionClass.TEMPO))
    }

    @Test
    fun `recovery time abstains below the minimum`() {
        val t = analyzer(tempoSessions(7), flat(50.0)).recoveryTime(SessionClass.TEMPO)
        assertEquals(RecoveryTime.Insufficient(7, 8), t)
        assertEquals(RecoveryTime.Insufficient(0, 8), analyzer(emptyList(), flat(50.0)).recoveryTime(SessionClass.INTERVAL))
    }

    @Test
    fun `a future day changes no earlier statistic`() {
        val sessions = tempoSessions(9)
        val hrv = withDeviation(50.0, sessions.map { it.date }, mapOf(1 to -10.0))
        val without = analyzer(sessions.take(8), hrv.filterKeys { it < sessions[8].date }).byClass().first { it.cls == SessionClass.TEMPO }
        val with = analyzer(sessions.take(8), hrv).byClass().first { it.cls == SessionClass.TEMPO }
        assertEquals(without.metrics, with.metrics)
    }
}

class RestDaysTest {
    private val start = T.D0
    private val cfg = LoadConfig.DEFAULT

    private fun scenario(lastDay: Int): List<RestGroup> {
        // runs on even days, rest on odd days; HRV rises by 1 per day so every morning pair changes by +1
        val sessions = (0..lastDay step 2).map { T.session(it.toLong(), start.plusDays(it.toLong()), SessionClass.EASY, 40.0) }
        val hrv = T.series(start, 40) { 50.0 + it }
        val series = RecoverySeries(hrv, emptyMap(), T.series(start, 40) { 450.0 }, emptyMap())
        return RestDays.analyze(sessions, series, start, start.plusDays(lastDay.toLong()), cfg)
    }

    @Test
    fun `15 rest days give a verdict and 14 abstain`() {
        val ok = scenario(29).first { it.kind == DayKind.REST }
        val hrv = ok.changes.first { it.metric == ResponseMetric.HRV }
        assertEquals(15, ok.days)
        assertEquals(15, hrv.n)
        assertEquals(1.0, hrv.medianChange!!, 1e-9)
        assertEquals(Verdict.DIFFERENT, hrv.verdict)
        assertEquals(450.0, ok.medianSleepMinutes)

        val few = scenario(28).first { it.kind == DayKind.REST }
        assertEquals(14, few.days)
        assertEquals(Verdict.INSUFFICIENT, few.changes.first { it.metric == ResponseMetric.HRV }.verdict)
    }

    @Test
    fun `a walk on a rest day is allowed and flagged`() {
        val sessions = listOf(T.session(1, start, SessionClass.EASY, 40.0), T.session(2, start.plusDays(1), SessionClass.OTHER, 20.0, isRun = false))
        val series = RecoverySeries(T.series(start, 5) { 50.0 }, emptyMap(), emptyMap(), emptyMap())
        val g = RestDays.analyze(sessions, series, start, start.plusDays(3), cfg)
        val rest = g.first { it.kind == DayKind.REST }
        assertEquals(3, rest.days)
        assertEquals(1, rest.withOtherActivity)
        assertEquals(1, g.first { it.kind == DayKind.EASY }.days)
    }

    @Test
    fun `second consecutive rest day is its own group`() {
        val sessions = listOf(T.session(1, start, SessionClass.EASY, 40.0))
        val series = RecoverySeries(T.series(start, 6) { 50.0 }, emptyMap(), emptyMap(), emptyMap())
        val g = RestDays.analyze(sessions, series, start, start.plusDays(4), cfg)
        // rest days: 1, 2, 3, 4. Second consecutive: 2, 3, 4
        assertEquals(4, g.first { it.kind == DayKind.REST }.days)
        assertEquals(3, g.first { it.kind == DayKind.SECOND_REST }.days)
    }
}

class LongRunDurationTest {
    @Test
    fun `groups by duration with their own minimum`() {
        val start = T.D0
        val sessions = (0 until 5).map { T.session(it.toLong(), start.plusDays(40L + it * 10), SessionClass.LONG, 150.0, minutes = 80) } +
            (0 until 4).map { T.session(10L + it, start.plusDays(100L + it * 10), SessionClass.LONG, 200.0, minutes = 130) }
        val series = RecoverySeries(T.series(start, 400) { 50.0 }, emptyMap(), emptyMap(), emptyMap())
        val an = ResponseAnalyzer(series, sessions)
        val g = LongRunDuration.analyze(an, sessions.associate { it.id to -2.0 }, LoadConfig.DEFAULT, 3, 2)
        assertEquals(4, g.size)
        assertEquals(5, g[0].isolated)
        assertTrue(g[0].enough)
        assertEquals(-2.0, g[0].efChangePct)
        assertEquals(4, g[3].isolated)
        assertFalse(g[3].enough)
        assertTrue(g[3].metrics.all { m -> m.mornings.all { it.verdict == Verdict.INSUFFICIENT } })
    }
}

class RestReferenceTest {
    private val start = T.D0

    @Test
    fun `rest reference uses days without a run and without a hard session around them`() {
        val series = RecoverySeries(T.series(start, 200) { 50.0 }, emptyMap(), emptyMap(), emptyMap())
        val sessions = listOf(
            T.session(1, start.plusDays(60), SessionClass.TEMPO),
            T.session(2, start.plusDays(100), SessionClass.EASY),
        )
        val an = ResponseAnalyzer(series, sessions, LoadConfig.DEFAULT, LoadWindows.DEFAULT)
        val ref = an.restReference(start.plusDays(40), start.plusDays(120), 3)
        val hrv = ref.first { it.metric == ResponseMetric.HRV }.mornings
        // 81 days, minus the run day, minus the days within 2 days before and 3 days after the hard session (6 days)
        assertEquals(81 - 1 - 6, hrv.first().n)
        assertEquals(0.0, hrv.first().medianZ!!, 1e-9)
        assertEquals(Verdict.NOT_CLEAR, hrv.first().verdict)
    }

    @Test
    fun `the load output types carry no risk zone or ratio colour`() {
        val names = (WindowSum::class.java.declaredFields + OwnBand.Result::class.java.declaredFields + WeekLoad::class.java.declaredFields).map { it.name.lowercase() }
        assertTrue(names.none { it.contains("risk") || it.contains("zone") || it.contains("ratio") || it.contains("sweet") })
    }
}
