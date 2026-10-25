package com.running.analysis

import com.running.config.GarminProperties
import com.running.garmin.recovery.PeriodAnalysis
import com.running.garmin.recovery.PeriodKind
import com.running.garmin.recovery.PeriodReport
import com.running.garmin.recovery.RecoveryAnalyzer
import com.running.garmin.recovery.RecoveryInputs
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.spi.GarminRepository
import com.running.strava.db.StravaTokenRepositoryImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Loads stored Garmin rows and runs the pure recovery analysis. Nothing derived is stored. Logs counts only. */
@Service
class RecoveryService(
    private val repository: GarminRepository,
    private val tokenRepository: StravaTokenRepositoryImpl,
    private val properties: GarminProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** Today, or the latest day with an HRV or sleep row in the last 14 days when today has none yet. Second value: fell back. */
    fun defaultDate(): Pair<LocalDate, Boolean> {
        val today = today()
        val from = today.minusDays(14)
        val dates = (repository.findHrv(from, today).map { it.calendarDate } + repository.findSleep(from, today).map { it.calendarDate }).toSet()
        return when {
            today in dates || dates.isEmpty() -> today to false
            else -> dates.max() to true
        }
    }

    private fun load(from: LocalDate, to: LocalDate) = RecoveryInputs(
        hrv = repository.findHrv(from, to),
        sleep = repository.findSleep(from, to),
        restingHr = repository.findRestingHr(from, to),
        bodyBattery = repository.findBodyBattery(from, to),
        bodyBatterySamples = repository.findBodyBatterySamples(
            from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        ),
        stress = repository.findStress(from, to),
        manualRestingHr = tokenRepository.getRestingHr(),
    )

    private fun logCounts(version: String, view: String, i: RecoveryInputs) = log.info(
        "Recovery computed {} {}: hrv={} sleep={} restingHr={} bodyBattery={} samples={} stress={}",
        version, view, i.hrv.size, i.sleep.size, i.restingHr.size, i.bodyBattery.size, i.bodyBatterySamples.size, i.stress.size,
    )

    fun report(date: LocalDate? = null): RecoveryReport {
        val today = today()
        val day = date ?: today
        val inputs = load(day.minusDays(RecoveryAnalyzer.HISTORY_DAYS.toLong() + 1), day)
        val report = RecoveryAnalyzer.analyze(inputs, day, today, properties.recovery.sleepTargetMinutes, ZONE)
        logCounts(report.algorithmVersion, "day", inputs)
        return report
    }

    fun periodReport(kind: PeriodKind, date: LocalDate): PeriodReport {
        val today = today()
        val (from, to) = PeriodAnalysis.bounds(kind, date)
        val inputs = load(minOf(PeriodAnalysis.previousStart(kind, from).minusDays(1), from.minusDays(30)), minOf(to, today))
        val report = PeriodAnalysis.analyze(inputs, kind, date, today, properties.recovery.sleepTargetMinutes)
        logCounts(report.algorithmVersion, kind.name.lowercase(), inputs)
        return report
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Brussels")
    }
}
