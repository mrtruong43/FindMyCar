package com.truong.findmycar.detect

import android.location.Location

data class BufferedLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timeMs: Long
)

/**
 * Thread-safe in-memory ring buffer storing recent location fixes from the last 5 minutes.
 * Decoupled from Android's Location lifecycle for straightforward JVM unit testing.
 */
class RecentLocationBuffer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val entries = ArrayDeque<BufferedLocation>()

    fun add(location: Location) {
        add(
            BufferedLocation(
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
                timeMs = if (location.time > 0L) location.time else timeProvider()
            )
        )
    }

    fun add(entry: BufferedLocation) {
        synchronized(lock) {
            val now = timeProvider()
            evictExpiredLocked(now)
            entries.addLast(entry)
        }
    }

    fun getBestRecentLocation(maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_M): BufferedLocation? {
        synchronized(lock) {
            val now = timeProvider()
            evictExpiredLocked(now)
            for (i in entries.indices.reversed()) {
                val item = entries[i]
                if (item.accuracyM <= maxAccuracyM) {
                    return item
                }
            }
            return null
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            evictExpiredLocked(timeProvider())
            return entries.size
        }
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (entries.isNotEmpty() && entries.first().timeMs < cutoff) {
            entries.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5L * 60L * 1000L
        const val DEFAULT_MAX_ACCURACY_M = 50f
    }
}
