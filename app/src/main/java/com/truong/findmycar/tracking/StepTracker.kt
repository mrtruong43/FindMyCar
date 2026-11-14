package com.truong.findmycar.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Registers Sensor.TYPE_STEP_DETECTOR (with hardware batching when supported)
 * and invokes [onStep] for each detected step.
 */
class StepTracker(
    context: Context,
    private val onStep: (timestampMs: Long) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                onStep(System.currentTimeMillis())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): Boolean {
        if (registered || stepSensor == null) return stepSensor != null
        val maxLatencyUs = if (stepSensor.fifoMaxEventCount > 0) BATCH_LATENCY_US else 0
        registered = sensorManager.registerListener(
            listener,
            stepSensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            maxLatencyUs
        )
        return registered
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }

    companion object {
        private const val BATCH_LATENCY_US = 1_000_000 // 1 second hardware batching
    }
}
