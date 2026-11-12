package com.edvis.core.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Tactical Voice Alarm Receiver: Wakes up the device and triggers
 * the natural female voice morning greeting (JARVIS style).
 */
class EdvisVoiceAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EdvisVoiceAlarm"
        const val EXTRA_ALARM_HOUR = "extra_alarm_hour"
        const val EXTRA_ALARM_MINUTE = "extra_alarm_minute"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Voice alarm triggered!")
        val hour = intent?.getIntExtra(EXTRA_ALARM_HOUR, 5) ?: 5
        val minute = intent?.getIntExtra(EXTRA_ALARM_MINUTE, 0) ?: 0

        // Acquire full wake lock to turn on the screen
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "EDVIS::MorningAlarmWakeLock"
        )
        wakeLock.acquire(45_000L)

        // Delegate morning wakeup briefing to EdvisForegroundService
        EdvisForegroundService.instance?.triggerJarvisMorningWakeup(hour, minute) ?: run {
            // If service was killed, restart it
            val serviceIntent = Intent(context, EdvisForegroundService::class.java).apply {
                action = "ACTION_MORNING_WAKEUP"
                putExtra(EXTRA_ALARM_HOUR, hour)
                putExtra(EXTRA_ALARM_MINUTE, minute)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
