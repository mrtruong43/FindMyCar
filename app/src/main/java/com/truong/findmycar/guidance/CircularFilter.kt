package com.truong.findmycar.guidance

import com.truong.findmycar.util.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-pass circular filter operating on sin(theta) and cos(theta)
 * to avoid 0°/360° wrap-around discontinuities.
 */
class CircularFilter(
    private val alpha: Float = DEFAULT_ALPHA
) {
    private var hasValue = false
    private var sinAvg = 0.0
    private var cosAvg = 0.0

    init {
        require(alpha > 0f && alpha <= 1f) { "alpha must be in (0, 1]" }
    }

    fun update(angleDeg: Float): Float = applyUpdate(angleDeg, alpha)

    /**
     * Adaptive circular update: keeps [baseAlpha] for small walking sway (< 12°)
     * and smoothly boosts effective alpha up to 0.45f for true turns (> 35°).
     */
    fun updateAdaptive(angleDeg: Float, baseAlpha: Float = alpha): Float {
        val current = currentAngle()
        val effectiveAlpha = if (current == null) {
            baseAlpha
        } else {
            val delta = abs(GeoMath.shortestAngleDeltaDegrees(current, angleDeg))
            val maxAlpha = maxOf(baseAlpha, ADAPTIVE_MAX_ALPHA)
            when {
                delta < SWAY_DELTA_DEG -> baseAlpha
                delta >= TURN_DELTA_DEG -> maxAlpha
                else -> {
                    val t = (delta - SWAY_DELTA_DEG) / (TURN_DELTA_DEG - SWAY_DELTA_DEG)
                    baseAlpha + t * (maxAlpha - baseAlpha)
                }
            }
        }
        return applyUpdate(angleDeg, effectiveAlpha)
    }

    private fun applyUpdate(angleDeg: Float, effectiveAlpha: Float): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        val s = sin(rad)
        val c = cos(rad)

        if (!hasValue) {
            sinAvg = s
            cosAvg = c
            hasValue = true
        } else {
            val a = effectiveAlpha.coerceIn(0.01f, 1f).toDouble()
            sinAvg = (1.0 - a) * sinAvg + a * s
            cosAvg = (1.0 - a) * cosAvg + a * c
        }

        val filteredDeg = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat()
        return GeoMath.normalizeAngleDegrees(filteredDeg)
    }

    fun currentAngle(): Float? {
        if (!hasValue) return null
        return GeoMath.normalizeAngleDegrees(Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat())
    }

    fun reset() {
        hasValue = false
        sinAvg = 0.0
        cosAvg = 0.0
    }

    companion object {
        const val DEFAULT_ALPHA = 0.25f
        const val ADAPTIVE_MAX_ALPHA = 0.45f
        const val SWAY_DELTA_DEG = 12f
        const val TURN_DELTA_DEG = 35f
    }
}
