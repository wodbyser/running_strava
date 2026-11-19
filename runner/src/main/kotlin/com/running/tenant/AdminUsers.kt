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
        users.setStatus(row.username, "ACTIVE")
        registry.add(Tenant(row.tenantId, row.tenantDb, owner = false))
        users.audit(row.username, "USER_ENABLED", "by owner")
    }

    private fun describe(row: AppUserListing): UserOverview {
        val owner = row.role == "OWNER"
        val active = row.status == "ACTIVE" && registry.find(row.tenantId) != null
        val base = UserOverview(
            row.username, owner, row.status, statusLabel(row.status), null, stamp(row.createdAt), row.lastLoginAt?.let(::stamp),
            null, null, null, null, active, canDisable = !owner && row.status == "ACTIVE", canEnable = !owner && row.status == "DISABLED",
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
