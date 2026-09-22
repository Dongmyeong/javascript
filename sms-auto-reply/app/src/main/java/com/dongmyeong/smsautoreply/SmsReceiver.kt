package com.dongmyeong.smsautoreply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.ReplyLog
import com.dongmyeong.smsautoreply.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives every incoming SMS, asks [AutoReplyEngine] what to do, and sends the reply.
 *
 * Registered in the manifest, so it runs even when the app has not been opened
 * since the last reboot. Work happens off the main thread via [goAsync].
 */
class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.originatingAddress
        // A long SMS arrives as several PDUs in one broadcast; join them back into one body.
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val subscriptionId = intent.getIntExtra(
            EXTRA_SUBSCRIPTION,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )

        val pendingResult = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, sender, body, subscriptionId)
            } catch (t: Throwable) {
                Log.e(TAG, "auto-reply failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, sender: String?, body: String, subscriptionId: Int) {
        val repo = SettingsRepository(context)
        val state = repo.current()
        val now = System.currentTimeMillis()

        when (val decision = AutoReplyEngine.decide(state, sender, body, now)) {
            is AutoReplyEngine.Decision.Skip -> {
                // Only log skips for numbers we actually care about; otherwise the log fills with spam.
                val isKnown = state.rules.any { PhoneNumbers.matches(it.phoneNumber, sender) }
                if (isKnown) {
                    repo.update { it.appendLog(now, sender, body, "건너뜀: ${decision.reason}") }
                }
                Log.d(TAG, "skip from=$sender reason=${decision.reason}")
            }

            is AutoReplyEngine.Decision.Reply -> {
                val destination = sender ?: return
                val result = try {
                    SmsSender.send(context, destination, decision.rule.replyMessage, subscriptionId)
                    "응답 전송"
                } catch (t: Throwable) {
                    Log.e(TAG, "send failed", t)
                    "전송 실패: ${t.javaClass.simpleName}"
                }
                repo.update {
                    it.copy(lastReplied = it.lastReplied + (decision.normalizedSender to now))
                        .appendLog(now, sender, body, result)
                }
                Log.i(TAG, "replied to=$sender result=$result")
            }
        }
    }

    private fun AppState.appendLog(now: Long, sender: String?, body: String, result: String): AppState {
        val entry = ReplyLog(
            timestampMillis = now,
            sender = sender ?: "(알 수 없음)",
            incomingPreview = body.take(60),
            result = result,
        )
        return copy(log = (listOf(entry) + log).take(AppState.MAX_LOG_ENTRIES))
    }

    private companion object {
        const val TAG = "SmsAutoReply"
        /** Undocumented but long-standing extra carrying the receiving SIM's subscription id. */
        const val EXTRA_SUBSCRIPTION = "subscription"
    }
}
