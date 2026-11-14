package com.running.garmin.web

import com.running.analysis.PerfDates
import com.running.analysis.RecoveryService
import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.spi.GarminSyncStatusRepository
import org.springframework.stereotype.Component

/** Texts and state for the "Garmin synchroniseren" button, shared by the pages that show it. */
@Component
class GarminSyncView(
    private val properties: GarminProperties,
    private val coordinator: GarminSyncCoordinator,
    private val statusRepository: GarminSyncStatusRepository,
) {
    val running: Boolean get() = coordinator.isRunning
    val possible: Boolean get() = properties.enabled && properties.configured && !coordinator.isRunning

    fun line(): String {
        if (!properties.enabled) return "Garmin-synchronisatie staat uit in de instellingen."
        if (!properties.configured) return "Geen Garmin-sleutel ingesteld: synchroniseren kan niet."
        if (coordinator.isRunning) return "Synchronisatie bezig. De pagina ververst vanzelf als het klaar is."
        val st = statusRepository.get()
        val ok = st.lastSuccessAt?.atZone(RecoveryService.ZONE)?.toLocalDateTime()?.let { PerfDates.dayTime(it) }
        val err = st.lastErrorCategory?.let { " Laatste fout: $it." } ?: ""
        return (ok?.let { "Laatste Garmin-sync: $it." } ?: "Nog niet gesynchroniseerd.") + err
    }

    /** Flash text once the background run has stopped, based on the stored status of that run. */
    fun finishedMessage(): Pair<String, String> {
        val st = statusRepository.get()
        return when {
            st.lastErrorCategory == GarminErrorCategory.AUTH ->
                "Garmin-synchronisatie klaar, maar de aanmelding bij Garmin lukte niet. Controleer de Garmin-sleutel." to "warning"
            st.failedCalls > 0 || st.lastErrorDetail != null ->
                "Garmin-synchronisatie klaar, maar niet alles is opgehaald (${st.failedCalls} mislukte oproep(en))." to "warning"
            else -> "Garmin-synchronisatie klaar: je Garmin-gegevens zijn bijgewerkt." to "success"
        }
    }
}
