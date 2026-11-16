package com.truong.findmycar.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceEstimatorTest {

    @Test
    fun hysteresisPreventsUiFlickerInMiddleBand() {
        val estimator = ConfidenceEstimator()
        assertEquals(ConfidenceLevel.HIGH, estimator.currentSnapshot().level)

        // Drop score into middle band [0.40, 0.70] -> should stay HIGH
        repeat(6) { estimator.onMagneticSpike() } // 1.0 - 0.36 = 0.64
        val midFromHigh = estimator.currentSnapshot()
        assertTrue(midFromHigh.score in 0.40f..0.70f)
        assertEquals(ConfidenceLevel.HIGH, midFromHigh.level)

        // Drop below 0.40 -> transitions to LOW
        repeat(5) { estimator.onMagneticSpike() } // 0.64 - 0.30 = 0.34
        val low = estimator.currentSnapshot()
        assertTrue(low.score < 0.40f)
        assertEquals(ConfidenceLevel.LOW, low.level)

        // Reset and verify return to HIGH
        estimator.reset()
        assertEquals(ConfidenceLevel.HIGH, estimator.currentSnapshot().level)
    }
}
