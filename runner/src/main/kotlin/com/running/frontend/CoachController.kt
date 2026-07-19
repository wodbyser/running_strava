package com.running.frontend

import com.running.analysis.CoachService
import com.running.strava.db.StravaTokenRepositoryImpl
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.sync.SyncStravaData
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class CoachController(
    private val activityRepository: ActivityRepository,
    private val tokenRepository: StravaTokenRepository,
    private val syncStravaData: SyncStravaData,
    private val coachService: CoachService,
    private val tokenRepoImpl: StravaTokenRepositoryImpl,
) {
    @GetMapping("/coach")
    fun coach(model: Model): String {
        val allActivities = activityRepository.findAll()
        val runs = allActivities.filter { it.type in runTypes }
        val hasToken = tokenRepository.get() != null
        val savedRhr = tokenRepoImpl.getRestingHr()

        model.addAttribute("hasToken", hasToken)
        model.addAttribute("hasData", runs.isNotEmpty())
        model.addAttribute("title", "Coach")
        model.addAttribute("savedRhr", savedRhr)

        if (runs.isNotEmpty()) {
            model.addAttribute("coachData", coachService.calculateCoachData(runs, savedRhr))
        }

        return "coach"
    }

    @PostMapping("/coach/sync")
    fun sync(ra: RedirectAttributes): String {
        val result = syncStravaData.execute()
        val msg = buildString {
            append("Synchronisatie voltooid. ")
            append("${result.newActivities} nieuw, ${result.streamsFetched} streams opgehaald")
            if (result.errors.isNotEmpty()) {
                append(", ${result.errors.size} fout(en)")
            }
        }
        val type = if (result.errors.isEmpty()) "success" else "warning"
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/coach"
    }

    @GetMapping("/coach/sync")
    fun syncGet(ra: RedirectAttributes): String = sync(ra)

    @PostMapping("/coach/rhr")
    fun saveRhr(@RequestParam rhr: Int, ra: RedirectAttributes): String {
        if (rhr in 30..100) {
            tokenRepoImpl.saveRestingHr(rhr)
            ra.addFlashAttribute("flashMessage", "Rusthartslag opgeslagen: $rhr bpm")
            ra.addFlashAttribute("flashType", "success")
        } else {
            ra.addFlashAttribute("flashMessage", "Ongeldige rusthartslag (30-100 bpm)")
            ra.addFlashAttribute("flashType", "error")
        }
        return "redirect:/coach"
    }

    companion object {
        val runTypes = listOf("Run", "TrailRun", "VirtualRun")
    }
}
