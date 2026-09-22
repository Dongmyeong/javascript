package com.dongmyeong.smsautoreply.data

import kotlinx.serialization.Serializable

/** One auto-reply rule: a sender to watch and what to send back. */
@Serializable
data class Rule(
    val id: String,
    /** Number exactly as the user typed it; matching uses [com.dongmyeong.smsautoreply.PhoneNumbers]. */
    val phoneNumber: String,
    /** Friendly name shown in the list (e.g. "엄마"). Optional. */
    val label: String = "",
    val replyMessage: String,
    /** Minimum minutes between two automatic replies to the same sender. */
    val cooldownMinutes: Int = 60,
    val enabled: Boolean = true,
)

/** A record of an automatic reply that was sent (or skipped), newest first in [AppState.log]. */
@Serializable
data class ReplyLog(
    val timestampMillis: Long,
    val sender: String,
    val incomingPreview: String,
    val result: String,
)

@Serializable
data class AppState(
    /** Master switch. Nothing is sent while this is off. */
    val enabled: Boolean = false,
    val rules: List<Rule> = emptyList(),
    /** normalised phone number -> epoch millis of the last auto-reply. */
    val lastReplied: Map<String, Long> = emptyMap(),
    val log: List<ReplyLog> = emptyList(),
) {
    companion object {
        const val MAX_LOG_ENTRIES = 100
    }
}
