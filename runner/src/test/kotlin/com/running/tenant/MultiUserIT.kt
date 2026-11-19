package com.running.tenant

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

object TestPostgres {
    val container: PostgreSQLContainer by lazy { PostgreSQLContainer("postgres:17").withDatabaseName("running").also { it.start() } }
}

/** Flag on, throwaway PostgreSQL (Testcontainers). The container's `running` database plays the owner's database. */
@SpringBootTest(
    properties = [
        "app.multi-user.enabled=true",
        "app.multi-user.secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.multi-user.signup-code=test-invite-code-0123456789",
        "app.security.username=wout",
        "app.security.password=owner-test-pw",
        "app.security.remember-me-key=remember-test-key",
        "garmin.load.warm-up=false",
        "garmin.race.warm-up=false",
    ],
)
@AutoConfigureMockMvc
abstract class MultiUserIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val c = TestPostgres.container
            registry.add("spring.datasource.url") { c.jdbcUrl }
            registry.add("spring.datasource.username") { c.username }
            registry.add("spring.datasource.password") { c.password }
        }
    }
}
