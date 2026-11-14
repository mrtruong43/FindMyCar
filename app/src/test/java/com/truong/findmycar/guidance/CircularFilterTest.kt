package com.truong.findmycar.guidance

import com.truong.findmycar.util.GeoMath
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CircularFilterTest {

    @Test
    fun smoothTransitionAcross359To1BoundaryWithoutJumpingTo180() {
        val filter = CircularFilter(alpha = 0.25f)
        var prev = filter.update(359f)

        val steps = listOf(359.5f, 0f, 0.5f, 1f, 1f, 1f)
        for (angle in steps) {
            val next = filter.update(angle)
            val delta = abs(GeoMath.shortestAngleDeltaDegrees(prev, next))
            assertTrue("Expected smooth step near 0°, got jump $delta° ($prev -> $next)", delta < 5f)
            assertTrue("Filtered angle $next jumped away from 0°/360°", next >= 355f || next <= 5f)
            prev = next
        }
    }

    @Test
    fun smoothTransitionAcross1To359Boundary() {
        val filter = CircularFilter(alpha = 0.25f)
        var prev = filter.update(1f)

        val steps = listOf(0.5f, 0f, 359.5f, 359f)
        for (angle in steps) {
            val next = filter.update(angle)
            val delta = abs(GeoMath.shortestAngleDeltaDegrees(prev, next))
            assertTrue("Expected smooth reverse wrap, got $delta°", delta < 5f)
            assertTrue("Filtered angle $next jumped away from 0°/360°", next >= 355f || next <= 5f)
            prev = next
        }
    }
}
