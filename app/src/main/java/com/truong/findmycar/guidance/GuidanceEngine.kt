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
    private val azimuthFilter = CircularFilter(alpha = 0.30f)
    private val arrowFilter = CircularFilter(alpha = 0.35f)

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
        latestAzimuthDeg = azimuthFilter.update(azimuthDeg)
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
        targetBearingDeg = GeoMath.initialBearingDegrees(lat, lon, tLat, tLon)
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
