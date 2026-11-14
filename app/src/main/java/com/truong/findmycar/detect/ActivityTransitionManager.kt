package com.truong.findmycar.detect

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.truong.findmycar.util.Permissions

object ActivityTransitionManager {

    private const val REQUEST_CODE = 2001

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (!Permissions.hasActivityRecognition(context)) return

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(request, buildPendingIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
