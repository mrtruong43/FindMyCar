package com.truong.findmycar.guidance

import com.truong.findmycar.util.GeoMath
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

    fun update(angleDeg: Float): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        val s = sin(rad)
        val c = cos(rad)

        if (!hasValue) {
            sinAvg = s
            cosAvg = c
            hasValue = true
        } else {
            val a = alpha.toDouble()
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
    }
}
