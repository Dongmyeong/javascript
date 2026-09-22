package com.dongmyeong.smsautoreply.scheduling

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import com.dongmyeong.smsautoreply.SmsSender
import com.dongmyeong.smsautoreply.data.LogEntry
import com.dongmyeong.smsautoreply.data.SettingsRepository
import com.dongmyeong.smsautoreply.engine.ConversationEngine

/** Performs the side effects an engine [ConversationEngine.Result] asks for. */
class ActionExecutor(private val context: Context, private val repository: SettingsRepository) {
    private val scheduler = AlarmScheduler(context)

    suspend fun execute(
        actions: List<ConversationEngine.Action>,
        subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ) {
        for (action in actions) {
            when (action) {
                is ConversationEngine.Action.SendSms -> send(action, subscriptionId)
                is ConversationEngine.Action.ScheduleTimeout ->
                    scheduler.scheduleTimeout(action.conversationId, action.nodeId, action.atMillis)
                is ConversationEngine.Action.CancelTimeout -> scheduler.cancelTimeout(action.conversationId)
            }
        }
    }

    private suspend fun send(action: ConversationEngine.Action.SendSms, subscriptionId: Int) {
        try {
            SmsSender.send(context, action.to, action.text, subscriptionId)
        } catch (t: Throwable) {
            Log.e(TAG, "SMS send failed", t)
            repository.update {
                it.withLog(
                    LogEntry(
                        timestampMillis = System.currentTimeMillis(),
                        contact = action.contactLabel,
                        text = "전송 실패: ${t.javaClass.simpleName} ${t.message ?: ""}".trim(),
                        kind = "실패",
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "SmsAutoReply"
    }
}
