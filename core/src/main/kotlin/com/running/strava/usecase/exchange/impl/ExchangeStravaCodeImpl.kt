package com.running.strava.usecase.exchange.impl

import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.exchange.ExchangeStravaCode

class ExchangeStravaCodeImpl(
    private val stravaApiClient: StravaApiClient,
    private val tokenRepository: StravaTokenRepository,
) : ExchangeStravaCode {

    override fun execute(request: ExchangeStravaCode.Request): ExchangeStravaCode.Response {
        val token = stravaApiClient.exchangeToken(request.code)
        tokenRepository.save(token)
        return ExchangeStravaCode.Response(
            athleteId = token.athleteId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
        )
    }
}
