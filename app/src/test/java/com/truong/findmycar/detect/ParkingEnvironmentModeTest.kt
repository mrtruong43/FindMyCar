package com.truong.findmycar.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingEnvironmentModeTest {

    @Test
    fun undergroundOverrideForcesFootprintTrackingEvenWithStrongGps() {
        val resolved = ParkingEnvironmentResolver.resolve(
            mode = ParkingEnvironmentMode.UNDERGROUND,
            accuracyM = 5f
        )
        assertEquals(ParkingEnvironmentMode.UNDERGROUND, resolved)
        assertTrue(ParkingEnvironmentResolver.shouldStartTrackingService(resolved))
    }

    @Test
    fun outdoorOverrideSkipsDeadReckoningTrackingServiceEvenWithWeakGps() {
        val resolved = ParkingEnvironmentResolver.resolve(
            mode = ParkingEnvironmentMode.OUTDOOR,
            accuracyM = 80f
        )
        assertEquals(ParkingEnvironmentMode.OUTDOOR, resolved)
        assertFalse(ParkingEnvironmentResolver.shouldStartTrackingService(resolved))
    }

    @Test
    fun autoModeResolvesFromGpsAccuracyThreshold() {
        val strongResolved = ParkingEnvironmentResolver.resolve(
            mode = ParkingEnvironmentMode.AUTO,
            accuracyM = 12f
        )
        assertEquals(ParkingEnvironmentMode.OUTDOOR, strongResolved)
        assertFalse(ParkingEnvironmentResolver.shouldStartTrackingService(strongResolved))

        val weakResolved = ParkingEnvironmentResolver.resolve(
            mode = ParkingEnvironmentMode.AUTO,
            accuracyM = 35f
        )
        assertEquals(ParkingEnvironmentMode.UNDERGROUND, weakResolved)
        assertTrue(ParkingEnvironmentResolver.shouldStartTrackingService(weakResolved))

        val nullResolved = ParkingEnvironmentResolver.resolve(
            mode = ParkingEnvironmentMode.AUTO,
            accuracyM = null
        )
        assertEquals(ParkingEnvironmentMode.UNDERGROUND, nullResolved)
        assertTrue(ParkingEnvironmentResolver.shouldStartTrackingService(nullResolved))
    }
}
