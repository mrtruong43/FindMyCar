package com.truong.findmycar.tracking

import com.truong.findmycar.guidance.CircularFilter
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class AccelSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val tMs: Long
)

data class HeadingEstimate(
    val headingDeg: Float,
    val isConfident: Boolean,
    val eigenvalueRatio: Float
)

/**
 * Pure Kotlin heading estimator supporting:
 * - Mode A (Hand-held): device azimuth filtered via CircularFilter.
 * - Mode B (In-pocket): 2x2 PCA on world-frame horizontal acceleration (ax, ay)
 *   with eigenvalue ratio guard and heel-strike (az maxima) 180° disambiguation.
 */
class HeadingEstimator(
    filterAlpha: Float = CircularFilter.DEFAULT_ALPHA,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minEigenRatio: Float = DEFAULT_MIN_EIGEN_RATIO,
    private val minPrincipalVariance: Double = DEFAULT_MIN_VARIANCE
) {
    private val circularFilter = CircularFilter(alpha = filterAlpha)
    private val pocketWindow = ArrayDeque<AccelSample>()
    private var lastValidHeadingDeg: Float = 0f
    private var hasValidHeading: Boolean = false

    fun updateHandHeldAzimuth(
        azimuthDeg: Float,
        baseAlpha: Float = CircularFilter.DEFAULT_ALPHA
    ): HeadingEstimate {
        val filtered = circularFilter.updateAdaptive(azimuthDeg, baseAlpha)
        lastValidHeadingDeg = filtered
        hasValidHeading = true
        return HeadingEstimate(
            headingDeg = filtered,
            isConfident = true,
            eigenvalueRatio = Float.POSITIVE_INFINITY
        )
    }

    fun addPocketSample(ax: Float, ay: Float, az: Float, tMs: Long): HeadingEstimate {
        pocketWindow.addLast(AccelSample(ax, ay, az, tMs))
        val cutoff = tMs - windowMs
        while (pocketWindow.isNotEmpty() && pocketWindow.first().tMs < cutoff) {
            pocketWindow.removeFirst()
        }
        return estimateFromPocketWindow(pocketWindow.toList())
    }

    fun estimateFromPocketWindow(samples: List<AccelSample>): HeadingEstimate {
        if (samples.size < MIN_SAMPLES) {
            return HeadingEstimate(
                headingDeg = lastValidHeadingDeg,
                isConfident = false,
                eigenvalueRatio = 1f
            )
        }

        val n = samples.size.toDouble()
        var meanX = 0.0
        var meanY = 0.0
        var meanZ = 0.0
        for (s in samples) {
            meanX += s.ax
            meanY += s.ay
            meanZ += s.az
        }
        meanX /= n
        meanY /= n
        meanZ /= n

        var cxx = 0.0
        var cyy = 0.0
        var cxy = 0.0
        for (s in samples) {
            val dx = s.ax - meanX
            val dy = s.ay - meanY
            cxx += dx * dx
            cyy += dy * dy
            cxy += dx * dy
        }
        cxx /= n
        cyy /= n
        cxy /= n

        val trace = cxx + cyy
        val diff = cxx - cyy
        val disc = sqrt(diff * diff + 4.0 * cxy * cxy)
        val lambda1 = 0.5 * (trace + disc)
        val lambda2 = (0.5 * (trace - disc)).coerceAtLeast(0.0)

        val ratio = if (lambda2 <= 1e-9) {
            if (lambda1 > minPrincipalVariance) 100f else 1f
        } else {
            (lambda1 / lambda2).toFloat()
        }

        if (lambda1 < minPrincipalVariance || ratio < minEigenRatio) {
            return HeadingEstimate(
                headingDeg = lastValidHeadingDeg,
                isConfident = false,
                eigenvalueRatio = ratio
            )
        }

        // Principal eigenvector for lambda1
        var vx: Double
        var vy: Double
        if (abs(cxy) > 1e-9) {
            val cand1X = lambda1 - cyy
            val cand1Y = cxy
            val cand2X = cxy
            val cand2Y = lambda1 - cxx
            if (cand1X * cand1X + cand1Y * cand1Y >= cand2X * cand2X + cand2Y * cand2Y) {
                vx = cand1X
                vy = cand1Y
            } else {
                vx = cand2X
                vy = cand2Y
            }
        } else if (cxx >= cyy) {
            vx = 1.0
            vy = 0.0
        } else {
            vx = 0.0
            vy = 1.0
        }

        val norm = sqrt(vx * vx + vy * vy)
        if (norm <= 1e-9) {
            return HeadingEstimate(
                headingDeg = lastValidHeadingDeg,
                isConfident = false,
                eigenvalueRatio = ratio
            )
        }
        vx /= norm
        vy /= norm

        // 180° disambiguation using horizontal acceleration along principal axis at az heel-strike peaks.
        var heelStrikeScore = 0.0
        var peakCount = 0
        for (i in 1 until samples.size - 1) {
            val prev = samples[i - 1].az
            val curr = samples[i].az
            val next = samples[i + 1].az
            if (curr > prev && curr >= next && curr > meanZ) {
                val dx = samples[i].ax - meanX
                val dy = samples[i].ay - meanY
                heelStrikeScore += dx * vx + dy * vy
                peakCount++
            }
        }

        if (peakCount > 0 && heelStrikeScore < 0.0) {
            vx = -vx
            vy = -vy
        }

        // Heading in degrees: 0° = +Y (North), 90° = +X (East)
        val rawDeg = GeoMath.normalizeAngleDegrees(Math.toDegrees(atan2(vx, vy)).toFloat())
        val filtered = circularFilter.update(rawDeg)
        lastValidHeadingDeg = filtered
        hasValidHeading = true

        return HeadingEstimate(
            headingDeg = filtered,
            isConfident = true,
            eigenvalueRatio = ratio
        )
    }

    fun currentHeadingDeg(): Float? = if (hasValidHeading) lastValidHeadingDeg else null

    fun reset() {
        pocketWindow.clear()
        circularFilter.reset()
        lastValidHeadingDeg = 0f
        hasValidHeading = false
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_500L
        const val DEFAULT_MIN_EIGEN_RATIO = 2.5f
        const val DEFAULT_MIN_VARIANCE = 1e-3
        const val MIN_SAMPLES = 8
    }
}
