package com.dongmyeong.smsautoreply.data

import kotlinx.serialization.Serializable

@Serializable
enum class TriggerType {
    /** Start the conversation at a scheduled time by sending the start node's message. */
    SCHEDULED,
    /** Start the conversation when the contact texts us. */
    ON_INCOMING,
}

@Serializable
enum class RepeatMode { NONE, DAILY, WEEKLY }

@Serializable
data class Trigger(
    val type: TriggerType = TriggerType.ON_INCOMING,
    /** SCHEDULED + NONE: absolute epoch millis of the one-shot send. */
    val atMillis: Long = 0L,
    /** SCHEDULED + DAILY/WEEKLY: minute of day (0..1439) at which to fire. */
    val minuteOfDay: Int = 9 * 60,
    val repeat: RepeatMode = RepeatMode.NONE,
    /** WEEKLY: java.time.DayOfWeek values, 1 = Monday .. 7 = Sunday. */
    val daysOfWeek: Set<Int> = emptySet(),
    /** ON_INCOMING: optional active window as minutes of day. Null = always active. */
    val activeFromMinute: Int? = null,
    val activeToMinute: Int? = null,
)

@Serializable
enum class NoMatchAction {
    /** Keep waiting at the current node. */
    WAIT,
    /** End the conversation. */
    END,
    /** Jump to [Node.noMatchNextNodeId]. */
    GOTO,
}

/** "If the reply contains any of [keywords], go to [nextNodeId]" (null = end conversation). */
@Serializable
data class Transition(
    val keywords: List<String> = emptyList(),
    val nextNodeId: String? = null,
) {
    companion object {
        /** A keyword that matches any reply. */
        const val ANY = "*"
    }
}

@Serializable
data class Node(
    val id: String,
    val title: String = "",
    /** Sent when the node is entered. Blank = listen only, send nothing. */
    val message: String = "",
    val transitions: List<Transition> = emptyList(),
    val noMatchAction: NoMatchAction = NoMatchAction.WAIT,
    val noMatchNextNodeId: String? = null,
    /**
     * Minutes to wait for a reply before moving to [timeoutNextNodeId] (null there = end).
     * 0 = move on immediately after sending (lets you chain several messages).
     * Null = wait indefinitely (until the conversation expires).
     */
    val replyTimeoutMinutes: Int? = null,
    val timeoutNextNodeId: String? = null,
)

@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val contactNumber: String,
    val contactLabel: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger(),
    val nodes: List<Node> = emptyList(),
    /** Defaults to the first node when null. */
    val startNodeId: String? = null,
    /** A conversation with no activity for this long is dropped. */
    val expiresAfterMinutes: Int = 12 * 60,
    /** Hard cap on messages we send in one conversation, so two bots can never loop forever. */
    val maxAutoMessages: Int = 20,
    /** ON_INCOMING: after a conversation ends, don't start a new one with this contact for this long. */
    val cooldownMinutes: Int = 30,
) {
    val startNode: Node? get() = nodes.firstOrNull { it.id == startNodeId } ?: nodes.firstOrNull()
    fun node(id: String?): Node? = nodes.firstOrNull { it.id == id }
}

/** A running instance of a [Scenario] with one contact. */
@Serializable
data class Conversation(
    val id: String,
    val scenarioId: String,
    /** Normalised phone number, see PhoneNumbers.normalize. */
    val contactKey: String,
    /** Raw address to send to (as received or as configured). */
    val contactAddress: String,
    val currentNodeId: String,
    val startedAtMillis: Long,
    val lastActivityMillis: Long,
    val sentCount: Int = 0,
    val lastSentText: String = "",
    val timeoutAtMillis: Long? = null,
)

@Serializable
data class LogEntry(
    val timestampMillis: Long,
    val contact: String,
    val text: String,
    /** e.g. 보냄 / 받음 / 시작 / 종료 / 건너뜀 / 실패 */
    val kind: String,
)

@Serializable
data class AppState(
    /** Master switch. Nothing is sent while this is off. */
    val enabled: Boolean = false,
    val scenarios: List<Scenario> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    /** contactKey -> epoch millis when the last conversation with them ended. */
    val lastEndedByContact: Map<String, Long> = emptyMap(),
    val log: List<LogEntry> = emptyList(),
) {
    fun conversationFor(contactKey: String): Conversation? = conversations.firstOrNull { it.contactKey == contactKey }

    fun withLog(entry: LogEntry): AppState = copy(log = (listOf(entry) + log).take(MAX_LOG_ENTRIES))

    companion object {
        const val MAX_LOG_ENTRIES = 200
    }
}
