package com.running.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.running.garmin.adapter.GarminApiClientImpl
import com.running.garmin.adapter.GarminRedactor
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.adapter.GarminTokenProvider
import com.running.garmin.spi.GarminApiClient
import com.running.garmin.usecase.BackfillGarminData
import com.running.garmin.usecase.SyncGarminData
import com.running.tenant.CredentialResolver
import com.running.tenant.DataModeGuard
import com.running.tenant.SecretCipher
import com.running.tenant.TenantRegistry
import com.running.tenant.TenantSettings
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Garmin adapters. The Garmin `RestClient` is built here and is not a bean, so it cannot clash with the
 * shared `restClient` bean used for Strava.
 */
@Configuration
class GarminConfig {

    @Bean
    fun garminClock(): Clock = Clock.systemUTC()

    private fun garminRestClient(builder: RestClient.Builder, properties: GarminProperties): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(properties.timeout).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(properties.timeout) }
        return builder.clone().baseUrl(properties.baseUrl).requestFactory(factory).build()
    }

    /** Flag off: no registry, so this is the env key as before. Flag on: per-tenant rules (design §7.3). */
    @Bean
    fun credentialResolver(
        properties: GarminProperties,
        registry: ObjectProvider<TenantRegistry>,
        settings: ObjectProvider<TenantSettings>,
        cipher: ObjectProvider<SecretCipher>,
        guard: ObjectProvider<DataModeGuard>,
    ) = CredentialResolver(properties, registry.ifAvailable, settings.ifAvailable, cipher.ifAvailable, guard.ifAvailable)

    @Bean
    fun garminTokenProvider(builder: RestClient.Builder, properties: GarminProperties, garminClock: Clock, mapper: ObjectMapper, credentials: CredentialResolver) =
        GarminTokenProvider(garminRestClient(builder, properties), properties, garminClock, mapper, credentials)

    @Bean
    fun garminApiClient(builder: RestClient.Builder, properties: GarminProperties, tokens: GarminTokenProvider, mapper: ObjectMapper): GarminApiClient =
        GarminApiClientImpl(
            garminRestClient(builder, properties), tokens, GarminRedactor { tokens.secrets() }, properties.storeRawPayloads, mapper,
        )

    @Bean(destroyMethod = "shutdown")
    fun garminExecutor() = Executors.newSingleThreadExecutor { r -> Thread(r, "garmin-sync").apply { isDaemon = true } }

    @Bean
    fun garminSyncCoordinator(
        properties: GarminProperties,
        sync: SyncGarminData,
        backfill: BackfillGarminData,
        credentials: CredentialResolver,
    ) = GarminSyncCoordinator(properties, sync, backfill, garminExecutor()) { credentials.garminConfigured() }
}
