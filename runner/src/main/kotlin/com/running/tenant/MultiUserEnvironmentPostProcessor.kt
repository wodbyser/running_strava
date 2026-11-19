package com.running.tenant

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Flag on only: no tenant is bound on the boot thread, so Hibernate must not touch a database while the
 * EntityManagerFactory starts; `TenantSchemaUpdater` does the schema work per database (design §5.7, §8.1).
 * With the flag off this does nothing and `ddl-auto: update` stays exactly as before.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
class MultiUserEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.getProperty("app.multi-user.enabled", Boolean::class.java, false)) return
        environment.propertySources.addFirst(
            MapPropertySource(
                "multiUserOverrides",
                mapOf(
                    "spring.jpa.hibernate.ddl-auto" to "none",
                    "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access" to "false",
                    "spring.sql.init.mode" to "never",
                ),
            ),
        )
    }
}
