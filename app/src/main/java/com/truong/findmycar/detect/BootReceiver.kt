package com.truong.findmycar.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lightweight BOOT_COMPLETED receiver.
 * In Milestone M3, this will re-register Activity Recognition transitions after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // M3: Re-register ActivityTransitionReceiver if autoDetectEnabled is true.
    }
}
