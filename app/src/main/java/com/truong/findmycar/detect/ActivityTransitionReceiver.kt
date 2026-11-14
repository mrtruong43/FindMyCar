package com.truong.findmycar.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.truong.findmycar.FindMyCarApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val app = context.applicationContext as? FindMyCarApp ?: return
        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                for (event in result.transitionEvents) {
                    app.container.parkingDetector.onActivityTransition(
                        activityType = event.activityType,
                        transitionType = event.transitionType
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
