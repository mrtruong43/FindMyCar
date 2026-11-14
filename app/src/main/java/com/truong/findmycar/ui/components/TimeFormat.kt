package com.truong.findmycar.ui.components

import android.content.Context
import com.truong.findmycar.R

fun formatElapsedTime(context: Context, savedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMinutes = ((nowMs - savedAtMs).coerceAtLeast(0L)) / 60_000L
    return when {
        diffMinutes < 1L -> context.getString(R.string.time_just_now)
        diffMinutes < 60L -> context.getString(R.string.time_minutes_ago, diffMinutes.toInt())
        diffMinutes < 24L * 60L -> {
            val hours = (diffMinutes / 60L).toInt()
            val mins = (diffMinutes % 60L).toInt()
            if (mins == 0) {
                context.getString(R.string.time_hours_ago, hours)
            } else {
                context.getString(R.string.time_hours_minutes_ago, hours, mins)
            }
        }
        else -> {
            val days = (diffMinutes / (24L * 60L)).toInt()
            context.getString(R.string.time_days_ago, days)
        }
    }
}
