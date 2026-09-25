package com.running.strava.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class WorkoutStructureTest {

    /** row = distance (m), moving time (s), avg HR, max HR */
    private fun laps(vararg rows: IntArray): List<Lap> = rows.mapIndexed { i, (d, t, hr, max) ->
        Lap(
            id = i.toLong(), name = null, elapsedTime = t, movingTime = t, startDate = ZonedDateTime.now(),
            startIndex = 0, endIndex = 0, distance = d.toFloat(), averageSpeed = d.toFloat() / t, maxSpeed = 0f,
            averageHeartrate = hr.toFloat(), maxHeartrate = max.toFloat(), averageCadence = null, lapIndex = i, split = 0,
        )
    }

    private fun r(d: Int, t: Int, hr: Int, max: Int) = intArrayOf(d, t, hr, max)

    private val sixBy800EightBy400 = laps(
        r(1000, 348, 133, 141), r(1000, 344, 140, 147), r(500, 173, 140, 143), r(27, 11, 142, 142),
        r(800, 178, 177, 186), r(198, 90, 158, 184), r(800, 182, 160, 167), r(193, 90, 161, 180),
        r(800, 180, 176, 185), r(201, 90, 165, 185), r(800, 182, 181, 189), r(181, 90, 163, 188),
        r(800, 182, 179, 190), r(188, 90, 163, 186), r(800, 182, 177, 186), r(162, 90, 159, 182),
        r(139, 60, 138, 140),
        r(400, 86, 174, 190), r(118, 60, 174, 189), r(400, 86, 181, 191), r(101, 60, 169, 191),
        r(400, 85, 171, 183), r(101, 60, 169, 185), r(400, 86, 164, 178), r(106, 60, 168, 180),
        r(400, 84, 163, 172), r(104, 60, 166, 173), r(400, 85, 171, 185), r(110, 60, 172, 185),
        r(400, 86, 182, 190), r(100, 60, 171, 188), r(400, 84, 171, 184), r(95, 60, 172, 185),
        r(1000, 345, 143, 150), r(1000, 349, 129, 134), r(500, 174, 132, 137), r(31, 11, 137, 138),
    )

    private val eightFastEightModerate = laps(
        r(1000, 359, 137, 152), r(1000, 354, 139, 150), r(1000, 359, 134, 139), r(1000, 362, 132, 135),
        r(400, 92, 159, 175), r(400, 114, 164, 178), r(400, 95, 168, 173), r(400, 115, 167, 173),
        r(400, 94, 171, 176), r(400, 115, 169, 175), r(400, 94, 170, 176), r(400, 114, 168, 176),
        r(400, 92, 171, 176), r(400, 114, 169, 176), r(400, 93, 173, 177), r(400, 113, 170, 177),
        r(400, 93, 175, 179), r(400, 111, 173, 179), r(400, 92, 178, 181), r(400, 112, 173, 180),
        r(141, 90, 146, 170), r(1000, 345, 145, 150), r(1000, 343, 146, 148), r(500, 173, 147, 150),
        r(16, 7, 144, 145),
    )

    @Test
    fun `6x800 and 8x400 are reported as two separate sets`() {
        val s = WorkoutStructure.analyze(sixBy800EightBy400)!!
        println(WorkoutStructure.describe(sixBy800EightBy400))
        assertEquals(listOf(6, 8), s.sets.map { it.reps.laps.size })
        assertTrue(s.sets[0].reps.laps.all { it.distance == 800f })
        assertTrue(s.sets[1].reps.laps.all { it.distance == 400f })
        assertEquals(listOf(90), s.sets[0].recovery.laps.map { it.movingTime }.distinct())
        assertEquals(8, s.sets[1].recovery.laps.size)
        assertEquals(2500.0, s.warmup.distance)
        assertEquals(2500.0, s.cooldown.distance)
        val text = WorkoutStructure.describe(sixBy800EightBy400)!!
        assertTrue(text.contains("6x 800m"))
        assertTrue(text.contains("8x 400m"))
    }

    @Test
    fun `fast and moderate 400s are reps and floats, not 16 reps`() {
        val s = WorkoutStructure.analyze(eightFastEightModerate)!!
        println(WorkoutStructure.describe(eightFastEightModerate))
        assertEquals(1, s.sets.size)
        assertEquals(8, s.sets[0].reps.laps.size)
        assertEquals(8, s.sets[0].floats.laps.size)
        assertEquals(4000.0, s.warmup.distance)
        val text = WorkoutStructure.describe(eightFastEightModerate)!!
        assertTrue(text.contains("8x 400m"))
        assertTrue(text.contains("8x 400m float"))
    }

    @Test
    fun `steady run has no intervals`() {
        val steady = laps(r(1000, 330, 140, 150), r(1000, 325, 142, 150), r(1000, 328, 145, 152))
        assertEquals(null, WorkoutStructure.describe(steady))
    }

    @Test
    fun `pyramid is one ladder set, not five sets with set pauses`() {
        val s = WorkoutStructure.analyze(GoldenLaps.pyramid)!!
        assertEquals(1, s.sets.size)
        assertTrue(s.sets[0].ladder)
        assertEquals(listOf(400f, 800f, 1200f, 800f, 400f), s.sets[0].reps.laps.map { it.distance })
        assertEquals(4, s.sets[0].recovery.laps.size)
        assertTrue(s.setPauses.isEmpty())
        assertTrue(WorkoutStructure.describe(GoldenLaps.pyramid)!!.contains("ladder 400-800-1200-800-400m"))
    }

    @Test
    fun `tempo block does not disappear into the warm-up when strides follow`() {
        val s = WorkoutStructure.analyze(GoldenLaps.tempoWithStrides)!!
        assertEquals(1, s.tempoBlocks.size)
        assertEquals(5000.0, s.tempoBlocks[0].distance)
        assertEquals(2000.0, s.warmup.distance)
        assertEquals(4, s.sets.single().reps.laps.size)
        val text = WorkoutStructure.describe(GoldenLaps.tempoWithStrides)!!
        assertTrue(text.contains("tempoblok 5.00 km in 22m30s @ 4:30 /km"), text)
    }

    @Test
    fun `tempo run is described as easy - tempo - easy without warm-up claims`() {
        val text = WorkoutStructure.describe(GoldenLaps.tempo)!!
        // 5 km in 270+270+270+268+272 = 1350 s = 22m30s -> 4:30 /km
        assertEquals("rustig 2.00 km @ 5:45 /km, gem. HR 138 | tempoblok 5.00 km in 22m30s @ 4:30 /km, gem. HR 170 | rustig 1.00 km @ 5:50 /km, gem. HR 150", text)
    }

    @Test
    fun `floats after a short warm-up are reported as floats`() {
        val s = WorkoutStructure.analyze(GoldenLaps.floatsShortWarmup)!!
        assertEquals(1, s.sets.size)
        assertEquals(8, s.sets[0].reps.laps.size)
        assertEquals(8, s.sets[0].floats.laps.size)
        assertEquals(0, s.sets[0].recovery.laps.size)
        assertEquals(2000.0, s.warmup.distance)
        assertEquals(1000.0, s.cooldown.distance)
    }

    @Test
    fun `hill repeats keep the warm-up out of the reps`() {
        val s = WorkoutStructure.analyze(GoldenLaps.hillRepeats)!!
        assertEquals(8, s.sets.single().reps.laps.size)
        assertEquals(2000.0, s.warmup.distance)
        assertEquals(1500.0, s.cooldown.distance)
    }

    @Test
    fun `time-based reps are described by duration`() {
        val s = WorkoutStructure.analyze(GoldenLaps.timeBased)!!
        assertTrue(s.sets.single().timeBased)
        assertTrue(WorkoutStructure.describe(GoldenLaps.timeBased)!!.contains("6x 3m00s"))
    }

    @Test
    fun `easy run, gps glitch, accidental lap and race produce no structure`() {
        assertEquals(null, WorkoutStructure.describe(GoldenLaps.easyRun))
        assertEquals(null, WorkoutStructure.describe(GoldenLaps.gpsGlitch))
        assertEquals(null, WorkoutStructure.describe(GoldenLaps.accidentalLap))
        assertEquals(null, WorkoutStructure.describe(GoldenLaps.race, workoutType = 1))
    }
}
