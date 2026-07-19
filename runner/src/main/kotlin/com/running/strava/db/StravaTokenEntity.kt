package com.running.strava.db

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "strava_tokens")
class StravaTokenEntity(
    @Id
    var id: Long = 1,
    @Column(name = "access_token", nullable = false, length = 512)
    var accessToken: String = "",
    @Column(name = "refresh_token", nullable = false, length = 512)
    var refreshToken: String = "",
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Long = 0,
    @Column(name = "athlete_id")
    var athleteId: Long = 0,
    @Column(name = "resting_hr")
    var restingHr: Int? = null,
)
