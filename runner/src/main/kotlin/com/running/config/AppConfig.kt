package com.running.config

import com.running.garmin.spi.GarminApiClient
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.garmin.usecase.BackfillGarminData
import com.running.garmin.usecase.BackfillGarminDataImpl
import com.running.garmin.usecase.GarminRangeSync
import com.running.garmin.usecase.GarminRetry
import com.running.garmin.usecase.GarminSyncSettings
import com.running.garmin.usecase.SyncGarminData
import com.running.garmin.usecase.SyncGarminDataImpl
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaApiClient
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillLapsData
import com.running.strava.usecase.backfill.impl.BackfillLapsDataImpl
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
import java.time.Clock
import java.time.ZoneId

@Configuration
@EnableConfigurationProperties(StravaProperties::class, GarminProperties::class)
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

    @Bean
    fun backfillLapsData(
        stravaApiClient: StravaApiClient,
        tokenRepository: StravaTokenRepository,
        activityRepository: ActivityRepository,
    ): BackfillLapsData = BackfillLapsDataImpl(stravaApiClient, tokenRepository, activityRepository)

    @Bean
    fun garminSyncSettings(properties: GarminProperties) = GarminSyncSettings(
        chunkDays = properties.backfill.chunkDays,
        pauseMillis = properties.backfill.pause.toMillis(),
        overlapDays = properties.sync.overlapDays,
        activityPageSize = properties.activityPageSize,
        defaultBackfillDays = properties.backfill.defaultDays,
        zone = ZoneId.of("Europe/Brussels"),
    )

    @Bean
    fun garminRangeSync(
        client: GarminApiClient,
        repository: GarminRepository,
        statusRepository: GarminSyncStatusRepository,
        properties: GarminProperties,
        settings: GarminSyncSettings,
        garminClock: Clock,
    ) = GarminRangeSync(
        client, repository, statusRepository,
        GarminRetry(properties.retry.maxAttempts, properties.retry.delays.map { it.toMillis() }),
        settings, garminClock,
    )

    @Bean
    fun backfillGarminData(engine: GarminRangeSync, settings: GarminSyncSettings): BackfillGarminData =
        BackfillGarminDataImpl(engine, settings)

    @Bean
    fun syncGarminData(
        engine: GarminRangeSync,
        statusRepository: GarminSyncStatusRepository,
        settings: GarminSyncSettings,
    ): SyncGarminData = SyncGarminDataImpl(engine, statusRepository, settings)
}
