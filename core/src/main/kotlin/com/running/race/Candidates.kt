package com.running.race

import com.running.strava.domain.SessionType

/** A run that looks like a race but is not marked on Strava (design 1.3, P5-7). Read only: the owner decides and marks it on Strava. */
data class Candidate(val run: RaceRun, val strong: Boolean, val reasons: List<String>)

object Candidates {
    private val RACE_WORDS = Regex("(?i)(wedstrijd|race|marathon|halve|corrida|ekiden|10 ?miles|10 ?mijl|cross)")
    private val OFFICIAL_TIME = Regex("""\b\d{1,2}[:'’]\d{2}([:'’]\d{2})?\b""")
    const val MIN_METERS = 3000.0
    const val HARD_HR = 170.0
    const val HARD_MIN_SECONDS = 12 * 60
    const val FAST_SECONDS_PER_KM = 285.0
    const val FAST_MIN_SECONDS = 15 * 60
    const val MAX_WEAK = 10
    const val MAX_STRONG = 15

    fun find(runs: List<RaceRun>): List<Candidate> {
        val all = runs.filter { !Races.isRace(it) && it.workoutType != Races.RACE && !it.isTrainer && it.distanceMeters >= MIN_METERS }.mapNotNull { r ->
            val reasons = ArrayList<String>()
            var strong = false
            if (RACE_WORDS.containsMatchIn(r.name)) { reasons += "wedstrijdwoord in de naam"; strong = true }
            if (OFFICIAL_TIME.containsMatchIn(r.name)) { reasons += "tijd in de naam"; strong = true }
            val hard = (r.avgHr ?: 0.0) >= HARD_HR && r.movingSeconds >= HARD_MIN_SECONDS
            val fast = r.movingSeconds >= FAST_MIN_SECONDS && r.movingSeconds / (r.distanceMeters / 1000) < FAST_SECONDS_PER_KM
            if (hard) reasons += "gemiddelde hartslag van ${Math.round(r.avgHr!!)} bpm"
            if (fast) reasons += "tempo onder 4:45 per km"
            if (reasons.isEmpty() || (!strong && r.sessionType == SessionType.INTERVAL)) null else Candidate(r, strong, reasons)
        }
        val strongList = all.filter { it.strong }.sortedByDescending { it.run.startEpochSeconds }.take(MAX_STRONG)
        val weakList = all.filter { !it.strong }.sortedByDescending { it.reasons.size * 1_000_000_000_000L + it.run.startEpochSeconds }.take(MAX_WEAK)
        return (strongList + weakList).sortedByDescending { it.run.startEpochSeconds }
    }
}
