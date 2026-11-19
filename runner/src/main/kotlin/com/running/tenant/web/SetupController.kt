package com.running.tenant.web

import com.running.garmin.adapter.GarminKeyCheck
import com.running.garmin.adapter.GarminTokenProvider
import com.running.strava.spi.StravaTokenRepository
import com.running.tenant.AppSettings
import com.running.tenant.CredentialResolver
import com.running.tenant.DataMode
import com.running.tenant.SecretCipher
import com.running.tenant.TenantBackfill
import com.running.tenant.TenantModes
import com.running.tenant.db.AppUserRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

internal const val GARMIN_LAST4 = "garmin.key_last4"

/**
 * Onboarding wizard (design §6.2). Reachable only while the tenant is not onboarded, never for the OWNER.
 * The Garmin key is tested first and only stored (encrypted) when Garmin accepts it; the page never shows more than its last 4 characters.
 */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class SetupController(
    private val modes: TenantModes,
    private val settings: AppSettings,
    private val tokens: StravaTokenRepository,
    private val garmin: GarminTokenProvider,
    private val cipher: SecretCipher,
    private val backfill: TenantBackfill,
    private val users: AppUserRepository,
) {
    @GetMapping("/setup")
    fun setup(model: Model): String {
        val user = tenantUser(modes)
        if (modes.onboardingComplete(user.tenantId)) return "redirect:/"
        val stravaLinked = tokens.get() != null
        val last4 = settings.get(GARMIN_LAST4)
        model.addAttribute("stravaLinked", stravaLinked)
        model.addAttribute("garminLast4", last4)
        model.addAttribute("resulting", DataMode.derive(stravaLinked, last4 != null).label())
        model.addAttribute("canFinish", stravaLinked || last4 != null)
        model.addAttribute("username", user.username)
        return "setup"
    }

    @PostMapping("/setup/garmin")
    fun saveGarmin(@RequestParam(defaultValue = "") key: String, ra: RedirectAttributes): String {
        val user = tenantUser(modes)
        if (modes.onboardingComplete(user.tenantId)) return "redirect:/"
        val outcome = storeGarminKey(key.trim(), user.tenantId, settings, garmin, cipher, users)
        flash(ra, outcome)
        return "redirect:/setup"
    }

    @PostMapping("/setup/garmin/clear")
    fun clearGarmin(ra: RedirectAttributes): String {
        val user = tenantUser(modes)
        if (modes.onboardingComplete(user.tenantId)) return "redirect:/"
        settings.remove(CredentialResolver.GARMIN_KEY)
        settings.remove(GARMIN_LAST4)
        garmin.evict(user.tenantId)
        users.audit(user.username, "KEY_CLEARED")
        flash(ra, KeyOutcome.CLEARED)
        return "redirect:/setup"
    }

    @PostMapping("/setup/finish")
    fun finish(ra: RedirectAttributes): String {
        val user = tenantUser(modes)
        if (modes.onboardingComplete(user.tenantId)) return "redirect:/"
        val mode = DataMode.derive(tokens.get() != null, settings.get(GARMIN_LAST4) != null)
        if (mode == DataMode.UNSET) {
            flash(ra, KeyOutcome.NOTHING_LINKED)
            return "redirect:/setup"
        }
        modes.complete(user.tenantId, mode)
        users.audit(user.username, "ONBOARDED", mode.name)
        if (mode.usesStrava) {
            backfill.start()
            return "redirect:/backfill"
        }
        return "redirect:/"
    }
}

internal enum class KeyOutcome(val ok: Boolean, val text: String) {
    SAVED(true, "Garmin-sleutel opgeslagen."),
    CLEARED(true, "Garmin-sleutel verwijderd."),
    EMPTY(false, "Plak eerst je Garmin-sleutel."),
    REJECTED(false, "Garmin weigert deze sleutel. Controleer of je hem volledig hebt gekopieerd en of hij niet verlopen is."),
    UNAVAILABLE(false, "Garmin is nu niet bereikbaar. Je sleutel is niet opgeslagen, probeer het straks opnieuw."),
    NOTHING_LINKED(false, "Koppel eerst Strava of voeg een Garmin-sleutel toe."),
}

internal fun flash(ra: RedirectAttributes, outcome: KeyOutcome) {
    ra.addFlashAttribute("flashMessage", outcome.text)
    ra.addFlashAttribute("flashType", if (outcome.ok) "success" else "error")
}

internal fun storeGarminKey(
    key: String,
    tenant: String,
    settings: AppSettings,
    garmin: GarminTokenProvider,
    cipher: SecretCipher,
    users: AppUserRepository,
): KeyOutcome {
    if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return KeyOutcome.EMPTY
    when (garmin.verify(key)) {
        GarminKeyCheck.REJECTED -> return KeyOutcome.REJECTED
        GarminKeyCheck.UNAVAILABLE -> return KeyOutcome.UNAVAILABLE
        GarminKeyCheck.OK -> Unit
    }
    settings.put(CredentialResolver.GARMIN_KEY, cipher.encrypt(key))
    settings.put(GARMIN_LAST4, key.takeLast(4))
    garmin.evict(tenant)
    users.audit(tenant, "KEY_SET")
    return KeyOutcome.SAVED
}

private const val MAX_KEY_LENGTH = 512
