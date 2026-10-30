package com.running.frontend

import com.running.analysis.PerformanceGlossary
import com.running.analysis.PerformancePageBuilder
import com.running.analysis.PerformanceReport
import com.running.analysis.PerformanceService
import com.running.analysis.PerformanceText
import com.running.analysis.PerformanceWindows
import com.running.analysis.RunPageBuilder
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder

/** Read only pages. Everything is computed on demand from stored Strava data; nothing is written. */
@Controller
class PerformanceController(private val service: PerformanceService) {

    @GetMapping("/performance")
    fun overview(model: Model, @RequestParam params: Map<String, String>): String {
        val zones = params["zones"]?.takeIf { it.isNotBlank() }
        val w = PerformanceWindows.from(params)
        val r = service.report(zones, w)
        model.addAttribute("title", "Prestaties")
        model.addAttribute("intro", PerformanceText.INTRO)
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("association", PerformanceText.ASSOCIATION)
        model.addAttribute("p", PerformancePageBuilder.build(r))
        model.addAttribute("g", PerformanceGlossary.terms)
        model.addAttribute("glossary", PerformanceGlossary.all)
        model.addAttribute("zonesParam", zones ?: "")
        model.addAttribute("w", w)
        model.addAttribute("wv", w.values)
        model.addAttribute("spec", PerformanceWindows.SPEC_BY_KEY)
        // Hidden fields per period form: every other choice the user made, so changing one period keeps the rest.
        val keep = w.changed.mapValues { it.value.toString() } + (zones?.let { mapOf("zones" to it) } ?: emptyMap())
        model.addAttribute("keep", keep)
        model.addAttribute("windowsChanged", w.changed.isNotEmpty())
        model.addAttribute("resetUrl", url(zones?.let { mapOf("zones" to it) } ?: emptyMap()))
        model.addAttribute("zoneUrls", listOf("" to "Standaard", "garmin" to "Garmin", "app" to "App-schatting").map { (k, label) ->
            Triple(k, label, url(w.changed.mapValues { it.value.toString() } + if (k.isEmpty()) emptyMap() else mapOf("zones" to k)))
        })
        model.addAttribute("version", r.algorithmVersion)
        return "performance"
    }

    @GetMapping("/performance/run/{id}")
    fun run(
        model: Model,
        @PathVariable id: Long,
        @RequestParam(required = false) zones: String?,
        @RequestParam(required = false) days: String?,
    ): String {
        val d = days?.trim()?.toIntOrNull()?.coerceIn(PerformanceService.MIN_TYPICAL_DAYS, PerformanceService.MAX_TYPICAL_DAYS)
            ?: PerformanceService.DEFAULT_TYPICAL_DAYS
        val r = service.runReport(id, zones, d) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Run niet gevonden")
        model.addAttribute("title", "Prestatie-analyse")
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("association", PerformanceText.ASSOCIATION)
        model.addAttribute("a", r.run)
        model.addAttribute("v", RunPageBuilder.build(r))
        model.addAttribute("g", PerformanceGlossary.terms)
        model.addAttribute("cmp", r.comparable)
        model.addAttribute("days", d)
        model.addAttribute("defaultDays", PerformanceService.DEFAULT_TYPICAL_DAYS)
        model.addAttribute("minDays", PerformanceService.MIN_TYPICAL_DAYS)
        model.addAttribute("maxDays", PerformanceService.MAX_TYPICAL_DAYS)
        model.addAttribute("zonesParam", zones ?: "")
        return "performance-run"
    }

    @GetMapping("/performance/data")
    @ResponseBody
    fun data(@RequestParam params: Map<String, String>): PerformanceReport =
        service.report(params["zones"]?.takeIf { it.isNotBlank() }, PerformanceWindows.from(params))

    private fun url(params: Map<String, String>): String {
        val b = UriComponentsBuilder.fromPath("/performance")
        params.forEach { (k, v) -> b.queryParam(k, v) }
        return b.encode().build().toUriString()
    }
}
