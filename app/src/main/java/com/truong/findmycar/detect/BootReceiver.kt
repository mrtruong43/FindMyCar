package com.truong.findmycar.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.truong.findmycar.FindMyCarApp
import com.truong.findmycar.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-registers Activity Transition updates after device reboot when autoDetectEnabled is true.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as? FindMyCarApp ?: return
        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                val autoDetect = app.container.settingsStore.settings.first().autoDetectEnabled
                if (autoDetect && Permissions.hasActivityRecognition(context)) {
                    ActivityTransitionManager.register(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
