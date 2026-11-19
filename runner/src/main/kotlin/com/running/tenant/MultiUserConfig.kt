package com.running.tenant

import com.running.config.AppSecurityProperties
import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.ControlSchema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.hibernate.jpa.boot.spi.IntegratorProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import javax.sql.DataSource
import kotlin.system.exitProcess
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

    @Bean(initMethod = "run")
    fun ownerBootstrap(users: AppUserRepository, security: AppSecurityProperties, owner: DataSourceProperties) =
        OwnerBootstrap(users, security, requireNotNull(JdbcUrls.databaseName(owner.url.orEmpty())))

    @Bean
    fun userDetailsService(users: AppUserRepository, ownerBootstrap: OwnerBootstrap): UserDetailsService =
        ControlUserDetailsService(users)

    @Bean
    fun tenantRegistry(users: AppUserRepository, ownerBootstrap: OwnerBootstrap, owner: DataSourceProperties): TenantRegistry {
        val ownerDb = JdbcUrls.databaseName(owner.url.orEmpty())
        val registry = TenantRegistry { users.findActive().map { Tenant(it.tenantId, it.tenantDb, it.role == "OWNER") } }
        registry.reload()
        check(registry.activeOrdered().first { it.owner }.db == ownerDb) { "The OWNER's tenant database differs from the configured database: refusing to start" }
        return registry
    }

    @Bean
    fun tenantContextFilter(registry: TenantRegistry) = TenantContextFilter(registry)

    /** Spring Boot would otherwise add the filter a second time, outside the security chain (design §5.4). */
    @Bean
    fun tenantContextFilterRegistration(filter: TenantContextFilter) = FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun tenantJobRunner(registry: TenantRegistry, props: MultiUserProperties) = TenantJobRunner(registry, props.tenantPause)

    @Bean
    fun hibernateMetadataCapture() = HibernateMetadataCapture()

    @Bean
    fun hibernateIntegratorCustomizer(capture: HibernateMetadataCapture) = HibernatePropertiesCustomizer {
        it["hibernate.integrator_provider"] = IntegratorProvider { listOf(capture) }
    }

    @Bean
    fun tenantSchemaUpdater(capture: HibernateMetadataCapture, dataSource: DataSource) =
        TenantSchemaUpdater.forHibernate(capture, dataSource)

    /** Owner first (a refused schema stops the start-up), then every other tenant; a broken tenant is skipped (design §8.2). */
    @Bean
    fun tenantSchemaBootstrap(registry: TenantRegistry, updater: TenantSchemaUpdater) = SmartInitializingSingleton {
        registry.activeOrdered().forEach { tenant ->
            try {
                TenantContext.asTenant(tenant.id) { updater.update() }
            } catch (e: Exception) {
                if (tenant.owner) throw e
                LoggerFactory.getLogger(MultiUserConfig::class.java).error("Tenant '{}' skipped, schema not ready: {}", tenant.id, e.javaClass.simpleName)
            }
        }
    }

    @Bean
    fun tenantProvisioner(
        users: AppUserRepository,
        registry: TenantRegistry,
        updater: TenantSchemaUpdater,
        dataSource: DataSource,
        owner: DataSourceProperties,
    ) = TenantProvisioner(
        users, registry, updater, { (dataSource as? TenantRoutingDataSource)?.closeTenant(it) },
        owner.url.orEmpty(), owner.username.orEmpty(), owner.password, PasswordEncoderFactories.createDelegatingPasswordEncoder(),
    )

    @Bean
    fun provisionUserRunner(provisioner: TenantProvisioner, context: ApplicationContext) = ApplicationRunner { args ->
        val names = args.getOptionValues(PROVISION_ARG).orEmpty()
        if (names.isEmpty()) return@ApplicationRunner
        var code = 0
        for (name in names) {
            code = try {
                val tenant = provisioner.provision(name)
                println("Provisioned tenant '${tenant.id}' (database ${tenant.db})")
                code
            } catch (e: TenantProvisionException) {
                System.err.println(e.message)
                1
            }
        }
        val exit = code
        exitProcess(SpringApplication.exit(context, ExitCodeGenerator { exit }))
    }

    @Bean
    fun lastLoginRecorder(users: AppUserRepository) = LastLoginRecorder(users)

    companion object {
        const val CONTROL_DB = "running_control"
        const val PROVISION_ARG = "provision-user"

        /** Wraps the auto-configured owner pool when the flag is on; the pool and its hikari settings stay untouched (design §5.2). */
        @Bean
        @JvmStatic
        fun tenantRoutingPostProcessor(beans: ObjectProvider<TenantRegistry>) = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (beanName == "dataSource" && bean is HikariDataSource) {
                    TenantRoutingDataSource(lazy { beans.getObject() }::value, bean) { id, db -> TenantRoutingDataSource.tenantPool(bean, id, db) }
                } else {
                    bean
                }
        }
    }
}

object MultiUserGuards {
    fun check(loginEnabled: Boolean, ownerUrl: String) {
        check(loginEnabled) { "app.multi-user.enabled needs APP_PASSWORD: without a login there is no user to pick a tenant" }
        check(JdbcUrls.isPostgres(ownerUrl)) { "app.multi-user.enabled needs PostgreSQL (the dev H2 profile stays single-user)" }
    }
}
