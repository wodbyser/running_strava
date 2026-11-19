package com.running.tenant.web

import com.running.tenant.BackfillPhase
import com.running.tenant.TenantBackfill
import com.running.tenant.TenantModes
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** Progress of the first Strava fetch (design §10.4). Plain counts, nothing of the training content. */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class BackfillController(private val modes: TenantModes, private val backfill: TenantBackfill) {
    @GetMapping("/backfill")
    fun progress(model: Model): String {
        val user = tenantUser(modes)
        if (!modes.mode(user.tenantId).usesStrava) return "redirect:/"
        val progress = backfill.progress()
        model.addAttribute("phase", progress.phase.name)
        model.addAttribute("active", progress.phase == BackfillPhase.QUEUED || progress.phase == BackfillPhase.RUNNING)
        model.addAttribute("activities", progress.activities)
        model.addAttribute("runs", progress.runs)
        model.addAttribute("runsWithDetail", progress.runsWithDetail)
        model.addAttribute("percent", if (progress.runs == 0) 0 else progress.runsWithDetail * 100 / progress.runs)
        return "backfill"
    }
}
