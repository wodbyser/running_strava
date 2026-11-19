package com.running.tenant

import com.running.settings.db.AppSettingEntity
import com.running.settings.db.AppSettingJpaRepository
import java.time.Instant

interface SettingsStore : TenantSettings {
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** The real store behind [TenantSettings]: `app_settings` in the database of the tenant bound to the current thread. */
class AppSettings(private val repository: AppSettingJpaRepository) : SettingsStore {
    override fun get(key: String): String? = repository.findById(key).map { it.value }.orElse(null)

    override fun put(key: String, value: String) {
        repository.save(AppSettingEntity(key, value, Instant.now()))
    }

    override fun remove(key: String) {
        if (repository.existsById(key)) repository.deleteById(key)
    }
}
