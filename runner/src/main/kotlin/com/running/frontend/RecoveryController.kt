package com.running.frontend

import com.running.analysis.RecoveryService
import com.running.analysis.RecoveryText
import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminStartOutcome
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.recovery.PeriodKind
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.spi.GarminSyncStatusRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read only views, plus one POST that starts a Garmin sync (same coordinator and lock as `POST /garmin/sync`). */
@Controller
class RecoveryController(
    private val recoveryService: RecoveryService,
    private val properties: GarminProperties,
    private val coordinator: GarminSyncCoordinator,
    private val statusRepository: GarminSyncStatusRepository,
) {
    private fun syncLine(): String {
        if (!properties.enabled) return "Garmin-synchronisatie staat uit (garmin.enabled=false)."
        if (!properties.configured) return "Geen Garmin-sleutel ingesteld: synchroniseren kan niet."
        val st = statusRepository.get()
        if (coordinator.isRunning) return "Synchronisatie bezig. Ververs de pagina over een minuut."
        val ok = st.lastSuccessAt?.atZone(RecoveryService.ZONE)?.format(DateTimeFormatter.ofPattern("d/MM/yyyy HH:mm"))
        val err = st.lastErrorCategory?.let { " Laatste fout: $it." } ?: ""
        return (ok?.let { "Laatste geslaagde synchronisatie: $it." } ?: "Nog niet gesynchroniseerd.") + err
    }

    @GetMapping("/recovery")
    fun recovery(
        model: Model,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false, defaultValue = "day") view: String,
    ): String {
        val (defaultDay, fellBack) = recoveryService.defaultDate()
        val day = date ?: defaultDay
        val kind = when (view) { "week" -> PeriodKind.WEEK; "month" -> PeriodKind.MONTH; else -> null }
        model.addAttribute("title", "Herstel")
        model.addAttribute("viewMode", view.takeIf { it == "week" || it == "month" } ?: "day")
        model.addAttribute("dateValue", day.toString())
        model.addAttribute("syncLine", syncLine())
        model.addAttribute("syncRunning", coordinator.isRunning)
        model.addAttribute("syncPossible", properties.enabled && properties.configured && !coordinator.isRunning)
        model.addAttribute("fellBackNote", if (date == null && fellBack) "Voor vandaag zijn er nog geen Garmin-gegevens (de synchronisatie liep nog niet). Toont de laatste dag met data: $day." else null)
        model.addAttribute("legend", RecoveryText.ESTIMATE_LEGEND)
        model.addAttribute("safety", RecoveryText.SAFETY)

        if (kind != null) {
            val period = recoveryService.periodReport(kind, day)
            model.addAttribute("period", RecoveryText.periodPage(period, properties.recovery.sleepTargetMinutes))
            model.addAttribute("legend", RecoveryText.ESTIMATE_LEGEND)
            model.addAttribute("pLabels", period.days.map { it.date.toString() })
            model.addAttribute("pHrv", period.days.map { it.hrv })
            model.addAttribute("pRhr", period.days.map { it.restingHr })
            model.addAttribute("pSleep", period.days.map { d -> d.sleepMinutes?.let { it / 60.0 } })
            model.addAttribute("pWake", period.days.map { it.bodyBatteryWake })
            fun median(kind: com.running.garmin.recovery.PeriodMetricKind) = period.metrics.first { it.kind == kind }.median
            model.addAttribute("pHrvMedian", median(com.running.garmin.recovery.PeriodMetricKind.HRV))
            model.addAttribute("pRhrMedian", median(com.running.garmin.recovery.PeriodMetricKind.RESTING_HR))
            model.addAttribute("pSleepMedian", median(com.running.garmin.recovery.PeriodMetricKind.SLEEP_DURATION)?.let { it / 60.0 })
            model.addAttribute("pWakeMedian", median(com.running.garmin.recovery.PeriodMetricKind.BODY_BATTERY_WAKE))
            model.addAttribute("pSleepTarget", properties.recovery.sleepTargetMinutes / 60.0)
            val step = if (kind == PeriodKind.WEEK) period.from.minusWeeks(1) else period.from.minusMonths(1)
            model.addAttribute("prevDate", step.toString())
            model.addAttribute("nextDate", period.fullPeriodEnd.plusDays(1).takeIf { !it.isAfter(recoveryService.today()) }?.toString())
            return "recovery"
        }

        val report = recoveryService.report(day)
        model.addAttribute("page", RecoveryText.page(report, properties.recovery.sleepTargetMinutes))
        model.addAttribute("hrvChart", report.hrvSeries.map { it.value })
        model.addAttribute("hrvChartLabels", report.hrvSeries.map { it.date.toString() })
        model.addAttribute("hrvMedian", report.hrv.metric.primary.median)
        model.addAttribute("rhrChart", report.restingHrSeries.map { it.value })
        model.addAttribute("rhrMedian", report.restingHr.metric?.primary?.median)
        return "recovery"
    }

    @GetMapping("/recovery/data")
    @ResponseBody
    fun data(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): RecoveryReport =
        recoveryService.report(date)

    @PostMapping("/recovery/garmin-update")
    fun update(ra: RedirectAttributes): String {
        val (msg, type) = when (coordinator.startSync()) {
            GarminStartOutcome.STARTED -> "Garmin-synchronisatie gestart op de achtergrond. Ververs de pagina over een minuut." to "success"
            GarminStartOutcome.BUSY -> "Er loopt al een synchronisatie. Er is geen nieuwe gestart." to "warning"
            GarminStartOutcome.DISABLED -> "Garmin-synchronisatie staat uit (garmin.enabled=false)." to "warning"
            GarminStartOutcome.NOT_CONFIGURED -> "Geen Garmin-sleutel ingesteld: er is niets gestart." to "error"
        }
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        return "redirect:/recovery"
    }
}
