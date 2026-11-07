package com.running.race.db

import com.running.race.LoggedPrediction
import com.running.race.PredictionLogRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** Additive table, created by `ddl-auto: update`. One frozen prediction per day and distance (race-v3, design section 13). */
@Entity
@Table(name = "race_prediction_log", uniqueConstraints = [UniqueConstraint(columnNames = ["log_date", "distance_m"])])
class RacePredictionLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "log_date", nullable = false) var logDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "distance_m", nullable = false) var distanceMeters: Double = 0.0,
    @Column(name = "seconds", nullable = false) var seconds: Double = 0.0,
    @Column(name = "low_seconds") var lowSeconds: Double? = null,
    @Column(name = "high_seconds") var highSeconds: Double? = null,
    @Column(name = "algorithm_version", nullable = false, length = 20) var algorithmVersion: String = "",
    @Column(name = "anchor_count", nullable = false) var anchorCount: Int = 0,
    @Column(name = "capped_by_activity_id") var cappedByActivityId: Long? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH,
)

interface RacePredictionLogJpaRepository : JpaRepository<RacePredictionLogEntity, Long> {
    fun findByLogDate(logDate: LocalDate): List<RacePredictionLogEntity>
}

@Repository
class PredictionLogRepositoryImpl(private val jpa: RacePredictionLogJpaRepository) : PredictionLogRepository {
    @Transactional
    override fun record(entries: List<LoggedPrediction>) {
        entries.groupBy { it.date }.forEach { (date, rows) ->
            val existing = jpa.findByLogDate(date).associateBy { it.distanceMeters }
            rows.forEach { e ->
                val row = existing[e.distanceMeters] ?: RacePredictionLogEntity(logDate = date, distanceMeters = e.distanceMeters)
                row.seconds = e.seconds
                row.lowSeconds = e.lowSeconds
                row.highSeconds = e.highSeconds
                row.algorithmVersion = e.algorithmVersion
                row.anchorCount = e.anchorCount
                row.cappedByActivityId = e.cappedByActivityId
                row.updatedAt = Instant.now()
                jpa.save(row)
            }
        }
    }

    override fun findAll(): List<LoggedPrediction> = jpa.findAll().map {
        LoggedPrediction(it.logDate, it.distanceMeters, it.seconds, it.lowSeconds, it.highSeconds, it.algorithmVersion, it.anchorCount, it.cappedByActivityId)
    }.sortedWith(compareBy({ it.date }, { it.distanceMeters }))
}
