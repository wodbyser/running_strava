package com.running.tenant

import com.running.config.AppSecurityProperties
import com.running.tenant.db.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder

/** Keeps the OWNER row in step with `APP_USERNAME` / `APP_PASSWORD` on every boot (design §6.3). */
class OwnerBootstrap(
    private val users: AppUserRepository,
    private val security: AppSecurityProperties,
    private val ownerDb: String,
    private val encoder: PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder(),
) {
    private val log = LoggerFactory.getLogger(OwnerBootstrap::class.java)

    fun run() {
        val username = security.username.trim().lowercase()
        check(TENANT_ID.matches(username)) { "app.security.username must match ${TENANT_ID.pattern} when multi-user is enabled" }
        val owners = users.findOwners()
        check(owners.size <= 1) { "More than one OWNER in the control database: refusing to start" }
        val owner = owners.firstOrNull()
        if (owner == null) {
            check(users.findByUsername(username) == null) { "User '$username' already exists and is not the OWNER: refusing to start" }
            users.insertOwner(username, encoder.encode(security.password), username, ownerDb)
            users.audit(username, "OWNER_CREATED")
            log.info("Created OWNER '{}' in the control database (tenant database '{}')", username, ownerDb)
            return
        }
        check(owner.username == username) {
            "APP_USERNAME '$username' differs from the OWNER in the control database: renaming the owner is not supported, refusing to start"
        }
        check(owner.tenantDb == ownerDb) {
            "The OWNER's tenant database '${owner.tenantDb}' differs from the configured database '$ownerDb': refusing to start"
        }
        if (security.password.isBlank()) {
            log.warn("APP_PASSWORD is blank: the OWNER's stored password is left untouched")
        } else if (!encoder.matches(security.password, owner.passwordHash)) {
            users.updatePasswordHash(owner.id, encoder.encode(security.password))
            users.audit(username, "OWNER_PASSWORD_UPDATED")
            log.info("OWNER password changed from the environment")
        }
    }

    companion object {
        val TENANT_ID = Regex("^[a-z][a-z0-9_]{2,30}$")
    }
}
