package com.running.frontend

import com.running.analysis.PerformanceGlossary
import com.running.analysis.PerformancePageBuilder
import com.running.analysis.PerformanceReport
import com.running.analysis.PerformanceService
import com.running.analysis.PerformanceText
import com.running.analysis.RunPageBuilder
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException

/** Read only pages. Everything is computed on demand from stored Strava data; nothing is written. */
@Controller
class PerformanceController(private val service: PerformanceService) {

    @GetMapping("/performance")
    fun overview(
        model: Model,
        @RequestParam(required = false) zones: String?,
        @RequestParam(required = false) short: Int?,
        @RequestParam(required = false) long: Int?,
    ): String {
        val (s, l) = windows(short, long)
        val r = service.report(zones, s, l)
        model.addAttribute("title", "Prestaties")
        model.addAttribute("intro", PerformanceText.INTRO)
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("association", PerformanceText.ASSOCIATION)
        model.addAttribute("p", PerformancePageBuilder.build(r))
        model.addAttribute("g", PerformanceGlossary.terms)
        model.addAttribute("glossary", PerformanceGlossary.all)
        model.addAttribute("zonesParam", zones ?: "")
        model.addAttribute("shortDays", r.efWindows.first())
        model.addAttribute("longDays", r.efWindows.last())
        model.addAttribute("minDays", PerformanceService.MIN_WINDOW_DAYS)
        model.addAttribute("maxDays", PerformanceService.MAX_WINDOW_DAYS)
        model.addAttribute("version", r.algorithmVersion)
        return "performance"
    }

    @GetMapping("/performance/run/{id}")
    fun run(model: Model, @PathVariable id: Long, @RequestParam(required = false) zones: String?): String {
        val r = service.runReport(id, zones) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Run niet gevonden")
        model.addAttribute("title", "Prestatie-analyse")
        model.addAttribute("safety", PerformanceText.SAFETY)
        model.addAttribute("association", PerformanceText.ASSOCIATION)
        model.addAttribute("a", r.run)
        model.addAttribute("v", RunPageBuilder.build(r))
        model.addAttribute("g", PerformanceGlossary.terms)
        model.addAttribute("cmp", r.comparable)
        return "performance-run"
    }

    @GetMapping("/performance/data")
    @ResponseBody
    fun data(
        @RequestParam(required = false) zones: String?,
        @RequestParam(required = false) short: Int?,
        @RequestParam(required = false) long: Int?,
    ): PerformanceReport {
        val (s, l) = windows(short, long)
        return service.report(zones, s, l)
    }

    /** Clamped comparison windows; the shorter one first. */
    private fun windows(short: Int?, long: Int?): Pair<Int, Int> {
        fun clamp(v: Int?, d: Int) = (v ?: d).coerceIn(PerformanceService.MIN_WINDOW_DAYS, PerformanceService.MAX_WINDOW_DAYS)
        val a = clamp(short, PerformanceService.DEFAULT_SHORT_DAYS)
        val b = clamp(long, PerformanceService.DEFAULT_LONG_DAYS)
        return minOf(a, b) to maxOf(a, b)
    }
}
