package com.running.frontend

import com.running.analysis.PerfDates
import com.running.analysis.RecoveryGlossary
import com.running.analysis.RecoveryService
import com.running.analysis.RecoveryText
import com.running.analysis.RecoveryViewBuilder
import com.running.analysis.RecoveryWindowSettings
import org.springframework.web.util.UriComponentsBuilder
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
        @RequestParam params: Map<String, String>,
    ): String {
        val w = RecoveryWindowSettings.from(params)
        val changed = RecoveryWindowSettings.changed(w).mapValues { it.value.toString() }
        val (defaultDay, fellBack) = recoveryService.defaultDate()
        val day = date ?: defaultDay
        val kind = when (view) { "week" -> PeriodKind.WEEK; "month" -> PeriodKind.MONTH; else -> null }
        model.addAttribute("title", "Herstel")
        val viewMode = view.takeIf { it == "week" || it == "month" } ?: "day"
        model.addAttribute("viewMode", viewMode)
        model.addAttribute("dateValue", day.toString())
        fun url(v: String, d: String?, windows: Map<String, String> = changed): String {
            val b = UriComponentsBuilder.fromPath("/recovery").queryParam("view", v)
            d?.let { b.queryParam("date", it) }
            windows.forEach { (k, x) -> b.queryParam(k, x) }
            return b.encode().build().toUriString()
        }
        model.addAttribute("viewUrls", listOf("day" to "Dag", "week" to "Week", "month" to "Maand").map { (k, l) -> Triple(k, l, url(k, day.toString())) })
        model.addAttribute("formAction", "/recovery")
        model.addAttribute("wv", RecoveryWindowSettings.values(w))
        model.addAttribute("spec", RecoveryWindowSettings.SPEC_BY_KEY)
        model.addAttribute("keep", changed + mapOf("view" to viewMode, "date" to day.toString()))
        model.addAttribute("windowKeep", changed)
        model.addAttribute("windowsChanged", changed.isNotEmpty())
        model.addAttribute("resetUrl", url(viewMode, day.toString(), emptyMap()))
        model.addAttribute("rw", w)
        model.addAttribute("syncLine", syncLine())
        model.addAttribute("syncRunning", coordinator.isRunning)
        model.addAttribute("syncPossible", properties.enabled && properties.configured && !coordinator.isRunning)
        model.addAttribute("fellBackNote", if (date == null && fellBack) "Voor vandaag zijn er nog geen Garmin-gegevens (de synchronisatie liep nog niet). Je ziet de laatste dag met data: ${PerfDates.dayLong(day)}." else null)
        model.addAttribute("legend", RecoveryText.ESTIMATE_LEGEND)
        model.addAttribute("safety", RecoveryText.SAFETY)
        model.addAttribute("g", RecoveryGlossary.terms)
        model.addAttribute("glossary", RecoveryGlossary.all)

        if (kind != null) {
            val period = recoveryService.periodReport(kind, day, w)
            model.addAttribute("period", RecoveryText.periodPage(period, properties.recovery.sleepTargetMinutes))
            model.addAttribute("v", RecoveryViewBuilder.period(period, properties.recovery.sleepTargetMinutes))
            val step = if (kind == PeriodKind.WEEK) period.from.minusWeeks(1) else period.from.minusMonths(1)
            model.addAttribute("prevUrl", url(viewMode, step.toString()))
            model.addAttribute("nextUrl", period.fullPeriodEnd.plusDays(1).takeIf { !it.isAfter(recoveryService.today()) }?.let { url(viewMode, it.toString()) })
            return "recovery"
        }

        val report = recoveryService.report(day, w)
        model.addAttribute("page", RecoveryText.page(report, properties.recovery.sleepTargetMinutes))
        model.addAttribute("v", RecoveryViewBuilder.day(report, properties.recovery.sleepTargetMinutes))
        model.addAttribute("prevUrl", url(viewMode, day.minusDays(1).toString()))
        model.addAttribute("nextUrl", day.plusDays(1).takeIf { !it.isAfter(recoveryService.today()) }?.let { url(viewMode, it.toString()) })
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
