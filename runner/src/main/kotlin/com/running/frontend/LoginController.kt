package com.running.frontend

import com.running.tenant.InviteCodes
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class LoginController(private val invites: ObjectProvider<InviteCodes>) {

    @GetMapping("/login")
    fun login(model: Model): String {
        model.addAttribute("signupOpen", invites.ifAvailable?.signupEnabled == true)
        return "login"
    }
}
