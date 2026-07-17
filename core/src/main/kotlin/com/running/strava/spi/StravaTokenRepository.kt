package com.running.strava.spi

import com.running.strava.domain.StravaToken

interface StravaTokenRepository {
    fun save(token: StravaToken)
    fun get(): StravaToken?
    fun delete()
}
