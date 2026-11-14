package com.truong.findmycar.tracking

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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.truong.findmycar.MainActivity
import com.truong.findmycar.R
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiverRegistered = false
    private var startTimeMs = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val batteryPct = (level * 100) / scale
                if (batteryPct < MIN_BATTERY_PERCENT) {
                    stopTrackingAndSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingAndSelf()
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
            // Already tracking; update spotId if needed.
            return
        }

        startTimeMs = System.currentTimeMillis()
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
        startElapsedTimer()
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(0L)
                TrackingStateHolder.updateElapsedSeconds(elapsedSec)

                if (elapsedSec >= MAX_TRACKING_SECONDS) {
                    stopTrackingAndSelf()
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
        TrackingStateHolder.setStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        timerJob?.cancel()
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

        const val ACTION_START = "com.truong.findmycar.action.START_TRACKING"
        const val ACTION_STOP = "com.truong.findmycar.action.STOP_TRACKING"
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

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
