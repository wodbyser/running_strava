package com.running.strava.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BestEffortFinderTest {

    /** 1 Hz streams from a list of (durationSeconds, speedMs) blocks. */
    private fun streams(vararg blocks: Pair<Int, Double>): Pair<List<Int>, List<Float>> {
        val t = mutableListOf(0)
        val d = mutableListOf(0f)
        var dist = 0.0
        var sec = 0
        blocks.forEach { (dur, v) ->
            repeat(dur) {
                sec++; dist += v
                t.add(sec); d.add(dist.toFloat())
            }
        }
        return t to d
    }

    @Test
    fun `half marathon inside a 21_9 km run is timed at exactly 21097_5 m`() {
        // 5475 s at 4.0 m/s = 21900 m. HM at 4.0 m/s = 21097.5 / 4 = 5274.375 s
        val (t, d) = streams(5475 to 4.0)
        val e = BestEffortFinder.fastest(t, d, 21097.5)!!
        assertEquals(5274.375, e.seconds, 0.05)
    }

    @Test
    fun `fast 5 km inside a 10 km run is found`() {
        // 5 km @ 5:00/km (1500 s) then 5 km @ 4:00/km (1200 s)
        val (t, d) = streams(1500 to 1000.0 / 300, 1200 to 1000.0 / 240)
        assertEquals(1200.0, BestEffortFinder.fastest(t, d, 5000.0)!!.seconds, 0.5)
        assertEquals(240.0, BestEffortFinder.fastest(t, d, 1000.0)!!.seconds, 0.5)
        assertEquals(2700.0, BestEffortFinder.fastest(t, d, 10000.0)!!.seconds, 0.5)
    }

    @Test
    fun `a 4_9 km run is not a 5 km effort`() {
        val (t, d) = streams(1225 to 4.0) // 4900 m
        assertNull(BestEffortFinder.fastest(t, d, 5000.0))
        assertNotNull(BestEffortFinder.fastest(t, d, 1000.0))
    }

    @Test
    fun `a stop inside the segment counts (elapsed time)`() {
        // 2.5 km @ 4 m/s, 60 s standing still, 2.5 km @ 4 m/s -> 5 km in 625 + 60 + 625 = 1310 s
        val (t, d) = streams(625 to 4.0, 60 to 0.0, 625 to 4.0)
        assertEquals(1310.0, BestEffortFinder.fastest(t, d, 5000.0)!!.seconds, 0.5)
    }

    @Test
    fun `gps jump never shortens an effort`() {
        // 6 km @ 4 m/s with an 800 m teleport in one second halfway
        val (t, d0) = streams(750 to 4.0, 1 to 800.0, 750 to 4.0)
        // Real running distance is 6000 m (+800 m fake). The only honest 5 km windows avoid the jump:
        // none exists (3000 m either side), so no 5 km effort may be reported.
        assertNull(BestEffortFinder.fastest(t, d0, 5000.0))
        // 1 km windows avoid the jump -> 250 s
        assertEquals(250.0, BestEffortFinder.fastest(t, d0, 1000.0)!!.seconds, 0.5)
    }

    @Test
    fun `missing streams give no effort`() {
        assertNull(BestEffortFinder.fastest(null, listOf(0f, 10f), 1000.0))
        assertNull(BestEffortFinder.fastest(listOf(0), listOf(0f), 1000.0))
    }
}
