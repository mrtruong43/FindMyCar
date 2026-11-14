package com.truong.findmycar.detect

/**
 * Pure Kotlin state machine for automatic parking detection timing and deduplication rules.
 * - Triggers auto-parking when WALKING ENTER occurs within 2 minutes of IN_VEHICLE EXIT.
 * - Deduplicates automatic parking saves within 90 seconds of the last saved spot.
 * - Manual user saves bypass deduplication; marking FOUND or deleting resets cooldown.
 */
class ParkingStateMachine(
    private val exitToWalkWindowMs: Long = DEFAULT_EXIT_TO_WALK_WINDOW_MS,
    private val deduplicationWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS
) {
    private val lock = Any()
    private var lastVehicleExitMs: Long? = null
    private var lastSavedSpotMs: Long? = null

    fun onVehicleExit(nowMs: Long) {
        synchronized(lock) {
            lastVehicleExitMs = nowMs
        }
    }

    fun onVehicleEnter() {
        synchronized(lock) {
            lastVehicleExitMs = null
        }
    }

    fun onWalkingEnter(nowMs: Long): Boolean {
        synchronized(lock) {
            val exitMs = lastVehicleExitMs ?: return false
            val elapsed = nowMs - exitMs
            lastVehicleExitMs = null
            return elapsed in 0L..exitToWalkWindowMs
        }
    }

    fun canSaveParkingSpot(nowMs: Long): Boolean {
        synchronized(lock) {
            val lastSaved = lastSavedSpotMs ?: return true
            return (nowMs - lastSaved) >= deduplicationWindowMs
        }
    }

    fun recordSpotSaved(nowMs: Long) {
        synchronized(lock) {
            lastSavedSpotMs = nowMs
        }
    }

    fun resetCooldown() {
        synchronized(lock) {
            lastSavedSpotMs = null
            lastVehicleExitMs = null
        }
    }

    companion object {
        const val DEFAULT_EXIT_TO_WALK_WINDOW_MS = 2L * 60L * 1000L
        const val DEFAULT_DEDUP_WINDOW_MS = 90L * 1000L
    }
}
