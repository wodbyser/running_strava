package com.running.strava.domain

data class StravaToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val athleteId: Long,
)
