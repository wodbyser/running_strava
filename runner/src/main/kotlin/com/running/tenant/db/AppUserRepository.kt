package com.running.tenant.db

import com.running.tenant.AppUserPrincipal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.OffsetDateTime

data class AppUserRow(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val role: String,
    val tenantId: String,
    val tenantDb: String,
    val status: String,
) {
    fun toPrincipal() = AppUserPrincipal(username, passwordHash, role, tenantId, tenantDb, status == "ACTIVE")
}

/** Plain JDBC on the control database; never touches a tenant database. */
class AppUserRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        AppUserRow(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("role"),
            rs.getString("tenant_id"), rs.getString("tenant_db"), rs.getString("status"),
        )
    }

    fun findByUsername(username: String): AppUserRow? =
        jdbc.query("SELECT * FROM app_user WHERE username = ?", mapper, username).firstOrNull()

    fun findOwners(): List<AppUserRow> = jdbc.query("SELECT * FROM app_user WHERE role = 'OWNER'", mapper)

    fun insertOwner(username: String, passwordHash: String, tenantId: String, tenantDb: String) {
        jdbc.update(
            "INSERT INTO app_user (username, password_hash, role, tenant_id, tenant_db, status, created_at) VALUES (?, ?, 'OWNER', ?, ?, 'ACTIVE', ?)",
            username, passwordHash, tenantId, tenantDb, OffsetDateTime.now(),
        )
    }

    fun insertUser(username: String, passwordHash: String, role: String, tenantId: String, tenantDb: String, status: String) {
        jdbc.update(
            "INSERT INTO app_user (username, password_hash, role, tenant_id, tenant_db, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            username, passwordHash, role, tenantId, tenantDb, status, OffsetDateTime.now(),
        )
    }

    fun setStatus(username: String, status: String) {
        jdbc.update("UPDATE app_user SET status = ? WHERE username = ?", status, username)
    }

    fun findActive(): List<AppUserRow> =
        jdbc.query("SELECT * FROM app_user WHERE status = 'ACTIVE' ORDER BY CASE WHEN role = 'OWNER' THEN 0 ELSE 1 END, created_at, id", mapper)

    fun updatePasswordHash(id: Long, passwordHash: String) {
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", passwordHash, id)
    }

    fun touchLogin(username: String) {
        jdbc.update("UPDATE app_user SET last_login_at = ? WHERE username = ?", OffsetDateTime.now(), username)
    }

    fun audit(username: String?, action: String, detail: String? = null) {
        jdbc.update("INSERT INTO auth_audit (at, username, action, detail) VALUES (?, ?, ?, ?)", OffsetDateTime.now(), username, action, detail)
    }
}
