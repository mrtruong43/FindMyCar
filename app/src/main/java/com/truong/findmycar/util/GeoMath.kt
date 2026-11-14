package com.truong.findmycar.util

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure Kotlin math and formatting helpers decoupled from Android framework classes
 * for straightforward JVM unit testing.
 */
object GeoMath {

    private const val BAROMETRIC_SCALE_M = 44330.0
    private const val BAROMETRIC_EXPONENT = 1.0 / 5.255

    /**
     * Barometric relative altitude (in meters) relative to [referencePressureHpa].
     * Positive when [currentPressureHpa] < [referencePressureHpa] (higher elevation).
     */
    fun relativeAltitudeMeters(currentPressureHpa: Float, referencePressureHpa: Float): Float {
        if (referencePressureHpa <= 0f || currentPressureHpa <= 0f) return 0f
        val ratio = (currentPressureHpa / referencePressureHpa).toDouble()
        return (BAROMETRIC_SCALE_M * (1.0 - ratio.pow(BAROMETRIC_EXPONENT))).toFloat()
    }

    fun estimateFloorDelta(relHeightM: Float, floorHeightM: Float): Int {
        if (floorHeightM <= 0f) return 0
        return (relHeightM / floorHeightM).roundToInt()
    }

    fun defaultStepLengthM(userHeightM: Float): Float = 0.415f * userHeightM

    fun buildGeoUri(lat: Double, lon: Double): String = "geo:$lat,$lon?q=$lat,$lon"
}
