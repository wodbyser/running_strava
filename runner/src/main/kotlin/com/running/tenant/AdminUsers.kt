package com.running.tenant

import com.running.analysis.PerfDates
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaTokenRepository
import com.running.tenant.db.AppUserListing
import com.running.tenant.db.AppUserRepository
import com.running.tenant.db.InviteListing
import com.running.tenant.web.GARMIN_LAST4
import com.running.tenant.web.label
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drops everything the app holds in memory for one tenant (pool, mode, caches, Garmin token). */
fun interface TenantEviction {
    fun evict(tenantId: String)
}

class AdminRefused(message: String) : RuntimeException(message)

/** Strava's `oauth/deauthorize`: the athlete's access is revoked on Strava's side (API policy, design R14). */
fun interface StravaDeauthorizer {
    fun deauthorize(accessToken: String)
}

class StravaRevokeFailed(message: String) : RuntimeException(message)

class TenantNotReady(message: String) : RuntimeException(message)

/** One line of `/admin/users` (design D13): counts and last sync only, never anything of the user's training. */
data class UserOverview(
    val username: String,
    val owner: Boolean,
    val status: String,
    val statusLabel: String,
    val mode: String?,
    val created: String,
    val lastLogin: String?,
    val activities: Int?,
    val lastSync: String?,
    val garminKey: String?,
    val stravaLinked: Boolean?,
    val reachable: Boolean,
    val canDisable: Boolean,
    val canEnable: Boolean,
    val canDelete: Boolean,
)

data class InviteOverview(val shortId: String, val created: String, val state: String, val usable: Boolean, val usedBy: String?)

/**
 * The owner's view of the users (design §6.2, D13). Only [AppUserRepository] and a few counts per tenant are read; the key is
 * shown as its last four characters (stored for exactly this purpose by the wizard), never decrypted.
 */
class AdminUsers(
    private val users: AppUserRepository,
    private val registry: TenantRegistry,
    private val modes: TenantModes,
    private val settings: AppSettings,
    private val activities: ActivityRepository,
    private val garminStatus: GarminSyncStatusRepository,
    private val tokens: StravaTokenRepository,
    private val eviction: TenantEviction,
    private val deauthorizer: StravaDeauthorizer = StravaDeauthorizer { },
    private val stravaClient: com.running.strava.spi.StravaApiClient? = null,
    private val reset: TenantReset? = null,
    private val schema: TenantSchemaUpdater? = null,
) {
    /**
     * A fresh thread per tenant read. The request thread carries an open-in-view EntityManager that keeps the connection of the
     * first tenant it touched; reading a second tenant on that thread would silently return the first tenant's rows.
     */
    private val reader = Executors.newCachedThreadPool { r -> Thread(r, "admin-tenant-read").apply { isDaemon = true } }

    fun overview(): List<UserOverview> = users.listAll().map(::describe)

    fun invites(): List<InviteOverview> {
        val now = OffsetDateTime.now()
        return users.listInvites().map { invite ->
            val usable = invite.usedAt == null && (invite.expiresAt == null || invite.expiresAt.isAfter(now))
            InviteOverview(invite.shortId, stamp(invite.createdAt), inviteState(invite, usable), usable, invite.usedBy)
        }
    }

    fun disable(username: String) {
        val row = users.findByUsername(username.trim().lowercase()) ?: throw AdminRefused("Unknown user")
        if (row.role == "OWNER" || registry.ownerId == row.tenantId) throw AdminRefused("The owner cannot be disabled")
        if (row.status != "ACTIVE") throw AdminRefused("Only an active user can be disabled")
        users.setStatus(row.username, "DISABLED")
        registry.remove(row.tenantId)
        eviction.evict(row.tenantId)
        users.audit(row.username, "USER_DISABLED", "by owner")
    }

    fun enable(username: String) {
        val row = users.findByUsername(username.trim().lowercase()) ?: throw AdminRefused("Unknown user")
        if (row.role == "OWNER") throw AdminRefused("The owner is always active")
        if (row.status != "DISABLED") throw AdminRefused("Only a disabled user can be enabled")
        registry.add(Tenant(row.tenantId, row.tenantDb, owner = false))
        // The start-up schema update skipped this tenant while it was disabled; a newer version may need extra columns.
        try {
            schema?.let { TenantContext.asTenant(row.tenantId) { it.update() } }
        } catch (e: Exception) {
            registry.remove(row.tenantId)
            eviction.evict(row.tenantId)
            throw TenantNotReady("Schema update failed")
        }
        users.setStatus(row.username, "ACTIVE")
        users.audit(row.username, "USER_ENABLED", "by owner")
    }

    /**
     * Delete-user (design §14, Phase 7): Strava access is revoked first and a failure stops everything; then the database is
     * archived by rename (never dropped) and the user row is kept with status DELETED, so the name stays reserved and the
     * audit trail intact. Returns the archive name for the owner's report. The OWNER is refused here, not only in the controller.
     */
    fun delete(username: String): String {
        val row = users.findByUsername(username.trim().lowercase()) ?: throw AdminRefused("Unknown user")
        if (row.role == "OWNER" || registry.ownerId == row.tenantId) throw AdminRefused("The owner cannot be deleted")
        if (row.status != "ACTIVE" && row.status != "DISABLED") throw AdminRefused("Only an active or disabled user can be deleted")
        val archiver = reset ?: throw AdminRefused("Deleting is not available")
        val tenant = Tenant(row.tenantId, row.tenantDb, owner = false)
        val wasRegistered = registry.find(row.tenantId) != null
        if (!wasRegistered) registry.add(tenant)
        try {
            revokeStrava(row.tenantId)
        } catch (e: Exception) {
            if (!wasRegistered) registry.remove(row.tenantId)
            users.audit(row.username, "USER_DELETE_FAILED", "strava revoke failed")
            throw StravaRevokeFailed("Strava access could not be revoked")
        }
        val archive = try {
            archiver.archive(row.tenantId, row.tenantDb)
        } catch (e: TenantResetRefused) {
            throw AdminRefused(e.message ?: "Refused")
        }
        users.setStatus(row.username, "DELETED")
        users.audit(row.username, "USER_DELETED", "archived as $archive")
        return archive
    }

    private fun revokeStrava(tenantId: String) {
        reader.submit {
            TenantContext.asTenant(tenantId) {
                var token = tokens.get() ?: return@asTenant
                if (token.expiresAt <= System.currentTimeMillis() / 1000 + 60) {
                    token = stravaClient?.refreshToken(token.refreshToken) ?: token
                }
                deauthorizer.deauthorize(token.accessToken)
            }
        }.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun describe(row: AppUserListing): UserOverview {
        val owner = row.role == "OWNER"
        val active = row.status == "ACTIVE" && registry.find(row.tenantId) != null
        val base = UserOverview(
            row.username, owner, row.status, statusLabel(row.status), null, stamp(row.createdAt), row.lastLoginAt?.let(::stamp),
            null, null, null, null, active, canDisable = !owner && row.status == "ACTIVE", canEnable = !owner && row.status == "DISABLED",
            canDelete = !owner && (row.status == "ACTIVE" || row.status == "DISABLED"),
        )
        if (!active) return base
        return try {
            reader.submit<UserOverview> {
                TenantContext.asTenant(row.tenantId) {
                    val strava = activities.getSyncStatus().lastSyncAt?.toInstant()
                    val garmin = garminStatus.get().lastSuccessAt
                    val last = listOfNotNull(strava, garmin).maxOrNull()
                    base.copy(
                        mode = modes.mode(row.tenantId).label(),
                        activities = activities.findAllIds().size,
                        lastSync = last?.let { stamp(OffsetDateTime.ofInstant(it, ZONE)) },
                        garminKey = if (owner) "via de omgeving" else settings.get(GARMIN_LAST4)?.let { "…$it" },
                        stravaLinked = tokens.get() != null,
                    )
                }
            }.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            base.copy(reachable = false)
        }
    }

    private fun statusLabel(status: String) = when (status) {
        "ACTIVE" -> "Actief"
        "DISABLED" -> "Uitgeschakeld"
        "DELETED" -> "Verwijderd"
        "PENDING" -> "Wordt aangemaakt"
        else -> status
    }

    private fun inviteState(invite: InviteListing, usable: Boolean) = when {
        invite.usedAt != null -> "Gebruikt op ${stamp(invite.usedAt)}"
        usable -> "Geldig tot ${stamp(invite.expiresAt!!)}"
        else -> "Verlopen of ingetrokken"
    }

    private fun stamp(at: OffsetDateTime) = PerfDates.dayTime(at.atZoneSameInstant(ZONE).toLocalDateTime())

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Brussels")
        const val READ_TIMEOUT_SECONDS = 10L
    }
}
