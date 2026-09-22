package com.dongmyeong.smsautoreply

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.ui.HomeScreen
import com.dongmyeong.smsautoreply.ui.ScenarioEditorScreen
import com.dongmyeong.smsautoreply.ui.theme.SmsAutoReplyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = AppController(this)
        setContent {
            SmsAutoReplyTheme {
                AppRoot(controller)
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val scenarioId: String?) : Screen
}

@Composable
private fun AppRoot(controller: AppController) {
    val scope = rememberCoroutineScope()
    val state by controller.repository.state.collectAsStateWithLifecycle(initialValue = AppState())
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Make sure alarms exist for whatever is persisted (e.g. after the app was force-stopped).
    LaunchedEffect(Unit) { controller.rescheduleAll() }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            state = state,
            controller = controller,
            onAddScenario = { screen = Screen.Edit(null) },
            onOpenScenario = { screen = Screen.Edit(it) },
        )

        is Screen.Edit -> ScenarioEditorScreen(
            initial = state.scenarios.firstOrNull { it.id == current.scenarioId },
            onBack = { screen = Screen.Home },
            onSave = { scenario ->
                scope.launch { controller.saveScenario(scenario) }
                screen = Screen.Home
            },
            onDelete = { id ->
                scope.launch { controller.deleteScenario(id) }
                screen = Screen.Home
            },
        )
    }
}
