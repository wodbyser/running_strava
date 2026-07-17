package com.running.strava.usecase.exchange

interface ExchangeStravaCode {
    fun execute(request: Request): Response

    data class Request(val code: String)
    data class Response(val athleteId: Long, val accessToken: String, val refreshToken: String)
}
