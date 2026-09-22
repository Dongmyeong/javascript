package com.dongmyeong.smsautoreply.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongmyeong.smsautoreply.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarms don't survive a reboot or an app update, so rebuild them from persisted state. */
class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val state = SettingsRepository(context.applicationContext).current()
                AlarmScheduler(context.applicationContext).rescheduleAll(state, System.currentTimeMillis())
                Log.i(TAG, "rescheduled ${state.scenarios.size} scenarios after ${intent.action}")
            } catch (t: Throwable) {
                Log.e(TAG, "reschedule failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsAutoReply"
    }
}
