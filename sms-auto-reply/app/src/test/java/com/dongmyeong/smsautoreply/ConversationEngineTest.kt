package com.dongmyeong.smsautoreply

import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.NoMatchAction
import com.dongmyeong.smsautoreply.data.Node
import com.dongmyeong.smsautoreply.data.RepeatMode
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.data.Transition
import com.dongmyeong.smsautoreply.data.Trigger
import com.dongmyeong.smsautoreply.data.TriggerType
import com.dongmyeong.smsautoreply.engine.ConversationEngine
import com.dongmyeong.smsautoreply.engine.ConversationEngine.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ConversationEngineTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private var counter = 0
    private val engine = ConversationEngine(zone) { "conv-${counter++}" }
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val phone = "010-1234-5678"

    // 예약: "밥 먹었어?" -> (먹었/응) "잘했어" | (아니/안) "얼른 먹어" -> 끝
    private val dinnerScenario = Scenario(
        id = "s1",
        name = "저녁 확인",
        contactNumber = phone,
        contactLabel = "엄마",
        trigger = Trigger(type = TriggerType.SCHEDULED, repeat = RepeatMode.NONE, atMillis = now),
        nodes = listOf(
            Node(
                id = "ask",
                message = "{이름}, 저녁 먹었어?",
                transitions = listOf(
                    Transition(listOf("먹었", "응"), "good"),
                    Transition(listOf("아니", "안"), "eat"),
                ),
                replyTimeoutMinutes = 60,
                timeoutNextNodeId = "nudge",
            ),
            Node(id = "good", message = "잘했어! 푹 쉬어."),
            Node(id = "eat", message = "얼른 챙겨 먹어~"),
            Node(id = "nudge", message = "답이 없네, 바쁜가 보다."),
        ),
    )

    private fun sends(actions: List<Action>) = actions.filterIsInstance<Action.SendSms>().map { it.text }

    @Test
    fun `scheduled scenario sends first message and waits with timeout`() {
        val state = AppState(enabled = true, scenarios = listOf(dinnerScenario))
        val result = engine.onScenarioFired(state, "s1", now)

        assertEquals(listOf("엄마, 저녁 먹었어?"), sends(result.actions))
        val timeout = result.actions.filterIsInstance<Action.ScheduleTimeout>().single()
        assertEquals(now + 60 * minute, timeout.atMillis)
        assertEquals("ask", timeout.nodeId)
        val conv = result.state.conversations.single()
        assertEquals("ask", conv.currentNodeId)
        assertEquals(1, conv.sentCount)
    }

    @Test
    fun `reply matching a keyword follows the branch and ends`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val result = engine.onIncoming(started, "+821012345678", "응 먹었어", now + 5 * minute)

        assertEquals(listOf("잘했어! 푹 쉬어."), sends(result.actions))
        assertTrue(result.state.conversations.isEmpty())
        assertTrue(result.actions.any { it is Action.CancelTimeout })
    }

    @Test
    fun `other branch is chosen for a different reply`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        // Rules are evaluated in order, so a reply must not also contain an earlier keyword.
        val result = engine.onIncoming(started, phone, "아니 아직", now + minute)
        assertEquals(listOf("얼른 챙겨 먹어~"), sends(result.actions))
    }

    @Test
    fun `unmatched reply keeps waiting by default`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val result = engine.onIncoming(started, phone, "지금 운전 중", now + minute)
        assertTrue(sends(result.actions).isEmpty())
        assertEquals("ask", result.state.conversations.single().currentNodeId)
    }

    @Test
    fun `timeout moves to the timeout node`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val convId = started.conversations.single().id
        val result = engine.onTimeout(started, convId, "ask", now + 60 * minute)
        assertEquals(listOf("답이 없네, 바쁜가 보다."), sends(result.actions))
        assertTrue(result.state.conversations.isEmpty())
    }

    @Test
    fun `stale timeout for a previous node is ignored`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val convId = started.conversations.single().id
        val result = engine.onTimeout(started, convId, "some-old-node", now + 60 * minute)
        assertTrue(result.actions.isEmpty())
        assertEquals(1, result.state.conversations.size)
    }

    @Test
    fun `nothing is sent while master switch is off`() {
        val result = engine.onScenarioFired(AppState(enabled = false, scenarios = listOf(dinnerScenario)), "s1", now)
        assertTrue(result.actions.isEmpty())
        assertTrue(result.state.conversations.isEmpty())
    }

    @Test
    fun `incoming scenario with listening start node replies by keyword`() {
        val scenario = Scenario(
            id = "s2",
            name = "키워드 응답",
            contactNumber = phone,
            trigger = Trigger(type = TriggerType.ON_INCOMING),
            nodes = listOf(
                Node(
                    id = "listen",
                    transitions = listOf(
                        Transition(listOf("어디"), "where"),
                        Transition(listOf("언제"), "when"),
                        Transition(listOf(Transition.ANY), "default"),
                    ),
                ),
                Node(id = "where", message = "지금 회사야"),
                Node(id = "when", message = "7시쯤 갈게"),
                Node(id = "default", message = "지금은 답장이 어려워"),
            ),
            cooldownMinutes = 0,
        )
        val state = AppState(enabled = true, scenarios = listOf(scenario))

        assertEquals(listOf("지금 회사야"), sends(engine.onIncoming(state, phone, "어디야?", now).actions))
        assertEquals(listOf("7시쯤 갈게"), sends(engine.onIncoming(state, phone, "언제 와", now).actions))
        assertEquals(listOf("지금은 답장이 어려워"), sends(engine.onIncoming(state, phone, "ㅋㅋ", now).actions))
    }

    @Test
    fun `unknown sender is ignored without logging`() {
        val state = AppState(enabled = true, scenarios = listOf(dinnerScenario))
        val result = engine.onIncoming(state, "01099998888", "안녕", now)
        assertTrue(result.actions.isEmpty())
        assertTrue(result.state.log.isEmpty())
    }

    @Test
    fun `cooldown blocks restarting an incoming scenario`() {
        val scenario = Scenario(
            id = "s3", name = "인사", contactNumber = phone,
            trigger = Trigger(type = TriggerType.ON_INCOMING),
            nodes = listOf(Node(id = "hi", message = "안녕!")),
            cooldownMinutes = 30,
        )
        val state = AppState(enabled = true, scenarios = listOf(scenario))
        val first = engine.onIncoming(state, phone, "야", now)
        assertEquals(listOf("안녕!"), sends(first.actions))
        assertTrue(first.state.conversations.isEmpty()) // single node -> ended right away

        val second = engine.onIncoming(first.state, phone, "야", now + 10 * minute)
        assertTrue(sends(second.actions).isEmpty())

        val third = engine.onIncoming(first.state, phone, "야", now + 31 * minute)
        assertEquals(listOf("안녕!"), sends(third.actions))
    }

    @Test
    fun `active window is respected for incoming scenarios`() {
        val scenario = Scenario(
            id = "s4", name = "야간", contactNumber = phone,
            trigger = Trigger(type = TriggerType.ON_INCOMING, activeFromMinute = 22 * 60, activeToMinute = 6 * 60),
            nodes = listOf(Node(id = "n", message = "자는 중")),
            cooldownMinutes = 0,
        )
        val state = AppState(enabled = true, scenarios = listOf(scenario))
        // 1_800_000_000_000 ms = 2027-01-15T08:00:00Z = 17:00 KST
        val afternoon = now
        val midnight = now + 7 * 60 * minute // 00:00 KST
        assertTrue(sends(engine.onIncoming(state, phone, "ㅎㅇ", afternoon).actions).isEmpty())
        assertEquals(listOf("자는 중"), sends(engine.onIncoming(state, phone, "ㅎㅇ", midnight).actions))
    }

    @Test
    fun `zero timeout chains several messages`() {
        val scenario = Scenario(
            id = "s5", name = "연속", contactNumber = phone,
            trigger = Trigger(type = TriggerType.SCHEDULED, atMillis = now),
            nodes = listOf(
                Node(id = "a", message = "첫째", replyTimeoutMinutes = 0, timeoutNextNodeId = "b"),
                Node(id = "b", message = "둘째", replyTimeoutMinutes = 0, timeoutNextNodeId = "c"),
                Node(id = "c", message = "셋째"),
            ),
        )
        val result = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(scenario)), "s5", now)
        assertEquals(listOf("첫째", "둘째", "셋째"), sends(result.actions))
        assertTrue(result.state.conversations.isEmpty())
    }

    @Test
    fun `max auto messages stops runaway loops`() {
        val scenario = Scenario(
            id = "s6", name = "루프", contactNumber = phone,
            trigger = Trigger(type = TriggerType.ON_INCOMING),
            nodes = listOf(
                Node(id = "a", message = "핑", transitions = listOf(Transition(listOf(Transition.ANY), "a"))),
            ),
            maxAutoMessages = 3,
            cooldownMinutes = 60,
        )
        var state = AppState(enabled = true, scenarios = listOf(scenario))
        var totalSent = 0
        repeat(10) { i ->
            val r = engine.onIncoming(state, phone, "퐁 $i", now + i * minute)
            totalSent += sends(r.actions).size
            state = r.state
        }
        assertEquals(3, totalSent)
    }

    @Test
    fun `echo of our own message is ignored`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val result = engine.onIncoming(started, phone, "엄마, 저녁 먹었어?", now + minute)
        assertTrue(sends(result.actions).isEmpty())
        assertEquals(1, result.state.conversations.size)
    }

    @Test
    fun `conversation expires after inactivity`() {
        val started = engine.onScenarioFired(AppState(enabled = true, scenarios = listOf(dinnerScenario)), "s1", now).state
        val late = now + (12 * 60 + 1) * minute
        val result = engine.onIncoming(started, phone, "응", late)
        // Expired before the reply was considered, and no ON_INCOMING scenario exists -> nothing sent.
        assertTrue(sends(result.actions).isEmpty())
        assertNull(result.state.conversationFor("01012345678"))
    }
}
