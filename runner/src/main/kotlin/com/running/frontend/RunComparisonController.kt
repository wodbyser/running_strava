package com.running.frontend

import com.running.analysis.PerfDates
import com.running.analysis.RunComparisonGlossary
import com.running.analysis.RunComparisonPage
import com.running.analysis.RunComparisonService
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Format
import com.running.strava.analysis.performance.RunComparison
import com.running.strava.analysis.speedMs
import com.running.strava.domain.Activity
import com.running.strava.domain.SessionType
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
            PickRow(a.id, a.name, date, meta, c.similar, a.id in shownIds, "${a.name} $date".lowercase(), day.toString(),
                c.sessionType.name.lowercase(), kindLabel, sport(a))
        }
        model.addAttribute("pick", rows)
        model.addAttribute("pickTotal", rows.size)
        model.addAttribute("pickFrom", rows.minOfOrNull { it.iso })
        model.addAttribute("pickTill", rows.maxOfOrNull { it.iso })
        model.addAttribute("kinds", SessionType.entries.map { t -> t.name.lowercase() to RunComparisonPage.typeLabel(t) }
            .map { (v, label) -> Option(v, label, rows.count { it.kind == v }) }.filter { it.count > 0 })
        model.addAttribute("sports", SPORTS.map { (v, label) -> Option(v, label, rows.count { it.sport == v }) }.filter { it.count > 0 })
        val glossary = RunComparisonGlossary.all
        model.addAttribute("glossary", glossary)
        model.addAttribute("g", RunComparisonGlossary.terms)
        return "compare-runs"
    }

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
