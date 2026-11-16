package com.truong.findmycar.tracking

import com.truong.findmycar.guidance.GuidancePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationFusionAndReplayTest {

    @Test
    fun orientationFusionIgnoresMagneticSpikeAndTracksGameRotation() {
        val fusion = OrientationFusion()

        // Initial clean calibration: game=10°, mag=100° -> offset=+90° -> fused=100°
        val init = fusion.update(
            gameAzimuthDeg = 10f,
            magAzimuthDeg = 100f,
            hasMagneticInterference = false,
            timestampMs = 1_000L
        )
        assertEquals(100f, init, 0.5f)

        // Steel magnetic spike: mag jumps wildly to 260° while user walks straight (game=10°)
        val underSpike = fusion.update(
            gameAzimuthDeg = 10f,
            magAzimuthDeg = 260f,
            hasMagneticInterference = true,
            timestampMs = 1_500L
        )
        assertEquals(100f, underSpike, 0.5f)

        // User turns +20° on gyro (game=30°) during spike -> fused = 120°
        val turnedUnderSpike = fusion.update(
            gameAzimuthDeg = 30f,
            magAzimuthDeg = 310f,
            hasMagneticInterference = true,
            timestampMs = 2_000L
        )
        assertEquals(120f, turnedUnderSpike, 0.5f)
    }

    @Test
    fun orientationFusionDetectsFastTurnAndFreezesOffset() {
        val fusion = OrientationFusion()
        fusion.update(0f, 90f, hasMagneticInterference = false, timestampMs = 1_000L)

        // 45° turn in 200ms = 225°/s > 35°/s -> fast turn
        val duringFastTurn = fusion.update(45f, 180f, hasMagneticInterference = false, timestampMs = 1_200L)
        assertTrue(fusion.isTurningFast)
        assertEquals(135f, duringFastTurn, 0.5f)

        // Straighten out -> no longer fast turning
        fusion.update(46f, 136f, hasMagneticInterference = false, timestampMs = 2_200L)
        assertFalse(fusion.isTurningFast)
    }

    @Test
    fun replayHarnessParsesDebugCsvAndReplaysOutboundAndReturn() {
        val sb = StringBuilder()
        sb.appendLine("timestamp_ms,event,steps,x_m,y_m,heading_deg,mode,pca_ratio,pressure_hpa,rel_height_m,floor_delta")
        // 10 steps North (0°), 1m each -> (0, 10)
        for (i in 1..10) {
            sb.appendLine("${1_000L + i * 600L},STEP,$i,0.0,${i.toFloat()},0.0,HAND_HELD,1.0,1013.25,0.0,0")
        }
        // 10 steps East (90°), 1m each -> (10, 10)
        for (i in 11..20) {
            sb.appendLine("${1_000L + i * 600L},STEP,$i,${(i - 10).toFloat()},10.0,90.0,HAND_HELD,1.0,1013.25,0.0,0")
        }

        val rows = ReplayHarness.parseCsv(sb.toString())
        assertEquals(20, rows.size)

        val result = ReplayHarness.replayOutboundAndReturn(
            rows = rows,
            stepLengthM = 1.0f,
            expectedAnchorX = 10f,
            expectedAnchorY = 10f
        )
        assertEquals(20, result.stepCount)
        assertTrue("Expected endpoint error < 2.5m, got ${result.endpointErrorM}", result.endpointErrorM < 2.5f)
        assertEquals(GuidancePhase.VERY_CLOSE, result.finalGuidanceState?.phase)
    }
}
