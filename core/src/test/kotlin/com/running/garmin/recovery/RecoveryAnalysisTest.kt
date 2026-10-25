package com.running.garmin.recovery

import com.running.garmin.domain.*
import com.running.garmin.source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RecoveryAnalysisTest {
    private val d = LocalDate.of(2026, 1, 15) // winter time: Brussels = UTC+1, no DST edge
    private val brussels = ZoneId.of("Europe/Brussels")
    private fun before(n: Int) = d.minusDays(n.toLong())

    private fun hrv(date: LocalDate, v: Int?) = GarminHrvDay(date, v, v, null, 40, 45, 60, null, "BALANCED", null, source())
    private fun rhr(date: LocalDate, v: Int?) = GarminRestingHrDay(date, v, null, null, source())
    private fun sleepDay(
        date: LocalDate, seconds: Int?, deep: Int? = null, light: Int? = null, rem: Int? = null, awake: Int? = null,
        unmeasurable: Int? = null, start: Long? = null, end: Long? = null, need: Int? = null,
    ) = GarminSleepDay(date, seconds, deep, light, rem, awake, unmeasurable, null, start, end, null, null, null, 80, "GOOD", need, null, source())

    private fun utcMs(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ---------------------------------------------------------------- HRV
    @Test
    fun `HRV normal day with a 28 day baseline`() {
        val days = (1..28).map { hrv(before(it), 50 + it % 3) } + hrv(d, 51)
        val r = HrvAnalysis.analyze(days, d)
        assertTrue(r.hasNight)
        assertEquals(BaselineStatus.OK, r.metric.primary.status)
        assertEquals(DeviationBand.WITHIN, r.metric.primary.band)
        assertEquals(45, r.garminBalancedLow)
        assertEquals("BALANCED", r.garminStatus)
    }

    @Test
    fun `HRV abstains with 3 days of history`() {
        val r = HrvAnalysis.analyze(listOf(hrv(before(1), 50), hrv(before(2), 51), hrv(before(3), 52), hrv(d, 50)), d)
        assertEquals(BaselineStatus.INSUFFICIENT, r.metric.primary.status)
        assertNull(r.metric.primary.deviation)
        assertEquals(3, r.metric.primary.n)
    }

    @Test
    fun `HRV deviates in both directions`() {
        val base = (1..28).map { hrv(before(it), 50 + if (it % 2 == 0) 1 else -1) }
        val high = HrvAnalysis.analyze(base + hrv(d, 70), d)
        val low = HrvAnalysis.analyze(base + hrv(d, 30), d)
        assertTrue(high.metric.primary.deviation!! > 2)
        assertTrue(low.metric.primary.deviation!! < -2)
        assertEquals(DeviationBand.STRONG, high.metric.primary.band)
        assertEquals(DeviationBand.STRONG, low.metric.primary.band)
    }

    @Test
    fun `HRV missing night gives no value and no deviation`() {
        val r = HrvAnalysis.analyze((1..28).map { hrv(before(it), 50) }, d)
        assertFalse(r.hasNight)
        assertNull(r.metric.value)
        assertNull(r.metric.primary.deviation)
    }

    // ---------------------------------------------------------------- resting HR
    @Test
    fun `resting HR prefers Garmin over manual`() {
        val r = RestingHrAnalysis.analyze((1..28).map { rhr(before(it), 50) } + rhr(d, 52), 48, d)
        assertEquals(RestingHrSource.GARMIN, r.source)
        assertEquals(52, r.value)
        assertEquals(DeviationBand.STRONG, r.metric!!.primary.band)
        assertEquals(2.0, r.metric!!.primary.deviation!!, 1e-9) // flat series, floor SD 1
    }

    @Test
    fun `resting HR lower than the norm is reported as lower`() {
        val r = RestingHrAnalysis.analyze((1..28).map { rhr(before(it), 50) } + rhr(d, 47), null, d)
        assertTrue(r.metric!!.primary.deviation!! < 0)
    }

    @Test
    fun `manual resting HR only gives a value and no baseline`() {
        val r = RestingHrAnalysis.analyze(emptyList(), 48, d)
        assertEquals(RestingHrSource.MANUAL, r.source)
        assertEquals(48, r.value)
        assertNull(r.metric)
    }

    @Test
    fun `no resting HR at all`() {
        val r = RestingHrAnalysis.analyze(listOf(rhr(d, null)), null, d)
        assertEquals(RestingHrSource.NONE, r.source)
        assertNull(r.value)
    }

    // ---------------------------------------------------------------- sleep quality
    @Test
    fun `sleep shares by hand`() {
        // 7 h sleep (25200): deep 3780 (15 %), light 15120 (60 %), REM 6300 (25 %). awake 2800: 2800 / 28000 = 10 %. unmeasurable 0
        val night = sleepDay(d, 25200, deep = 3780, light = 15120, rem = 6300, awake = 2800, unmeasurable = 0)
        val n = SleepAnalysis.quality(listOf(night), d, brussels).night!!
        assertEquals(15.0, n.deepSharePercent!!, 1e-9)
        assertEquals(60.0, n.lightSharePercent!!, 1e-9)
        assertEquals(25.0, n.remSharePercent!!, 1e-9)
        assertEquals(10.0, n.awakeSharePercent!!, 1e-9)
        assertFalse(n.stagesHidden)
        assertEquals(80, n.overallScore)
    }

    @Test
    fun `more than 10 percent unmeasurable hides the stages`() {
        // night total = 25200 + 0 + 3000 = 28200, 3000 / 28200 = 10.6 %
        val hidden = SleepAnalysis.quality(listOf(sleepDay(d, 25200, deep = 3000, light = 15000, rem = 7200, awake = 0, unmeasurable = 3000)), d, brussels).night!!
        assertTrue(hidden.stagesHidden)
        assertNull(hidden.deepSharePercent)
        // exactly 10 % is not above the limit: 2800 / 28000
        val edge = SleepAnalysis.quality(listOf(sleepDay(d, 25200, deep = 3000, light = 15000, rem = 7200, awake = 0, unmeasurable = 2800)), d, brussels).night!!
        assertFalse(edge.stagesHidden)
    }

    @Test
    fun `sleep duration baseline and deviation`() {
        val days = (1..28).map { sleepDay(before(it), 27000 + (it % 2) * 600) } + sleepDay(d, 20000)
        val q = SleepAnalysis.quality(days, d, brussels)
        assertTrue(q.duration.primary.deviation!! < -2)
        assertEquals(15, q.deepReferenceLowPercent)
        assertEquals(20, q.deepReferenceHighPercent)
    }

    @Test
    fun `regularity treats midnight as continuous and needs 7 nights`() {
        // Brussels = UTC+1: 22:30Z is 23:30 local, 23:30Z is 00:30 local (the next day). 60 minutes apart, not 23 hours.
        val nights = (0..6).map {
            val date = before(it)
            val start = if (it % 2 == 0) utcMs(date.minusDays(1), 22, 30) else utcMs(date.minusDays(1), 23, 30)
            sleepDay(date, 27000, start = start, end = utcMs(date, 6, 0))
        }
        val reg = SleepAnalysis.quality(nights, d, brussels).regularity
        assertEquals(BaselineStatus.OK, reg.status)
        // 4 nights at 330 and 3 nights at 390 minutes after 18:00: population SD 29.69
        assertEquals(29.6923, reg.startSdMinutes!!, 0.01)
        assertEquals(0.0, reg.endSdMinutes!!, 1e-9)
        val six = SleepAnalysis.quality(nights.take(6), d, brussels).regularity
        assertEquals(BaselineStatus.INSUFFICIENT, six.status)
        assertEquals(6, six.nights)
        assertNull(six.startSdMinutes)
    }

    @Test
    fun `minutes after 18 wraps correctly`() {
        assertEquals(330.0, SleepAnalysis.minutesAfter18(utcMs(d, 22, 30), brussels))
        assertEquals(390.0, SleepAnalysis.minutesAfter18(utcMs(d, 23, 30), brussels))
        assertEquals(0.0, SleepAnalysis.minutesAfter18(utcMs(d, 17, 0), brussels)) // 18:00 local
    }

    // ---------------------------------------------------------------- sleep debt
    private fun minutes(vararg m: Int) = m.reversed().mapIndexed { i, x -> sleepDay(before(i), x * 60) }

    @Test
    fun `sleep debt by hand`() {
        // target 450: nights 480 420 450 400 500 430 460 -> shortfalls 0 30 0 50 0 20 0 = 100 min, net 3140 - 3150 = -10 min
        val debt = SleepAnalysis.debt(minutes(480, 420, 450, 400, 500, 430, 460), d, 450)
        assertEquals(BaselineStatus.OK, debt.status)
        assertEquals(100L * 60, debt.shortfallSeconds)
        assertEquals(3, debt.nightsBelowTarget)
        assertEquals(-10L * 60, debt.netBalanceSeconds)
        assertEquals(7, debt.nightsWithData)
    }

    @Test
    fun `a long night does not offset a short one in the shortfall`() {
        val debt = SleepAnalysis.debt(minutes(450, 450, 450, 450, 450, 390, 600), d, 450)
        assertEquals(60L * 60, debt.shortfallSeconds)
        assertEquals(90L * 60, debt.netBalanceSeconds)
    }

    @Test
    fun `sleep debt abstains with 4 of 7 nights and missing nights are not zero`() {
        val four = listOf(sleepDay(before(0), 27000), sleepDay(before(1), 27000), sleepDay(before(2), null), sleepDay(before(3), 27000), sleepDay(before(5), 27000))
        val r = SleepAnalysis.debt(four, d, 450)
        assertEquals(BaselineStatus.INSUFFICIENT, r.status)
        assertEquals(4, r.nightsWithData)
        assertNull(r.shortfallSeconds)
        val five = SleepAnalysis.debt(four + sleepDay(before(6), 27000), d, 450)
        assertEquals(BaselineStatus.OK, five.status)
        assertEquals(0L, five.shortfallSeconds)
    }

    @Test
    fun `low deep sleep creates no debt`() {
        val nights = (0..6).map { sleepDay(before(it), 27000, deep = 100, light = 20000, rem = 6900, awake = 0, unmeasurable = 0) }
        assertEquals(0L, SleepAnalysis.debt(nights, d, 450).shortfallSeconds)
    }

    @Test
    fun `Garmin sleep need is passed through unused`() {
        val nights = (0..6).map { sleepDay(before(it), 27000, need = 480) }
        val debt = SleepAnalysis.debt(nights, d, 450)
        assertEquals(480, debt.garminSleepNeedBaseline)
        assertEquals(0L, debt.shortfallSeconds)
    }

    // ---------------------------------------------------------------- Body Battery
    private fun bbDay(date: LocalDate, charged: Int? = 60, drained: Int? = 50) = GarminBodyBatteryDay(date, 10, 90, charged, drained, 50, source())

    @Test
    fun `recharge from samples around sleep`() {
        val start = utcMs(before(1), 22, 0)
        val end = utcMs(d, 6, 0)
        val sleep = listOf(sleepDay(d, 27000, start = start, end = end))
        val samples = listOf(
            GarminBodyBatterySample(start - 120_000, 30), // last sample before bedtime, 2 min away
            GarminBodyBatterySample(start + 60_000, 31),
            GarminBodyBatterySample(end + 180_000, 85), // first sample after wake-up, 3 min away
        )
        val r = BodyBatteryAnalysis.analyze(listOf(bbDay(d)), samples, sleep, d, d.plusDays(1))
        assertEquals(30, r.sleepStartLevel)
        assertEquals(85, r.wakeLevel)
        assertEquals(55, r.recharge)
        assertFalse(r.incomplete)
    }

    @Test
    fun `a sample 16 minutes away is not used`() {
        val start = utcMs(before(1), 22, 0)
        val end = utcMs(d, 6, 0)
        val sleep = listOf(sleepDay(d, 27000, start = start, end = end))
        val samples = listOf(GarminBodyBatterySample(start - 16 * 60_000, 30), GarminBodyBatterySample(end + 16 * 60_000, 85))
        val r = BodyBatteryAnalysis.analyze(listOf(bbDay(d)), samples, sleep, d, d.plusDays(1))
        assertNull(r.sleepStartLevel)
        assertNull(r.wakeLevel)
        assertNull(r.recharge)
        val edge = BodyBatteryAnalysis.analyze(
            listOf(bbDay(d)), listOf(GarminBodyBatterySample(start - 15 * 60_000, 30), GarminBodyBatterySample(end + 15 * 60_000, 85)), sleep, d, d.plusDays(1),
        )
        assertEquals(55, edge.recharge)
    }

    @Test
    fun `today is incomplete and excluded from baselines`() {
        val days = (1..28).map { bbDay(before(it), charged = 60, drained = 50) } + bbDay(d, charged = 5, drained = 3)
        val r = BodyBatteryAnalysis.analyze(days, emptyList(), emptyList(), d, today = d)
        assertTrue(r.incomplete)
        assertTrue(r.chargedMetric.incomplete)
        assertEquals(5.0, r.chargedMetric.value)
        assertEquals(60.0, r.chargedMetric.primary.median)
        val finished = BodyBatteryAnalysis.analyze(days, emptyList(), emptyList(), d, today = d.plusDays(1))
        assertFalse(finished.incomplete)
    }

    @Test
    fun `Body Battery abstains below 20 of 28 days`() {
        val days = (1..19).map { bbDay(before(it)) } + bbDay(d)
        val r = BodyBatteryAnalysis.analyze(days, emptyList(), emptyList(), d, d.plusDays(1))
        assertEquals(BaselineStatus.INSUFFICIENT, r.chargedMetric.primary.status)
    }

    // ---------------------------------------------------------------- stress
    private val hourMs = 3_600_000L
    private fun stressDay(date: LocalDate, rest: Long?, low: Long?, medium: Long?, high: Long?, avg: Int? = 30) =
        GarminStressDay(date, avg, 80, rest, low, medium, high, 1L, 1L, null, source())

    @Test
    fun `high share by hand`() {
        // rest 8 h, low 6 h, medium 1.5 h, high 0.5 h -> measured 16 h, share 2 / 16 = 12.5 %
        val day = stressDay(d, 8 * hourMs, 6 * hourMs, 3 * hourMs / 2, hourMs / 2)
        val r = StressAnalysis.analyze(listOf(day), d, d.plusDays(1))
        assertEquals(12.5, r.highSharePercent!!, 1e-9)
        assertEquals(16.0, r.measuredHours!!, 1e-9)
        assertFalse(r.tooShort)
    }

    @Test
    fun `under 8 hours measured gives no share`() {
        val day = stressDay(d, 4 * hourMs, 2 * hourMs, hourMs, 59 * 60_000L)
        val r = StressAnalysis.analyze(listOf(day), d, d.plusDays(1))
        assertNull(r.highSharePercent)
        assertTrue(r.tooShort)
        val exactly = StressAnalysis.analyze(listOf(stressDay(d, 4 * hourMs, 2 * hourMs, hourMs, hourMs)), d, d.plusDays(1))
        assertEquals(25.0, exactly.highSharePercent!!, 1e-9)
    }

    @Test
    fun `zero durations do not divide by zero`() {
        val r = StressAnalysis.analyze(listOf(stressDay(d, 0, 0, 0, 0)), d, d.plusDays(1))
        assertNull(r.highSharePercent)
        assertTrue(r.tooShort)
    }

    @Test
    fun `missing durations give no share`() {
        val r = StressAnalysis.analyze(listOf(stressDay(d, null, 8 * hourMs, hourMs, hourMs)), d, d.plusDays(1))
        assertNull(r.highSharePercent)
        assertFalse(r.tooShort)
    }

    @Test
    fun `stress of today is flagged incomplete and not part of the baseline`() {
        val days = (1..28).map { stressDay(before(it), 8 * hourMs, 6 * hourMs, hourMs, hourMs, avg = 30) } + stressDay(d, hourMs, hourMs, hourMs, hourMs, avg = 90)
        val r = StressAnalysis.analyze(days, d, today = d)
        assertTrue(r.incomplete)
        assertEquals(30.0, r.avgLevelMetric.primary.median)
        assertEquals(90.0, r.avgLevelMetric.value)
    }

    // ---------------------------------------------------------------- readiness
    private fun fullInputs(withToday: Boolean = true, hrvDays: Int = 28, rhrDays: Int = 28, sleepDays: Int = 28, bbDays: Int = 28): RecoveryInputs {
        val hrv = (1..hrvDays).map { hrv(before(it), 50 + it % 2) } + if (withToday) listOf(hrv(d, 51)) else emptyList()
        val rhr = (1..rhrDays).map { rhr(before(it), 50 + it % 2) } + if (withToday) listOf(rhr(d, 50)) else emptyList()
        val sleep = (1..sleepDays).map { sleepDay(before(it), 27000 + it % 2 * 300, start = utcMs(before(it + 1), 22, 0), end = utcMs(before(it), 6, 0)) } +
            if (withToday) listOf(sleepDay(d, 27000, start = utcMs(before(1), 22, 0), end = utcMs(d, 6, 0))) else emptyList()
        val bb = (1..bbDays).map { before(it) } + if (withToday) listOf(d) else emptyList()
        val samples = bb.flatMap { day ->
            listOf(GarminBodyBatterySample(utcMs(day.minusDays(1), 22, 0), 30), GarminBodyBatterySample(utcMs(day, 6, 0), 80 + day.dayOfMonth % 2))
        }
        return RecoveryInputs(hrv, sleep, rhr, bb.map { bbDay(it) }, samples, emptyList(), null)
    }

    private fun report(inputs: RecoveryInputs) = RecoveryAnalyzer.analyze(inputs, d, d.plusDays(1), 450, brussels)

    @Test
    fun `readiness with 4 of 4 components`() {
        val r = report(fullInputs()).readiness
        assertEquals(4, r.summary.componentsWithData)
        assertTrue(r.summary.available)
        assertTrue(r.components.all { it.hasData })
    }

    @Test
    fun `readiness with 3 of 4 shows the summary`() {
        val r = report(fullInputs(rhrDays = 3)).readiness
        assertEquals(3, r.summary.componentsWithData)
        assertTrue(r.summary.available)
        assertFalse(r.components.first { it.kind == ReadinessKind.RESTING_HR }.hasData)
    }

    @Test
    fun `readiness with 2 of 4 abstains from a summary but keeps the components`() {
        val r = report(fullInputs(rhrDays = 3, hrvDays = 3)).readiness
        assertEquals(2, r.summary.componentsWithData)
        assertFalse(r.summary.available)
        assertEquals(4, r.components.size)
        assertTrue(r.components.count { it.hasData } == 2)
    }

    @Test
    fun `readiness with every component abstaining`() {
        val r = report(RecoveryInputs(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)).readiness
        assertEquals(0, r.summary.componentsWithData)
        assertFalse(r.summary.available)
        assertTrue(r.components.none { it.hasData })
    }

    @Test
    fun `readiness lists deviating components with direction`() {
        val inputs = fullInputs()
        val spike = inputs.restingHr.filter { it.calendarDate != d } + rhr(d, 62)
        val r = report(inputs.copy(restingHr = spike)).readiness
        assertEquals(1, r.summary.deviating.size)
        assertEquals(ReadinessKind.RESTING_HR, r.summary.deviating[0].kind)
        assertTrue(r.summary.deviating[0].deviation > 0)
        assertEquals(3, r.summary.within)
    }

    @Test
    fun `report carries the algorithm version and no numeric score field`() {
        val rep = report(fullInputs())
        assertEquals("recovery-v1", rep.algorithmVersion)
        val readinessFields = Readiness::class.java.declaredFields.map { it.name } + ReadinessSummary::class.java.declaredFields.map { it.name }
        assertTrue(readinessFields.none { it.contains("score", ignoreCase = true) }, readinessFields.toString())
        assertEquals(60, rep.hrvSeries.size)
        assertEquals(7, rep.recentNights.size)
        assertEquals(d, rep.recentNights.last().date)
    }
}
