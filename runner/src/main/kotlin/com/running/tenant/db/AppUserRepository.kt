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

/** One line of `/admin/users`: control-plane facts only, never anything from the user's own database. */
data class AppUserListing(
    val username: String,
    val role: String,
    val tenantId: String,
    val tenantDb: String,
    val status: String,
    val createdAt: OffsetDateTime,
    val lastLoginAt: OffsetDateTime?,
)

/** A single-use invite as the admin page shows it: only a short id of the hash, never the code. */
data class InviteListing(val shortId: String, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime?, val usedAt: OffsetDateTime?, val usedBy: String?)

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

    fun audit(username: String?, action: String, detail: String? = null, ip: String? = null) {
        jdbc.update(
            "INSERT INTO auth_audit (at, username, action, detail, ip) VALUES (?, ?, ?, ?, ?)",
            OffsetDateTime.now(), username?.take(64), action, detail?.take(300), ip?.take(64),
        )
    }

    fun listAll(): List<AppUserListing> = jdbc.query(
        "SELECT * FROM app_user ORDER BY CASE WHEN role = 'OWNER' THEN 0 ELSE 1 END, created_at, id",
        { rs, _ ->
            AppUserListing(
                rs.getString("username"), rs.getString("role"), rs.getString("tenant_id"), rs.getString("tenant_db"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("last_login_at", OffsetDateTime::class.java),
            )
        },
    )

    fun insertInvite(codeHash: String, expiresAt: OffsetDateTime) {
        jdbc.update("INSERT INTO invite_code (code_hash, created_at, expires_at) VALUES (?, ?, ?)", codeHash, OffsetDateTime.now(), expiresAt)
    }

    fun inviteUsable(codeHash: String): Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM invite_code WHERE code_hash = ? AND used_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
        Int::class.java, codeHash,
    ) == 1

    /** Atomic: of two simultaneous signups with the same code exactly one gets `true`. */
    fun claimInvite(codeHash: String): Boolean = jdbc.update(
        "UPDATE invite_code SET used_at = now() WHERE code_hash = ? AND used_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
        codeHash,
    ) == 1

    fun releaseInvite(codeHash: String) {
        jdbc.update("UPDATE invite_code SET used_at = NULL WHERE code_hash = ? AND used_by IS NULL", codeHash)
    }

    fun markInviteUsedBy(codeHash: String, username: String) {
        jdbc.update("UPDATE invite_code SET used_by = (SELECT id FROM app_user WHERE username = ?) WHERE code_hash = ?", username, codeHash)
    }

    /** Revoking = expiring now, so no row is ever deleted. Only an unused invite can be revoked. */
    fun expireInvite(shortId: String): Boolean = jdbc.update(
        "UPDATE invite_code SET expires_at = now() WHERE code_hash LIKE ? AND used_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
        shortId.replace("%", "").replace("_", "") + "%",
    ) == 1

    fun listInvites(): List<InviteListing> = jdbc.query(
        "SELECT i.code_hash, i.created_at, i.expires_at, i.used_at, u.username AS used_by FROM invite_code i LEFT JOIN app_user u ON u.id = i.used_by ORDER BY i.created_at DESC LIMIT 50",
        { rs, _ ->
            InviteListing(
                rs.getString("code_hash").take(SHORT_ID), rs.getObject("created_at", OffsetDateTime::class.java),
                rs.getObject("expires_at", OffsetDateTime::class.java), rs.getObject("used_at", OffsetDateTime::class.java), rs.getString("used_by"),
            )
        },
    )

    companion object {
        const val SHORT_ID = 8
    }
}
