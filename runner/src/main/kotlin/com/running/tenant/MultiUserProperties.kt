package com.running.tenant

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Multi-user switch. Off by default: with it off no multi-user bean exists (design §5.2). */
@ConfigurationProperties(prefix = "app.multi-user")
data class MultiUserProperties(
    val enabled: Boolean = false,
    /** Blank = the owner's JDBC URL with the database name replaced by `running_control`. */
    val controlUrl: String = "",
    val tenantPause: Duration = Duration.ZERO,
)
