package com.running.load

import com.running.strava.domain.ActivityStream
import java.time.LocalDate
import kotlin.math.exp

object T {
    val D0: LocalDate = LocalDate.of(2026, 1, 5) // a Monday

    fun session(
        id: Long,
        date: LocalDate,
        cls: SessionClass = SessionClass.EASY,
        trimp: Double? = 100.0,
        isRun: Boolean = true,
        minutes: Int = 60,
        km: Double = 10.0,
    ) = LoadSession(
        id, date, "run $id", if (isRun) "Run" else "Walk", isRun, cls, minutes * 60, km * 1000, 0.0, null, null, trimp,
        if (trimp == null) TrimpSource.NONE else TrimpSource.STREAM, null,
    )

    fun stream(times: List<Int>, hr: List<Int>) = ActivityStream(times, null, null, null, hr, null, null, null, null)

    /** One sample per [step] seconds for [seconds] seconds at a constant heart rate. */
    fun constantStream(seconds: Int, hr: Int, step: Int = 1): ActivityStream {
        val t = (0..seconds step step).toList()
        return stream(t, t.map { hr })
    }

    fun week(start: LocalDate, trimp: Double?, runs: Int = 3, complete: Boolean = true, known: Int = 7) = WeekLoad(
        start, complete, trimp, known, runs * 10.0, runs * 3600, null, 0.0, runs, null, null, null, 0.0,
    )

    /** Series with a value on every day from [from] for [days] days. */
    fun series(from: LocalDate, days: Int, f: (Int) -> Double?): Map<LocalDate, Double> =
        (0 until days).mapNotNull { i -> f(i)?.let { from.plusDays(i.toLong()) to it } }.toMap()

    fun alpha(tau: Double) = 1 - exp(-1 / tau)
}
