package com.running.tenant

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/** The logged-in user as Spring Security sees it. `tenantId` is the only thing that may select a database. */
data class AppUserPrincipal(
    private val username: String,
    private val passwordHash: String,
    val role: String,
    val tenantId: String,
    val tenantDb: String,
    private val active: Boolean,
) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_$role"))
    override fun getPassword(): String = passwordHash
    override fun getUsername(): String = username
    override fun isEnabled(): Boolean = active
    override fun toString(): String = "AppUserPrincipal(username=$username, role=$role, tenantId=$tenantId)"
}
