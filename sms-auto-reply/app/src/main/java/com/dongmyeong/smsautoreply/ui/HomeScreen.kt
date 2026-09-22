package com.dongmyeong.smsautoreply.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dongmyeong.smsautoreply.AppController
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.Conversation
import com.dongmyeong.smsautoreply.data.LogEntry
import com.dongmyeong.smsautoreply.data.RepeatMode
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.data.TriggerType
import com.dongmyeong.smsautoreply.engine.Schedule
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d(E) HH:mm")
private val logTimeFormat = DateTimeFormatter.ofPattern("MM/dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    controller: AppController,
    onAddScenario: () -> Unit,
    onOpenScenario: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermissions by remember { mutableStateOf(context.hasSmsPermissions()) }
    var ignoresBatteryOptimizations by remember { mutableStateOf(context.ignoresBatteryOptimizations()) }
    var canExactAlarm by remember { mutableStateOf(controller.scheduler.canScheduleExact()) }

    // Re-check permissions/battery status whenever the user comes back from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasPermissions = context.hasSmsPermissions()
            ignoresBatteryOptimizations = context.ignoresBatteryOptimizations()
            canExactAlarm = controller.scheduler.canScheduleExact()
        }
    }

    var permissionRequestFailed by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermissions = result.values.all { it }
        if (!hasPermissions) permissionRequestFailed = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("문자 자동응답") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddScenario,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("시나리오 추가") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPermissions) {
                item {
                    WarningCard(
                        title = "SMS 권한이 필요합니다",
                        body = if (permissionRequestFailed) {
                            "권한 창이 뜨지 않거나 바로 거부되면 Android의 '제한된 설정' 때문입니다. " +
                                "아래 '앱 설정 열기' → 오른쪽 위 ⋮ 메뉴 → '제한된 설정 허용'을 누른 뒤, 권한 → SMS → 허용으로 바꿔 주세요."
                        } else {
                            "문자를 읽고 답장을 보내려면 SMS 수신/전송 권한을 허용해야 합니다."
                        },
                        actionLabel = "권한 허용",
                        onAction = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                        secondaryLabel = "앱 설정 열기",
                        onSecondary = { context.openAppDetails() },
                    )
                }
            }
            if (!canExactAlarm) {
                item {
                    WarningCard(
                        title = "정확한 시각 알람 권한 권장",
                        body = "예약 문자를 정확한 시각에 보내려면 '알람 및 리마인더' 권한이 필요합니다. 없으면 몇 분 늦게 갈 수 있습니다.",
                        actionLabel = "설정 열기",
                        onAction = { context.requestExactAlarmPermission() },
                    )
                }
            }
            if (!ignoresBatteryOptimizations) {
                item {
                    WarningCard(
                        title = "배터리 최적화 예외 권장",
                        body = "One UI가 앱을 절전 상태로 두면 응답이 누락될 수 있습니다. 배터리 최적화에서 이 앱을 제외해 주세요.",
                        actionLabel = "설정 열기",
                        onAction = { context.requestIgnoreBatteryOptimizations() },
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("자동응답", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.enabled) "켜짐 · 시나리오대로 문자를 보내고 답장에 반응합니다" else "꺼짐 · 아무 문자도 보내지 않습니다",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            enabled = hasPermissions,
                            onCheckedChange = { on -> scope.launch { controller.setEnabled(on) } },
                        )
                    }
                }
            }

            if (state.conversations.isNotEmpty()) {
                item { SectionTitle("진행 중인 대화 (${state.conversations.size})") }
                items(state.conversations, key = { "conv-" + it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        scenario = state.scenarios.firstOrNull { it.id == conversation.scenarioId },
                        onEnd = { scope.launch { controller.endConversation(conversation.id) } },
                    )
                }
            }

            item { SectionTitle("시나리오 (${state.scenarios.size})") }

            if (state.scenarios.isEmpty()) {
                item {
                    Text(
                        "아직 시나리오가 없습니다. 오른쪽 아래 버튼으로 추가하세요.\n\n" +
                            "시나리오 = 상대 한 명 + 시작 방식(예약 시각 또는 문자 수신) + 단계별 문구와 답장 규칙",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.scenarios, key = { "sc-" + it.id }) { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    running = state.conversations.any { it.scenarioId == scenario.id },
                    onToggle = { on -> scope.launch { controller.setScenarioEnabled(scenario.id, on) } },
                    onOpen = { onOpenScenario(scenario.id) },
                    onRunNow = { scope.launch { controller.runScenarioNow(scenario.id) } },
                    masterEnabled = state.enabled,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("최근 기록", modifier = Modifier.weight(1f))
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { controller.clearLog() } }) { Text("기록 지우기") }
                    }
                }
            }

            if (state.log.isEmpty()) {
                item {
                    Text(
                        "아직 기록이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.log) { entry ->
                LogRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction) { Text(actionLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: Scenario,
    running: Boolean,
    masterEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRunNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(scenario.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(scenario.contactLabel.ifBlank { scenario.contactNumber })
                            if (scenario.contactLabel.isNotBlank()) append(" · ").append(scenario.contactNumber)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = scenario.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(scenario.triggerSummary(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${scenario.nodes.size}단계" + if (running) " · 진행 중" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scenario.trigger.type == TriggerType.SCHEDULED) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRunNow, enabled = masterEnabled && scenario.enabled) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("지금 바로 시작")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(conversation: Conversation, scenario: Scenario?, onEnd: () -> Unit) {
    val node = scenario?.node(conversation.currentNodeId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(scenario?.name ?: "(삭제된 시나리오)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "현재 단계: ${node?.title?.ifBlank { null } ?: node?.message?.take(20) ?: conversation.currentNodeId}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val since = Instant.ofEpochMilli(conversation.startedAtMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormat)
                val timeout = conversation.timeoutAtMillis?.let {
                    " · 대기 마감 " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dateTimeFormat)
                } ?: ""
                Text(
                    "시작 $since · 보낸 문자 ${conversation.sentCount}개$timeout",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEnd) { Text("종료") }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(
                Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault()).format(logTimeFormat),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(entry.contact, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Text(
                entry.kind,
                style = MaterialTheme.typography.labelMedium,
                color = when (entry.kind) {
                    "보냄" -> MaterialTheme.colorScheme.primary
                    "받음" -> MaterialTheme.colorScheme.tertiary
                    "실패" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
    }
}

fun Scenario.triggerSummary(nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    return when (trigger.type) {
        TriggerType.ON_INCOMING -> buildString {
            append("상대 문자를 받으면 시작")
            val from = trigger.activeFromMinute
            val to = trigger.activeToMinute
            if (from != null && to != null) append(" · ${formatMinuteOfDay(from)}~${formatMinuteOfDay(to)}에만")
        }

        TriggerType.SCHEDULED -> {
            val next = Schedule.nextFireTime(trigger, nowMillis, zone)
            val repeatText = when (trigger.repeat) {
                RepeatMode.NONE -> "한 번"
                RepeatMode.DAILY -> "매일 ${formatMinuteOfDay(trigger.minuteOfDay)}"
                RepeatMode.WEEKLY -> "매주 " + trigger.daysOfWeek.sorted().joinToString("") { Schedule.dayName(it) } +
                    " ${formatMinuteOfDay(trigger.minuteOfDay)}"
            }
            val nextText = when {
                !enabled -> "꺼짐"
                next == null && trigger.repeat == RepeatMode.NONE -> "예약 시각 지남"
                next == null -> "요일 미선택"
                else -> "다음 발송 " + Instant.ofEpochMilli(next).atZone(zone).format(dateTimeFormat)
            }
            "예약 발송 · $repeatText · $nextText"
        }
    }
}

private fun Context.hasSmsPermissions(): Boolean =
    REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

private fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

private fun Context.requestIgnoreBatteryOptimizations() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    try {
        startActivity(intent)
    } catch (e: Exception) {
        // Some OEM builds hide the direct dialog; fall back to the general list.
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun Context.openAppDetails() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

private fun Context.requestExactAlarmPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
    try {
        startActivity(intent)
    } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
