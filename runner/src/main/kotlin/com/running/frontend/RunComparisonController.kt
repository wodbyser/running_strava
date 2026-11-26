package com.running.frontend

import com.running.ai.AiProperties
import com.running.analysis.AiAnalysisService
import com.running.analysis.PerfDates
import com.running.analysis.RunComparisonGlossary
import com.running.analysis.RunComparisonPage
import com.running.analysis.RunComparisonService
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.RunComparison
import com.running.strava.analysis.speedMs
import com.running.strava.domain.Activity
import java.util.Locale
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** "Trainingen vergelijken": 2 to 6 chosen runs side by side. Read only, computed on demand. */
@Controller
class RunComparisonController(
    private val service: RunComparisonService,
    private val aiAnalysisService: AiAnalysisService,
    private val aiProperties: AiProperties,
) {

    data class PickRow(
        val id: Long,
        val name: String,
        val date: String,
        val meta: String,
        val km: Double,
        val similar: Boolean,
        val checked: Boolean,
        /** Lower-case text the search box filters on. */
        val search: String,
        /** ISO date for the date filter (never shown). */
        val iso: String,
        val kind: String,
        val kindLabel: String,
        val sport: String,
    )

    data class Option(val value: String, val label: String, val count: Int)

    @GetMapping("/compare-runs")
    fun compareRuns(model: Model, @RequestParam(required = false) ids: List<String>?): String {
        val asked = RunComparisonService.parseIds(ids)
        val chosen = service.normalize(asked)
        val comparison = service.compare(chosen)
        val shownIds = comparison?.entries?.map { it.analysis.id } ?: chosen
        val candidates = service.candidates(shownIds)
        val known = candidates.map { it.activity.id }.toSet()

        val view = comparison?.let { RunComparisonPage.build(it, shownIds) }
        model.addAttribute("title", "Trainingen vergelijken")
        model.addAttribute("v", view)
        model.addAttribute("chart", view?.chart)
        model.addAttribute("selectedCount", shownIds.count { it in known })
        model.addAttribute("maxRuns", RunComparison.MAX_RUNS)
        model.addAttribute("minRuns", RunComparison.MIN_RUNS)
        model.addAttribute("tooMany", asked.size > RunComparison.MAX_RUNS)
        model.addAttribute("unknown", chosen.count { it !in known })
        model.addAttribute("hasSimilar", candidates.any { it.similar })
        val rows = candidates.map { c ->
            val a = c.activity
            val day = ActivityTime.localDate(a)
            val date = PerfDates.day(day)
            val meta = listOf(Format.km(a.distance), Format.pace(a.speedMs()), Format.hr(a.averageHeartrate), Format.duration(a.movingTime))
                .filter { it != "-" }.joinToString(" · ")
            val kindLabel = RunComparisonPage.typeLabel(c.sessionType)
            PickRow(a.id, a.name, date, meta, a.distance / 1000.0, c.similar, a.id in shownIds, "${a.name} $date".lowercase(), day.toString(),
                c.sessionType.name.lowercase(), kindLabel, sport(a))
        }
        model.addAttribute("pick", rows)
        model.addAttribute("pickTotal", rows.size)
        model.addAttribute("pickFrom", rows.minOfOrNull { it.iso })
        model.addAttribute("pickTill", rows.maxOfOrNull { it.iso })
        model.addAttribute("sports", SPORTS.map { (v, label) -> Option(v, label, rows.count { it.sport == v }) }.filter { it.count > 0 })
        // Same distance band as the comparable-run finder, without a training-kind filter: the list stays comparable.
        model.addAttribute("distanceHint", chosen.firstOrNull()?.let { id ->
            rows.firstOrNull { it.id == id }?.km?.let { km -> "%.1f–%.1f".format(Locale.ROOT, km * 0.8, km * 1.25) }
        })
        val glossary = RunComparisonGlossary.all
        model.addAttribute("glossary", glossary)
        model.addAttribute("g", RunComparisonGlossary.terms)
        model.addAttribute("promptUrl", view?.let { "/compare-runs/prompt?ids=" + shownIds.joinToString(",") })
        return "compare-runs"
    }

    @GetMapping("/compare-runs/prompt")
    fun compareRunsPrompt(model: Model, @RequestParam(required = false) ids: List<String>?): String {
        val wanted = service.normalize(RunComparisonService.parseIds(ids))
        val prompt = aiAnalysisService.buildRunComparisonPrompt(wanted) ?: return redirectToComparison(wanted)
        model.addAttribute("title", "Vergelijk-prompt trainingen")
        model.addAttribute(
            "promptIntro",
            "Deze prompt bevat de gekozen trainingen met al hun rondes, hun berekende waarden en de vergelijking die de app zelf maakte.",
        )
        model.addAttribute(
            "promptNote",
            if (aiProperties.isConfigured) {
                "De app stuurt niets door tenzij je hieronder op 'Vraag het aan de AI' klikt. " +
                    "Een AI-antwoord blijft een interpretatie, geen meting."
            } else {
                "De app stuurt niets door: je plakt de tekst zelf in een AI-chat. " +
                    "Een AI-antwoord blijft een interpretatie, geen meting."
            },
        )
        model.addAttribute("prompt", prompt)
        model.addAttribute("backUrl", "/compare-runs" + query(wanted))
        model.addAttribute("downloadUrl", "/compare-runs/prompt/download" + query(wanted))
        return "ai"
    }

    @GetMapping("/compare-runs/prompt/download")
    fun compareRunsPromptDownload(@RequestParam(required = false) ids: List<String>?): ResponseEntity<ByteArray> {
        val wanted = service.normalize(RunComparisonService.parseIds(ids))
        val prompt = aiAnalysisService.buildRunComparisonPrompt(wanted) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trainingen-vergelijken-prompt.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(prompt.toByteArray())
    }

    private fun redirectToComparison(ids: List<Long>) = "redirect:/compare-runs" + query(ids)

    private fun query(ids: List<Long>) = if (ids.isEmpty()) "" else "?ids=" + ids.joinToString(",")

    /** Where the run took place: trail and treadmill/virtual runs are a different kind of running than the road. */
    private fun sport(a: Activity) = when {
        a.isTrainer || a.type == "VirtualRun" -> "loopband"
        a.type == "TrailRun" -> "trail"
        else -> "weg"
    }

    companion object {
        private val SPORTS = listOf("weg" to "Weg", "trail" to "Trail", "loopband" to "Loopband of virtueel")
    }
}
