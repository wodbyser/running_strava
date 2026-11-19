package com.running.tenant

class TenantNotResolvedException : RuntimeException("No tenant is bound to this thread: refusing to pick a database")

class TenantDisabledException(tenantId: String) : RuntimeException("Tenant '$tenantId' is not active")

/** The tenant of the current thread. ThreadLocal does not propagate: capture on the submitting thread (design §5.4). */
object TenantContext {
    private val holder = ThreadLocal<String>()

    fun current(): String? = holder.get()

    fun currentOrFail(): String = holder.get() ?: throw TenantNotResolvedException()

    fun <T> asTenant(id: String, block: () -> T): T {
        val previous = holder.get()
        holder.set(id)
        return try {
            block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}

/** In-memory cache key that cannot collide across tenants (design §10.3). The tenant is null when multi-user is off. */
data class TenantKey<K>(val tenant: String?, val key: K) {
    companion object {
        fun <K> of(key: K) = TenantKey(TenantContext.current(), key)
    }
}
