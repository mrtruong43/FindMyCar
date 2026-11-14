package com.truong.findmycar.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin math and geometry helpers decoupled from Android framework classes
 * for straightforward JVM unit testing.
 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val BAROMETRIC_SCALE_M = 44_330.0
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

    fun estimateFloorDelta(relHeightM: Float, floorHeightM: Float = 3.2f): Int {
        if (floorHeightM <= 0f) return 0
        return (relHeightM / floorHeightM).roundToInt()
    }

    fun defaultStepLengthM(userHeightM: Float): Float = 0.415f * userHeightM

    fun buildGeoUri(lat: Double, lon: Double): String = "geo:$lat,$lon?q=$lat,$lon"

    fun haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)

        val sinDPhi = sin(dPhi / 2.0)
        val sinDLambda = sin(dLambda / 2.0)
        val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_M * c
    }

    /**
     * Initial bearing from (lat1, lon1) to (lat2, lon2) in degrees [0, 360),
     * where 0 = North, 90 = East.
     */
    fun initialBearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val deg = Math.toDegrees(atan2(y, x)).toFloat()
        return normalizeAngleDegrees(deg)
    }

    fun normalizeAngleDegrees(angleDeg: Float): Float {
        val mod = angleDeg % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    fun shortestAngleDeltaDegrees(fromDeg: Float, toDeg: Float): Float {
        val diff = (toDeg - fromDeg + 540f) % 360f - 180f
        return if (diff < -180f) diff + 360f else diff
    }
}
