package com.truong.findmycar.tracking

import com.truong.findmycar.util.GeoMath

data class AltimeterSample(
    val pressureHpa: Float,
    val tMs: Long
)

data class AltimeterReading(
    val filteredPressureHpa: Float,
    val relHeightM: Float,
    val floorOffset: Int
)

/**
 * Pure Kotlin barometric altimeter:
 * moving average over ~1.5 seconds followed by a low-pass filter,
 * outputting relative height and floor delta relative to [baselinePressureHpa].
 */
class PressureAltimeter(
    private var baselinePressureHpa: Float,
    private val floorHeightM: Float = DEFAULT_FLOOR_HEIGHT_M,
    private val movingAvgWindowMs: Long = DEFAULT_MOVING_AVG_WINDOW_MS,
    private val lowPassAlpha: Float = DEFAULT_LOW_PASS_ALPHA
) {
    private val window = ArrayDeque<AltimeterSample>()
    private var filteredHpa: Float? = null

    fun update(pressureHpa: Float, tMs: Long): AltimeterReading {
        window.addLast(AltimeterSample(pressureHpa, tMs))
        val cutoff = tMs - movingAvgWindowMs
        while (window.isNotEmpty() && window.first().tMs < cutoff) {
            window.removeFirst()
        }

        val avg = window.map { it.pressureHpa.toDouble() }.average().toFloat()
        val prev = filteredHpa
        val smoothed = if (prev == null) {
            avg
        } else {
            prev + lowPassAlpha * (avg - prev)
        }
        filteredHpa = smoothed

        val relHeight = GeoMath.relativeAltitudeMeters(smoothed, baselinePressureHpa)
        val floorOffset = GeoMath.estimateFloorDelta(relHeight, floorHeightM)
        return AltimeterReading(
            filteredPressureHpa = smoothed,
            relHeightM = relHeight,
            floorOffset = floorOffset
        )
    }

    fun resetBaseline(newBaselinePressureHpa: Float) {
        baselinePressureHpa = newBaselinePressureHpa
        window.clear()
        filteredHpa = null
    }

    companion object {
        const val DEFAULT_FLOOR_HEIGHT_M = 3.2f
        const val DEFAULT_MOVING_AVG_WINDOW_MS = 1_500L
        const val DEFAULT_LOW_PASS_ALPHA = 0.25f
    }
}
