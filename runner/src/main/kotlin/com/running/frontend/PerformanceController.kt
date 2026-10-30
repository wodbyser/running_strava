package com.running.frontend

import com.running.analysis.PerformanceService
import com.running.analysis.PerformanceReport
import com.running.analysis.PerformanceText
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import java.util.Locale

/** Read only pages. Everything is computed on demand from stored Strava data; nothing is written. */
@Controller
class PerformanceController(private val service: PerformanceService) {

    @GetMapping("/performance")
    fun overview(model: Model, @RequestParam(required = false) zones: String?): String {
        val r = service.report(zones)
        model.addAttribute("title", "Prestaties")
        model.addAttribute("intro", PerformanceText.INTRO)
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("sections", PerformanceText.sections(r))
        model.addAttribute("zonesParam", zones ?: "")
        model.addAttribute("version", r.algorithmVersion)
        val months = r.efMonthly.map { it.month }.distinct().sorted()
        model.addAttribute("efLabels", months)
        model.addAttribute("efSeries", Stratum.entries.map { s ->
            mapOf("label" to s.text, "data" to months.map { m -> r.efMonthly.firstOrNull { it.month == m && it.stratum == s }?.medianEf },
                "counts" to months.map { m -> r.efMonthly.firstOrNull { it.month == m && it.stratum == s }?.runs ?: 0 })
        })
        model.addAttribute("zoneLabels", r.weeks.map { it.label })
        model.addAttribute("zoneSeries", (0 until 5).map { z -> r.weeks.map { "%.1f".format(Locale.ROOT, it.minutes[z]).toDouble() } })
        model.addAttribute("recent", r.recent.map { it.a })
        return "performance"
    }

    @GetMapping("/performance/run/{id}")
    fun run(model: Model, @PathVariable id: Long, @RequestParam(required = false) zones: String?): String {
        val r = service.runReport(id, zones) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Run niet gevonden")
        val a = r.run
        model.addAttribute("title", "Prestatie-analyse")
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("association", PerformanceText.ASSOCIATION)
        model.addAttribute("a", a)
        model.addAttribute("typeText", PerformanceText.typeText(a.sessionType))
        model.addAttribute("pace", Format.pace(a.distanceMeters / a.movingSeconds))
        model.addAttribute("steadyText", a.steadyFailure?.let { "niet stabiel: ${it.text}" } ?: "stabiele run")
        model.addAttribute("coveragePct", "%.0f".format(Locale.ROOT, a.coverage * 100))
        model.addAttribute("efText", a.ef?.let { com.running.strava.analysis.EfficiencyFactor.format(it.ef) })
        model.addAttribute("efGapText", a.ef?.efGap?.let { com.running.strava.analysis.EfficiencyFactor.format(it) })
        model.addAttribute("gapPace", a.gap?.gapSpeed?.let { Format.pace(it) })
        model.addAttribute("zoneNote", r.zoneNote)
        model.addAttribute("zoneSeconds", r.zoneSeconds?.map { "%.0f".format(Locale.ROOT, it / 60) })
        val c = r.comparable
        model.addAttribute("cmp", c)
        model.addAttribute("cmpRows", c.matches.map { m ->
            mapOf(
                "id" to m.run.activityId, "date" to m.run.date.toString(), "km" to Format.km(m.run.distanceMeters),
                "pace" to Format.pace(m.run.speed), "gap" to (m.run.gapSpeed?.let { Format.pace(it) } ?: "-"),
                "hr" to Format.hr(m.run.avgHr), "ef" to com.running.strava.analysis.EfficiencyFactor.format(m.run.efWindow),
                "drift" to (m.run.driftPct?.let { "%+.1f %%".format(Locale.ROOT, it) } ?: "-"),
                "criteria" to Criterion.entries.joinToString(", ") { k ->
                    k.text + ": " + when (m.criteria[k]) { Agreement.AGREE -> "ja"; Agreement.DIFFER -> "nee"; else -> "onbekend" }
                },
            )
        })
        return "performance-run"
    }

    @GetMapping("/performance/data")
    @ResponseBody
    fun data(@RequestParam(required = false) zones: String?): PerformanceReport = service.report(zones)
}
