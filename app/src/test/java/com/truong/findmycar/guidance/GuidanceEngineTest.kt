package com.truong.findmycar.guidance

import com.truong.findmycar.detect.ParkingEnvironmentMode
import com.truong.findmycar.tracking.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GuidanceEngineTest {

    @Test
    fun phase1TransitionsToPhase2Within15MetersOfAnchor() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = 10.7769,
                carLon = 106.7009,
                anchorLat = 10.7775,
                anchorLon = 106.7009,
                anchorRelHeightM = 6.4f,
                footprint = listOf(
                    PathPoint(0f, 0f),
                    PathPoint(0f, 20f)
                )
            )
        )

        // ~50m away from anchor -> still Phase 1
        val farState = engine.onGpsUpdate(10.77795, 106.7009, accuracyM = 8f)
        assertEquals(GuidancePhase.PHASE1_GPS_TO_ANCHOR, farState.phase)
        assertTrue(farState.canEnterPhase2Manually)

        // ~10m away from anchor (< 15m) -> transitions to Phase 2
        val nearAnchorState = engine.onGpsUpdate(10.77759, 106.7009, accuracyM = 8f, currentPressureHpa = 1012.5f)
        assertEquals(GuidancePhase.PHASE2_FOOTPRINT, nearAnchorState.phase)
        assertEquals(20f, nearAnchorState.distanceMeters!!, 0.5f)
        assertEquals(-2, nearAnchorState.floorDeltaToTarget)
    }

    @Test
    fun reversedPolylineAndProximityBelow8Meters() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = null,
                carLon = null,
                anchorLat = null,
                anchorLon = null,
                anchorRelHeightM = 3.2f,
                footprint = listOf(
                    PathPoint(0f, 0f),
                    PathPoint(0f, 20f)
                )
            )
        )

        // Starts in Phase 2 at (0, 20), car is at (0, 0) -> South = 180°
        // User facing South (180°) -> arrow should point straight ahead (~0°)
        engine.onAzimuthUpdate(180f)
        repeat(5) { engine.onAzimuthUpdate(180f) }
        val startState = engine.currentState()
        assertEquals(GuidancePhase.PHASE2_FOOTPRINT, startState.phase)
        assertEquals(20f, startState.distanceMeters!!, 0.5f)
        assertTrue(abs(startState.relativeArrowDeg) < 5f)

        // Walk 13m South -> 7m remaining (< 8m) -> VERY_CLOSE
        var state = startState
        repeat(13) {
            state = engine.onStep(1.0f)
        }
        assertEquals(GuidancePhase.VERY_CLOSE, state.phase)
        assertEquals(7f, state.distanceMeters!!, 0.5f)
    }

    @Test
    fun floorDeltaUsesNegativeAnchorRelHeightInPhase2() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = null,
                carLon = null,
                anchorLat = null,
                anchorLon = null,
                anchorRelHeightM = 6.4f, // anchor is 2 floors above car
                footprint = listOf(PathPoint(0f, 0f), PathPoint(0f, 15f)),
                floorHeightM = 3.2f,
                hasBarometer = true
            )
        )

        engine.enterPhase2(currentPressureHpa = 1012.48f)
        assertEquals(-2, engine.currentState().floorDeltaToTarget)

        // Descend 1 floor (~3.2m -> pressure +0.384 hPa)
        var t = 1000L
        lateinit var midState: GuidanceState
        repeat(10) {
            midState = engine.onPressureUpdate(1012.864f, t)
            t += 200L
        }
        assertEquals(-1, midState.floorDeltaToTarget)
    }

    @Test
    fun noSignalFallbackWhenNoFootprintAndNoGps() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = null,
                carLon = null,
                anchorLat = null,
                anchorLon = null,
                anchorRelHeightM = null,
                footprint = emptyList()
            )
        )
        val state = engine.currentState()
        assertEquals(GuidancePhase.NO_SIGNAL_FALLBACK, state.phase)
        assertNull(state.distanceMeters)
    }

    @Test
    fun outdoorSpot200mAwayStaysInPhase1GpsToCarAndRequiresGoodAccuracyForVeryClose() {
        val carLat = 10.7769
        val carLon = 106.7009
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = carLat,
                carLon = carLon,
                anchorLat = 10.7770,
                anchorLon = 106.7010,
                anchorRelHeightM = 0.5f,
                footprint = listOf(PathPoint(0f, 0f), PathPoint(0f, 20f)),
                carAccuracyM = 8f,
                anchorSet = true
            )
        )

        // Outdoor spot must start in PHASE1_GPS_TO_CAR directly toward car
        assertEquals(GuidancePhase.PHASE1_GPS_TO_CAR, engine.currentState().phase)

        // ~200m north of car -> stays PHASE1_GPS_TO_CAR (~200m)
        val farState = engine.onGpsUpdate(lat = carLat + 0.0018, lon = carLon, accuracyM = 10f)
        assertEquals(GuidancePhase.PHASE1_GPS_TO_CAR, farState.phase)
        assertTrue((farState.distanceMeters ?: 0f) > 180f)

        // Within 5m but poor accuracy (40m) -> must NOT jump to VERY_CLOSE
        val inaccurateState = engine.onGpsUpdate(lat = carLat + 0.00004, lon = carLon, accuracyM = 40f)
        assertEquals(GuidancePhase.PHASE1_GPS_TO_CAR, inaccurateState.phase)

        // Within 5m AND good accuracy (8m) -> transitions to VERY_CLOSE
        val closeState = engine.onGpsUpdate(lat = carLat + 0.00004, lon = carLon, accuracyM = 8f)
        assertEquals(GuidancePhase.VERY_CLOSE, closeState.phase)
    }

    @Test
    fun phase2StartsAtAnchorEndpointWithFullRemainingDistance() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = null,
                carLon = null,
                anchorLat = 10.7775,
                anchorLon = 106.7015,
                anchorRelHeightM = -3.2f,
                footprint = listOf(
                    PathPoint(0f, 0f),
                    PathPoint(0f, 100f),
                    PathPoint(0f, 200f)
                ),
                carAccuracyM = 50f,
                anchorSet = true
            )
        )

        assertEquals(GuidancePhase.PHASE1_GPS_TO_ANCHOR, engine.currentState().phase)
        val p2State = engine.enterPhase2()
        assertEquals(GuidancePhase.PHASE2_FOOTPRINT, p2State.phase)
        // Must start at anchor endpoint (0, 200) -> ~200m remaining to car (0, 0), NOT VERY_CLOSE
        assertEquals(200f, p2State.distanceMeters ?: 0f, 1.0f)
    }

    @Test
    fun explicitUndergroundModeUsesFootprintFlowEvenWithStrongCarGps() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = 10.7769,
                carLon = 106.7009,
                anchorLat = 10.7775,
                anchorLon = 106.7015,
                anchorRelHeightM = 0.2f,
                footprint = listOf(PathPoint(0f, 0f), PathPoint(0f, 40f)),
                carAccuracyM = 6f,
                anchorSet = true,
                environmentMode = ParkingEnvironmentMode.UNDERGROUND
            )
        )
        assertEquals(GuidancePhase.PHASE1_GPS_TO_ANCHOR, engine.currentState().phase)
        val p2 = engine.enterPhase2()
        assertEquals(GuidancePhase.PHASE2_FOOTPRINT, p2.phase)
        assertEquals(40f, p2.distanceMeters ?: 0f, 1.0f)
    }

    @Test
    fun explicitOutdoorModeRoutesDirectToCarEvenWithWeakCarGpsAndFootprint() {
        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = 10.7769,
                carLon = 106.7009,
                anchorLat = 10.7775,
                anchorLon = 106.7015,
                anchorRelHeightM = 4.0f,
                footprint = listOf(PathPoint(0f, 0f), PathPoint(0f, 40f)),
                carAccuracyM = 60f,
                anchorSet = true,
                environmentMode = ParkingEnvironmentMode.OUTDOOR
            )
        )
        assertEquals(GuidancePhase.PHASE1_GPS_TO_CAR, engine.currentState().phase)
    }

    @Test
    fun stationaryGpsJitterBelowThreeMetersHoldsTargetBearing() {
        // Car is 100m North of user
        val carLat = 10.7779
        val carLon = 106.7009
        val userLat = 10.7770
        val userLon = 106.7009

        val engine = GuidanceEngine(
            GuidanceTargetInfo(
                carLat = carLat,
                carLon = carLon,
                anchorLat = null,
                anchorLon = null,
                anchorRelHeightM = null,
                footprint = emptyList(),
                carAccuracyM = 8f,
                environmentMode = ParkingEnvironmentMode.OUTDOOR
            )
        )

        engine.onAzimuthUpdate(0f) // User facing North
        val initial = engine.onGpsUpdate(userLat, userLon, accuracyM = 8f)
        val initialArrow = initial.relativeArrowDeg

        // Jitter < 3m (~1.5m East) -> bearing must remain frozen
        val jittered = engine.onGpsUpdate(userLat, userLon + 0.000014, accuracyM = 8f)
        assertEquals(initialArrow, jittered.relativeArrowDeg, 0.5f)
    }
}
