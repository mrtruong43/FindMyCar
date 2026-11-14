package com.truong.findmycar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PathModelTest {

    @Test
    fun closed100mSquareLoopReturnsNearOrigin() {
        val path = PathModel()
        val stepLen = 0.625f // 40 steps per 25m side

        repeat(40) { path.addStep(stepLen, 0f) }   // 25m North
        repeat(40) { path.addStep(stepLen, 90f) }  // 25m East
        repeat(40) { path.addStep(stepLen, 180f) } // 25m South
        repeat(40) { path.addStep(stepLen, 270f) } // 25m West

        assertEquals(100f, path.totalLengthMeters(), 0.05f)
        val end = path.currentPosition()
        val endError = hypot(end.x, end.y)
        assertTrue("Expected closed-loop error < 0.1m, got $endError", endError < 0.1f)
    }

    @Test
    fun polylineReversalProjectionAndLookaheadWorkCorrectly() {
        val path = PathModel()
        // Walk 20m North, then 20m East
        repeat(20) { path.addStep(1f, 0f) }
        repeat(20) { path.addStep(1f, 90f) }

        val reversed = path.reversedFromAnchor()
        assertEquals(20f, reversed.first().x, 0.01f)
        assertEquals(20f, reversed.first().y, 0.01f)
        assertEquals(0f, reversed.last().x, 0.01f)
        assertEquals(0f, reversed.last().y, 0.01f)

        // User is near (10, 21) -> projects onto (10, 20), 30m remaining back to (0, 0)
        val proj = PathModel.projectOntoPolyline(px = 10f, py = 21f, polyline = reversed)
        assertEquals(10f, proj.projectedX, 0.05f)
        assertEquals(20f, proj.projectedY, 0.05f)
        assertEquals(1f, proj.distanceToPolylineM, 0.05f)
        assertEquals(30f, proj.remainingDistanceM, 0.1f)

        // Lookahead 6.5m from (10, 20) along reversed path heading West -> (3.5, 20)
        val lookahead = PathModel.lookaheadPoint(proj, reversed, lookaheadM = 6.5f)
        assertEquals(3.5f, lookahead.x, 0.1f)
        assertEquals(20f, lookahead.y, 0.1f)
    }
}
