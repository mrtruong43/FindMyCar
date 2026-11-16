package com.truong.findmycar.tracking

import com.truong.findmycar.guidance.GuidanceEngine
import com.truong.findmycar.guidance.GuidanceState
import com.truong.findmycar.guidance.GuidanceTargetInfo
import kotlin.math.hypot

data class ReplayCsvRow(
    val timestampMs: Long,
    val event: String,
    val steps: Int,
    val xM: Float,
    val yM: Float,
    val headingDeg: Float,
    val mode: String,
    val pcaRatio: Float,
    val pressureHpa: Float?,
    val relHeightM: Float,
    val floorDelta: Int
)

data class ReplayResult(
    val stepCount: Int,
    val finalX: Float,
    val finalY: Float,
    val totalPathLengthM: Float,
    val endpointErrorM: Float,
    val pathPoints: List<PathPoint>,
    val finalGuidanceState: GuidanceState?
)

/**
 * Pure Kotlin replay harness (Phase 0) that parses CSV logs exported by [TrackingStateHolder] / DebugScreen
 * and replays outbound dead-reckoning + return guidance in JUnit without Android hardware.
 */
object ReplayHarness {

    fun parseCsv(csvText: String): List<ReplayCsvRow> {
        val rows = ArrayList<ReplayCsvRow>()
        val lines = csvText.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("timestamp_ms")) continue
            val parts = line.split(',')
            if (parts.size < 11) continue
            val ts = parts[0].toLongOrNull() ?: continue
            val event = parts[1].trim()
            val steps = parts[2].toIntOrNull() ?: continue
            val x = parts[3].toFloatOrNull() ?: continue
            val y = parts[4].toFloatOrNull() ?: continue
            val heading = parts[5].toFloatOrNull() ?: continue
            val mode = parts[6].trim()
            val pcaRatio = parts[7].toFloatOrNull() ?: 1f
            val pressure = parts[8].toFloatOrNull()
            val relHeight = parts[9].toFloatOrNull() ?: 0f
            val floorDelta = parts[10].toIntOrNull() ?: 0
            rows.add(
                ReplayCsvRow(
                    timestampMs = ts,
                    event = event,
                    steps = steps,
                    xM = x,
                    yM = y,
                    headingDeg = heading,
                    mode = mode,
                    pcaRatio = pcaRatio,
                    pressureHpa = pressure,
                    relHeightM = relHeight,
                    floorDelta = floorDelta
                )
            )
        }
        return rows
    }

    fun replayOutboundAndReturn(
        rows: List<ReplayCsvRow>,
        stepLengthM: Float,
        expectedAnchorX: Float? = null,
        expectedAnchorY: Float? = null,
        simulateReturnAlongReversedPath: Boolean = true
    ): ReplayResult {
        val headingEstimator = HeadingEstimator()
        val firstPressure = rows.firstNotNullOfOrNull { it.pressureHpa }
        val altimeter = firstPressure?.let { PressureAltimeter(baselinePressureHpa = it) }

        val pathModel = PathModel()
        var curRelH = 0f
        var stepCount = 0

        for (row in rows) {
            row.pressureHpa?.let { p ->
                curRelH = altimeter?.update(p, row.timestampMs)?.relHeightM ?: curRelH
            }
            val est = headingEstimator.updateHandHeldAzimuth(row.headingDeg)
            if (row.event == "STEP") {
                stepCount++
                pathModel.addStep(
                    stepLengthM = stepLengthM,
                    headingDeg = est.headingDeg,
                    relHeightM = curRelH,
                    tMs = row.timestampMs
                )
            }
        }

        val pathPoints = pathModel.points.toList()
        val lastPt = pathModel.currentPosition()
        val curX = lastPt.x
        val curY = lastPt.y

        val endpointErrorM = if (expectedAnchorX != null && expectedAnchorY != null) {
            hypot((curX - expectedAnchorX).toDouble(), (curY - expectedAnchorY).toDouble()).toFloat()
        } else {
            0f
        }

        val finalGuidanceState = if (simulateReturnAlongReversedPath && pathPoints.size >= 2) {
            val engine = GuidanceEngine(
                GuidanceTargetInfo(
                    carLat = null,
                    carLon = null,
                    anchorLat = null,
                    anchorLon = null,
                    anchorRelHeightM = curRelH,
                    footprint = pathPoints
                )
            )
            val reversed = PathModel.reversedPath(pathPoints)
            var state = engine.currentState()
            for (i in 0 until reversed.size - 1) {
                val from = reversed[i]
                val to = reversed[i + 1]
                val dx = to.x - from.x
                val dy = to.y - from.y
                val segLen = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (segLen > 0.05f) {
                    val segHeading = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
                    engine.onAzimuthUpdate(segHeading)
                    state = engine.onStep(segLen)
                }
            }
            state
        } else {
            null
        }

        return ReplayResult(
            stepCount = stepCount,
            finalX = curX,
            finalY = curY,
            totalPathLengthM = PathModel.totalLengthMeters(pathPoints),
            endpointErrorM = endpointErrorM,
            pathPoints = pathPoints,
            finalGuidanceState = finalGuidanceState
        )
    }
}
