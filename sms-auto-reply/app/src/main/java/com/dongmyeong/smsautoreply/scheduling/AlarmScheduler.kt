package com.dongmyeong.smsautoreply.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.engine.Schedule
import java.time.ZoneId

/**
 * Wraps [AlarmManager] for two kinds of alarms:
 *  - scenario alarms: "start scenario X at its next scheduled time"
 *  - timeout alarms: "conversation Y at node Z has waited long enough"
 *
 * Each alarm gets a distinct data URI so a single request code is enough and
 * cancel() finds the right PendingIntent.
 */
class AlarmScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** (Re)schedules the next firing of [scenario], or cancels it when there is none. */
    fun scheduleScenario(scenario: Scenario, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val pending = scenarioIntent(scenario.id)
        val next = if (scenario.enabled) Schedule.nextFireTime(scenario.trigger, nowMillis, zone) else null
        if (next == null) {
            alarmManager.cancel(pending)
        } else {
            setAlarm(next, pending)
        }
    }

    fun cancelScenario(scenarioId: String) {
        alarmManager.cancel(scenarioIntent(scenarioId))
    }

    fun scheduleTimeout(conversationId: String, nodeId: String, atMillis: Long) {
        setAlarm(atMillis, timeoutIntent(conversationId, nodeId))
    }

    fun cancelTimeout(conversationId: String) {
        alarmManager.cancel(timeoutIntent(conversationId, null))
    }

    /** Called after boot / app update / time change: rebuild every alarm from persisted state. */
    fun rescheduleAll(state: AppState, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()) {
        state.scenarios.forEach { scheduleScenario(it, nowMillis, zone) }
        state.conversations.forEach { conversation ->
            val at = conversation.timeoutAtMillis ?: return@forEach
            // Fire overdue timeouts right away rather than dropping them.
            scheduleTimeout(conversation.id, conversation.currentNodeId, maxOf(at, nowMillis + 5_000))
        }
    }

    private fun setAlarm(atMillis: Long, pending: PendingIntent) {
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            // Without the exact-alarm permission the system may delay this by several minutes.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    private fun scenarioIntent(scenarioId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SCENARIO_FIRE)
            .setData(Uri.parse("smsautoreply://scenario/$scenarioId"))
            .putExtra(AlarmReceiver.EXTRA_SCENARIO_ID, scenarioId)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private fun timeoutIntent(conversationId: String, nodeId: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_REPLY_TIMEOUT)
            .setData(Uri.parse("smsautoreply://conversation/$conversationId"))
            .putExtra(AlarmReceiver.EXTRA_CONVERSATION_ID, conversationId)
        if (nodeId != null) intent.putExtra(AlarmReceiver.EXTRA_NODE_ID, nodeId)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private companion object {
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
