package com.truong.findmycar.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.truong.findmycar.FindMyCarApp
import com.truong.findmycar.MainActivity
import com.truong.findmycar.R
import com.truong.findmycar.data.db.TrackPointEntity
import com.truong.findmycar.util.GeoMath
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiverRegistered = false
    private var startTimeMs = 0L

    private var currentSpotId: Long = -1L
    private var stepLengthM: Float = GeoMath.defaultStepLengthM(1.65f)
    private val pathModel = PathModel()
    private var stepSeq = 0
    private var lastStepTimeMs = 0L
    private var isFinalizing = false

    private val trackPointLock = Any()
    private val pendingTrackPoints = mutableListOf<TrackPointEntity>()

    private var stepTracker: StepTracker? = null
    private var headingAdapter: HeadingSensorAdapter? = null
    private var pressureAdapter: PressureSensorAdapter? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var anchorLocationCallback: LocationCallback? = null
    private var goodFixStartMs: Long? = null
    private var lastGoodLocation: Location? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val batteryPct = (level * 100) / scale
                if (batteryPct < MIN_BATTERY_PERCENT) {
                    finalizeAnchorAndStop(
                        anchorLat = lastGoodLocation?.latitude,
                        anchorLon = lastGoodLocation?.longitude
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_FINALIZE_ANCHOR -> {
                finalizeAnchorAndStop(
                    anchorLat = lastGoodLocation?.latitude,
                    anchorLon = lastGoodLocation?.longitude
                )
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val spotId = intent?.getLongExtra(EXTRA_SPOT_ID, -1L) ?: -1L
                if (!Permissions.hasForegroundLocation(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTracking(spotId)
            }
        }
        return START_STICKY
    }

    private fun startTracking(spotId: Long) {
        if (TrackingStateHolder.isTracking.value && timerJob?.isActive == true) {
            return
        }

        currentSpotId = spotId
        startTimeMs = System.currentTimeMillis()
        lastStepTimeMs = startTimeMs
        stepSeq = 0
        isFinalizing = false
        goodFixStartMs = null
        lastGoodLocation = null
        pathModel.reset()
        synchronized(trackPointLock) { pendingTrackPoints.clear() }
        TrackingStateHolder.setStarted(spotId)

        try {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(0L),
                foregroundType
            )
        } catch (_: SecurityException) {
            stopTrackingAndSelf()
            return
        }

        acquireWakeLock()
        registerBatteryMonitor()
        initializeSensorsAndConfig(spotId)
        startOutdoorAnchorMonitor()
        startElapsedTimer()
    }

    private fun initializeSensorsAndConfig(spotId: Long) {
        val app = applicationContext as? FindMyCarApp ?: return
        serviceScope.launch(Dispatchers.IO) {
            val settings = app.container.settingsStore.settings.first()
            stepLengthM = GeoMath.defaultStepLengthM(settings.userHeightM)
            val spot = if (spotId > 0L) app.container.repository.getSpotById(spotId)
            else app.container.repository.getActiveSpotOnce()

            if (spot != null && currentSpotId <= 0L) {
                currentSpotId = spot.id
            }

            // Insert origin (0, 0) point
            if (currentSpotId > 0L) {
                synchronized(trackPointLock) {
                    pendingTrackPoints.add(
                        TrackPointEntity(
                            spotId = currentSpotId,
                            seq = 0,
                            x = 0f,
                            y = 0f,
                            relHeightM = 0f,
                            t = startTimeMs
                        )
                    )
                }
            }

            val baselinePressure = spot?.pressureHpa
            serviceScope.launch(Dispatchers.Main.immediate) {
                headingAdapter = HeadingSensorAdapter(this@TrackingService) { snapshot ->
                    TrackingStateHolder.updateTelemetry {
                        it.copy(
                            headingDeg = snapshot.headingDeg,
                            headingMode = snapshot.mode,
                            pcaRatio = snapshot.pcaRatio
                        )
                    }
                }.also { it.start() }

                if (baselinePressure != null) {
                    pressureAdapter = PressureSensorAdapter(
                        context = this@TrackingService,
                        baselinePressureHpa = baselinePressure,
                        floorHeightM = settings.floorHeightM
                    ) { reading ->
                        TrackingStateHolder.updateTelemetry {
                            it.copy(
                                pressureHpa = reading.filteredPressureHpa,
                                relHeightM = reading.relHeightM,
                                floorDelta = reading.floorOffset
                            )
                        }
                    }.also { it.start() }
                }

                stepTracker = StepTracker(this@TrackingService) { stepTimeMs ->
                    onStepDetected(stepTimeMs)
                }.also { it.start() }
            }
        }
    }

    private fun onStepDetected(stepTimeMs: Long) {
        lastStepTimeMs = stepTimeMs
        headingAdapter?.setLowPowerMode(false)

        val headingDeg = headingAdapter?.currentSnapshot?.headingDeg ?: 0f
        val relHeightM = pressureAdapter?.latestReading?.relHeightM ?: 0f
        val point = pathModel.addStep(
            stepLengthM = stepLengthM,
            headingDeg = headingDeg,
            relHeightM = relHeightM,
            tMs = stepTimeMs
        )
        stepSeq += 1

        TrackingStateHolder.updateTelemetry {
            val updated = it.copy(
                stepCount = stepSeq,
                x = point.x,
                y = point.y
            )
            TrackingStateHolder.recordStepCsv(updated)
            updated
        }

        if (currentSpotId > 0L) {
            var shouldFlush = false
            synchronized(trackPointLock) {
                pendingTrackPoints.add(
                    TrackPointEntity(
                        spotId = currentSpotId,
                        seq = stepSeq,
                        x = point.x,
                        y = point.y,
                        relHeightM = relHeightM,
                        t = stepTimeMs
                    )
                )
                if (pendingTrackPoints.size >= BATCH_POINT_THRESHOLD) {
                    shouldFlush = true
                }
            }
            if (shouldFlush) {
                flushTrackPointsAsync()
            }
        }
    }

    private fun flushTrackPointsAsync() {
        val batch: List<TrackPointEntity>
        synchronized(trackPointLock) {
            if (pendingTrackPoints.isEmpty()) return
            batch = pendingTrackPoints.toList()
            pendingTrackPoints.clear()
        }
        val app = applicationContext as? FindMyCarApp ?: return
        serviceScope.launch(Dispatchers.IO) {
            app.container.repository.insertTrackPoints(batch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOutdoorAnchorMonitor() {
        if (!Permissions.hasForegroundLocation(this)) return
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            ANCHOR_GPS_INTERVAL_MS
        ).setMinUpdateIntervalMillis(ANCHOR_GPS_MIN_INTERVAL_MS).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.hasAccuracy() && loc.accuracy < ANCHOR_ACCURACY_THRESHOLD_M) {
                    lastGoodLocation = loc
                    val now = System.currentTimeMillis()
                    val started = goodFixStartMs
                    if (started == null) {
                        goodFixStartMs = now
                    } else if (now - started >= ANCHOR_GOOD_FIX_DURATION_MS) {
                        finalizeAnchorAndStop(loc.latitude, loc.longitude)
                    }
                } else {
                    goodFixStartMs = null
                }
            }
        }
        anchorLocationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopOutdoorAnchorMonitor() {
        val callback = anchorLocationCallback ?: return
        anchorLocationCallback = null
        fusedLocationClient.removeLocationUpdates(callback)
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsedSec = ((now - startTimeMs) / 1000L).coerceAtLeast(0L)
                TrackingStateHolder.updateElapsedSeconds(elapsedSec)

                if (now - lastStepTimeMs >= IDLE_THROTTLE_MS) {
                    headingAdapter?.setLowPowerMode(true)
                }

                if (elapsedSec > 0L && elapsedSec % BATCH_FLUSH_INTERVAL_SEC == 0L) {
                    flushTrackPointsAsync()
                }

                if (elapsedSec >= MAX_TRACKING_SECONDS) {
                    finalizeAnchorAndStop(
                        anchorLat = lastGoodLocation?.latitude,
                        anchorLon = lastGoodLocation?.longitude
                    )
                    break
                }

                if (elapsedSec % NOTIFICATION_UPDATE_INTERVAL_SEC == 0L) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(elapsedSec))
                }

                delay(1_000L)
            }
        }
    }

    private fun finalizeAnchorAndStop(anchorLat: Double?, anchorLon: Double?) {
        if (isFinalizing) return
        isFinalizing = true
        timerJob?.cancel()
        stopSensorsAndLocation()

        val app = applicationContext as? FindMyCarApp
        val spotId = currentSpotId
        val remainingBatch: List<TrackPointEntity>
        synchronized(trackPointLock) {
            remainingBatch = pendingTrackPoints.toList()
            pendingTrackPoints.clear()
        }
        val anchorPressure = pressureAdapter?.latestReading?.filteredPressureHpa
        val anchorRelHeight = pressureAdapter?.latestReading?.relHeightM

        if (app != null && spotId > 0L) {
            serviceScope.launch(Dispatchers.IO) {
                app.container.repository.insertTrackPoints(remainingBatch)
                app.container.repository.finalizeAnchor(
                    spotId = spotId,
                    anchorLat = anchorLat,
                    anchorLon = anchorLon,
                    anchorPressureHpa = anchorPressure,
                    anchorRelHeightM = anchorRelHeight
                )
                serviceScope.launch(Dispatchers.Main.immediate) {
                    stopTrackingAndSelf()
                }
            }
        } else {
            stopTrackingAndSelf()
        }
    }

    private fun stopSensorsAndLocation() {
        stepTracker?.stop()
        stepTracker = null
        headingAdapter?.stop()
        headingAdapter = null
        pressureAdapter?.stop()
        pressureAdapter = null
        stopOutdoorAnchorMonitor()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: RuntimeException) {
            // Ignore if already released by timeout.
        }
    }

    private fun registerBatteryMonitor() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryReceiverRegistered = true
    }

    private fun unregisterBatteryMonitor() {
        if (!batteryReceiverRegistered) return
        runCatching { unregisterReceiver(batteryReceiver) }
        batteryReceiverRegistered = false
    }

    private fun stopTrackingAndSelf() {
        timerJob?.cancel()
        stopSensorsAndLocation()
        TrackingStateHolder.setStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        stopSensorsAndLocation()
        flushTrackPointsAsync()
        serviceScope.cancel()
        unregisterBatteryMonitor()
        releaseWakeLock()
        TrackingStateHolder.setStopped()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tracking_notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(elapsedSec: Long): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedTime = formatDurationMmSs(elapsedSec)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.tracking_notif_title))
            .setContentText(getString(R.string.tracking_notif_text, formattedTime))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                getString(R.string.tracking_notif_stop),
                stopPendingIntent
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "FindMyCar:TrackingWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 35L * 60L * 1000L
        private const val MAX_TRACKING_SECONDS = 30L * 60L
        private const val MIN_BATTERY_PERCENT = 10
        private const val NOTIFICATION_UPDATE_INTERVAL_SEC = 5L

        private const val BATCH_POINT_THRESHOLD = 10
        private const val BATCH_FLUSH_INTERVAL_SEC = 5L
        private const val IDLE_THROTTLE_MS = 10_000L

        private const val ANCHOR_GPS_INTERVAL_MS = 3_000L
        private const val ANCHOR_GPS_MIN_INTERVAL_MS = 2_000L
        private const val ANCHOR_ACCURACY_THRESHOLD_M = 20f
        private const val ANCHOR_GOOD_FIX_DURATION_MS = 20_000L

        const val ACTION_START = "com.truong.findmycar.action.START_TRACKING"
        const val ACTION_STOP = "com.truong.findmycar.action.STOP_TRACKING"
        const val ACTION_FINALIZE_ANCHOR = "com.truong.findmycar.action.FINALIZE_ANCHOR"
        const val EXTRA_SPOT_ID = "extra_spot_id"

        fun formatDurationMmSs(totalSeconds: Long): String {
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        fun start(context: Context, spotId: Long) {
            if (!Permissions.hasForegroundLocation(context)) return
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SPOT_ID, spotId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun finalizeAnchor(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_FINALIZE_ANCHOR
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
