package com.running.garmin.adapter

import com.running.config.GarminProperties
import com.running.garmin.domain.GarminSyncResult
import com.running.garmin.usecase.BackfillGarminData
import com.running.garmin.usecase.SyncGarminData
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

enum class GarminStartOutcome { STARTED, BUSY, DISABLED, NOT_CONFIGURED }

/**
 * One Garmin run at a time. A second trigger gets BUSY and makes no HTTP call.
 * With the flag off or no key, nothing starts and no HTTP call is made.
 */
class GarminSyncCoordinator(
    private val properties: GarminProperties,
    private val syncGarminData: SyncGarminData,
    private val backfillGarminData: BackfillGarminData,
    private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(GarminSyncCoordinator::class.java)
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun startSync(async: Boolean = true): GarminStartOutcome = launch("sync", async) { syncGarminData.execute() }

    fun startBackfill(from: LocalDate?, to: LocalDate?, async: Boolean = true): GarminStartOutcome =
        launch("backfill", async) { backfillGarminData.execute(from, to) }

    private fun launch(name: String, async: Boolean, job: () -> GarminSyncResult): GarminStartOutcome {
        if (!properties.enabled) return GarminStartOutcome.DISABLED
        if (!properties.configured) return GarminStartOutcome.NOT_CONFIGURED
        if (!running.compareAndSet(false, true)) return GarminStartOutcome.BUSY
        val task = Runnable {
            try {
                val result = job()
                log.info(
                    "Garmin {} finished: records={}, failed calls={}, aborted={}",
                    name, result.recordsPerEndpoint, result.failures.size, result.aborted,
                )
            } catch (e: Exception) {
                log.warn("Garmin {} failed: {}", name, e.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
        if (async) {
            try {
                executor.execute(task)
            } catch (e: RuntimeException) {
                running.set(false)
                throw e
            }
        } else {
            task.run()
        }
        return GarminStartOutcome.STARTED
    }
}
