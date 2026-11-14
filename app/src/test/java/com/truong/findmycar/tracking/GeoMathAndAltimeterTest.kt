package com.truong.findmycar.tracking

import com.truong.findmycar.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathAndAltimeterTest {

    @Test
    fun climbingAndDescendingFloors() {
        val baseHpa = 1013.25f
        // ~3.2m up -> pressure drops by ~0.384 hPa
        val altimeter = PressureAltimeter(baselinePressureHpa = baseHpa, floorHeightM = 3.2f)
        var t = 1000L
        lateinit var upReading: AltimeterReading
        repeat(10) {
            upReading = altimeter.update(1012.866f, t)
            t += 200L
        }
        assertEquals(1, upReading.floorOffset)
        assertEquals(3.2f, upReading.relHeightM, 0.2f)

        // ~6.4m down -> pressure rises by ~0.768 hPa
        val downAltimeter = PressureAltimeter(baselinePressureHpa = baseHpa, floorHeightM = 3.2f)
        t = 1000L
        lateinit var downReading: AltimeterReading
        repeat(10) {
            downReading = downAltimeter.update(1014.018f, t)
            t += 200L
        }
        assertEquals(-2, downReading.floorOffset)
        assertEquals(-6.4f, downReading.relHeightM, 0.3f)
    }

    @Test
    fun noisyPressureSpikeIsSmoothedOut() {
        val baseHpa = 1013.25f
        val altimeter = PressureAltimeter(
            baselinePressureHpa = baseHpa,
            floorHeightM = 3.2f,
            movingAvgWindowMs = 1_500L,
            lowPassAlpha = 0.2f
        )

        var t = 0L
        repeat(8) {
            altimeter.update(baseHpa, t)
            t += 200L
        }
        // Single sudden spike (-2.0 hPa ~ +16m) should be heavily damped by moving avg + low-pass
        val afterSpike = altimeter.update(baseHpa - 2.0f, t)
        assertTrue(
            "Expected spike to be smoothed (< 2.5m), got ${afterSpike.relHeightM}m",
            afterSpike.relHeightM < 2.5f
        )
    }

    @Test
    fun haversineAndBearingSanity() {
        val dist = GeoMath.haversineDistanceMeters(10.0, 106.0, 10.000899, 106.0)
        assertEquals(100.0, dist, 2.0)

        val northBearing = GeoMath.initialBearingDegrees(10.0, 106.0, 10.01, 106.0)
        assertEquals(0f, northBearing, 0.5f)

        val eastBearing = GeoMath.initialBearingDegrees(10.0, 106.0, 10.0, 106.01)
        assertEquals(90f, eastBearing, 0.5f)
    }
}
