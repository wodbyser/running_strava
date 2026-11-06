package com.running.frontend

import com.running.analysis.HistoryPage
import com.running.analysis.HistoryPageBuilder
import com.running.analysis.PerfDates
import com.running.analysis.RaceFmt
import com.running.analysis.RaceGlossary
import com.running.analysis.RaceHistoryReport
import com.running.analysis.RaceOverview
import com.running.analysis.RacePage
import com.running.analysis.RacePageBuilder
import com.running.analysis.RaceService
import com.running.analysis.RaceText
import com.running.analysis.RaceWindowSettings
import com.running.race.RaceAlgorithm
import com.running.race.RaceGoalRepository
import com.running.race.RaceWindows
import com.running.strava.usecase.sync.SyncStravaData
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Race pages. The pages and the JSON are read only; goals are saved and deleted, and the Strava re-read is started, with POST only.
 */
@Controller
class RaceController(
    private val service: RaceService,
    private val goals: RaceGoalRepository,
    private val syncStravaData: SyncStravaData,
) {
    data class RaceData(val overview: RaceOverview, val history: RaceHistoryReport)

    @GetMapping("/race")
    fun overview(model: Model, @RequestParam params: Map<String, String>): String {
        val w = RaceWindowSettings.from(params)
        val o = service.overview(w)
        val page: RacePage = RacePageBuilder.build(o)
        model.addAttribute("title", "Wedstrijd")
        model.addAttribute("intro", RaceText.INTRO)
        model.addAttribute("safety", RaceText.SAFETY)
        model.addAttribute("predictionNote", RaceText.PREDICTION_NOTE)
        model.addAttribute("courseNote", RaceText.COURSE_NOTE)
        model.addAttribute("calmNote", RaceText.CALM_NOTE)
        model.addAttribute("candidateNote", RaceText.CANDIDATE_NOTE)
        model.addAttribute("p", page)
        model.addAttribute("g", RaceGlossary.terms)
        model.addAttribute("glossary", RaceGlossary.forPage("/race"))
        model.addAttribute("today", o.today.toString())
        model.addAttribute("todayText", PerfDates.dayLong(o.today))
        windowModel(model, w, "/race")
        model.addAttribute("otherPage", url("/race/history", w))
        model.addAttribute("version", o.algorithmVersion)
        return "race"
    }

    @GetMapping("/race/history")
    fun history(model: Model, @RequestParam params: Map<String, String>): String {
        val w = RaceWindowSettings.from(params)
        val r = service.history(w)
        val page: HistoryPage = HistoryPageBuilder.build(r)
        model.addAttribute("title", "Terugblik op wedstrijden")
        model.addAttribute("intro", RaceText.INTRO_HISTORY)
        model.addAttribute("safety", RaceText.SAFETY)
        model.addAttribute("association", RaceText.ASSOCIATION)
        model.addAttribute("p", page)
        model.addAttribute("g", RaceGlossary.terms)
        model.addAttribute("glossary", RaceGlossary.forPage("/race/history"))
        windowModel(model, w, "/race/history")
        model.addAttribute("otherPage", url("/race", w))
        model.addAttribute("version", r.algorithmVersion)
        return "race-history"
    }

    @GetMapping("/race/data")
    @ResponseBody
    fun data(@RequestParam params: Map<String, String>): RaceData {
        val w = RaceWindowSettings.from(params)
        return RaceData(service.overview(w), service.history(w))
    }

    @PostMapping("/race/goal")
    fun addGoal(
        @RequestParam distance: String,
        @RequestParam(required = false) custom: String?,
        @RequestParam date: String,
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false) name: String?,
        ra: RedirectAttributes,
    ): String {
        val meters = if (distance == "custom") custom?.trim()?.replace(',', '.')?.toDoubleOrNull()?.times(1000) else distance.toDoubleOrNull()
        val day = try { LocalDate.parse(date.trim()) } catch (e: DateTimeParseException) { null }
        val seconds = RaceFmt.parseTime(target)
        val error = when {
            meters == null || meters < 1000 || meters > 100_000 -> "Kies een afstand tussen 1 en 100 km."
            day == null -> "Vul een geldige datum in."
            !target.isNullOrBlank() && seconds == null -> "De doeltijd begrijp ik niet. Typ bijvoorbeeld 19:00 of 1:45:00."
            goals.findAll().size >= MAX_GOALS -> "Er staan al $MAX_GOALS doelen. Verwijder er eerst een."
            else -> null
        }
        if (error != null) {
            ra.addFlashAttribute("flashMessage", error)
            ra.addFlashAttribute("flashType", "error")
        } else {
            goals.save(meters!!, day!!, seconds, name)
            ra.addFlashAttribute("flashMessage", "Doel opgeslagen.")
            ra.addFlashAttribute("flashType", "success")
        }
        return "redirect:/race#doelen"
    }

    @PostMapping("/race/goal/{id}/delete")
    fun deleteGoal(@PathVariable id: Long, ra: RedirectAttributes): String {
        goals.delete(id)
        ra.addFlashAttribute("flashMessage", "Doel verwijderd.")
        ra.addFlashAttribute("flashType", "success")
        return "redirect:/race#doelen"
    }

    /** Re-reads every Strava activity summary once, so race markings on old activities arrive (design P5-6). */
    @PostMapping("/race/strava-recheck")
    fun recheck(ra: RedirectAttributes): String {
        val result = syncStravaData.executeFull()
        ra.addFlashAttribute(
            "flashMessage",
            "Markeringen opnieuw opgehaald: ${result.activitiesFetched} activiteiten bekeken, ${result.updatedActivities} bijgewerkt, ${result.newActivities} nieuw" +
                (if (result.errors.isNotEmpty()) ", ${result.errors.size} fout(en)" else "") + ".",
        )
        ra.addFlashAttribute("flashType", if (result.errors.isEmpty()) "success" else "warning")
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/race#kandidaten"
    }

    private fun windowModel(model: Model, w: RaceWindows, path: String) {
        val changed = RaceWindowSettings.changed(w)
        model.addAttribute("spec", RaceWindowSettings.SPEC_BY_KEY)
        model.addAttribute("wv", RaceWindowSettings.values(w))
        model.addAttribute("keep", changed.mapValues { it.value.toString() })
        model.addAttribute("windowsChanged", changed.isNotEmpty())
        model.addAttribute("resetUrl", path)
        model.addAttribute("formAction", path)
    }

    private fun url(path: String, w: RaceWindows): String {
        val b = UriComponentsBuilder.fromPath(path)
        RaceWindowSettings.changed(w).forEach { (k, v) -> b.queryParam(k, v) }
        return b.encode().build().toUriString()
    }

    companion object {
        const val MAX_GOALS = 20
        val VERSION = RaceAlgorithm.VERSION
    }
}
