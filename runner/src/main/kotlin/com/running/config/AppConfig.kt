package com.running.config

import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.exchange.ExchangeStravaCode
import com.running.strava.usecase.exchange.impl.ExchangeStravaCodeImpl
import com.running.strava.usecase.fetch.FetchAllHistoricalData
import com.running.strava.usecase.fetch.FetchRemainingData
import com.running.strava.usecase.fetch.impl.FetchAllHistoricalDataImpl
import com.running.strava.usecase.fetch.impl.FetchRemainingDataImpl
import com.running.strava.usecase.sync.SyncStravaData
import com.running.strava.usecase.sync.impl.SyncStravaDataImpl
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(StravaProperties::class)
class AppConfig {

    @Bean
    fun restClient(): RestClient = RestClient.create()

    @Bean
    fun exchangeStravaCode(
        stravaApiClient: StravaApiClient,
        tokenRepository: StravaTokenRepository,
    ): ExchangeStravaCode = ExchangeStravaCodeImpl(stravaApiClient, tokenRepository)

    @Bean
    fun syncStravaData(
        stravaApiClient: StravaApiClient,
        tokenRepository: StravaTokenRepository,
        activityRepository: ActivityRepository,
    ): SyncStravaData = SyncStravaDataImpl(stravaApiClient, tokenRepository, activityRepository)

    @Bean
    fun fetchAllHistoricalData(
        stravaApiClient: StravaApiClient,
        tokenRepository: StravaTokenRepository,
        activityRepository: ActivityRepository,
    ): FetchAllHistoricalData = FetchAllHistoricalDataImpl(stravaApiClient, tokenRepository, activityRepository)

    @Bean
    fun fetchRemainingData(
        stravaApiClient: StravaApiClient,
        tokenRepository: StravaTokenRepository,
        activityRepository: ActivityRepository,
    ): FetchRemainingData = FetchRemainingDataImpl(stravaApiClient, tokenRepository, activityRepository)
}
