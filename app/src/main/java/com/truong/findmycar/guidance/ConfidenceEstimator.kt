package com.truong.findmycar.guidance

import com.truong.findmycar.util.GeoMath
import kotlin.math.abs

enum class ConfidenceLevel {
    HIGH,
    LOW
}

data class ConfidenceSnapshot(
    val score: Float,
    val level: ConfidenceLevel
)

/**
 * Pure Kotlin confidence estimator for dead-reckoning guidance with hysteresis:
 * - Score in [0f, 1f] based on distance walked since anchor, large turns, magnetic spikes, and PCA ratio.
 * - Hysteresis: > 0.70 -> HIGH, < 0.40 -> LOW, 0.40..0.70 retains previous state to avoid UI flicker.
 */
class ConfidenceEstimator(
    private val highThreshold: Float = DEFAULT_HIGH_THRESHOLD,
    private val lowThreshold: Float = DEFAULT_LOW_THRESHOLD
) {
    private var distanceWalkedM: Float = 0f
    private var largeTurnCount: Int = 0
    private var magneticSpikeCount: Int = 0
    private var minPcaRatio: Float? = null
    private var lastTurnRefAzimuthDeg: Float? = null
    private var currentLevel: ConfidenceLevel = ConfidenceLevel.HIGH

    fun onStep(stepLengthM: Float, currentAzimuthDeg: Float): ConfidenceSnapshot {
        distanceWalkedM += stepLengthM
        val prevAz = lastTurnRefAzimuthDeg
        if (prevAz == null) {
            lastTurnRefAzimuthDeg = currentAzimuthDeg
        } else {
            val turnDelta = abs(GeoMath.shortestAngleDeltaDegrees(prevAz, currentAzimuthDeg))
            if (turnDelta >= LARGE_TURN_THRESHOLD_DEG) {
                largeTurnCount++
                lastTurnRefAzimuthDeg = currentAzimuthDeg
            }
        }
        return currentSnapshot()
    }

    fun onMagneticSpike(): ConfidenceSnapshot {
        magneticSpikeCount++
        return currentSnapshot()
    }

    fun onPcaRatio(ratio: Float): ConfidenceSnapshot {
        val prev = minPcaRatio
        if (prev == null || ratio < prev) {
            minPcaRatio = ratio
        }
        return currentSnapshot()
    }

    fun reset() {
        distanceWalkedM = 0f
        largeTurnCount = 0
        magneticSpikeCount = 0
        minPcaRatio = null
        lastTurnRefAzimuthDeg = null
        currentLevel = ConfidenceLevel.HIGH
    }

    fun currentSnapshot(): ConfidenceSnapshot {
        var score = 1.0f
        score -= (distanceWalkedM / 180f) * 0.45f
        score -= largeTurnCount * 0.08f
        score -= magneticSpikeCount * 0.06f
        val pca = minPcaRatio
        if (pca != null && pca < 2.5f) {
            score -= 0.20f
        }
        val clamped = score.coerceIn(0f, 1f)

        currentLevel = when {
            clamped > highThreshold -> ConfidenceLevel.HIGH
            clamped < lowThreshold -> ConfidenceLevel.LOW
            else -> currentLevel
        }
        return ConfidenceSnapshot(score = clamped, level = currentLevel)
    }

    companion object {
        const val DEFAULT_HIGH_THRESHOLD = 0.70f
        const val DEFAULT_LOW_THRESHOLD = 0.40f
        const val LARGE_TURN_THRESHOLD_DEG = 45f
    }
}
