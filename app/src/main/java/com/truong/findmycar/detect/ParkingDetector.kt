package com.truong.findmycar.detect

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.truong.findmycar.MainActivity
import com.truong.findmycar.R
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.data.db.SpotSource
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class SaveSpotResult(
    val spotId: Long,
    val hasLocation: Boolean,
    val hasPressure: Boolean,
    val skippedDuplicate: Boolean = false
)

class ParkingDetector(
    private val context: Context,
    private val repository: ParkingRepository
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saveMutex = Mutex()

    val recentLocationBuffer = RecentLocationBuffer()
    private val stateMachine = ParkingStateMachine()

    private var drivingCallback: LocationCallback? = null
    private var drivingTimeoutJob: Job? = null

    suspend fun saveManualSpot(): SaveSpotResult = saveMutex.withLock {
        val nowMs = System.currentTimeMillis()
        if (!stateMachine.canSaveParkingSpot(nowMs)) {
            return@withLock SaveSpotResult(
                spotId = -1L,
                hasLocation = false,
                hasPressure = false,
                skippedDuplicate = true
            )
        }

        stateMachine.recordSpotSaved(nowMs)
        executeParkingSave(source = SpotSource.MANUAL, fromAutoDetect = false)
    }

    suspend fun updateAnchorToCurrentPosition(spot: ParkingSpotEntity): Boolean = coroutineScope {
        val locationDeferred = async { fetchCurrentLocationWithTimeout(LOCATION_TIMEOUT_MS) }
        val pressureDeferred = async { sampleBarometerPressure(PRESSURE_SAMPLE_DURATION_MS) }

        val loc = locationDeferred.await()
        val pressureHpa = pressureDeferred.await()
        val relHeight = if (pressureHpa != null && spot.pressureHpa != null) {
            GeoMath.relativeAltitudeMeters(pressureHpa, spot.pressureHpa)
        } else {
            spot.anchorRelHeightM
        }

        repository.finalizeAnchor(
            spotId = spot.id,
            anchorLat = loc?.latitude,
            anchorLon = loc?.longitude,
            anchorPressureHpa = pressureHpa ?: spot.anchorPressureHpa,
            anchorRelHeightM = relHeight
        )
        true
    }

    suspend fun onActivityTransition(
        activityType: Int,
        transitionType: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        when (activityType) {
            DetectedActivity.IN_VEHICLE -> {
                if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    stateMachine.onVehicleEnter()
                    startDrivingLocationUpdates()
                } else if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                    stateMachine.onVehicleExit(nowMs)
                    stopDrivingLocationUpdates()
                }
            }
            DetectedActivity.WALKING -> {
                if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    if (stateMachine.onWalkingEnter(nowMs)) {
                        triggerAutoParkingSave(nowMs)
                    }
                }
            }
        }
    }

    private suspend fun triggerAutoParkingSave(nowMs: Long) = saveMutex.withLock {
        if (!stateMachine.canSaveParkingSpot(nowMs)) return@withLock
        stateMachine.recordSpotSaved(nowMs)

        val result = executeParkingSave(source = SpotSource.AUTO_ACTIVITY, fromAutoDetect = true)
        if (result.spotId > 0L) {
            if (Permissions.hasForegroundLocation(context)) {
                TrackingService.start(context, result.spotId)
            }
            postAutoSavedNotification(result.spotId)
        }
    }

    private suspend fun executeParkingSave(
        source: String,
        fromAutoDetect: Boolean
    ): SaveSpotResult = coroutineScope {
        val buffered = if (fromAutoDetect) recentLocationBuffer.getBestRecentLocation() else null

        val locationDeferred = async {
            if (buffered != null) {
                null
            } else {
                fetchCurrentLocationWithTimeout(LOCATION_TIMEOUT_MS)
            }
        }
        val pressureDeferred = async { sampleBarometerPressure(PRESSURE_SAMPLE_DURATION_MS) }

        val freshLocation = locationDeferred.await()
        val pressureHpa = pressureDeferred.await()

        val finalLat = buffered?.lat ?: freshLocation?.latitude
        val finalLon = buffered?.lon ?: freshLocation?.longitude
        val finalAcc = buffered?.accuracyM ?: if (freshLocation?.hasAccuracy() == true) {
            freshLocation.accuracy
        } else {
            null
        }

        val spotId = repository.saveNewSpot(
            lat = finalLat,
            lon = finalLon,
            accuracyM = finalAcc,
            pressureHpa = pressureHpa,
            source = source
        )

        SaveSpotResult(
            spotId = spotId,
            hasLocation = finalLat != null && finalLon != null,
            hasPressure = pressureHpa != null
        )
    }

    @SuppressLint("MissingPermission")
    fun startDrivingLocationUpdates() {
        if (!Permissions.hasForegroundLocation(context)) return
        if (drivingCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            DRIVING_UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(DRIVING_MIN_UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    recentLocationBuffer.add(location)
                }
            }
        }
        drivingCallback = callback

        fusedLocationClient.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
        )

        drivingTimeoutJob?.cancel()
        drivingTimeoutJob = detectorScope.launch {
            delay(DRIVING_SAFETY_TIMEOUT_MS)
            stopDrivingLocationUpdates()
        }
    }

    fun stopDrivingLocationUpdates() {
        drivingTimeoutJob?.cancel()
        drivingTimeoutJob = null
        val callback = drivingCallback ?: return
        drivingCallback = null
        fusedLocationClient.removeLocationUpdates(callback)
    }

    private fun postAutoSavedNotification(spotId: Long) {
        if (!Permissions.hasPostNotifications(context)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            PARKING_CHANNEL_ID,
            context.getString(R.string.parking_notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.parking_notif_channel_desc)
        }
        manager.createNotificationChannel(channel)

        val detailIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SPOT_DETAIL_ID, spotId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            spotId.toInt(),
            detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PARKING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.parking_notif_title))
            .setContentText(context.getString(R.string.parking_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(PARKING_NOTIFICATION_ID, notification)
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
        private const val DRIVING_UPDATE_INTERVAL_MS = 7_000L
        private const val DRIVING_MIN_UPDATE_INTERVAL_MS = 5_000L
        private const val DRIVING_SAFETY_TIMEOUT_MS = 60L * 60L * 1000L

        private const val PARKING_CHANNEL_ID = "auto_parking_channel"
        private const val PARKING_NOTIFICATION_ID = 1002
        const val EXTRA_OPEN_SPOT_DETAIL_ID = "extra_open_spot_detail_id"
    }
}
