package com.truong.findmycar.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentLocationBufferTest {

    @Test
    fun evictsEntriesOlderThanFiveMinutes() {
        var now = 1_000_000L
        val buffer = RecentLocationBuffer(timeProvider = { now })

        buffer.add(BufferedLocation(lat = 10.0, lon = 106.0, accuracyM = 12f, timeMs = now))
        now += 4 * 60 * 1000L // +4m
        buffer.add(BufferedLocation(lat = 10.1, lon = 106.1, accuracyM = 15f, timeMs = now))
        assertEquals(2, buffer.size())

        now += 2 * 60 * 1000L // +6m from first entry -> first entry evicted
        assertEquals(1, buffer.size())
        val best = buffer.getBestRecentLocation(50f)
        assertEquals(10.1, best!!.lat, 0.0001)
    }

    @Test
    fun returnsMostRecentFixWithinAccuracyThreshold() {
        var now = 1_000_000L
        val buffer = RecentLocationBuffer(timeProvider = { now })

        buffer.add(BufferedLocation(lat = 10.0, lon = 106.0, accuracyM = 18f, timeMs = now))
        now += 10_000L
        buffer.add(BufferedLocation(lat = 10.2, lon = 106.2, accuracyM = 80f, timeMs = now)) // too inaccurate

        val best = buffer.getBestRecentLocation(50f)
        assertEquals(10.0, best!!.lat, 0.0001)
    }

    @Test
    fun returnsNullWhenAllFixesAreTooInaccurate() {
        val now = 1_000_000L
        val buffer = RecentLocationBuffer(timeProvider = { now })
        buffer.add(BufferedLocation(lat = 10.0, lon = 106.0, accuracyM = 75f, timeMs = now))

        assertNull(buffer.getBestRecentLocation(50f))
    }
}
