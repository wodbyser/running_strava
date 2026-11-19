package com.running.tenant

import com.running.tenant.db.AppUserRepository
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.context.ApplicationListener

/** Login lookup in the control database only (design §5.4); the routed DataSource is never used here. */
class ControlUserDetailsService(private val users: AppUserRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails =
        users.findByUsername(username.trim().lowercase())?.toPrincipal()
            ?: throw UsernameNotFoundException("Unknown user")
}

class LastLoginRecorder(private val users: AppUserRepository) : ApplicationListener<AuthenticationSuccessEvent> {
    override fun onApplicationEvent(event: AuthenticationSuccessEvent) {
        val principal = event.authentication.principal as? AppUserPrincipal ?: return
        users.touchLogin(principal.username)
    }
}
