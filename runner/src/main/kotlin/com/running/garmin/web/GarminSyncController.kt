package com.running.garmin.web

import com.running.config.GarminProperties
import com.running.garmin.adapter.GarminStartOutcome
import com.running.garmin.adapter.GarminSyncCoordinator
import com.running.garmin.domain.GarminErrorCategory
import com.running.garmin.domain.GarminRunKind
import com.running.garmin.domain.GarminSyncStatus
import com.running.garmin.matching.GarminActivityMatcher
import com.running.garmin.matching.GarminMatchResult
import com.running.garmin.matching.MatchReport
import com.running.garmin.matching.MatchTier
import com.running.garmin.spi.GarminRepository
import com.running.garmin.spi.GarminSyncStatusRepository
import com.running.strava.spi.ActivityRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class GarminState { DISABLED, NOT_CONFIGURED, RUNNING, AUTH_FAILED, NEVER_RUN, OK, PARTIAL_FAILURE }

/** Contains no key, no token and no health value. */
data class GarminStatusView(
    val state: GarminState,
    val lastRunKind: GarminRunKind?,
    val lastRunStartedAt: Instant?,
    val lastRunFinishedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastSuccessDate: LocalDate?,
    val recordsPerEndpoint: Map<String, Int>,
    val nullFieldsPerEndpoint: Map<String, Int>,
    val failedCalls: Int,
    val lastErrorCategory: GarminErrorCategory?,
    val lastErrorDetail: String?,
    val reviewMatchCount: Int?,
)

data class GarminReviewCandidateView(
    val stravaActivityId: Long,
    val startDiffSeconds: Long,
    val distanceDiffPercent: Double?,
    val elapsedDiffPercent: Double?,
    val startPositionDiffMeters: Double?,
    val start: String,
    val distance: String,
    val elapsed: String,
    val position: String?,
)

data class GarminReviewView(
    val garminActivityId: Long,
    val garminStartTimeGmt: Instant,
    val reasons: List<String>,
    val candidates: List<GarminReviewCandidateView>,
)

@RestController
@RequestMapping("/garmin")
class GarminSyncController(
    private val properties: GarminProperties,
    private val coordinator: GarminSyncCoordinator,
    private val statusRepository: GarminSyncStatusRepository,
    private val garminRepository: GarminRepository,
    private val activityRepository: ActivityRepository,
    private val clock: Clock,
) {
    private val matcher = GarminActivityMatcher()
    private val zone = ZoneId.of("Europe/Brussels")

    @PostMapping("/sync")
    fun sync(): ResponseEntity<Map<String, Any>> = respond(coordinator.startSync())

    @PostMapping("/backfill")
    fun backfill(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<Map<String, Any>> {
        val today = LocalDate.now(clock.withZone(zone))
        if (from != null && to != null && from.isAfter(to)) return bad("from must not be after to")
        if (to != null && to.isAfter(today)) return bad("to must not be in the future")
        if (from != null && from.isAfter(today)) return bad("from must not be in the future")
        if (from != null && to == null && from.isAfter(today.minusDays(1))) return bad("from must be before yesterday when to is not given")
        return respond(coordinator.startBackfill(from, to))
    }

    @GetMapping("/status")
    fun status(): GarminStatusView {
        val status = statusRepository.get()
        val state = state(status)
        val review = if (state == GarminState.DISABLED) null else reviewResults().count()
        return GarminStatusView(
            state, status.lastRunKind, status.lastRunStartedAt, status.lastRunFinishedAt, status.lastSuccessAt, status.lastSuccessDate,
            status.recordsPerEndpoint, status.nullFieldsPerEndpoint, status.failedCalls, status.lastErrorCategory, status.lastErrorDetail, review,
        )
    }

    /** Read-only. Nothing is stored or linked in Phase 1. */
    @GetMapping("/matches/review")
    fun matchReview(): List<GarminReviewView> {
        val garmin = garminRepository.findAllActivities().associateBy { it.activityId }
        return reviewResults().map { r ->
            GarminReviewView(r.garminActivityId, garmin.getValue(r.garminActivityId).startTimeGmt, r.reasons, r.candidates.map {
                GarminReviewCandidateView(
                    it.stravaActivityId, it.startDiffSeconds, it.distanceDiffRatio?.times(100), it.elapsedDiffRatio?.times(100),
                    it.startPositionDiffMeters, it.start.name, it.distance.name, it.elapsed.name, it.position?.name,
                )
            })
        }
    }

    /** Dry run for the threshold decision (docs/garmin-data-provenance.md section 11). Read-only. */
    @GetMapping("/matches/report")
    fun matchReport(): MatchReport = matcher.report(garminRepository.findAllActivities(), activityRepository.findAll())

    private fun reviewResults(): List<GarminMatchResult> =
        matcher.match(garminRepository.findAllActivities(), activityRepository.findAll()).filter { it.tier == MatchTier.REVIEW }

    private fun state(status: GarminSyncStatus): GarminState = when {
        !properties.enabled -> GarminState.DISABLED
        !properties.configured -> GarminState.NOT_CONFIGURED
        coordinator.isRunning -> GarminState.RUNNING
        status.lastRunFinishedAt == null -> GarminState.NEVER_RUN
        status.lastErrorCategory == GarminErrorCategory.AUTH -> GarminState.AUTH_FAILED
        status.failedCalls > 0 || status.lastErrorDetail != null -> GarminState.PARTIAL_FAILURE
        else -> GarminState.OK
    }

    private fun respond(outcome: GarminStartOutcome): ResponseEntity<Map<String, Any>> = when (outcome) {
        GarminStartOutcome.STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("started" to true))
        GarminStartOutcome.BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("started" to false, "state" to "RUNNING"))
        GarminStartOutcome.DISABLED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("started" to false, "state" to "DISABLED"))
        GarminStartOutcome.NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("started" to false, "state" to "NOT_CONFIGURED"))
    }

    private fun bad(message: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("started" to false, "error" to message))
}
