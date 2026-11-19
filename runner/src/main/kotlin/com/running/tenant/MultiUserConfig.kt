package com.running.tenant

import com.running.config.AppSecurityProperties
import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.userdetails.UserDetailsService

/** Everything multi-user lives behind the flag; with it off none of these beans exist (design §5.2, D9). */
@Configuration
@ConditionalOnProperty("app.multi-user.enabled", havingValue = "true")
@EnableConfigurationProperties(MultiUserProperties::class)
class MultiUserConfig {

    @Bean(destroyMethod = "close")
    fun controlDb(props: MultiUserProperties, security: AppSecurityProperties, owner: DataSourceProperties): ControlDb {
        val ownerUrl = owner.url.orEmpty()
        MultiUserGuards.check(security.loginEnabled, ownerUrl)
        val controlUrl = props.controlUrl.ifBlank { JdbcUrls.withDatabase(ownerUrl, CONTROL_DB) }
        check(JdbcUrls.isPostgres(controlUrl)) { "app.multi-user.control-url must be a PostgreSQL JDBC URL" }
        check(JdbcUrls.databaseName(controlUrl) != JdbcUrls.databaseName(ownerUrl)) { "The control database must not be the owner's database" }
        ControlSchema.ensureDatabase(controlUrl, owner.username.orEmpty(), owner.password)
        val pool = HikariDataSource(
            HikariConfig().apply {
                poolName = "control"
                jdbcUrl = controlUrl
                username = owner.username
                password = owner.password
                maximumPoolSize = 2
                minimumIdle = 0
            },
        )
        val jdbc = JdbcTemplate(pool)
        ControlSchema.create(jdbc)
        return ControlDb(jdbc, pool)
    }

    @Bean
    fun appUserRepository(db: ControlDb) = AppUserRepository(db.jdbc)

    @Bean
    fun ownerBootstrap(users: AppUserRepository, security: AppSecurityProperties, owner: DataSourceProperties): InitializingBean {
        val bootstrap = OwnerBootstrap(users, security, requireNotNull(JdbcUrls.databaseName(owner.url.orEmpty())))
        return InitializingBean { bootstrap.run() }
    }

    @Bean
    fun userDetailsService(users: AppUserRepository, ownerBootstrap: InitializingBean): UserDetailsService =
        ControlUserDetailsService(users)

    @Bean
    fun lastLoginRecorder(users: AppUserRepository) = LastLoginRecorder(users)

    companion object {
        const val CONTROL_DB = "running_control"
    }
}

object MultiUserGuards {
    fun check(loginEnabled: Boolean, ownerUrl: String) {
        check(loginEnabled) { "app.multi-user.enabled needs APP_PASSWORD: without a login there is no user to pick a tenant" }
        check(JdbcUrls.isPostgres(ownerUrl)) { "app.multi-user.enabled needs PostgreSQL (the dev H2 profile stays single-user)" }
    }
}
