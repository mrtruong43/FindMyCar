package com.truong.findmycar.detect

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.db.SpotSource
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class SaveSpotResult(
    val spotId: Long,
    val hasLocation: Boolean,
    val hasPressure: Boolean
)

class ParkingDetector(
    private val context: Context,
    private val repository: ParkingRepository
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    suspend fun saveManualSpot(): SaveSpotResult = coroutineScope {
        val locationDeferred = async { fetchCurrentLocationWithTimeout(LOCATION_TIMEOUT_MS) }
        val pressureDeferred = async { sampleBarometerPressure(PRESSURE_SAMPLE_DURATION_MS) }

        val location = locationDeferred.await()
        val pressureHpa = pressureDeferred.await()

        val spotId = repository.saveNewSpot(
            lat = location?.latitude,
            lon = location?.longitude,
            accuracyM = if (location?.hasAccuracy() == true) location.accuracy else null,
            pressureHpa = pressureHpa,
            source = SpotSource.MANUAL
        )

        SaveSpotResult(
            spotId = spotId,
            hasLocation = location != null,
            hasPressure = pressureHpa != null
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocationWithTimeout(timeoutMs: Long): Location? {
        if (!Permissions.hasLocationPermission(context)) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()
                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }.addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }.addOnCanceledListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private suspend fun sampleBarometerPressure(durationMs: Long): Float? {
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null

        val samples = mutableListOf<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) {
                    synchronized(samples) {
                        samples.add(event.values[0])
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            pressureSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        if (!registered) return null

        return try {
            delay(durationMs)
            synchronized(samples) {
                if (samples.isEmpty()) null else samples.average().toFloat()
            }
        } finally {
            sensorManager.unregisterListener(listener)
        }
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 5_000L
        private const val PRESSURE_SAMPLE_DURATION_MS = 2_000L
    }
}
