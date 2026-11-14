package com.truong.findmycar.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.truong.findmycar.util.GeoMath
import kotlin.math.abs

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
 * - Mode A (Hand-held): TYPE_ROTATION_VECTOR -> getRotationMatrixFromVector -> getOrientation.
 * - Mode B (In-pocket): TYPE_LINEAR_ACCELERATION rotated to world frame -> 2s PCA.
 * - Adaptive sampling: throttles to SENSOR_DELAY_NORMAL when stationary, restores SENSOR_DELAY_GAME on step.
 */
class HeadingSensorAdapter(
    context: Context,
    private val onHeadingUpdated: (HeadingSensorSnapshot) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val estimator = HeadingEstimator()
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasRotationMatrix = false
    private var latestAzimuthDeg = 0f
    private var latestPitchDeg = 0f
    private var latestRollDeg = 0f
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
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    hasRotationMatrix = true

                    latestPitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    latestRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                    latestAzimuthDeg = computeTiltCompensatedAzimuthDeg(
                        rotationMatrix = rotationMatrix,
                        remappedMatrix = remappedMatrix,
                        orientationOut = orientationAngles,
                        pitchDeg = latestPitchDeg
                    )

                    if (isHandHeldPosture()) {
                        val est = estimator.updateHandHeldAzimuth(latestAzimuthDeg)
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
                    if (!hasRotationMatrix || event.values.size < 3) return
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
                        // Keep PCA ratio visible for debug telemetry even in hand-held mode
                        currentSnapshot = currentSnapshot.copy(pcaRatio = pcaEstimate.eigenvalueRatio)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun isHandHeldPosture(): Boolean {
        // Hand-held posture: phone tilted 5°..88° (flat to upright) and roll < 45°
        return abs(latestPitchDeg) in 5f..88f && abs(latestRollDeg) < 45f
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
        registered = rotationSensor != null || linearAccelSensor != null
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }

    companion object {
        /**
         * Computes tilt-compensated forward azimuth from [rotationMatrix].
         * When held upright (|pitch| > 45°), remaps the coordinate system so the
         * camera's -Z viewing axis projects cleanly onto the horizontal world plane,
         * preventing gimbal-lock flips up to 88°.
         */
        fun computeTiltCompensatedAzimuthDeg(
            rotationMatrix: FloatArray,
            remappedMatrix: FloatArray,
            orientationOut: FloatArray,
            pitchDeg: Float
        ): Float {
            if (abs(pitchDeg) > 45f) {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientationOut)
            }
            return GeoMath.normalizeAngleDegrees(
                Math.toDegrees(orientationOut[0].toDouble()).toFloat()
            )
        }
    }
}
