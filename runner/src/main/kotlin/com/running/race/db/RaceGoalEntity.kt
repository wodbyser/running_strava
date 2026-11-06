package com.running.race.db

import com.running.race.RaceGoal
import com.running.race.RaceGoalRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

/** Additive table, created by `ddl-auto: update`. Holds the race goals typed in on the race page (design P5-5). */
@Entity
@Table(name = "race_goal")
class RaceGoalEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "distance_m", nullable = false) var distanceMeters: Double = 0.0,
    @Column(name = "race_date", nullable = false) var raceDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "target_seconds") var targetSeconds: Int? = null,
    @Column(name = "name", length = 120) var name: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
)

interface RaceGoalJpaRepository : JpaRepository<RaceGoalEntity, Long>

@Repository
class RaceGoalRepositoryImpl(private val jpa: RaceGoalJpaRepository) : RaceGoalRepository {
    override fun findAll(): List<RaceGoal> = jpa.findAll().map { it.toGoal() }.sortedBy { it.date }

    override fun save(distanceMeters: Double, date: LocalDate, targetSeconds: Int?, name: String?): RaceGoal =
        jpa.save(RaceGoalEntity(null, distanceMeters, date, targetSeconds, name?.trim()?.takeIf { it.isNotEmpty() }?.take(120), Instant.now())).toGoal()

    override fun delete(id: Long) {
        if (jpa.existsById(id)) jpa.deleteById(id)
    }

    private fun RaceGoalEntity.toGoal() = RaceGoal(id!!, distanceMeters, raceDate, targetSeconds, name)
}
