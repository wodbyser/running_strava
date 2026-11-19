package com.running.tenant

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import java.time.Duration

/** Runs a job once per active tenant, strictly sequential, OWNER first (design §10.1). */
class TenantJobRunner(
    private val registry: TenantRegistry,
    private val pause: Duration,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
) {
    private val log = LoggerFactory.getLogger(TenantJobRunner::class.java)

    fun runAll(name: String, block: () -> Unit) {
        val tenants = registry.activeOrdered()
        log.info("Job '{}' starting for {} tenants", name, tenants.size)
        tenants.forEachIndexed { i, tenant ->
            if (i > 0 && !pause.isZero) sleeper(pause)
            try {
                TenantContext.asTenant(tenant.id) { block() }
            } catch (e: Exception) {
                log.warn("Job '{}' failed for tenant '{}': {}", name, tenant.id, e.javaClass.simpleName)
            }
        }
    }
}

/** Flag off: the runner bean does not exist and the job simply runs once, exactly as before. */
fun ObjectProvider<TenantJobRunner>.runPerTenant(name: String, block: () -> Unit) {
    val runner = ifAvailable
    if (runner == null) block() else runner.runAll(name, block)
}
