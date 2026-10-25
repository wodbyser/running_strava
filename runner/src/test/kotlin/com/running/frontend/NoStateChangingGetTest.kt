package com.running.frontend

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping

class NoStateChangingGetTest {

    private val stateChanging = listOf("sync", "fetch", "backfill", "rhr")

    @Test
    fun `no GET endpoint in the Strava controllers changes state`() {
        val offenders = listOf(FrontendController::class.java, CoachController::class.java)
            .flatMap { c -> c.declaredMethods.mapNotNull { it.getAnnotation(GetMapping::class.java) } }
            .flatMap { it.value.toList() }
            .filter { path -> stateChanging.any { path.contains(it) } }
        assertTrue(offenders.isEmpty(), "state-changing GET endpoints: $offenders")
    }
}
