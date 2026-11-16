package com.truong.findmycar.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

enum class HeadingMode {
    HAND_HELD,
    POCKET_PCA
}

data class HeadingSensorSnapshot(
    val headingDeg: Float,
    val mode: HeadingMode,
    val pcaRatio: Float
)

/**
 * Android SensorManager adapter for M4A's pure Kotlin [HeadingEstimator].
 * - Mode A (Hand-held / Relaxed): 3x3 rotationMatrix vector projection (-Z upright, +Y flat/side-edge)
 *   with adaptive filtering, phone-call proximity hold, and basement steel magnetic spike guard.
 * - Mode B (In-pocket): TYPE_LINEAR_ACCELERATION rotated to world frame -> 2.5s PCA.
 */
class HeadingSensorAdapter(
    context: Context,
    private val onHeadingUpdated: (HeadingSensorSnapshot) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val estimator = HeadingEstimator()
    private val rotationMatrix = FloatArray(9)

    private var hasRotationMatrix = false
    private var latestTopUp = 0f
    private var lastValidHandHeldHeading: Float? = null
    private var isProximityCovered = false
    private var hasMagneticInterference = false
    private var registered = false
    private var lowPowerMode = false

    @Volatile
    var currentSnapshot = HeadingSensorSnapshot(0f, HeadingMode.HAND_HELD, 1f)
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    hasRotationMatrix = true
                    latestTopUp = rotationMatrix[7]

                    if (isProximityCovered) {
                        // Phone-call proximity hold: freeze last valid hand-held heading
                        lastValidHandHeldHeading?.let { held ->
                            updateSnapshot(
                                HeadingSensorSnapshot(
                                    headingDeg = held,
                                    mode = HeadingMode.HAND_HELD,
                                    pcaRatio = currentSnapshot.pcaRatio
                                )
                            )
                        }
                        return
                    }

                    val (azimuthDeg, postureBaseAlpha) = computeMultiPostureAzimuth(rotationMatrix)
                    val effectiveBaseAlpha = if (hasMagneticInterference) {
                        MAGNETIC_INTERFERENCE_ALPHA
                    } else {
                        postureBaseAlpha
                    }

                    if (isHandHeldPosture()) {
                        val est = estimator.updateHandHeldAzimuth(azimuthDeg, effectiveBaseAlpha)
                        lastValidHandHeldHeading = est.headingDeg
                        updateSnapshot(
                            HeadingSensorSnapshot(
                                headingDeg = est.headingDeg,
                                mode = HeadingMode.HAND_HELD,
                                pcaRatio = currentSnapshot.pcaRatio
                            )
                        )
                    }
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!hasRotationMatrix || event.values.size < 3 || isProximityCovered) return
                    val lx = event.values[0]
                    val ly = event.values[1]
                    val lz = event.values[2]

                    // Rotate device-frame linear acceleration into world frame (X=East, Y=North, Z=Up)
                    val ax = rotationMatrix[0] * lx + rotationMatrix[1] * ly + rotationMatrix[2] * lz
                    val ay = rotationMatrix[3] * lx + rotationMatrix[4] * ly + rotationMatrix[5] * lz
                    val az = rotationMatrix[6] * lx + rotationMatrix[7] * ly + rotationMatrix[8] * lz

                    val pcaEstimate = estimator.addPocketSample(ax, ay, az, System.currentTimeMillis())
                    if (!isHandHeldPosture() && pcaEstimate.isConfident) {
                        updateSnapshot(
                            HeadingSensorSnapshot(
                                headingDeg = pcaEstimate.headingDeg,
                                mode = HeadingMode.POCKET_PCA,
                                pcaRatio = pcaEstimate.eigenvalueRatio
                            )
                        )
                    } else if (pcaEstimate.isConfident) {
                        currentSnapshot = currentSnapshot.copy(pcaRatio = pcaEstimate.eigenvalueRatio)
                    }
                }

                Sensor.TYPE_PROXIMITY -> {
                    val maxRange = proximitySensor?.maximumRange ?: 0f
                    if (event.values.isNotEmpty() && maxRange > 0f) {
                        isProximityCovered = event.values[0] < maxRange
                    }
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (event.values.size >= 3) {
                        val bx = event.values[0]
                        val by = event.values[1]
                        val bz = event.values[2]
                        val magUt = sqrt(bx * bx + by * by + bz * bz)
                        hasMagneticInterference = magUt < MIN_NORMAL_MAG_UT || magUt > MAX_NORMAL_MAG_UT
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun isHandHeldPosture(): Boolean {
        // Allows natural arm back-swing while walking (topUp >= -0.50f)
        return latestTopUp >= -0.50f
    }

    private fun updateSnapshot(snapshot: HeadingSensorSnapshot) {
        currentSnapshot = snapshot
        onHeadingUpdated(snapshot)
    }

    fun start() {
        if (registered) return
        registerWithDelay(if (lowPowerMode) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME)
    }

    fun setLowPowerMode(enabled: Boolean) {
        if (lowPowerMode == enabled) return
        lowPowerMode = enabled
        if (registered) {
            stop()
            registerWithDelay(if (enabled) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun registerWithDelay(delayUs: Int) {
        rotationSensor?.let { sensorManager.registerListener(listener, it, delayUs) }
        linearAccelSensor?.let { sensorManager.registerListener(listener, it, delayUs) }
        proximitySensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magneticSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        registered = rotationSensor != null ||
            linearAccelSensor != null ||
            proximitySensor != null ||
            magneticSensor != null
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }

    companion object {
        const val UPRIGHT_TOP_UP_THRESHOLD = 0.707f
        const val UPRIGHT_BASE_ALPHA = 0.35f
        const val FLAT_OR_SIDE_BASE_ALPHA = 0.18f
        const val MAGNETIC_INTERFERENCE_ALPHA = 0.08f
        const val MIN_NORMAL_MAG_UT = 20f
        const val MAX_NORMAL_MAG_UT = 85f

        /**
         * Computes forward horizontal azimuth directly from the 3x3 world [rotationMatrix]
         * (row 0 = East, row 1 = North, row 2 = Up):
         * - Upright viewing (> 45°, |topUp| > 0.707): projects rear camera (-Z) -> (-R[2], -R[5]).
         * - Flat or relaxed arm-at-side (side-edge down): projects top edge (+Y) -> (R[1], R[4]).
         *
         * @return Pair(azimuthDeg, recommendedBaseAlpha)
         */
        fun computeMultiPostureAzimuth(rotationMatrix: FloatArray): Pair<Float, Float> {
            val topUp = rotationMatrix[7]
            val east: Float
            val north: Float
            val baseAlpha: Float
            if (abs(topUp) > UPRIGHT_TOP_UP_THRESHOLD) {
                east = -rotationMatrix[2]
                north = -rotationMatrix[5]
                baseAlpha = UPRIGHT_BASE_ALPHA
            } else {
                east = rotationMatrix[1]
                north = rotationMatrix[4]
                baseAlpha = FLAT_OR_SIDE_BASE_ALPHA
            }
            val azimuthDeg = GeoMath.normalizeAngleDegrees(
                Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
            )
            return azimuthDeg to baseAlpha
        }
    }
}
