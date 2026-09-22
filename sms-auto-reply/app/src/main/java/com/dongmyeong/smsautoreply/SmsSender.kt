package com.dongmyeong.smsautoreply

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** Thin wrapper over [SmsManager] that handles dual-SIM and long (multipart) messages. */
object SmsSender {

    /**
     * @param subscriptionId SIM the incoming message arrived on, or
     *   [SubscriptionManager.INVALID_SUBSCRIPTION_ID] to use the default SMS SIM.
     */
    fun send(context: Context, destination: String, message: String, subscriptionId: Int) {
        val manager = resolveManager(context, subscriptionId)

        val parts = manager.divideMessage(message)
        if (parts.size <= 1) {
            manager.sendTextMessage(destination, null, message, null, null)
        } else {
            manager.sendMultipartTextMessage(destination, null, parts, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveManager(context: Context, subscriptionId: Int): SmsManager {
        val useSpecificSim = subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (useSpecificSim) base.createForSubscriptionId(subscriptionId) else base
        } else {
            // API 29/30: SmsManager is not yet a Context system service.
            if (useSpecificSim) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }
    }
}
