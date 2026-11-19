package com.running.tenant

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Multi-user switch. Off by default: with it off no multi-user bean exists (design §5.2). */
@ConfigurationProperties(prefix = "app.multi-user")
data class MultiUserProperties(
    val enabled: Boolean = false,
    /** Blank = the owner's JDBC URL with the database name replaced by `running_control`. */
    val controlUrl: String = "",
    /** Pause between tenants inside one job; never before the owner (design §10.4). */
    val tenantPause: Duration = Duration.ofSeconds(5),
    /** Base64 of 32 random bytes (`APP_TENANT_SECRET_KEY`); required while the flag is on. */
    val secretKey: String = "",
    /** Invite code for `/register` (`APP_SIGNUP_CODE`); signup stays off while it is blank or shorter than 16 characters. */
    val signupCode: String = "",
) {
    /** Neither the master key nor the invite code may reach a log line. */
    override fun toString() = "MultiUserProperties(enabled=$enabled, controlUrl=$controlUrl, tenantPause=$tenantPause)"
}
