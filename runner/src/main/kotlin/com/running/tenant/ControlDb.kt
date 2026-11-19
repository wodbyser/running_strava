package com.running.tenant

import org.springframework.jdbc.core.JdbcTemplate

/** Not a DataSource bean on purpose: auto-configuration must keep building the owner's pool (design §5.2). */
class ControlDb(val jdbc: JdbcTemplate, private val pool: AutoCloseable) : AutoCloseable {
    override fun close() = pool.close()
}
