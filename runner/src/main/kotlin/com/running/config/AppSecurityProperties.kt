package com.running.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Login for an online deployment. With a blank password the app stays open (local use). */
@ConfigurationProperties(prefix = "app.security")
data class AppSecurityProperties(
    val username: String = "wout",
    val password: String = "",
    val rememberMeKey: String = "",
) {
    val loginEnabled: Boolean get() = password.isNotBlank()
}
