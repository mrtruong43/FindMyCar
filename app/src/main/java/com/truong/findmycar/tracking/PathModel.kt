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
