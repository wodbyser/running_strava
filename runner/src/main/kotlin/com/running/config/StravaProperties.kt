package com.running.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "strava")
data class StravaProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "http://localhost:8080/callback",
)
