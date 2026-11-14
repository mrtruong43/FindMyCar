package com.truong.findmycar.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingStateMachineTest {

    @Test
    fun triggersAutoParkingWhenWalkingWithinTwoMinutesOfVehicleExit() {
        val sm = ParkingStateMachine()
        val t0 = 1_000_000L

        sm.onVehicleExit(t0)
        assertTrue(sm.onWalkingEnter(t0 + 60_000L))
        // Second walking event should not re-trigger without another vehicle exit.
        assertFalse(sm.onWalkingEnter(t0 + 70_000L))
    }

    @Test
    fun doesNotTriggerAfterTwoMinutesWindow() {
        val sm = ParkingStateMachine()
        val t0 = 1_000_000L

        sm.onVehicleExit(t0)
        assertFalse(sm.onWalkingEnter(t0 + 121_000L))
    }

    @Test
    fun deduplicatesSavesWithinNinetySeconds() {
        val sm = ParkingStateMachine()
        val t0 = 1_000_000L

        assertTrue(sm.canSaveParkingSpot(t0))
        sm.recordSpotSaved(t0)

        assertFalse(sm.canSaveParkingSpot(t0 + 89_999L))
        assertTrue(sm.canSaveParkingSpot(t0 + 90_000L))
    }

    @Test
    fun resetCooldownAllowsImmediateAutoOrManualResaveAfterFound() {
        val sm = ParkingStateMachine()
        val t0 = 1_000_000L

        sm.recordSpotSaved(t0)
        assertFalse(sm.canSaveParkingSpot(t0 + 5_000L))

        sm.resetCooldown()
        assertTrue(sm.canSaveParkingSpot(t0 + 5_000L))
    }
}
