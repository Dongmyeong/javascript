package com.dongmyeong.smsautoreply

import android.content.Context
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.data.SettingsRepository
import com.dongmyeong.smsautoreply.engine.ConversationEngine
import com.dongmyeong.smsautoreply.scheduling.ActionExecutor
import com.dongmyeong.smsautoreply.scheduling.AlarmScheduler

/** Everything the UI needs to do, in one place, so screens stay declarative. */
class AppController(context: Context) {
    private val appContext = context.applicationContext
    val repository = SettingsRepository(appContext)
    val scheduler = AlarmScheduler(appContext)
    private val engine = ConversationEngine()
    private val executor = ActionExecutor(appContext, repository)

    suspend fun setEnabled(on: Boolean) {
        repository.update { it.copy(enabled = on) }
    }

    suspend fun saveScenario(scenario: Scenario) {
        repository.update { state ->
            val exists = state.scenarios.any { it.id == scenario.id }
            val scenarios = if (exists) state.scenarios.map { if (it.id == scenario.id) scenario else it } else state.scenarios + scenario
            state.copy(scenarios = scenarios)
        }
        scheduler.scheduleScenario(scenario, System.currentTimeMillis())
    }

    suspend fun setScenarioEnabled(id: String, on: Boolean) {
        var updated: Scenario? = null
        repository.update { state ->
            state.copy(
                scenarios = state.scenarios.map {
                    if (it.id == id) it.copy(enabled = on).also { u -> updated = u } else it
                },
            )
        }
        updated?.let { scheduler.scheduleScenario(it, System.currentTimeMillis()) }
    }

    suspend fun deleteScenario(id: String) {
        val now = System.currentTimeMillis()
        val actions = repository.transact { state ->
            var next = state.copy(scenarios = state.scenarios.filterNot { it.id == id })
            val collected = mutableListOf<ConversationEngine.Action>()
            next.conversations.filter { it.scenarioId == id }.forEach { c ->
                val r = engine.endConversation(next, c.id, now)
                next = r.state
                collected += r.actions
            }
            next to collected.toList()
        }
        scheduler.cancelScenario(id)
        executor.execute(actions)
    }

    suspend fun endConversation(conversationId: String) {
        val now = System.currentTimeMillis()
        val actions = repository.transact { state ->
            val r = engine.endConversation(state, conversationId, now)
            r.state to r.actions
        }
        executor.execute(actions)
    }

    /** Starts a scenario right now, ignoring its schedule. Handy for testing a flow. */
    suspend fun runScenarioNow(id: String) {
        val now = System.currentTimeMillis()
        val actions = repository.transact { state ->
            val r = engine.onScenarioFired(state, id, now)
            r.state to r.actions
        }
        executor.execute(actions)
    }

    suspend fun clearLog() {
        repository.update { it.copy(log = emptyList()) }
    }

    suspend fun rescheduleAll() {
        scheduler.rescheduleAll(repository.current(), System.currentTimeMillis())
    }
}
