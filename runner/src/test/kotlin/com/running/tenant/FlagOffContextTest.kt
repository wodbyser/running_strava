package com.running.tenant

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import javax.sql.DataSource

/** Default config (flag off): today's bean graph, no multi-user bean, owner pool as Spring Boot builds it. */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:flagoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    ],
)
class FlagOffContextTest @Autowired constructor(private val context: ApplicationContext, private val env: Environment) {
    @Test
    fun `no multi-user bean exists and the in-memory user store is used`() {
        assertTrue(context.getBeansOfType(ControlDb::class.java).isEmpty())
        assertTrue(context.getBeansOfType(MultiUserProperties::class.java).isEmpty())
        assertFalse(context.containsBean("controlDb"))
        assertFalse(context.containsBean("ownerBootstrap"))
        assertTrue(context.getBean("userDetailsService") is InMemoryUserDetailsManager)
    }

    @Test
    fun `owner pool is Spring Boot's own pool with the configured size`() {
        val pool = context.getBean(DataSource::class.java)
        assertTrue(pool is HikariDataSource)
        assertEquals(2, (pool as HikariDataSource).maximumPoolSize)
    }

    @Test
    fun `flag off leaves Hibernate's own schema update and metadata access alone`() {
        assertEquals("update", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals(null, env.getProperty("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access"))
        assertFalse(context.getBeansOfType(TenantRoutingDataSource::class.java).isNotEmpty())
        assertTrue(context.getBeansOfType(TenantContextFilter::class.java).isEmpty())
    }

    @Test
    fun `flag off has no tenant secrets, the env key resolver and a silent banner`() {
        assertTrue(context.getBeansOfType(SecretCipher::class.java).isEmpty())
        assertTrue(context.getBeansOfType(TenantSettings::class.java).isEmpty())
        assertFalse(context.getBean(com.running.garmin.web.GarminKeyBanner::class.java).expired())
    }

    @Test
    fun `flag off has no signup, wizard, settings, state or login limiter, and the single-user Strava flow is still there`() {
        for (type in listOf(
            com.running.tenant.web.RegisterController::class.java, com.running.tenant.web.SetupController::class.java,
            com.running.tenant.web.SettingsController::class.java, com.running.tenant.web.BackfillController::class.java,
            com.running.tenant.web.TenantStravaOAuthController::class.java, com.running.tenant.web.LoginAttemptFilter::class.java,
            TenantModes::class.java, DataModeGuard::class.java, InviteCodes::class.java, OAuthState::class.java, TenantBackfill::class.java, TenantReset::class.java,
            com.running.tenant.web.AdminUsersController::class.java, AdminUsers::class.java, SingleUseInvites::class.java, TenantEviction::class.java,
        )) {
            assertTrue(context.getBeansOfType(type).isEmpty(), type.simpleName)
        }
        assertEquals(1, context.getBeansOfType(com.running.strava.adapter.StravaOAuthController::class.java).size)
        assertFalse(context.getBean(com.running.tenant.web.TenantUi::class.java).showSettings())
        assertFalse(context.getBean(com.running.tenant.web.TenantUi::class.java).showAdmin())
    }

    @Test
    fun `the owner's database only gets the two additive changes (a settings table and a nullable column)`() {
        val ddl = com.running.settings.db.AppSettingEntity::class.java.getAnnotation(jakarta.persistence.Table::class.java)
        assertEquals("app_settings", ddl.name)
        val column = com.running.strava.db.ActivityEntity::class.java.getDeclaredField("source").getAnnotation(jakarta.persistence.Column::class.java)
        assertTrue(column.nullable, "activities.source must stay nullable: null means STRAVA and the owner's rows are never rewritten")
    }
}
