package com.truong.findmycar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun relativeAltitudeMeters_lowerPressureIsHigher() {
        val h = GeoMath.relativeAltitudeMeters(
            currentPressureHpa = 1012.8f,
            referencePressureHpa = 1013.2f
        )
        assertEquals(3.3f, h, 0.3f)
    }

    @Test
    fun estimateFloorDelta_roundsToNearestFloor() {
        assertEquals(1, GeoMath.estimateFloorDelta(3.3f, 3.2f))
        assertEquals(-2, GeoMath.estimateFloorDelta(-6.2f, 3.2f))
        assertEquals(0, GeoMath.estimateFloorDelta(1.1f, 3.2f))
    }

    @Test
    fun buildGeoUri_formatsCorrectly() {
        assertEquals(
            "geo:10.7769,106.7009?q=10.7769,106.7009",
            GeoMath.buildGeoUri(10.7769, 106.7009)
        )
    }
}
