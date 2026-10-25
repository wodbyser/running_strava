package com.running.garmin.usecase

import com.running.garmin.domain.GarminRunKind
import com.running.garmin.domain.GarminSyncResult
import com.running.garmin.spi.GarminSyncStatusRepository
import java.time.LocalDate

interface BackfillGarminData {
    /** Defaults: `to` = yesterday, `from` = `to` minus the default backfill days (Europe/Brussels). */
    fun execute(from: LocalDate? = null, to: LocalDate? = null): GarminSyncResult
}

interface SyncGarminData {
    fun execute(): GarminSyncResult
}

class BackfillGarminDataImpl(
    private val engine: GarminRangeSync,
    private val settings: GarminSyncSettings,
) : BackfillGarminData {
    override fun execute(from: LocalDate?, to: LocalDate?): GarminSyncResult {
        val end = to ?: engine.today().minusDays(1)
        val start = from ?: end.minusDays(settings.defaultBackfillDays.toLong())
        return engine.run(GarminRunKind.BACKFILL, start, end)
    }
}

/** Incremental: from the last successful run date minus the overlap, to today. Never run before: the default backfill range. */
class SyncGarminDataImpl(
    private val engine: GarminRangeSync,
    private val statusRepository: GarminSyncStatusRepository,
    private val settings: GarminSyncSettings,
) : SyncGarminData {
    override fun execute(): GarminSyncResult {
        val today = engine.today()
        val last = statusRepository.get().lastSuccessDate
        val from = if (last == null) today.minusDays(settings.defaultBackfillDays.toLong())
        else minOf(last.minusDays(settings.overlapDays.toLong()), today)
        return engine.run(GarminRunKind.SYNC, from, today)
    }
}
