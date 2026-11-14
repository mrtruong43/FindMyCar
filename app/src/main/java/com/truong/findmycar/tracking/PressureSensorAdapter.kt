package com.truong.findmycar.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Android SensorManager adapter for M4A's pure Kotlin [PressureAltimeter].
 */
class PressureSensorAdapter(
    context: Context,
    baselinePressureHpa: Float,
    floorHeightM: Float,
    private val onReading: (AltimeterReading) -> Unit
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val altimeter = PressureAltimeter(
        baselinePressureHpa = baselinePressureHpa,
        floorHeightM = floorHeightM
    )
    private var registered = false

    @Volatile
    var latestReading: AltimeterReading? = null
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE && event.values.isNotEmpty()) {
                val reading = altimeter.update(event.values[0], System.currentTimeMillis())
                latestReading = reading
                onReading(reading)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): Boolean {
        if (registered || pressureSensor == null) return pressureSensor != null
        registered = sensorManager.registerListener(
            listener,
            pressureSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        return registered
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }
}
