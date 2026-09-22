package com.dongmyeong.smsautoreply.engine

import com.dongmyeong.smsautoreply.PhoneNumbers
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.Conversation
import com.dongmyeong.smsautoreply.data.LogEntry
import com.dongmyeong.smsautoreply.data.NoMatchAction
import com.dongmyeong.smsautoreply.data.Node
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.data.Transition
import com.dongmyeong.smsautoreply.data.TriggerType
import java.time.ZoneId
import java.util.UUID

/**
 * Pure state machine: takes the current [AppState] plus an event and returns the
 * new state together with the side effects the caller must perform.
 * No Android classes here, so it is unit-testable on the JVM.
 */
class ConversationEngine(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    sealed interface Action {
        data class SendSms(val to: String, val text: String, val contactLabel: String) : Action
        data class ScheduleTimeout(val conversationId: String, val nodeId: String, val atMillis: Long) : Action
        data class CancelTimeout(val conversationId: String) : Action
    }

    data class Result(val state: AppState, val actions: List<Action> = emptyList())

    // ---------------------------------------------------------------- events

    /** An SMS arrived from [sender] with [body]. */
    fun onIncoming(state: AppState, sender: String?, body: String, now: Long): Result {
        val key = PhoneNumbers.normalize(sender) ?: return Result(state)
        val address = sender ?: return Result(state)
        val ctx = Ctx(expireStale(state, now), now)

        val knownContact = ctx.state.scenarios.any { PhoneNumbers.matches(it.contactNumber, sender) }
        if (!knownContact) return ctx.result()

        val label = ctx.state.scenarios.firstOrNull { PhoneNumbers.matches(it.contactNumber, sender) }
            ?.let { it.contactLabel.ifBlank { it.contactNumber } } ?: address
        ctx.log(label, body, "받음")

        if (!ctx.state.enabled) {
            ctx.log(label, "자동응답이 꺼져 있어 무시", "건너뜀")
            return ctx.result()
        }

        val existing = ctx.state.conversationFor(key)
        if (existing != null) {
            val scenario = ctx.state.scenarios.firstOrNull { it.id == existing.scenarioId }
            if (scenario == null) {
                ctx.endConversation(existing, "시나리오가 삭제됨")
                return ctx.result()
            }
            if (body.trim().isNotEmpty() && body.trim() == existing.lastSentText.trim()) {
                ctx.log(label, "내가 보낸 문구와 동일한 답장 (루프 방지)", "건너뜀")
                return ctx.result()
            }
            ctx.handleReply(scenario, existing, body)
            return ctx.result()
        }

        // No running conversation: can an ON_INCOMING scenario start one?
        val scenario = ctx.state.scenarios.firstOrNull {
            it.enabled && it.trigger.type == TriggerType.ON_INCOMING &&
                PhoneNumbers.matches(it.contactNumber, sender) && it.nodes.isNotEmpty()
        }
        if (scenario == null) {
            ctx.log(label, "이 번호에 수신 시 시작되는 시나리오 없음", "건너뜀")
            return ctx.result()
        }
        if (!Schedule.isWithinActiveWindow(scenario.trigger, now, zone)) {
            ctx.log(label, "활성 시간대가 아님", "건너뜀")
            return ctx.result()
        }
        val lastEnded = ctx.state.lastEndedByContact[key]
        if (lastEnded != null && now - lastEnded < scenario.cooldownMinutes * 60_000L) {
            val remaining = scenario.cooldownMinutes - (now - lastEnded) / 60_000
            ctx.log(label, "쿨다운 중 (${remaining}분 남음)", "건너뜀")
            return ctx.result()
        }

        val conversation = ctx.startConversation(scenario, key, address)
        val start = scenario.startNode ?: return ctx.result()
        // A listening start node (blank message) should evaluate the message that woke it up.
        if (start.message.isBlank()) {
            val current = ctx.state.conversationFor(key) ?: return ctx.result()
            ctx.handleReply(scenario, current, body)
        } else {
            // Start node already sent its message inside startConversation; nothing else to do.
            check(conversation.id.isNotEmpty())
        }
        return ctx.result()
    }

    /** A scheduled trigger for [scenarioId] fired. */
    fun onScenarioFired(state: AppState, scenarioId: String, now: Long): Result {
        val ctx = Ctx(expireStale(state, now), now)
        val scenario = ctx.state.scenarios.firstOrNull { it.id == scenarioId } ?: return ctx.result()
        val label = scenario.contactLabel.ifBlank { scenario.contactNumber }
        if (!ctx.state.enabled) {
            ctx.log(label, "예약 시각 도달했지만 자동응답이 꺼져 있음", "건너뜀")
            return ctx.result()
        }
        if (!scenario.enabled || scenario.nodes.isEmpty()) return ctx.result()
        val key = PhoneNumbers.normalize(scenario.contactNumber) ?: return ctx.result()

        ctx.state.conversationFor(key)?.let { ctx.endConversation(it, "새 예약 시나리오 시작으로 종료") }
        ctx.startConversation(scenario, key, scenario.contactNumber)
        return ctx.result()
    }

    /** The reply timeout for [conversationId] at [nodeId] elapsed. */
    fun onTimeout(state: AppState, conversationId: String, nodeId: String, now: Long): Result {
        val ctx = Ctx(state, now)
        val conversation = ctx.state.conversations.firstOrNull { it.id == conversationId } ?: return ctx.result()
        if (conversation.currentNodeId != nodeId) return ctx.result() // stale alarm
        val scenario = ctx.state.scenarios.firstOrNull { it.id == conversation.scenarioId }
        if (scenario == null) {
            ctx.endConversation(conversation, "시나리오가 삭제됨")
            return ctx.result()
        }
        val node = scenario.node(nodeId)
        if (node == null) {
            ctx.endConversation(conversation, "단계가 삭제됨")
            return ctx.result()
        }
        ctx.log(scenario.displayName(), "답장 대기 시간 초과", "시간초과")
        ctx.moveTo(scenario, conversation, node.timeoutNextNodeId, "대기 시간 초과 후 종료")
        return ctx.result()
    }

    /** User manually ended a conversation from the UI. */
    fun endConversation(state: AppState, conversationId: String, now: Long): Result {
        val ctx = Ctx(state, now)
        val conversation = ctx.state.conversations.firstOrNull { it.id == conversationId } ?: return ctx.result()
        ctx.endConversation(conversation, "수동 종료")
        return ctx.result()
    }

    // ---------------------------------------------------------------- internals

    private fun expireStale(state: AppState, now: Long): AppState {
        var next = state
        for (c in state.conversations) {
            val scenario = state.scenarios.firstOrNull { it.id == c.scenarioId }
            val ttl = (scenario?.expiresAfterMinutes ?: 0) * 60_000L
            if (scenario == null || now - c.lastActivityMillis > ttl) {
                next = next.copy(
                    conversations = next.conversations.filterNot { it.id == c.id },
                    lastEndedByContact = next.lastEndedByContact + (c.contactKey to now),
                ).withLog(LogEntry(now, scenario?.displayName() ?: c.contactAddress, "대화가 만료되어 종료", "종료"))
            }
        }
        return next
    }

    private fun Scenario.displayName(): String = contactLabel.ifBlank { contactNumber }

    /** Mutable working context for one event. */
    private inner class Ctx(var state: AppState, val now: Long) {
        val actions = mutableListOf<Action>()

        fun result() = Result(state, actions.toList())

        fun log(contact: String, text: String, kind: String) {
            state = state.withLog(LogEntry(now, contact, text, kind))
        }

        fun startConversation(scenario: Scenario, key: String, address: String): Conversation {
            val start = scenario.startNode ?: error("scenario has no nodes")
            val conversation = Conversation(
                id = idGenerator(),
                scenarioId = scenario.id,
                contactKey = key,
                contactAddress = address,
                currentNodeId = start.id,
                startedAtMillis = now,
                lastActivityMillis = now,
            )
            state = state.copy(conversations = state.conversations + conversation)
            log(scenario.displayName(), "「${scenario.name}」 시작", "시작")
            enterNode(scenario, conversation, start, depth = 0)
            return conversation
        }

        fun handleReply(scenario: Scenario, conversation: Conversation, body: String) {
            val node = scenario.node(conversation.currentNodeId)
            if (node == null) {
                endConversation(conversation, "현재 단계가 삭제됨")
                return
            }
            val touched = conversation.copy(lastActivityMillis = now)
            replace(touched)

            val match = node.transitions.firstOrNull { it.matches(body) }
            when {
                match != null -> moveTo(scenario, touched, match.nextNodeId, "규칙에 따라 종료")
                node.noMatchAction == NoMatchAction.END -> endConversation(touched, "규칙에 맞지 않는 답장으로 종료")
                node.noMatchAction == NoMatchAction.GOTO -> moveTo(scenario, touched, node.noMatchNextNodeId, "기타 답장 후 종료")
                else -> log(scenario.displayName(), "규칙에 맞는 단어 없음, 계속 대기", "대기")
            }
        }

        /** Move [conversation] to [nextNodeId]; a null id ends the conversation. */
        fun moveTo(scenario: Scenario, conversation: Conversation, nextNodeId: String?, endReason: String) {
            if (nextNodeId == null) {
                endConversation(conversation, endReason)
                return
            }
            val next = scenario.node(nextNodeId)
            if (next == null) {
                endConversation(conversation, "다음 단계를 찾을 수 없어 종료")
                return
            }
            enterNode(scenario, conversation.copy(currentNodeId = next.id, lastActivityMillis = now), next, depth = 0)
        }

        fun enterNode(scenario: Scenario, conversation: Conversation, node: Node, depth: Int) {
            var current = conversation.copy(currentNodeId = node.id, lastActivityMillis = now, timeoutAtMillis = null)

            if (node.message.isNotBlank()) {
                if (current.sentCount >= scenario.maxAutoMessages) {
                    replace(current)
                    endConversation(current, "최대 발송 횟수(${scenario.maxAutoMessages}) 도달")
                    return
                }
                val text = Templates.render(node.message, scenario, now, zone)
                actions += Action.SendSms(current.contactAddress, text, scenario.displayName())
                current = current.copy(sentCount = current.sentCount + 1, lastSentText = text)
                log(scenario.displayName(), text, "보냄")
            }

            val timeout = node.replyTimeoutMinutes
            val waitsForReply = node.transitions.isNotEmpty() || node.noMatchAction != NoMatchAction.WAIT

            when {
                // Chain immediately to the next node.
                timeout != null && timeout <= 0 -> {
                    replace(current)
                    if (depth > scenario.nodes.size) {
                        endConversation(current, "단계가 순환하여 종료")
                        return
                    }
                    val next = scenario.node(node.timeoutNextNodeId)
                    if (next == null) {
                        endConversation(current, "마지막 단계 완료")
                    } else {
                        enterNode(scenario, current.copy(currentNodeId = next.id), next, depth + 1)
                    }
                }

                timeout != null -> {
                    val at = now + timeout * 60_000L
                    current = current.copy(timeoutAtMillis = at)
                    replace(current)
                    actions += Action.ScheduleTimeout(current.id, node.id, at)
                }

                waitsForReply -> {
                    replace(current)
                    actions += Action.CancelTimeout(current.id)
                }

                else -> {
                    replace(current)
                    endConversation(current, "마지막 단계 완료")
                }
            }
        }

        fun endConversation(conversation: Conversation, reason: String) {
            val scenario = state.scenarios.firstOrNull { it.id == conversation.scenarioId }
            state = state.copy(
                conversations = state.conversations.filterNot { it.id == conversation.id },
                lastEndedByContact = state.lastEndedByContact + (conversation.contactKey to now),
            )
            actions += Action.CancelTimeout(conversation.id)
            log(scenario?.displayName() ?: conversation.contactAddress, reason, "종료")
        }

        private fun replace(conversation: Conversation) {
            state = state.copy(
                conversations = state.conversations.map { if (it.id == conversation.id) conversation else it },
            )
        }
    }

    private fun Transition.matches(body: String): Boolean {
        val text = body.trim().lowercase()
        return keywords.any { raw ->
            val k = raw.trim().lowercase()
            k.isNotEmpty() && (k == Transition.ANY || text.contains(k))
        }
    }
}
