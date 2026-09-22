package com.dongmyeong.smsautoreply

import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.Rule

/**
 * Pure decision logic, kept free of Android classes so it can be unit-tested.
 */
object AutoReplyEngine {

    sealed interface Decision {
        data class Reply(val rule: Rule, val normalizedSender: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    fun decide(
        state: AppState,
        sender: String?,
        body: String,
        nowMillis: Long,
    ): Decision {
        if (!state.enabled) return Decision.Skip("자동응답 꺼짐")

        val normalizedSender = PhoneNumbers.normalize(sender)
            ?: return Decision.Skip("전화번호가 아닌 발신자")

        val rule = state.rules.firstOrNull { it.enabled && PhoneNumbers.matches(it.phoneNumber, sender) }
            ?: return Decision.Skip("등록되지 않은 번호")

        // If the other side is also auto-replying with our own text, don't ping-pong forever.
        if (body.trim() == rule.replyMessage.trim()) {
            return Decision.Skip("내 자동응답 문구와 동일한 수신 (루프 방지)")
        }

        val last = state.lastReplied[normalizedSender]
        if (last != null) {
            val elapsedMinutes = (nowMillis - last) / 60_000
            if (elapsedMinutes < rule.cooldownMinutes) {
                val remaining = rule.cooldownMinutes - elapsedMinutes
                return Decision.Skip("쿨다운 중 (${remaining}분 남음)")
            }
        }

        return Decision.Reply(rule, normalizedSender)
    }
}
