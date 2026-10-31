package com.running.garmin.web

import com.running.analysis.RecoveryService
import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.spi.GarminSyncStatusRepository
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

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
        if (!properties.enabled) return "Garmin-synchronisatie staat uit (garmin.enabled=false)."
        if (!properties.configured) return "Geen Garmin-sleutel ingesteld: synchroniseren kan niet."
        if (coordinator.isRunning) return "Synchronisatie bezig. Ververs de pagina over een minuut."
        val st = statusRepository.get()
        val ok = st.lastSuccessAt?.atZone(RecoveryService.ZONE)?.format(DateTimeFormatter.ofPattern("d/MM/yyyy HH:mm"))
        val err = st.lastErrorCategory?.let { " Laatste fout: $it." } ?: ""
        return (ok?.let { "Laatste geslaagde synchronisatie: $it." } ?: "Nog niet gesynchroniseerd.") + err
    }
}
