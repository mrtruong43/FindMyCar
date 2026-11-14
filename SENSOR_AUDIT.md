# Sensor & Guidance Math Audit

## app/src/main/java/com/truong/findmycar/guidance/GuidanceEngine.kt

```kotlin
package com.truong.findmycar.guidance

import com.truong.findmycar.detect.ParkingEnvironmentMode
import com.truong.findmycar.tracking.PathModel
import com.truong.findmycar.tracking.PathPoint
import com.truong.findmycar.tracking.PressureAltimeter
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class GuidancePhase {
    PHASE1_GPS_TO_ANCHOR,
    PHASE1_GPS_TO_CAR,
    PHASE2_FOOTPRINT,
    VERY_CLOSE,
    NO_SIGNAL_FALLBACK
}

data class GuidanceTargetInfo(
    val carLat: Double?,
    val carLon: Double?,
    val anchorLat: Double?,
    val anchorLon: Double?,
    val anchorRelHeightM: Float?,
    val footprint: List<PathPoint>,
    val floorHeightM: Float = 3.2f,
    val hasBarometer: Boolean = true,
    val carAccuracyM: Float? = null,
    val anchorSet: Boolean = false,
    val environmentMode: ParkingEnvironmentMode = ParkingEnvironmentMode.AUTO
)

data class GuidanceState(
    val phase: GuidancePhase,
    val relativeArrowDeg: Float = 0f,
    val distanceMeters: Float? = null,
    val floorDeltaToTarget: Int? = null,
    val isCompassLowAccuracy: Boolean = false,
    val hasWeakGpsSignal: Boolean = false,
    val canEnterPhase2Manually: Boolean = false
)

/**
 * Pure Kotlin two-phase return navigation state machine.
 * - Phase 1 (Outdoor GPS): guides to anchor (or car if no footprint). Transitions to Phase 2 at < 15m or manual tap.
 * - Phase 2 (Underground Polyline): reverses recorded footprint, projects (x, y) onto polyline,
 *   computes lookahead (5m-8m), true remaining polyline distance, and floor delta relative to Phase 2 pressure reset.
 * - Proximity (< 8m): transitions to VERY_CLOSE.
 */
class GuidanceEngine(
    private val info: GuidanceTargetInfo
) {
    private val arrowFilter = CircularFilter(alpha = 0.45f)

    private val hasFootprint = info.footprint.size >= 2
    private val reversedPolyline: List<PathPoint> =
        if (hasFootprint) PathModel.reversedPath(info.footprint) else emptyList()
    private val totalPolylineLengthM: Float =
        if (hasFootprint) PathModel.totalLengthMeters(reversedPolyline) else 0f

    private val isOutdoorSpot: Boolean = when (info.environmentMode) {
        ParkingEnvironmentMode.OUTDOOR -> info.carLat != null && info.carLon != null
        ParkingEnvironmentMode.UNDERGROUND -> false
        ParkingEnvironmentMode.AUTO ->
            info.carLat != null &&
                info.carLon != null &&
                info.carAccuracyM != null &&
                info.carAccuracyM <= OUTDOOR_ORIGIN_ACCURACY_M &&
                abs(info.anchorRelHeightM ?: 0f) < OUTDOOR_MAX_REL_HEIGHT_M
    }

    private val targetGpsLat: Double? = if (isOutdoorSpot) info.carLat else (info.anchorLat ?: info.carLat)
    private val targetGpsLon: Double? = if (isOutdoorSpot) info.carLon else (info.anchorLon ?: info.carLon)
    private val hasTargetGps: Boolean = targetGpsLat != null && targetGpsLon != null

    private var phase: GuidancePhase = when {
        isOutdoorSpot -> GuidancePhase.PHASE1_GPS_TO_CAR
        !hasFootprint && !hasTargetGps -> GuidancePhase.NO_SIGNAL_FALLBACK
        !hasFootprint && hasTargetGps -> GuidancePhase.PHASE1_GPS_TO_CAR
        hasFootprint && hasTargetGps -> GuidancePhase.PHASE1_GPS_TO_ANCHOR
        else -> GuidancePhase.PHASE2_FOOTPRINT
    }

    private var latestAzimuthDeg: Float = 0f
    private var targetBearingDeg: Float = 0f
    private var lastBearingLat: Double? = null
    private var lastBearingLon: Double? = null
    private var distanceMeters: Float? = if (phase == GuidancePhase.PHASE2_FOOTPRINT) {
        totalPolylineLengthM
    } else {
        null
    }

    private var currentX: Float = reversedPolyline.firstOrNull()?.x ?: 0f
    private var currentY: Float = reversedPolyline.firstOrNull()?.y ?: 0f

    private var phase2Altimeter: PressureAltimeter? = null
    private var floorDeltaToTarget: Int? = computeInitialFloorDelta()
    private var isCompassLowAccuracy: Boolean = false
    private var hasWeakGpsSignal: Boolean = false

    init {
        if (phase == GuidancePhase.PHASE2_FOOTPRINT) {
            recomputePhase2Guidance(isInitial = true)
        }
    }

    private fun computeInitialFloorDelta(): Int? {
        if (!info.hasBarometer) return null
        val anchorH = info.anchorRelHeightM ?: return null
        return GeoMath.estimateFloorDelta(-anchorH, info.floorHeightM)
    }

    fun currentState(): GuidanceState {
        val rawRelative = GeoMath.shortestAngleDeltaDegrees(latestAzimuthDeg, targetBearingDeg)
        val filteredAbs = arrowFilter.currentAngle() ?: GeoMath.normalizeAngleDegrees(rawRelative)
        val relative = GeoMath.shortestAngleDeltaDegrees(0f, filteredAbs)
        return GuidanceState(
            phase = phase,
            relativeArrowDeg = relative,
            distanceMeters = distanceMeters,
            floorDeltaToTarget = floorDeltaToTarget,
            isCompassLowAccuracy = isCompassLowAccuracy,
            hasWeakGpsSignal = hasWeakGpsSignal,
            canEnterPhase2Manually = phase == GuidancePhase.PHASE1_GPS_TO_ANCHOR && hasFootprint
        )
    }

    fun onCompassAccuracyChanged(isLowAccuracy: Boolean): GuidanceState {
        isCompassLowAccuracy = isLowAccuracy
        return currentState()
    }

    fun onAzimuthUpdate(azimuthDeg: Float): GuidanceState {
        latestAzimuthDeg = GeoMath.normalizeAngleDegrees(azimuthDeg)
        updateArrowFilter()
        return currentState()
    }

    fun onGpsUpdate(lat: Double, lon: Double, accuracyM: Float, currentPressureHpa: Float? = null): GuidanceState {
        hasWeakGpsSignal = accuracyM > WEAK_GPS_ACCURACY_M

        if (phase != GuidancePhase.PHASE1_GPS_TO_ANCHOR && phase != GuidancePhase.PHASE1_GPS_TO_CAR) {
            return currentState()
        }

        val tLat = targetGpsLat ?: return currentState()
        val tLon = targetGpsLon ?: return currentState()

        val dist = GeoMath.haversineDistanceMeters(lat, lon, tLat, tLon).toFloat()

        // Freeze/hold target bearing when user has moved < 3m to prevent stationary GPS jitter
        val prevLat = lastBearingLat
        val prevLon = lastBearingLon
        val movedMeters = if (prevLat != null && prevLon != null) {
            GeoMath.haversineDistanceMeters(prevLat, prevLon, lat, lon)
        } else {
            Double.MAX_VALUE
        }
        if (movedMeters >= STATIONARY_BEARING_THRESHOLD_M) {
            targetBearingDeg = GeoMath.initialBearingDegrees(lat, lon, tLat, tLon)
            lastBearingLat = lat
            lastBearingLon = lon
        }

        distanceMeters = dist
        updateArrowFilter()

        if (phase == GuidancePhase.PHASE1_GPS_TO_ANCHOR && dist < PHASE2_TRANSITION_DISTANCE_M) {
            return enterPhase2(currentPressureHpa)
        }
        if (phase == GuidancePhase.PHASE1_GPS_TO_CAR &&
            dist < OUTDOOR_VERY_CLOSE_DISTANCE_M &&
            accuracyM <= OUTDOOR_VERY_CLOSE_MAX_ACCURACY_M
        ) {
            phase = GuidancePhase.VERY_CLOSE
        }

        return currentState()
    }

    fun enterPhase2(currentPressureHpa: Float? = null): GuidanceState {
        if (!hasFootprint) return currentState()
        phase = GuidancePhase.PHASE2_FOOTPRINT
        hasWeakGpsSignal = false
        currentX = reversedPolyline.first().x
        currentY = reversedPolyline.first().y
        if (currentPressureHpa != null && info.hasBarometer) {
            phase2Altimeter = PressureAltimeter(
                baselinePressureHpa = currentPressureHpa,
                floorHeightM = info.floorHeightM
            )
        }
        recomputePhase2Guidance(isInitial = true)
        return currentState()
    }

    fun onStep(stepLengthM: Float): GuidanceState {
        if (phase != GuidancePhase.PHASE2_FOOTPRINT && phase != GuidancePhase.VERY_CLOSE) {
            return currentState()
        }
        val rad = Math.toRadians(latestAzimuthDeg.toDouble())
        currentX += (stepLengthM * sin(rad)).toFloat()
        currentY += (stepLengthM * cos(rad)).toFloat()
        recomputePhase2Guidance()
        return currentState()
    }

    fun onPressureUpdate(pressureHpa: Float, tMs: Long): GuidanceState {
        if (!info.hasBarometer) {
            floorDeltaToTarget = null
            return currentState()
        }
        if (phase != GuidancePhase.PHASE2_FOOTPRINT && phase != GuidancePhase.VERY_CLOSE) {
            return currentState()
        }
        val altimeter = phase2Altimeter ?: PressureAltimeter(
            baselinePressureHpa = pressureHpa,
            floorHeightM = info.floorHeightM
        ).also { phase2Altimeter = it }

        val reading = altimeter.update(pressureHpa, tMs)
        val anchorH = info.anchorRelHeightM
        if (anchorH != null) {
            val hTargetFromPhase2Start = -anchorH
            val remainingHeight = hTargetFromPhase2Start - reading.relHeightM
            floorDeltaToTarget = GeoMath.estimateFloorDelta(remainingHeight, info.floorHeightM)
        }
        return currentState()
    }

    private fun recomputePhase2Guidance(isInitial: Boolean = false) {
        if (reversedPolyline.isEmpty()) return
        val proj = PathModel.projectOntoPolyline(currentX, currentY, reversedPolyline)
        val lookahead = PathModel.lookaheadPoint(proj, reversedPolyline, LOOKAHEAD_METERS)
        val dx = lookahead.x - currentX
        val dy = lookahead.y - currentY
        targetBearingDeg = GeoMath.normalizeAngleDegrees(
            Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        )
        distanceMeters = proj.remainingDistanceM
        updateArrowFilter()

        if (!isInitial || totalPolylineLengthM <= MIN_INITIAL_POLYLINE_FOR_VERY_CLOSE_GUARD_M) {
            if (proj.remainingDistanceM < VERY_CLOSE_DISTANCE_M) {
                phase = GuidancePhase.VERY_CLOSE
            }
        }
    }

    private fun updateArrowFilter() {
        val rawRelative = GeoMath.shortestAngleDeltaDegrees(latestAzimuthDeg, targetBearingDeg)
        arrowFilter.update(GeoMath.normalizeAngleDegrees(rawRelative))
    }

    companion object {
        const val PHASE2_TRANSITION_DISTANCE_M = 15f
        const val VERY_CLOSE_DISTANCE_M = 8f
        const val OUTDOOR_VERY_CLOSE_DISTANCE_M = 10f
        const val OUTDOOR_VERY_CLOSE_MAX_ACCURACY_M = 25f
        const val OUTDOOR_ORIGIN_ACCURACY_M = 20f
        const val OUTDOOR_MAX_REL_HEIGHT_M = 2.0f
        const val MIN_INITIAL_POLYLINE_FOR_VERY_CLOSE_GUARD_M = 15f
        const val STATIONARY_BEARING_THRESHOLD_M = 3.0
        const val LOOKAHEAD_METERS = 6.5f
        const val WEAK_GPS_ACCURACY_M = 35f

        fun hapticIntervalMsForDistance(distanceMeters: Float?): Long {
            if (distanceMeters == null) return 1_500L
            if (distanceMeters >= 30f) return 1_500L
            if (distanceMeters <= 5f) return 300L
            val t = (distanceMeters - 5f) / 25f
            return (300L + (t * 1_200L)).toLong()
        }
    }
}
```

## app/src/main/java/com/truong/findmycar/guidance/CircularFilter.kt

```kotlin
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
```

## app/src/main/java/com/truong/findmycar/tracking/HeadingEstimator.kt

```kotlin
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

    fun updateHandHeldAzimuth(azimuthDeg: Float): HeadingEstimate {
        val filtered = circularFilter.update(azimuthDeg)
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
        const val DEFAULT_WINDOW_MS = 2_000L
        const val DEFAULT_MIN_EIGEN_RATIO = 2.0f
        const val DEFAULT_MIN_VARIANCE = 1e-3
        const val MIN_SAMPLES = 8
    }
}
```

## app/src/main/java/com/truong/findmycar/tracking/HeadingSensorAdapter.kt

```kotlin
package com.truong.findmycar.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs

enum class HeadingMode {
    HAND_HELD,
    POCKET_PCA
}

data class HeadingSensorSnapshot(
    val headingDeg: Float,
    val mode: HeadingMode,
    val pcaRatio: Float
)

/**
 * Android SensorManager adapter for M4A's pure Kotlin [HeadingEstimator].
 * - Mode A (Hand-held): TYPE_ROTATION_VECTOR -> getRotationMatrixFromVector -> getOrientation.
 * - Mode B (In-pocket): TYPE_LINEAR_ACCELERATION rotated to world frame -> 2s PCA.
 * - Adaptive sampling: throttles to SENSOR_DELAY_NORMAL when stationary, restores SENSOR_DELAY_GAME on step.
 */
class HeadingSensorAdapter(
    context: Context,
    private val onHeadingUpdated: (HeadingSensorSnapshot) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val estimator = HeadingEstimator()
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasRotationMatrix = false
    private var latestAzimuthDeg = 0f
    private var latestPitchDeg = 0f
    private var latestRollDeg = 0f
    private var registered = false
    private var lowPowerMode = false

    @Volatile
    var currentSnapshot = HeadingSensorSnapshot(0f, HeadingMode.HAND_HELD, 1f)
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    hasRotationMatrix = true

                    latestAzimuthDeg = GeoMath.normalizeAngleDegrees(
                        Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    )
                    latestPitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    latestRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                    if (isHandHeldPosture()) {
                        val est = estimator.updateHandHeldAzimuth(latestAzimuthDeg)
                        updateSnapshot(
                            HeadingSensorSnapshot(
                                headingDeg = est.headingDeg,
                                mode = HeadingMode.HAND_HELD,
                                pcaRatio = currentSnapshot.pcaRatio
                            )
                        )
                    }
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!hasRotationMatrix || event.values.size < 3) return
                    val lx = event.values[0]
                    val ly = event.values[1]
                    val lz = event.values[2]

                    // Rotate device-frame linear acceleration into world frame (X=East, Y=North, Z=Up)
                    val ax = rotationMatrix[0] * lx + rotationMatrix[1] * ly + rotationMatrix[2] * lz
                    val ay = rotationMatrix[3] * lx + rotationMatrix[4] * ly + rotationMatrix[5] * lz
                    val az = rotationMatrix[6] * lx + rotationMatrix[7] * ly + rotationMatrix[8] * lz

                    val pcaEstimate = estimator.addPocketSample(ax, ay, az, System.currentTimeMillis())
                    if (!isHandHeldPosture() && pcaEstimate.isConfident) {
                        updateSnapshot(
                            HeadingSensorSnapshot(
                                headingDeg = pcaEstimate.headingDeg,
                                mode = HeadingMode.POCKET_PCA,
                                pcaRatio = pcaEstimate.eigenvalueRatio
                            )
                        )
                    } else if (pcaEstimate.isConfident) {
                        // Keep PCA ratio visible for debug telemetry even in hand-held mode
                        currentSnapshot = currentSnapshot.copy(pcaRatio = pcaEstimate.eigenvalueRatio)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun isHandHeldPosture(): Boolean {
        // Hand-held posture: phone tilted forward 10°..75° and roll < 45°
        return abs(latestPitchDeg) in 10f..75f && abs(latestRollDeg) < 45f
    }

    private fun updateSnapshot(snapshot: HeadingSensorSnapshot) {
        currentSnapshot = snapshot
        onHeadingUpdated(snapshot)
    }

    fun start() {
        if (registered) return
        registerWithDelay(if (lowPowerMode) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME)
    }

    fun setLowPowerMode(enabled: Boolean) {
        if (lowPowerMode == enabled) return
        lowPowerMode = enabled
        if (registered) {
            stop()
            registerWithDelay(if (enabled) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun registerWithDelay(delayUs: Int) {
        rotationSensor?.let { sensorManager.registerListener(listener, it, delayUs) }
        linearAccelSensor?.let { sensorManager.registerListener(listener, it, delayUs) }
        registered = rotationSensor != null || linearAccelSensor != null
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }
}
```

## app/src/main/java/com/truong/findmycar/tracking/PathModel.kt

```kotlin
package com.truong.findmycar.tracking

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PathPoint(
    val x: Float,
    val y: Float,
    val relHeightM: Float = 0f,
    val tMs: Long = 0L
)

data class PolylineProjection(
    val segmentIndex: Int,
    val segmentT: Float,
    val projectedX: Float,
    val projectedY: Float,
    val distanceToPolylineM: Float,
    val remainingDistanceM: Float
)

/**
 * Pure Kotlin dead-reckoning path model.
 * Coordinate frame: x = East (m), y = North (m), origin (0, 0) at the parked car.
 */
class PathModel {
    private val _points = mutableListOf(PathPoint(0f, 0f, 0f, 0L))
    val points: List<PathPoint> get() = _points

    fun addStep(
        stepLengthM: Float,
        headingDeg: Float,
        relHeightM: Float = 0f,
        tMs: Long = 0L
    ): PathPoint {
        val last = _points.last()
        val thetaRad = Math.toRadians(headingDeg.toDouble())
        val nextX = last.x + (stepLengthM * sin(thetaRad)).toFloat()
        val nextY = last.y + (stepLengthM * cos(thetaRad)).toFloat()
        val point = PathPoint(x = nextX, y = nextY, relHeightM = relHeightM, tMs = tMs)
        _points.add(point)
        return point
    }

    fun currentPosition(): PathPoint = _points.last()

    fun totalLengthMeters(): Float = totalLengthMeters(_points)

    fun reversedFromAnchor(): List<PathPoint> = reversedPath(_points)

    fun reset() {
        _points.clear()
        _points.add(PathPoint(0f, 0f, 0f, 0L))
    }

    fun loadExisting(existing: List<PathPoint>) {
        _points.clear()
        if (existing.isEmpty()) {
            _points.add(PathPoint(0f, 0f, 0f, 0L))
        } else {
            _points.addAll(existing)
        }
    }

    companion object {
        fun totalLengthMeters(polyline: List<PathPoint>): Float {
            if (polyline.size < 2) return 0f
            var total = 0f
            for (i in 0 until polyline.size - 1) {
                val a = polyline[i]
                val b = polyline[i + 1]
                total += hypot(b.x - a.x, b.y - a.y)
            }
            return total
        }

        fun reversedPath(polyline: List<PathPoint>): List<PathPoint> = polyline.asReversed()

        fun projectOntoPolyline(
            px: Float,
            py: Float,
            polyline: List<PathPoint>
        ): PolylineProjection {
            require(polyline.isNotEmpty()) { "Polyline must not be empty" }
            if (polyline.size == 1) {
                val p = polyline[0]
                return PolylineProjection(
                    segmentIndex = 0,
                    segmentT = 0f,
                    projectedX = p.x,
                    projectedY = p.y,
                    distanceToPolylineM = hypot(px - p.x, py - p.y),
                    remainingDistanceM = 0f
                )
            }

            var bestSegIdx = 0
            var bestT = 0f
            var bestProjX = polyline[0].x
            var bestProjY = polyline[0].y
            var bestDistSq = Float.POSITIVE_INFINITY

            for (i in 0 until polyline.size - 1) {
                val a = polyline[i]
                val b = polyline[i + 1]
                val abx = b.x - a.x
                val aby = b.y - a.y
                val lenSq = abx * abx + aby * aby

                val t = if (lenSq <= 1e-9f) {
                    0f
                } else {
                    (((px - a.x) * abx + (py - a.y) * aby) / lenSq).coerceIn(0f, 1f)
                }

                val projX = a.x + t * abx
                val projY = a.y + t * aby
                val dx = px - projX
                val dy = py - projY
                val distSq = dx * dx + dy * dy

                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestSegIdx = i
                    bestT = t
                    bestProjX = projX
                    bestProjY = projY
                }
            }

            // Remaining distance from projected point to end of polyline
            var remaining = hypot(
                polyline[bestSegIdx + 1].x - bestProjX,
                polyline[bestSegIdx + 1].y - bestProjY
            )
            for (i in (bestSegIdx + 1) until polyline.size - 1) {
                val a = polyline[i]
                val b = polyline[i + 1]
                remaining += hypot(b.x - a.x, b.y - a.y)
            }

            return PolylineProjection(
                segmentIndex = bestSegIdx,
                segmentT = bestT,
                projectedX = bestProjX,
                projectedY = bestProjY,
                distanceToPolylineM = kotlin.math.sqrt(bestDistSq),
                remainingDistanceM = remaining
            )
        }

        fun lookaheadPoint(
            projection: PolylineProjection,
            polyline: List<PathPoint>,
            lookaheadM: Float = 6.5f
        ): PathPoint {
            require(polyline.isNotEmpty()) { "Polyline must not be empty" }
            if (polyline.size == 1) return polyline[0]

            var budget = lookaheadM.coerceAtLeast(0f)
            var currX = projection.projectedX
            var currY = projection.projectedY
            var segIdx = projection.segmentIndex

            while (segIdx < polyline.size - 1) {
                val next = polyline[segIdx + 1]
                val segRem = hypot(next.x - currX, next.y - currY)
                if (segRem >= budget && segRem > 1e-6f) {
                    val ratio = budget / segRem
                    return PathPoint(
                        x = currX + ratio * (next.x - currX),
                        y = currY + ratio * (next.y - currY),
                        relHeightM = next.relHeightM
                    )
                }
                budget -= segRem
                currX = next.x
                currY = next.y
                segIdx++
            }

            return polyline.last()
        }
    }
}
```

## app/src/main/java/com/truong/findmycar/ui/find/FindCarViewModel.kt

```kotlin
package com.truong.findmycar.ui.find

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.VehicleHologramType
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.guidance.GuidanceEngine
import com.truong.findmycar.guidance.GuidancePhase
import com.truong.findmycar.guidance.GuidanceState
import com.truong.findmycar.guidance.GuidanceTargetInfo
import com.truong.findmycar.tracking.PathPoint
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class FindCarUiState(
    val spot: ParkingSpotEntity? = null,
    val guidance: GuidanceState = GuidanceState(phase = GuidancePhase.NO_SIGNAL_FALLBACK),
    val userHeadingDeg: Float = 0f,
    val vehicleType: VehicleHologramType = VehicleHologramType.SCOOTER,
    val hapticEnabled: Boolean = true,
    val isLoading: Boolean = true
)

class FindCarViewModel(
    private val spotId: Long,
    private val appContext: Context,
    private val repository: ParkingRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(FindCarUiState())
    val uiState: StateFlow<FindCarUiState> = _uiState.asStateFlow()

    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var engine: GuidanceEngine? = null
    private var stepLengthM: Float = GeoMath.defaultStepLengthM(1.65f)
    private var hapticEnabled: Boolean = true
    private var lastPressureHpa: Float? = null
    private var gpsCallback: LocationCallback? = null
    private var hapticJob: Job? = null

    private var wasAligned = false
    private var wasVeryClose = false

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val g = engine ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuthDeg = GeoMath.normalizeAngleDegrees(
                        Math.toDegrees(orientation[0].toDouble()).toFloat()
                    )
                    _uiState.value = _uiState.value.copy(userHeadingDeg = azimuthDeg)
                    updateGuidanceState(g.onAzimuthUpdate(azimuthDeg))
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    updateGuidanceState(g.onStep(stepLengthM))
                }
                Sensor.TYPE_PRESSURE -> {
                    if (event.values.isNotEmpty()) {
                        lastPressureHpa = event.values[0]
                        updateGuidanceState(
                            g.onPressureUpdate(event.values[0], System.currentTimeMillis())
                        )
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val lowAcc = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                engine?.let { updateGuidanceState(it.onCompassAccuracyChanged(lowAcc)) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val spot = repository.getSpotById(spotId)
            val trackPoints = repository.getTrackPoints(spotId).map {
                PathPoint(x = it.x, y = it.y, relHeightM = it.relHeightM, tMs = it.t)
            }
            val settings = settingsStore.settings.first()
            stepLengthM = settings.effectiveStepLengthM()
            hapticEnabled = settings.hapticFeedbackEnabled

            val hasBarometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
            val targetInfo = GuidanceTargetInfo(
                carLat = spot?.lat,
                carLon = spot?.lon,
                anchorLat = spot?.anchorLat,
                anchorLon = spot?.anchorLon,
                anchorRelHeightM = spot?.anchorRelHeightM,
                footprint = trackPoints,
                floorHeightM = settings.floorHeightM,
                hasBarometer = hasBarometer,
                carAccuracyM = spot?.accuracyM,
                anchorSet = spot?.anchorSet ?: false,
                environmentMode = com.truong.findmycar.detect.ParkingEnvironmentResolver.fromStorageName(
                    spot?.environmentMode
                )
            )
            val createdEngine = GuidanceEngine(targetInfo)
            engine = createdEngine

            _uiState.value = FindCarUiState(
                spot = spot,
                guidance = createdEngine.currentState(),
                vehicleType = settings.vehicleType,
                hapticEnabled = settings.hapticFeedbackEnabled,
                isLoading = false
            )

            registerSensors()
            val phase = createdEngine.currentState().phase
            if (phase == GuidancePhase.PHASE1_GPS_TO_ANCHOR || phase == GuidancePhase.PHASE1_GPS_TO_CAR) {
                startGpsUpdates()
            }
            startHapticLoop()
        }
    }

    fun enterPhase2Manually() {
        val g = engine ?: return
        stopGpsUpdates()
        updateGuidanceState(g.enterPhase2(lastPressureHpa))
    }

    fun markCarFound(onDone: () -> Unit) {
        viewModelScope.launch {
            stopAllSensorsAndGps()
            TrackingService.stop(appContext)
            repository.markAsFound(spotId)
            onDone()
        }
    }

    private fun updateGuidanceState(next: GuidanceState) {
        if (next.phase == GuidancePhase.PHASE2_FOOTPRINT || next.phase == GuidancePhase.VERY_CLOSE) {
            stopGpsUpdates()
        }

        val isNowAligned = !next.isCompassLowAccuracy &&
            next.phase != GuidancePhase.VERY_CLOSE &&
            next.phase != GuidancePhase.NO_SIGNAL_FALLBACK &&
            abs(next.relativeArrowDeg) < 15f
        if (isNowAligned && !wasAligned) {
            vibrateOneShot(40L, 180)
        }
        wasAligned = isNowAligned

        val isNowVeryClose = next.phase == GuidancePhase.VERY_CLOSE
        if (isNowVeryClose && !wasVeryClose) {
            vibrateOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        wasVeryClose = isNowVeryClose

        _uiState.value = _uiState.value.copy(guidance = next)
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (!Permissions.hasForegroundLocation(appContext)) return
        if (gpsCallback != null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val g = engine ?: return
                val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
                updateGuidanceState(g.onGpsUpdate(loc.latitude, loc.longitude, acc, lastPressureHpa))
            }
        }
        gpsCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopGpsUpdates() {
        val cb = gpsCallback ?: return
        gpsCallback = null
        fusedLocationClient.removeLocationUpdates(cb)
    }

    private fun startHapticLoop() {
        hapticJob?.cancel()
        hapticJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value.guidance
                val interval = GuidanceEngine.hapticIntervalMsForDistance(state.distanceMeters)
                delay(interval)
                if (hapticEnabled &&
                    state.phase != GuidancePhase.NO_SIGNAL_FALLBACK &&
                    state.phase != GuidancePhase.VERY_CLOSE &&
                    state.distanceMeters != null
                ) {
                    vibrateOneShot(25L, 110)
                }
            }
        }
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        if (!hapticEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }

    private fun stopAllSensorsAndGps() {
        hapticJob?.cancel()
        stopGpsUpdates()
        sensorManager.unregisterListener(sensorListener)
    }

    override fun onCleared() {
        stopAllSensorsAndGps()
        super.onCleared()
    }

    class Factory(
        private val spotId: Long,
        private val context: Context,
        private val repository: ParkingRepository,
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FindCarViewModel(spotId, context.applicationContext, repository, settingsStore) as T
        }
    }
}
```

