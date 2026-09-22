package com.dongmyeong.smsautoreply

import com.dongmyeong.smsautoreply.AutoReplyEngine.Decision
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyEngineTest {
    private val rule = Rule(
        id = "r1",
        phoneNumber = "010-1234-5678",
        label = "테스트",
        replyMessage = "지금은 답장이 어려워요.",
        cooldownMinutes = 60,
    )
    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun state(enabled: Boolean = true, lastReplied: Map<String, Long> = emptyMap()) =
        AppState(enabled = enabled, rules = listOf(rule), lastReplied = lastReplied)

    @Test
    fun `replies to a registered sender`() {
        val decision = AutoReplyEngine.decide(state(), "+821012345678", "어디야?", now)
        assertTrue(decision is Decision.Reply)
        assertEquals("01012345678", (decision as Decision.Reply).normalizedSender)
    }

    @Test
    fun `does nothing while master switch is off`() {
        val decision = AutoReplyEngine.decide(state(enabled = false), "01012345678", "어디야?", now)
        assertTrue(decision is Decision.Skip)
    }

    @Test
    fun `ignores unknown senders`() {
        val decision = AutoReplyEngine.decide(state(), "01099998888", "어디야?", now)
        assertTrue(decision is Decision.Skip)
    }

    @Test
    fun `ignores disabled rules`() {
        val s = AppState(enabled = true, rules = listOf(rule.copy(enabled = false)))
        assertTrue(AutoReplyEngine.decide(s, "01012345678", "어디야?", now) is Decision.Skip)
    }

    @Test
    fun `respects cooldown window`() {
        val recent = state(lastReplied = mapOf("01012345678" to now - 10 * minute))
        assertTrue(AutoReplyEngine.decide(recent, "01012345678", "어디야?", now) is Decision.Skip)

        val old = state(lastReplied = mapOf("01012345678" to now - 61 * minute))
        assertTrue(AutoReplyEngine.decide(old, "01012345678", "어디야?", now) is Decision.Reply)
    }

    @Test
    fun `zero cooldown replies every time`() {
        val s = AppState(
            enabled = true,
            rules = listOf(rule.copy(cooldownMinutes = 0)),
            lastReplied = mapOf("01012345678" to now),
        )
        assertTrue(AutoReplyEngine.decide(s, "01012345678", "어디야?", now) is Decision.Reply)
    }

    @Test
    fun `does not answer its own reply text to avoid loops`() {
        val decision = AutoReplyEngine.decide(state(), "01012345678", " 지금은 답장이 어려워요. ", now)
        assertTrue(decision is Decision.Skip)
    }
}
