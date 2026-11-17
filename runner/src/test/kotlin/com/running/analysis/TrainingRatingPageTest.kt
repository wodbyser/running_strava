package com.running.analysis

import com.running.analysis.Fixtures.run
import com.running.strava.analysis.TrainingAssessment
import com.running.strava.domain.Lap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** Wording of the training assessment page: the numbers come from core, this checks how they are named. */
class TrainingRatingPageTest {

    private val start = ZonedDateTime.parse("2026-11-15T09:30:00+01:00")

    private fun lap(index: Int, meters: Double, seconds: Double, hr: Double?) = Lap(
        id = index.toLong(), name = "Lap $index", elapsedTime = seconds.toInt(), movingTime = seconds.toInt(),
        startDate = start.plusSeconds((index * seconds).toLong()), startIndex = 0, endIndex = 0,
        distance = meters.toFloat(), averageSpeed = (meters / seconds).toFloat(), maxSpeed = (meters / seconds * 1.1).toFloat(),
        averageHeartrate = hr?.toFloat(), maxHeartrate = hr?.plus(8)?.toFloat(), averageCadence = 84f,
        lapIndex = index, split = index + 1,
    )

    private fun steadyLaps(): List<Lap> {
        val paces = listOf(320.0, 318.0, 322.0, 319.0, 321.0, 325.0, 328.0, 326.0, 329.0, 330.0)
        return paces.mapIndexed { i, s -> lap(i, 1000.0, s, if (i < 5) 145.0 else 152.0) }
    }

    private fun view(activity: com.running.strava.domain.Activity, laps: List<Lap>) =
        TrainingRatingPageBuilder.build(activity, TrainingAssessment.assess(activity, laps, listOf(activity), emptyList()))

    @Test
    fun `an even easy run gets a Dutch conclusion without repeated units`() {
        val activity = run(1, start, 10000.0, 3230, hr = 150.0, maxHr = 190.0)
        val v = view(activity, steadyLaps())

        assertEquals("Uitvoering klopt", v.hero.levelLabel)
        assertEquals("Duurloop van 10.00 km, gelijkmatig", v.hero.headline)
        assertEquals("Datum" to "15 nov 2026", v.hero.facts.first())
        assertEquals(5, v.hero.points.size)
        assertEquals("Gelijkmatigheid", v.hero.points.first().label)

        // The pace helper already carries "/km"; a range must not add it a second time.
        val heroText = v.hero.points.flatMap { listOf(it.value, it.sub ?: "") } + v.hero.conclusion
        assertTrue(heroText.none { it.contains("/km /km") })
        assertFalse(v.hero.conclusion.isEmpty())
        assertEquals(4, v.load.tiles.size)
    }

    @Test
    fun `a treadmill run without laps and heart rate gets no verdict and says why`() {
        val activity = run(2, start, 0.0, 0, hr = null, trainer = true, maxHr = null)
        val v = view(activity, emptyList())

        assertEquals("Geen oordeel", v.hero.levelLabel)
        assertEquals("Loopband: geen oordeel", v.hero.headline)
        assertTrue(v.hero.sub.startsWith("Daarom geeft de app geen oordeel"))
        assertTrue(v.hero.sub.contains("geen rondes"))
        assertTrue(v.caveats.any { it.startsWith("Loopband") })
        assertTrue(v.repRows.isEmpty())
    }
}
