package com.dongmyeong.smsautoreply.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongmyeong.smsautoreply.data.SettingsRepository
import com.dongmyeong.smsautoreply.engine.ConversationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles scenario start alarms and reply-timeout alarms. */
class AlarmReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, action, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "alarm handling failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String, intent: Intent) {
        val repository = SettingsRepository(context)
        val engine = ConversationEngine()
        val executor = ActionExecutor(context, repository)
        val scheduler = AlarmScheduler(context)
        val now = System.currentTimeMillis()

        when (action) {
            ACTION_SCENARIO_FIRE -> {
                val scenarioId = intent.getStringExtra(EXTRA_SCENARIO_ID) ?: return
                val (actions, scenario) = repository.transact { state ->
                    val result = engine.onScenarioFired(state, scenarioId, now)
                    result.state to (result.actions to result.state.scenarios.firstOrNull { it.id == scenarioId })
                }
                executor.execute(actions)
                // Repeating triggers need their next alarm; one-shots simply get cancelled.
                if (scenario != null) scheduler.scheduleScenario(scenario, now + 1_000)
                Log.i(TAG, "scenario fired id=$scenarioId actions=${actions.size}")
            }

            ACTION_REPLY_TIMEOUT -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
                val actions = repository.transact { state ->
                    val result = engine.onTimeout(state, conversationId, nodeId, now)
                    result.state to result.actions
                }
                executor.execute(actions)
                Log.i(TAG, "timeout conversation=$conversationId node=$nodeId actions=${actions.size}")
            }
        }
    }

    companion object {
        const val TAG = "SmsAutoReply"
        const val ACTION_SCENARIO_FIRE = "com.dongmyeong.smsautoreply.SCENARIO_FIRE"
        const val ACTION_REPLY_TIMEOUT = "com.dongmyeong.smsautoreply.REPLY_TIMEOUT"
        const val EXTRA_SCENARIO_ID = "scenario_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NODE_ID = "node_id"
    }
}
