package com.running.analysis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.running.config.GarminProperties
import com.running.garmin.domain.*
import com.running.garmin.recovery.RecoveryAnalyzer
import com.running.garmin.recovery.RecoveryInputs
import com.running.garmin.spi.GarminRepository
import com.running.strava.db.StravaTokenRepositoryImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecoveryTest {
    private val day = LocalDate.of(2026, 1, 15)
    private val src = GarminSource("test", null, "test")
    private val marker = 4321 // a value that must never appear in a log line

    private class StubRepo(
        val hrv: List<GarminHrvDay> = emptyList(),
        val sleep: List<GarminSleepDay> = emptyList(),
        val rhr: List<GarminRestingHrDay> = emptyList(),
    ) : GarminRepository by mock(GarminRepository::class.java) {
        override fun findHrv(from: LocalDate, to: LocalDate) = hrv.filter { it.calendarDate in from..to }
        override fun findSleep(from: LocalDate, to: LocalDate) = sleep.filter { it.calendarDate in from..to }
        override fun findRestingHr(from: LocalDate, to: LocalDate) = rhr.filter { it.calendarDate in from..to }
        override fun findBodyBattery(from: LocalDate, to: LocalDate) = emptyList<GarminBodyBatteryDay>()
        override fun findBodyBatterySamples(fromMs: Long, toMs: Long) = emptyList<GarminBodyBatterySample>()
        override fun findStress(from: LocalDate, to: LocalDate) = emptyList<GarminStressDay>()
    }

    private fun tokens(manual: Int?): StravaTokenRepositoryImpl {
        val m = mock(StravaTokenRepositoryImpl::class.java)
        `when`(m.getRestingHr()).thenReturn(manual)
        return m
    }

    private fun service(repo: GarminRepository, manual: Int? = null) =
        RecoveryService(repo, tokens(manual), GarminProperties(), Clock.fixed(Instant.parse("2026-01-16T10:00:00Z"), ZoneOffset.UTC))

    private fun hrvDays(value: (Int) -> Int) = (0..28).map {
        val v = value(it)
        GarminHrvDay(day.minusDays(it.toLong()), v, v, null, 40, 45, 60, null, "BALANCED", null, src)
    }

    private fun report(repo: GarminRepository = StubRepo(hrv = hrvDays { 50 + it % 2 }), manual: Int? = null) = service(repo, manual).report(day)

    @Test
    fun `service reads the rows, uses Brussels today and returns the version`() {
        val r = report()
        assertEquals(day, r.date)
        assertFalse(r.isToday)
        assertEquals("recovery-v1", r.algorithmVersion)
        assertTrue(r.hrv.hasNight)
        assertEquals(service(StubRepo()).today(), LocalDate.of(2026, 1, 16))
    }

    @Test
    fun `manual resting HR is the fallback`() {
        val r = report(manual = 49)
        assertEquals(49, r.restingHr.value)
        assertEquals(com.running.garmin.recovery.RestingHrSource.MANUAL, r.restingHr.source)
    }

    @Test
    fun `computation logs counts and the version but no health value`() {
        val logger = LoggerFactory.getLogger(RecoveryService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val old = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val r = report(StubRepo(hrv = hrvDays { marker }), manual = 4322)
            assertEquals(marker, r.hrv.lastNightAvg)
        } finally {
            logger.detachAppender(appender)
            logger.level = old
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.contains("recovery-v1") })
        assertTrue(lines.none { it.contains(marker.toString()) || it.contains("4322") }, lines.toString())
    }

    private val forbidden = listOf("beter", "slechter", "veilig", "klaar om te trainen", "gezond")

    private fun allText(r: com.running.garmin.recovery.RecoveryReport): String {
        val p = RecoveryText.page(r, 450)
        val h = p.hero
        return (listOf(p.summary, p.safety, h.headline) + h.conclusion + h.method + h.components.flatMap { listOf(it.norm, it.deviation, it.aria) } + p.sections.flatMap { s -> s.notes + s.rows.flatMap { listOf(it.label, it.value, it.reference, it.deviation) } } + p.recentNights)
            .joinToString("\n").lowercase()
    }

    @Test
    fun `wording never judges and always carries the safety line`() {
        val high = report(StubRepo(hrv = hrvDays { if (it == 0) 80 else 50 + it % 2 }))
        val low = report(StubRepo(hrv = hrvDays { if (it == 0) 20 else 50 + it % 2 }))
        val textHigh = allText(high)
        val textLow = allText(low)
        assertTrue("hoger dan jouw eigen norm" in textHigh)
        assertTrue("lager dan jouw eigen norm" in textLow)
        forbidden.forEach { assertFalse(it in textHigh, it); assertFalse(it in textLow, it) }
        assertTrue(RecoveryText.page(high, 450).safety.startsWith("Geen medische beoordeling."))
        assertTrue(RecoveryText.promptBlock(high, 450).contains(RecoveryText.SAFETY))
    }

    @Test
    fun `abstaining metrics say so with counts and use the not-available label`() {
        val r = report(StubRepo())
        val p = RecoveryText.page(r, 450)
        assertTrue(p.summary.startsWith("Onvoldoende data voor een samenvatting"))
        val hrvRows = p.sections.first { it.id == "hrv" }.rows
        assertTrue(hrvRows.any { it.tag == RecoveryTag.NA })
        assertTrue(allText(r).contains("onvoldoende data"))
    }

    @Test
    fun `summary text names the deviating component and direction`() {
        val inputs = RecoveryInputs(
            hrvDays { 50 + it % 2 }, emptyList(),
            (0..28).map { GarminRestingHrDay(day.minusDays(it.toLong()), if (it == 0) 65 else 50 + it % 2, null, null, src) },
            emptyList(), emptyList(), emptyList(), null,
        )
        val sleep = (0..28).map { GarminSleepDay(day.minusDays(it.toLong()), 27000 + it % 2 * 300, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, src) }
        val r = RecoveryAnalyzer.analyze(inputs.copy(sleep = sleep), day, day.plusDays(1), 450, RecoveryService.ZONE)
        // HRV and RHR have data; sleep too: 3 components
        assertTrue(r.readiness.summary.available)
        val text = RecoveryText.summaryText(r)
        assertTrue("rusthartslag hoger dan jouw eigen norm" in text, text)
    }

    @Test
    fun `prompt block carries labels, version and no identifiers`() {
        val block = RecoveryText.promptBlock(report(), 450)
        assertTrue(block.startsWith("<herstel algoritme=\"recovery-v1\""))
        assertTrue(block.contains("[gemeten]"))
        assertTrue(block.contains("[app-schatting]"))
        assertTrue(block.trimEnd().endsWith("</herstel>"))
    }

    @Test
    fun `default date falls back to the latest day with data`() {
        val (d, fellBack) = service(StubRepo(hrv = hrvDays { 50 })).defaultDate() // clock is 2026-01-16, latest row 2026-01-15
        assertEquals(day, d)
        assertTrue(fellBack)
        val (d2, fb2) = service(StubRepo()).defaultDate()
        assertEquals(LocalDate.of(2026, 1, 16), d2)
        assertFalse(fb2)
    }

    @Test
    fun `period page wording is neutral and shows counts`() {
        val page = RecoveryText.periodPage(service(StubRepo(hrv = hrvDays { if (it < 7) 60 else 50 })).periodReport(com.running.garmin.recovery.PeriodKind.WEEK, day), 450)
        val text = (page.rows.flatMap { listOf(it.label, it.value, it.reference, it.deviation) } + page.notes + page.subtitle).joinToString("\n").lowercase()
        assertTrue(page.rows.any { it.reference.contains("n=") })
        forbidden.forEach { assertFalse(it in text, it) }
    }

    private fun withRhr(today: Int?, sleepToo: Boolean = true): com.running.garmin.recovery.RecoveryReport {
        val rhr = (0..28).map { GarminRestingHrDay(day.minusDays(it.toLong()), if (it == 0) today else 50 + it % 2, null, null, src) }.filter { it.restingHr != null }
        val sleep = if (sleepToo) (0..28).map { GarminSleepDay(day.minusDays(it.toLong()), 27000 + it % 2 * 300, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, src) } else emptyList()
        val inputs = RecoveryInputs(hrvDays { 50 + it % 2 }.filter { it.calendarDate != day } + hrvDays { 50 }.first(), sleep, rhr, emptyList(), emptyList(), emptyList(), null)
        return RecoveryAnalyzer.analyze(inputs, day, day.plusDays(1), 450, RecoveryService.ZONE)
    }

    @Test
    fun `hero levels follow the documented rules`() {
        assertEquals(HeroLevel.WITHIN, RecoveryText.heroLevel(withRhr(51)))
        assertEquals(HeroLevel.SLIGHT, RecoveryText.heroLevel(withRhr(52))) // 1.5 SD, one deviating component
        assertEquals(HeroLevel.CLEAR, RecoveryText.heroLevel(withRhr(65))) // strong
        assertEquals(HeroLevel.INSUFFICIENT, RecoveryText.heroLevel(withRhr(51, sleepToo = false))) // 2 of 4 have data
    }

    @Test
    fun `hero spectrum position is 0 to 100 on minus 3 to plus 3 SD and clamps`() {
        val slight = RecoveryText.hero(withRhr(52), 450).components.first { it.label == "Rusthartslag" }
        assertEquals(75.0, slight.position!!, 1e-9)
        assertEquals("hero-warn", slight.css)
        val strong = RecoveryText.hero(withRhr(90), 450).components.first { it.label == "Rusthartslag" }
        assertEquals(100.0, strong.position!!, 1e-9)
        assertEquals("hero-alert", strong.css)
        val empty = RecoveryText.hero(withRhr(51, sleepToo = false), 450).components.first { it.label == "Slaapduur" }
        assertNull(empty.position)
        assertEquals("hero-na", empty.css)
    }

    @Test
    fun `hero conclusion names the deviating component and carries no numeric score`() {
        val hero = RecoveryText.hero(withRhr(52), 450)
        val text = hero.conclusion.joinToString(" ")
        assertTrue("Afwijkend van je eigen norm: rusthartslag hoger dan jouw eigen norm" in text, text)
        assertTrue("Binnen je eigen norm: HRV, slaapduur" in text, text)
        assertTrue("geen diagnose" in text)
        assertTrue(hero.components.none { it.value.matches(Regex("\\d+ ?/ ?100")) })
        forbidden.forEach { assertFalse(it in text.lowercase(), it) }
    }

    @Test
    fun `hero for missing data explains what is missing`() {
        val hero = RecoveryText.hero(report(StubRepo()), 450)
        assertEquals(HeroLevel.INSUFFICIENT, hero.level)
        assertTrue(hero.conclusion.first().startsWith("Er is te weinig data voor een conclusie"))
    }
}
