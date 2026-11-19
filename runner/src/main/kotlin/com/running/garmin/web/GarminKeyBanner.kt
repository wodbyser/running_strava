package com.running.garmin.web

import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.tenant.TenantContext
import com.running.tenant.TenantRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * "Key verlopen" banner (design D14). Only for non-owner tenants: with the flag off, or for the owner,
 * it is false without touching the database, so the owner's pages stay exactly as they are.
 */
@Component("garminKeyBanner")
class GarminKeyBanner(
    private val registry: ObjectProvider<TenantRegistry>,
    private val statusRepository: GarminSyncStatusRepository,
) {
    fun expired(): Boolean {
        val known = registry.ifAvailable ?: return false
        val tenant = TenantContext.current() ?: return false
        if (tenant == known.ownerId) return false
        return statusRepository.get().lastErrorCategory == GarminErrorCategory.AUTH
    }
}
