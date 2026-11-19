package com.running.tenant

import java.util.concurrent.atomic.AtomicReference

data class Tenant(val id: String, val db: String, val owner: Boolean)

/** In-memory tenant list (design §5.3): resolution never costs a query. OWNER first, then creation order. */
class TenantRegistry(private val loader: () -> List<Tenant>) {
    private val tenants = AtomicReference<List<Tenant>>(emptyList())

    fun reload() {
        val loaded = loader()
        check(loaded.count { it.owner } == 1) { "The tenant list needs exactly one OWNER" }
        tenants.set(loaded.sortedBy { if (it.owner) 0 else 1 })
    }

    val ownerId: String get() = tenants.get().first { it.owner }.id

    fun activeOrdered(): List<Tenant> = tenants.get()

    fun activeCount(): Int = tenants.get().size

    fun find(id: String): Tenant? = tenants.get().firstOrNull { it.id == id }

    fun requireActive(id: String): Tenant = find(id) ?: throw TenantDisabledException(id)

    fun add(tenant: Tenant) {
        tenants.updateAndGet { list -> if (list.any { it.id == tenant.id }) list else list + tenant }
    }

    fun remove(id: String) {
        tenants.updateAndGet { list -> list.filterNot { it.id == id && !it.owner } }
    }
}
