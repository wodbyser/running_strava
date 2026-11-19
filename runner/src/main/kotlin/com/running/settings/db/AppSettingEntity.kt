package com.running.settings.db

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** Per-database key/value settings (data mode, onboarding flag, encrypted Garmin key). Additive table, see multi-user design §8.4. */
@Entity
@Table(name = "app_settings")
class AppSettingEntity(
    @Id
    @Column(name = "setting_key", length = 64)
    var key: String = "",
    @Column(name = "setting_value", columnDefinition = "TEXT")
    var value: String = "",
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH,
)

interface AppSettingJpaRepository : JpaRepository<AppSettingEntity, String>
