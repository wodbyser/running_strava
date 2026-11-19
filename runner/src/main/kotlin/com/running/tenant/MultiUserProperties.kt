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
)
