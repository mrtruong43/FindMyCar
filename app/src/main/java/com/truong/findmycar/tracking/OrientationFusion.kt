package com.truong.findmycar.tracking

import com.truong.findmycar.util.GeoMath
import kotlin.math.abs

/**
 * Pure Kotlin complementary fusion between:
 * - [gameAzimuthDeg]: from TYPE_GAME_ROTATION_VECTOR (gyro + accel, immune to steel magnetic spikes, arbitrary zero).
 * - [magAzimuthDeg]: from TYPE_ROTATION_VECTOR (anchored to True North, susceptible to basement steel).
 *
 * Maintains a slow-drifting [offsetDeg] mapping game-frame azimuth to True North:
 * - When [hasMagneticInterference] is true or the user is turning fast, magnetometer weight drops to 0.
 * - When magnetic field is clean (20..85 µT) and walking straight, [offsetDeg] gently corrects gyro drift.
 */
class OrientationFusion(
    private val driftCorrectionAlpha: Float = DEFAULT_DRIFT_ALPHA,
    private val fastTurnRateDegPerSec: Float = DEFAULT_FAST_TURN_DEG_PER_SEC
) {
    private var hasOffset = false
    private var offsetDeg = 0f
    private var prevGameAzimuthDeg: Float? = null
    private var prevTimeMs: Long? = null

    var isTurningFast: Boolean = false
        private set

    fun update(
        gameAzimuthDeg: Float,
        magAzimuthDeg: Float?,
        hasMagneticInterference: Boolean,
        timestampMs: Long
    ): Float {
        val normGame = GeoMath.normalizeAngleDegrees(gameAzimuthDeg)

        val prevG = prevGameAzimuthDeg
        val prevT = prevTimeMs
        isTurningFast = if (prevG != null && prevT != null && timestampMs > prevT) {
            val dtSec = (timestampMs - prevT) / 1000f
            val delta = abs(GeoMath.shortestAngleDeltaDegrees(prevG, normGame))
            (delta / dtSec) > fastTurnRateDegPerSec
        } else {
            false
        }
        prevGameAzimuthDeg = normGame
        prevTimeMs = timestampMs

        if (!hasOffset) {
            if (magAzimuthDeg != null) {
                offsetDeg = GeoMath.shortestAngleDeltaDegrees(
                    normGame,
                    GeoMath.normalizeAngleDegrees(magAzimuthDeg)
                )
                hasOffset = true
            }
        } else if (!hasMagneticInterference && !isTurningFast && magAzimuthDeg != null) {
            val desiredOffset = GeoMath.shortestAngleDeltaDegrees(
                normGame,
                GeoMath.normalizeAngleDegrees(magAzimuthDeg)
            )
            val err = GeoMath.shortestAngleDeltaDegrees(offsetDeg, desiredOffset)
            offsetDeg += err * driftCorrectionAlpha
        }

        return GeoMath.normalizeAngleDegrees(normGame + offsetDeg)
    }

    fun reset() {
        hasOffset = false
        offsetDeg = 0f
        prevGameAzimuthDeg = null
        prevTimeMs = null
        isTurningFast = false
    }

    companion object {
        const val DEFAULT_DRIFT_ALPHA = 0.03f
        const val DEFAULT_FAST_TURN_DEG_PER_SEC = 35f
    }
}
