package com.running.tenant.web

import com.running.garmin.adapter.GarminTokenProvider
import com.running.strava.spi.StravaTokenRepository
import com.running.tenant.AppSettings
import com.running.tenant.SecretCipher
import com.running.tenant.TenantBackfill
import com.running.tenant.TenantModes
import com.running.tenant.TenantReset
import com.running.tenant.TenantResetRefused
import com.running.tenant.db.AppUserRepository
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Settings of a non-owner tenant (design §6.2, §9.3): rotate a credential of the source already in use, or start over with a
 * full reset. A source that is not part of the mode is refused with 409; the reset archives the database, it never deletes rows.
 */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class SettingsController(
    private val modes: TenantModes,
    private val settings: AppSettings,
    private val tokens: StravaTokenRepository,
    private val garmin: GarminTokenProvider,
    private val cipher: SecretCipher,
    private val reset: TenantReset,
    private val backfill: TenantBackfill,
    private val users: AppUserRepository,
) {
    @GetMapping("/settings")
    fun settings(model: Model): String {
        val user = tenantUser(modes)
        val mode = modes.mode(user.tenantId)
        model.addAttribute("modeLabel", mode.label())
        model.addAttribute("usesStrava", mode.usesStrava)
        model.addAttribute("usesGarmin", mode.usesGarmin)
        model.addAttribute("stravaLinked", mode.usesStrava && tokens.get() != null)
        model.addAttribute("garminLast4", if (mode.usesGarmin) settings.get(GARMIN_LAST4) else null)
        model.addAttribute("username", user.username)
        if (mode.usesStrava) model.addAttribute("backfillPhase", backfill.phase().name)
        return "settings"
    }

    @PostMapping("/settings/garmin")
    fun rotateGarmin(@RequestParam(defaultValue = "") key: String, ra: RedirectAttributes, response: HttpServletResponse, model: Model): String {
        val user = tenantUser(modes)
        if (!modes.mode(user.tenantId).usesGarmin) return conflict(response, model, "Garmin hoort niet bij je account.")
        flash(ra, storeGarminKey(key.trim(), user.tenantId, settings, garmin, cipher, users))
        return "redirect:/settings"
    }

    @PostMapping("/settings/reset")
    fun reset(@RequestParam(defaultValue = "") confirm: String, ra: RedirectAttributes): String {
        val principal = currentPrincipal()
        if (principal.role == "OWNER") throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (confirm.trim().lowercase() != principal.username) {
            ra.addFlashAttribute("flashMessage", "Typ je gebruikersnaam om opnieuw te beginnen.")
            ra.addFlashAttribute("flashType", "error")
            return "redirect:/settings"
        }
        try {
            reset.reset(principal.tenantId)
        } catch (e: TenantResetRefused) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        return "redirect:/setup"
    }
}

internal fun conflict(response: HttpServletResponse, model: Model, reason: String): String {
    response.status = HttpStatus.CONFLICT.value()
    model.addAttribute("reason", reason)
    return "conflict"
}
