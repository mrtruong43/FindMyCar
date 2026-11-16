package com.truong.findmycar.ui.find

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.truong.findmycar.data.ParkingRepository
import com.truong.findmycar.data.SettingsStore
import com.truong.findmycar.data.VehicleHologramType
import com.truong.findmycar.data.db.ParkingSpotEntity
import com.truong.findmycar.guidance.GuidanceEngine
import com.truong.findmycar.guidance.GuidancePhase
import com.truong.findmycar.guidance.GuidanceState
import com.truong.findmycar.guidance.GuidanceTargetInfo
import com.truong.findmycar.tracking.HeadingSensorAdapter
import com.truong.findmycar.tracking.OrientationFusion
import com.truong.findmycar.tracking.PathPoint
import com.truong.findmycar.tracking.TrackingService
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

data class FindCarUiState(
    val spot: ParkingSpotEntity? = null,
    val guidance: GuidanceState = GuidanceState(phase = GuidancePhase.NO_SIGNAL_FALLBACK),
    val userHeadingDeg: Float = 0f,
    val vehicleType: VehicleHologramType = VehicleHologramType.SCOOTER,
    val hapticEnabled: Boolean = true,
    val isLoading: Boolean = true
)

class FindCarViewModel(
    private val spotId: Long,
    private val appContext: Context,
    private val repository: ParkingRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(FindCarUiState())
    val uiState: StateFlow<FindCarUiState> = _uiState.asStateFlow()

    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var engine: GuidanceEngine? = null
    private var stepLengthM: Float = GeoMath.defaultStepLengthM(1.65f)
    private var hapticEnabled: Boolean = true
    private var lastPressureHpa: Float? = null
    private var gpsCallback: LocationCallback? = null
    private var hapticJob: Job? = null

    private var wasAligned = false
    private var wasVeryClose = false

    private val rotationMatrix = FloatArray(9)
    private val gameRotationMatrix = FloatArray(9)
    private val orientationFusion = OrientationFusion()
    private var hasGameRotationSensor = false
    private var latestMagAzimuthDeg: Float? = null
    private var hasMagneticInterference = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val g = engine ?: return
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(gameRotationMatrix, event.values)
                    val (gameAzimuthDeg, _) = HeadingSensorAdapter.computeMultiPostureAzimuth(gameRotationMatrix)
                    val fusedAzimuthDeg = orientationFusion.update(
                        gameAzimuthDeg = gameAzimuthDeg,
                        magAzimuthDeg = latestMagAzimuthDeg,
                        hasMagneticInterference = hasMagneticInterference,
                        timestampMs = System.currentTimeMillis()
                    )
                    _uiState.value = _uiState.value.copy(userHeadingDeg = fusedAzimuthDeg)
                    updateGuidanceState(g.onAzimuthUpdate(fusedAzimuthDeg))
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val (magAzimuthDeg, _) = HeadingSensorAdapter.computeMultiPostureAzimuth(rotationMatrix)
                    latestMagAzimuthDeg = magAzimuthDeg
                    if (!hasGameRotationSensor) {
                        _uiState.value = _uiState.value.copy(userHeadingDeg = magAzimuthDeg)
                        updateGuidanceState(g.onAzimuthUpdate(magAzimuthDeg))
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (event.values.size >= 3) {
                        val bx = event.values[0]
                        val by = event.values[1]
                        val bz = event.values[2]
                        val magUt = sqrt(bx * bx + by * by + bz * bz)
                        val isSpike = magUt < HeadingSensorAdapter.MIN_NORMAL_MAG_UT ||
                            magUt > HeadingSensorAdapter.MAX_NORMAL_MAG_UT
                        if (isSpike && !hasMagneticInterference) {
                            updateGuidanceState(g.onMagneticSpike())
                        }
                        hasMagneticInterference = isSpike
                    }
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    updateGuidanceState(g.onStep(stepLengthM))
                }
                Sensor.TYPE_PRESSURE -> {
                    if (event.values.isNotEmpty()) {
                        lastPressureHpa = event.values[0]
                        updateGuidanceState(
                            g.onPressureUpdate(event.values[0], System.currentTimeMillis())
                        )
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val lowAcc = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                engine?.let { updateGuidanceState(it.onCompassAccuracyChanged(lowAcc)) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val spot = repository.getSpotById(spotId)
            val trackPoints = repository.getTrackPoints(spotId).map {
                PathPoint(x = it.x, y = it.y, relHeightM = it.relHeightM, tMs = it.t)
            }
            val settings = settingsStore.settings.first()
            stepLengthM = settings.effectiveStepLengthM()
            hapticEnabled = settings.hapticFeedbackEnabled

            val hasBarometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
            val targetInfo = GuidanceTargetInfo(
                carLat = spot?.lat,
                carLon = spot?.lon,
                anchorLat = spot?.anchorLat,
                anchorLon = spot?.anchorLon,
                anchorRelHeightM = spot?.anchorRelHeightM,
                footprint = trackPoints,
                floorHeightM = settings.floorHeightM,
                hasBarometer = hasBarometer,
                carAccuracyM = spot?.accuracyM,
                anchorSet = spot?.anchorSet ?: false,
                environmentMode = com.truong.findmycar.detect.ParkingEnvironmentResolver.fromStorageName(
                    spot?.environmentMode
                )
            )
            val createdEngine = GuidanceEngine(targetInfo)
            engine = createdEngine

            _uiState.value = FindCarUiState(
                spot = spot,
                guidance = createdEngine.currentState(),
                vehicleType = settings.vehicleType,
                hapticEnabled = settings.hapticFeedbackEnabled,
                isLoading = false
            )

            registerSensors()
            val phase = createdEngine.currentState().phase
            if (phase == GuidancePhase.PHASE1_GPS_TO_ANCHOR || phase == GuidancePhase.PHASE1_GPS_TO_CAR) {
                startGpsUpdates()
            }
            startHapticLoop()
        }
    }

    fun enterPhase2Manually() {
        val g = engine ?: return
        stopGpsUpdates()
        updateGuidanceState(g.enterPhase2(lastPressureHpa))
    }

    fun markCarFound(onDone: () -> Unit) {
        viewModelScope.launch {
            stopAllSensorsAndGps()
            TrackingService.stop(appContext)
            repository.markAsFound(spotId)
            onDone()
        }
    }

    private fun updateGuidanceState(next: GuidanceState) {
        if (next.phase == GuidancePhase.PHASE2_FOOTPRINT || next.phase == GuidancePhase.VERY_CLOSE) {
            stopGpsUpdates()
        }

        val isNowAligned = !next.isCompassLowAccuracy &&
            next.phase != GuidancePhase.VERY_CLOSE &&
            next.phase != GuidancePhase.NO_SIGNAL_FALLBACK &&
            abs(next.relativeArrowDeg) < 15f
        if (isNowAligned && !wasAligned) {
            vibrateOneShot(40L, 180)
        }
        wasAligned = isNowAligned

        val isNowVeryClose = next.phase == GuidancePhase.VERY_CLOSE
        if (isNowVeryClose && !wasVeryClose) {
            vibrateOneShot(400L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        wasVeryClose = isNowVeryClose

        _uiState.value = _uiState.value.copy(guidance = next)
    }

    private fun registerSensors() {
        val gameSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        hasGameRotationSensor = gameSensor != null
        gameSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (!Permissions.hasForegroundLocation(appContext)) return
        if (gpsCallback != null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val g = engine ?: return
                val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
                updateGuidanceState(g.onGpsUpdate(loc.latitude, loc.longitude, acc, lastPressureHpa))
            }
        }
        gpsCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopGpsUpdates() {
        val cb = gpsCallback ?: return
        gpsCallback = null
        fusedLocationClient.removeLocationUpdates(cb)
    }

    private fun startHapticLoop() {
        hapticJob?.cancel()
        hapticJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value.guidance
                val interval = GuidanceEngine.hapticIntervalMsForDistance(state.distanceMeters)
                delay(interval)
                if (hapticEnabled &&
                    state.phase != GuidancePhase.NO_SIGNAL_FALLBACK &&
                    state.phase != GuidancePhase.VERY_CLOSE &&
                    state.distanceMeters != null
                ) {
                    vibrateOneShot(25L, 110)
                }
            }
        }
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        if (!hapticEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }

    private fun stopAllSensorsAndGps() {
        hapticJob?.cancel()
        stopGpsUpdates()
        sensorManager.unregisterListener(sensorListener)
    }

    override fun onCleared() {
        stopAllSensorsAndGps()
        super.onCleared()
    }

    class Factory(
        private val spotId: Long,
        private val context: Context,
        private val repository: ParkingRepository,
        private val settingsStore: SettingsStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FindCarViewModel(spotId, context.applicationContext, repository, settingsStore) as T
        }
    }
}
