package com.running.strava.domain

import java.time.ZonedDateTime

/**
 * Golden dataset: synthetic but realistic lap lists for every session type the app must handle.
 * Each fixture documents what the session *is*; tests assert the app recognises exactly that.
 */
object GoldenLaps {

    /** One lap: distance (m), moving time (s), average HR (null = no HR strap). */
    data class L(val d: Double, val t: Int, val hr: Int? = 150)

    fun laps(vararg rows: L): List<Lap> = rows.mapIndexed { i, r ->
        Lap(
            id = i.toLong(), name = null, elapsedTime = r.t, movingTime = r.t, startDate = ZonedDateTime.parse("2026-09-01T08:00:00Z"),
            startIndex = 0, endIndex = 0, distance = r.d.toFloat(), averageSpeed = (r.d / r.t).toFloat(), maxSpeed = 0f,
            averageHeartrate = r.hr?.toFloat(), maxHeartrate = r.hr?.plus(8)?.toFloat(), averageCadence = 84f,
            lapIndex = i + 1, split = 0,
        )
    }

    /** Lap of [d] metres at [pace] seconds per km. */
    fun p(d: Double, pace: Int, hr: Int? = 150) = L(d, Math.round(d / 1000 * pace).toInt(), hr)

    val easyRun = laps(
        p(1000.0, 355, 138), p(1000.0, 360, 141), p(1000.0, 352, 142), p(1000.0, 365, 143),
        p(1000.0, 358, 144), p(1000.0, 350, 145), p(1000.0, 362, 145), p(1000.0, 356, 146), p(400.0, 350, 146),
    )

    /** 18 km long run with natural drift 5:40-6:20 and a slightly faster last km. */
    val longRun = laps(
        *(listOf(360, 355, 348, 342, 350, 365, 372, 380, 360, 345, 352, 358, 340, 362, 370, 355, 348, 330)
            .mapIndexed { i, pace -> p(1000.0, pace, 140 + i / 2) }.toTypedArray()),
    )

    /** 2 km warm-up, 5 km tempo @ 4:30, 1 km cool-down. */
    val tempo = laps(
        p(1000.0, 345, 135), p(1000.0, 345, 140),
        p(1000.0, 270, 165), p(1000.0, 270, 168), p(1000.0, 270, 170), p(1000.0, 268, 172), p(1000.0, 272, 173),
        p(1000.0, 350, 150),
    )

    /** 2 km warm-up, 8x (400 m @ 3:50 + 400 m float @ 4:45), 1 km cool-down. */
    val floatsShortWarmup = laps(
        p(1000.0, 345, 135), p(1000.0, 345, 140),
        *(1..8).flatMap { listOf(p(400.0, 230, 172), p(400.0, 285, 165)) }.toTypedArray(),
        p(1000.0, 350, 150),
    )

    /** 1.5 km warm-up, pyramid 400-800-1200-800-400 with 90 s jog recoveries, 1 km cool-down. */
    val pyramid = laps(
        p(1500.0, 345, 138),
        p(400.0, 225, 168), L(200.0, 90, 150),
        p(800.0, 235, 172), L(200.0, 90, 152),
        p(1200.0, 245, 175), L(200.0, 90, 154),
        p(800.0, 235, 176), L(200.0, 90, 155),
        p(400.0, 225, 177),
        p(1000.0, 355, 148),
    )

    /** 2 km warm-up @ 5:50, 8x hill (300 m up @ 5:10, 300 m jog down @ 6:30), 1.5 km cool-down. */
    val hillRepeats = laps(
        p(2000.0, 350, 138),
        *(1..8).flatMap { listOf(p(300.0, 310, 170), p(300.0, 390, 150)) }.toTypedArray(),
        p(1500.0, 360, 145),
    )

    /** Time-based: 2 km warm-up, 6x 3 min (distance varies 820-860 m), 90 s jog, 1 km cool-down. */
    val timeBased = laps(
        p(1000.0, 350, 135), p(1000.0, 348, 140),
        L(820.0, 180, 170), L(200.0, 90, 150), L(850.0, 180, 172), L(210.0, 90, 152),
        L(860.0, 180, 174), L(205.0, 90, 153), L(835.0, 180, 175), L(200.0, 90, 154),
        L(845.0, 180, 176), L(195.0, 90, 155), L(830.0, 180, 177), L(200.0, 90, 150),
        p(1000.0, 355, 145),
    )

    /** 6x 800 m without a HR strap. */
    val sixBy800NoHr = laps(
        p(1000.0, 350, null), p(1000.0, 345, null),
        *(1..6).flatMap { listOf(p(800.0, 225, null), L(200.0, 90, null)) }.toTypedArray(),
        p(1000.0, 355, null),
    )

    /** Easy 6 km with one 1 km lap at 3:10 from a GPS glitch: heart rate does not react. */
    val gpsGlitch = laps(
        p(1000.0, 360, 140), p(1000.0, 358, 141), p(1000.0, 190, 141), p(1000.0, 362, 142), p(1000.0, 360, 142), p(1000.0, 359, 143),
    )

    /** Easy run with an accidental double lap press (5 m in 2 s). */
    val accidentalLap = laps(
        p(1000.0, 360, 140), p(1000.0, 358, 141), L(5.0, 2, 141), p(1000.0, 362, 142), p(1000.0, 360, 142),
    )

    /** 10 km race, even pace 4:10. */
    val race = laps(*(1..10).map { p(1000.0, 250 + (it % 3), 175) }.toTypedArray())

    /** Progression 10x 1 km from 6:00 to 4:30. */
    val progression = laps(*(0 until 10).map { p(1000.0, 360 - it * 10, 140 + it * 3) }.toTypedArray())

    /** Easy 5 km + 500 m finishing kick @ 4:00. */
    val finishingKick = laps(
        p(1000.0, 360, 140), p(1000.0, 358, 141), p(1000.0, 355, 142), p(1000.0, 357, 143), p(1000.0, 356, 144), p(500.0, 240, 168),
    )

    /** 2 km warm-up, 5x 1 km tempo @ 4:30, then 4x (100 m stride @ 3:20 + 100 m jog). */
    val tempoWithStrides = laps(
        p(1000.0, 345, 135), p(1000.0, 345, 140),
        p(1000.0, 270, 165), p(1000.0, 270, 168), p(1000.0, 270, 170), p(1000.0, 270, 172), p(1000.0, 270, 173),
        *(1..4).flatMap { listOf(L(100.0, 20, 165), L(100.0, 40, 155)) }.toTypedArray(),
    )

    /** Easy 6 km with 4 strides at the end. */
    val easyWithStrides = laps(
        p(1000.0, 360, 140), p(1000.0, 358, 141), p(1000.0, 355, 142), p(1000.0, 357, 143), p(1000.0, 356, 144), p(1000.0, 355, 144),
        *(1..4).flatMap { listOf(L(100.0, 20, 160), L(100.0, 45, 150)) }.toTypedArray(),
    )
}
