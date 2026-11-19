package com.running.tenant.web

import com.running.tenant.AdminRefused
import com.running.tenant.AdminUsers
import com.running.tenant.SingleUseInvites
import com.running.tenant.db.AppUserRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Owner-only user management (design §6.2, D13). `SecurityConfig` already limits every `/admin` path to the OWNER role; the check
 * here is a second, server-side line so the page never depends on one place only.
 */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class AdminUsersController(
    private val admin: AdminUsers,
    private val invites: SingleUseInvites,
    private val users: AppUserRepository,
) {
    @GetMapping("/admin/users")
    fun list(model: Model): String {
        ownerOnly()
        return page(model, null)
    }

    /** Rendered directly (no redirect, no flash): the code lives in this one response and nowhere else. */
    @PostMapping("/admin/users/invites")
    fun createInvite(model: Model): String {
        val owner = ownerOnly()
        val code = invites.create()
        users.audit(owner, "INVITE_CREATED", "single-use")
        return page(model, code)
    }

    @PostMapping("/admin/users/invites/{id}/revoke")
    fun revokeInvite(@PathVariable id: String, ra: RedirectAttributes): String {
        val owner = ownerOnly()
        if (id.matches(HEX) && users.expireInvite(id)) {
            users.audit(owner, "INVITE_REVOKED", id)
            flash(ra, "De uitnodigingscode is ingetrokken.")
        } else {
            flash(ra, "Die uitnodigingscode kon niet worden ingetrokken.", "error")
        }
        return "redirect:/admin/users"
    }

    @PostMapping("/admin/users/{username}/disable")
    fun disable(@PathVariable username: String, ra: RedirectAttributes): String {
        ownerOnly()
        try {
            admin.disable(username)
            flash(ra, "$username kan niet meer inloggen. De gegevens blijven bewaard.")
        } catch (e: AdminRefused) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        return "redirect:/admin/users"
    }

    @PostMapping("/admin/users/{username}/enable")
    fun enable(@PathVariable username: String, ra: RedirectAttributes): String {
        ownerOnly()
        try {
            admin.enable(username)
            flash(ra, "$username kan weer inloggen.")
        } catch (e: AdminRefused) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        return "redirect:/admin/users"
    }

    private fun page(model: Model, newCode: String?): String {
        val rows = admin.overview()
        model.addAttribute("title", "Gebruikers")
        model.addAttribute("rows", rows)
        model.addAttribute("invites", admin.invites())
        model.addAttribute("newCode", newCode)
        model.addAttribute("activeCount", rows.count { it.status == "ACTIVE" })
        model.addAttribute("totalCount", rows.size)
        return "admin-users"
    }

    private fun ownerOnly(): String {
        val principal = currentPrincipal()
        if (principal.role != "OWNER") throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return principal.username
    }

    private fun flash(ra: RedirectAttributes, message: String, type: String = "success") {
        ra.addFlashAttribute("flashMessage", message)
        ra.addFlashAttribute("flashType", type)
    }

    private companion object {
        val HEX = Regex("^[0-9a-f]{${AppUserRepository.SHORT_ID}}$")
    }
}
