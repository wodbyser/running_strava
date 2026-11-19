package com.running.tenant.db

import com.running.tenant.JdbcUrls
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.DriverManager

/** Control-plane tables (design §5.1). Created with plain `IF NOT EXISTS`, never by JPA. */
object ControlSchema {
    private val DATABASE_NAME = Regex("^[a-z][a-z0-9_]{2,62}$")

    val STATEMENTS = listOf(
        """CREATE TABLE IF NOT EXISTS app_user (
            id BIGSERIAL PRIMARY KEY,
            username VARCHAR(64) NOT NULL UNIQUE,
            password_hash VARCHAR(255) NOT NULL,
            role VARCHAR(16) NOT NULL,
            tenant_id VARCHAR(40) NOT NULL UNIQUE,
            tenant_db VARCHAR(63) NOT NULL,
            status VARCHAR(16) NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            last_login_at TIMESTAMP WITH TIME ZONE NULL
        )""",
        """CREATE TABLE IF NOT EXISTS invite_code (
            code_hash VARCHAR(255) PRIMARY KEY,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            expires_at TIMESTAMP WITH TIME ZONE NULL,
            used_by BIGINT NULL REFERENCES app_user(id),
            used_at TIMESTAMP WITH TIME ZONE NULL
        )""",
        """CREATE TABLE IF NOT EXISTS auth_audit (
            id BIGSERIAL PRIMARY KEY,
            at TIMESTAMP WITH TIME ZONE NOT NULL,
            username VARCHAR(64) NULL,
            action VARCHAR(40) NOT NULL,
            detail VARCHAR(300) NULL,
            ip VARCHAR(64) NULL
        )""",
    )

    fun create(jdbc: JdbcTemplate) = STATEMENTS.forEach { jdbc.execute(it) }

    /** Creates the control database when missing. Connects to `postgres` with autocommit (CREATE DATABASE cannot run in a transaction). */
    fun ensureDatabase(controlUrl: String, username: String, password: String?) {
        val name = requireNotNull(JdbcUrls.databaseName(controlUrl)) { "Control DB URL has no database name" }
        require(DATABASE_NAME.matches(name)) { "Invalid control database name" }
        DriverManager.getConnection(JdbcUrls.withDatabase(controlUrl, "postgres"), username, password).use { conn ->
            conn.autoCommit = true
            val exists = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { st ->
                st.setString(1, name)
                st.executeQuery().use { it.next() }
            }
            if (!exists) conn.createStatement().use { it.execute("CREATE DATABASE \"$name\"") }
        }
    }
}
