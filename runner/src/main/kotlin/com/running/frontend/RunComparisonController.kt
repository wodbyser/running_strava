package com.running.frontend

import com.running.analysis.PerfDates
import com.running.analysis.RunComparisonGlossary
import com.running.analysis.RunComparisonPage
import com.running.analysis.RunComparisonService
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.RunComparison
import com.running.strava.analysis.speedMs
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** "Trainingen vergelijken": 2 to 6 chosen runs side by side. Read only, computed on demand. */
@Controller
class RunComparisonController(private val service: RunComparisonService) {

    data class PickRow(
        val id: Long,
        val name: String,
        val date: String,
        val meta: String,
        val similar: Boolean,
        val checked: Boolean,
        /** Lower-case text the search box filters on. */
        val search: String,
    )

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
        model.addAttribute("pick", candidates.map { c ->
            val a = c.activity
            val date = PerfDates.day(ActivityTime.localDate(a))
            val meta = listOf(Format.km(a.distance), Format.pace(a.speedMs()), Format.hr(a.averageHeartrate), Format.duration(a.movingTime))
                .filter { it != "-" }.joinToString(" · ")
            PickRow(a.id, a.name, date, meta, c.similar, a.id in shownIds, "${a.name} $date".lowercase())
        })
        val glossary = RunComparisonGlossary.all
        model.addAttribute("glossary", glossary)
        model.addAttribute("g", RunComparisonGlossary.terms)
        return "compare-runs"
    }
}
