package com.dongmyeong.smsautoreply.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dongmyeong.smsautoreply.data.AppState
import com.dongmyeong.smsautoreply.data.ReplyLog
import com.dongmyeong.smsautoreply.data.Rule
import com.dongmyeong.smsautoreply.data.SettingsRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsStateWithLifecycle(initialValue = AppState())

    var hasPermissions by remember { mutableStateOf(context.hasSmsPermissions()) }
    var ignoresBatteryOptimizations by remember { mutableStateOf(context.ignoresBatteryOptimizations()) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    // Re-check permissions/battery status whenever the user comes back from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasPermissions = context.hasSmsPermissions()
            ignoresBatteryOptimizations = context.ignoresBatteryOptimizations()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> hasPermissions = result.values.all { it } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("문자 자동응답") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingRule = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("번호 추가") },
            )
        },
    ) { innerPadding ->
        // Fold7's inner screen is ~8 inches; cap the width so the list doesn't stretch edge to edge.
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
                        body = "문자를 읽고 답장을 보내려면 SMS 수신/전송 권한을 허용해야 합니다.",
                        actionLabel = "권한 허용",
                        onAction = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
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
                                if (state.enabled) "켜짐 · 등록된 번호에 자동으로 답장합니다" else "꺼짐",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            enabled = hasPermissions,
                            onCheckedChange = { on ->
                                scope.launch { repository.update { it.copy(enabled = on) } }
                            },
                        )
                    }
                }
            }

            item { SectionTitle("대상 번호 (${state.rules.size})") }

            if (state.rules.isEmpty()) {
                item {
                    Text(
                        "아직 등록된 번호가 없습니다. 오른쪽 아래 버튼으로 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { on ->
                        scope.launch {
                            repository.update { s ->
                                s.copy(rules = s.rules.map { if (it.id == rule.id) it.copy(enabled = on) else it })
                            }
                        }
                    },
                    onEdit = { editingRule = rule; showEditor = true },
                    onDelete = {
                        scope.launch {
                            repository.update { s -> s.copy(rules = s.rules.filterNot { it.id == rule.id }) }
                        }
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("최근 기록", modifier = Modifier.weight(1f))
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch { repository.update { it.copy(log = emptyList(), lastReplied = emptyMap()) } }
                        }) { Text("기록 지우기") }
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

    if (showEditor) {
        RuleEditorDialog(
            initial = editingRule,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                showEditor = false
                scope.launch {
                    repository.update { s ->
                        val exists = s.rules.any { it.id == saved.id }
                        val rules = if (exists) s.rules.map { if (it.id == saved.id) saved else it } else s.rules + saved
                        s.copy(rules = rules)
                    }
                }
            },
        )
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
private fun WarningCard(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rule.label.ifBlank { rule.phoneNumber },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (rule.label.isNotBlank()) {
                        Text(rule.phoneNumber, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Text(rule.replyMessage, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "같은 번호에 ${rule.cooldownMinutes}분마다 최대 1회",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "수정") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "삭제") }
            }
        }
    }
}

@Composable
private fun LogRow(entry: ReplyLog) {
    val formatter = remember { SimpleDateFormat("MM/dd HH:mm", Locale.KOREA) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(
                formatter.format(Date(entry.timestampMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(entry.sender, style = MaterialTheme.typography.labelMedium)
        }
        Text(entry.incomingPreview, style = MaterialTheme.typography.bodyMedium)
        Text(entry.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
