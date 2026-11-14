package com.truong.findmycar.tracking

import com.truong.findmycar.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HeadingEstimatorPcaTest {

    @Test
    fun synthetic45DegWalkingConvergesWithin10Degrees() {
        val estimator = HeadingEstimator(filterAlpha = 0.4f)
        val targetRad = Math.toRadians(45.0)
        val ux = sin(targetRad) // East component
        val uy = cos(targetRad) // North component

        // Simulate 2 seconds @ 50Hz (100 samples) of 2Hz gait oscillation along 45°
        // Horizontal accel peaks positively in walking direction at vertical heel-strike (az maxima).
        val samples = mutableListOf<AccelSample>()
        for (i in 0 until 100) {
            val tSec = i * 0.02
            val phase = 2.0 * PI * 2.0 * tSec
            val forwardAccel = 2.5 * sin(phase)
            val az = 3.0 * sin(phase) // maxima aligned with positive forwardAccel
            samples.add(
                AccelSample(
                    ax = (forwardAccel * ux).toFloat(),
                    ay = (forwardAccel * uy).toFloat(),
                    az = az.toFloat(),
                    tMs = i * 20L
                )
            )
        }

        val estimate = estimator.estimateFromPocketWindow(samples)
        assertTrue("Expected confident PCA estimate, ratio=${estimate.eigenvalueRatio}", estimate.isConfident)
        val error = abs(GeoMath.shortestAngleDeltaDegrees(45f, estimate.headingDeg))
        assertTrue("Expected heading within ±10° of 45°, got ${estimate.headingDeg}°", error <= 10f)
    }

    @Test
    fun lowVarianceOrIsotropicNoiseDoesNotOverwriteValidHeading() {
        val estimator = HeadingEstimator(filterAlpha = 1.0f)
        estimator.updateHandHeldAzimuth(120f)

        // Isotropic circular shake (equal variance in X and Y -> ratio ~ 1.0 < 2.0)
        val isotropicSamples = (0 until 40).map { i ->
            val angle = i * 0.4
            AccelSample(
                ax = (1.5 * cos(angle)).toFloat(),
                ay = (1.5 * sin(angle)).toFloat(),
                az = 0.5f,
                tMs = i * 50L
            )
        }

        val result = estimator.estimateFromPocketWindow(isotropicSamples)
        assertFalse(result.isConfident)
        assertEquals(120f, result.headingDeg, 0.01f)
    }
}
