package com.dongmyeong.smsautoreply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.dongmyeong.smsautoreply.data.SettingsRepository
import com.dongmyeong.smsautoreply.engine.ConversationEngine
import com.dongmyeong.smsautoreply.scheduling.ActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives every incoming SMS and feeds it to the [ConversationEngine].
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
                Log.e(TAG, "handling incoming SMS failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, sender: String?, body: String, subscriptionId: Int) {
        val repository = SettingsRepository(context)
        val engine = ConversationEngine()
        val now = System.currentTimeMillis()

        val actions = repository.transact { state ->
            val result = engine.onIncoming(state, sender, body, now)
            result.state to result.actions
        }
        ActionExecutor(context, repository).execute(actions, subscriptionId)
        Log.d(TAG, "incoming from=$sender actions=${actions.size}")
    }

    private companion object {
        const val TAG = "SmsAutoReply"
        /** Undocumented but long-standing extra carrying the receiving SIM's subscription id. */
        const val EXTRA_SUBSCRIPTION = "subscription"
    }
}
