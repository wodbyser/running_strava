package com.running.tenant.web

import com.running.tenant.AttemptLimiter
import com.running.tenant.ClientIp
import com.running.tenant.InviteCodes
import com.running.tenant.OwnerBootstrap
import com.running.tenant.SingleUseInvites
import com.running.tenant.TenantProvisionException
import com.running.tenant.TenantProvisioner
import com.running.tenant.db.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/** Signup with the invite code (design §6.4). Off (404) while `APP_SIGNUP_CODE` is blank or too short. */
@Controller
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
class RegisterController(
    private val invites: InviteCodes,
    private val singleUse: SingleUseInvites,
    private val provisioner: TenantProvisioner,
    private val users: AppUserRepository,
) {
    private val limiter = AttemptLimiter(MAX_ATTEMPTS, WINDOW)

    @GetMapping("/register")
    fun form(): String {
        if (!invites.signupEnabled) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return "register"
    }

    @PostMapping("/register")
    fun submit(
        @RequestParam(defaultValue = "") code: String,
        @RequestParam(defaultValue = "") username: String,
        @RequestParam(defaultValue = "") password: String,
        @RequestParam(defaultValue = "") password2: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        if (!invites.signupEnabled) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val ip = ClientIp.of(request)
        val name = username.trim().lowercase()
        fun refuse(status: HttpStatus, message: String, action: String): String {
            users.audit(name.ifBlank { null }, action, null, ip)
            response.status = status.value()
            model.addAttribute("error", message)
            model.addAttribute("username", name)
            return "register"
        }
        if (!limiter.hit(ip)) return refuse(HttpStatus.TOO_MANY_REQUESTS, "Te veel pogingen. Probeer het over een kwartier opnieuw.", "SIGNUP_LIMITED")
        val candidate = code.trim()
        val envCode = invites.matches(candidate)
        if (!envCode && !singleUse.usable(candidate)) return refuse(HttpStatus.FORBIDDEN, "Die uitnodigingscode klopt niet.", "SIGNUP_BAD_CODE")
        if (!OwnerBootstrap.TENANT_ID.matches(name) || name in TenantProvisioner.RESERVED) {
            return refuse(HttpStatus.BAD_REQUEST, "Kies een gebruikersnaam van 3 tot 31 tekens: kleine letters, cijfers of _, beginnend met een letter.", "SIGNUP_INVALID")
        }
        if (password.length < MIN_PASSWORD || password.toByteArray().size > MAX_PASSWORD_BYTES) {
            return refuse(HttpStatus.BAD_REQUEST, "Kies een wachtwoord van minstens 10 tekens.", "SIGNUP_INVALID")
        }
        if (password != password2) return refuse(HttpStatus.BAD_REQUEST, "De twee wachtwoorden zijn niet gelijk.", "SIGNUP_INVALID")
        if (users.findByUsername(name) != null) return refuse(HttpStatus.CONFLICT, "Die gebruikersnaam is al bezet.", "SIGNUP_TAKEN")
        if (!envCode && !singleUse.claim(candidate)) return refuse(HttpStatus.FORBIDDEN, "Die uitnodigingscode klopt niet.", "SIGNUP_BAD_CODE")
        try {
            provisioner.provision(name, password)
        } catch (e: DuplicateKeyException) {
            if (!envCode) singleUse.release(candidate)
            return refuse(HttpStatus.CONFLICT, "Die gebruikersnaam is al bezet.", "SIGNUP_TAKEN")
        } catch (e: TenantProvisionException) {
            if (!envCode) singleUse.release(candidate)
            return refuse(HttpStatus.INTERNAL_SERVER_ERROR, "Je account aanmaken is niet gelukt. Vraag de beheerder om het opnieuw te proberen.", "SIGNUP_FAILED")
        }
        if (!envCode) singleUse.markUsedBy(candidate, name)
        users.audit(name, "SIGNUP", null, ip)
        login(name, request, response)
        return "redirect:/setup"
    }

    private fun login(username: String, request: HttpServletRequest, response: HttpServletResponse) {
        val principal = checkNotNull(users.findByUsername(username)).toPrincipal()
        if (request.getSession(false) != null) request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        SecurityContextHolder.setContext(context)
        HttpSessionSecurityContextRepository().saveContext(context, request, response)
        users.touchLogin(username)
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
        const val MIN_PASSWORD = 10
        const val MAX_PASSWORD_BYTES = 72
    }
}
