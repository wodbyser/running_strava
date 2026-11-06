package com.running.race

import java.time.LocalDate

/** Storage of race goals (design P5-5). The adapter lives in the runner module. */
interface RaceGoalRepository {
    fun findAll(): List<RaceGoal>
    fun save(distanceMeters: Double, date: LocalDate, targetSeconds: Int?, name: String?): RaceGoal
    fun delete(id: Long)
}
