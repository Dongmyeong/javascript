package com.dongmyeong.smsautoreply.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dongmyeong.smsautoreply.PhoneNumbers
import com.dongmyeong.smsautoreply.data.Node
import com.dongmyeong.smsautoreply.data.RepeatMode
import com.dongmyeong.smsautoreply.data.Scenario
import com.dongmyeong.smsautoreply.data.Trigger
import com.dongmyeong.smsautoreply.data.TriggerType
import com.dongmyeong.smsautoreply.engine.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val dateFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioEditorScreen(
    initial: Scenario?,
    onBack: () -> Unit,
    onSave: (Scenario) -> Unit,
    onDelete: (String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var draft by remember {
        mutableStateOf(
            initial ?: Scenario(
                id = UUID.randomUUID().toString(),
                name = "",
                contactNumber = "",
                trigger = Trigger(
                    type = TriggerType.SCHEDULED,
                    atMillis = System.currentTimeMillis() + 60 * 60_000L,
                    minuteOfDay = 9 * 60,
                ),
                nodes = listOf(Node(id = UUID.randomUUID().toString(), title = "1단계")),
            ),
        )
    }
    var editingNode by remember { mutableStateOf<Node?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Node editor takes over the whole screen while open.
    val nodeBeingEdited = editingNode
    if (nodeBeingEdited != null) {
        NodeEditorScreen(
            node = nodeBeingEdited,
            allNodes = draft.nodes,
            onBack = { editingNode = null },
            onSave = { saved ->
                draft = draft.copy(nodes = draft.nodes.map { if (it.id == saved.id) saved else it })
                editingNode = null
            },
        )
        return
    }

    BackHandler(onBack = onBack)

    val errors = draft.validationErrors(System.currentTimeMillis())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "시나리오 추가" else "시나리오 수정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "삭제") }
                    }
                    TextButton(enabled = errors.isEmpty(), onClick = { onSave(draft.normalized()) }) { Text("저장") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("기본 정보")
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("시나리오 이름") },
                placeholder = { Text("예: 저녁 안부") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.contactNumber,
                onValueChange = { draft = draft.copy(contactNumber = it) },
                label = { Text("상대 전화번호") },
                placeholder = { Text("010-1234-5678") },
                isError = draft.contactNumber.isNotEmpty() && PhoneNumbers.normalize(draft.contactNumber) == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.contactLabel,
                onValueChange = { draft = draft.copy(contactLabel = it) },
                label = { Text("상대 이름 (선택, 문구의 {이름}에 들어감)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("시작 방식")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.trigger.type == TriggerType.SCHEDULED,
                    onClick = { draft = draft.copy(trigger = draft.trigger.copy(type = TriggerType.SCHEDULED)) },
                    label = { Text("예약 시각에 내가 먼저 보내기") },
                )
                FilterChip(
                    selected = draft.trigger.type == TriggerType.ON_INCOMING,
                    onClick = { draft = draft.copy(trigger = draft.trigger.copy(type = TriggerType.ON_INCOMING)) },
                    label = { Text("상대 문자를 받으면") },
                )
            }

            when (draft.trigger.type) {
                TriggerType.SCHEDULED -> ScheduleEditor(
                    trigger = draft.trigger,
                    zone = zone,
                    onChange = { draft = draft.copy(trigger = it) },
                )

                TriggerType.ON_INCOMING -> IncomingEditor(
                    scenario = draft,
                    onChange = { draft = it },
                )
            }

            SectionLabel("단계 (${draft.nodes.size})")
            Text(
                "시나리오가 시작되면 시작 단계의 문구를 보내고(비어 있으면 보내지 않고) 답장을 기다립니다. " +
                    "답장에 포함된 단어에 따라 다음 단계로 넘어가며, 단계마다 문구와 규칙을 정합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            draft.nodes.forEachIndexed { index, node ->
                NodeSummaryCard(
                    index = index,
                    node = node,
                    isStart = (draft.startNode?.id == node.id),
                    allNodes = draft.nodes,
                    onEdit = { editingNode = node },
                    onSetStart = { draft = draft.copy(startNodeId = node.id) },
                    onDelete = {
                        if (draft.nodes.size > 1) {
                            val remaining = draft.nodes.filterNot { it.id == node.id }
                            draft = draft.copy(
                                nodes = remaining,
                                startNodeId = if (draft.startNodeId == node.id) remaining.first().id else draft.startNodeId,
                            )
                        }
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    val newNode = Node(id = UUID.randomUUID().toString(), title = "${draft.nodes.size + 1}단계")
                    draft = draft.copy(nodes = draft.nodes + newNode)
                    editingNode = newNode
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("단계 추가") }

            SectionLabel("안전장치")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = draft.expiresAfterMinutes,
                    label = "대화 만료 (분)",
                    onChange = { draft = draft.copy(expiresAfterMinutes = it) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = draft.maxAutoMessages,
                    label = "최대 발송 수",
                    onChange = { draft = draft.copy(maxAutoMessages = it) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "답장이 없어도 만료 시간이 지나면 대화를 끝냅니다. 최대 발송 수는 한 번의 대화에서 내가 보낼 수 있는 문자 개수 상한입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (errors.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("시나리오 삭제") },
            text = { Text("「${initial.name}」을(를) 삭제할까요? 진행 중인 대화도 함께 종료됩니다.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial.id) }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ScheduleEditor(trigger: Trigger, zone: ZoneId, onChange: (Trigger) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val oneShot = Instant.ofEpochMilli(trigger.atMillis).atZone(zone)
    val oneShotMinute = oneShot.hour * 60 + oneShot.minute

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatMode.entries.forEach { mode ->
                FilterChip(
                    selected = trigger.repeat == mode,
                    onClick = { onChange(trigger.copy(repeat = mode)) },
                    label = {
                        Text(
                            when (mode) {
                                RepeatMode.NONE -> "한 번"
                                RepeatMode.DAILY -> "매일"
                                RepeatMode.WEEKLY -> "매주"
                            },
                        )
                    },
                )
            }
        }

        if (trigger.repeat == RepeatMode.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = day in trigger.daysOfWeek,
                        onClick = {
                            val days = if (day in trigger.daysOfWeek) trigger.daysOfWeek - day else trigger.daysOfWeek + day
                            onChange(trigger.copy(daysOfWeek = days))
                        },
                        label = { Text(Schedule.dayName(day)) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trigger.repeat == RepeatMode.NONE) {
                AssistChip(onClick = { showDate = true }, label = { Text(oneShot.format(dateFormat)) })
                AssistChip(onClick = { showTime = true }, label = { Text(formatMinuteOfDay(oneShotMinute)) })
            } else {
                AssistChip(onClick = { showTime = true }, label = { Text(formatMinuteOfDay(trigger.minuteOfDay)) })
            }
        }

        val next = Schedule.nextFireTime(trigger, System.currentTimeMillis(), zone)
        Text(
            when {
                next != null -> "다음 발송: " + Instant.ofEpochMilli(next).atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm"))
                trigger.repeat == RepeatMode.WEEKLY -> "요일을 선택하세요"
                else -> "이미 지난 시각입니다"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (next != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }

    if (showDate) {
        DatePickerPopup(
            initialDate = oneShot.toLocalDate(),
            onDismiss = { showDate = false },
            onPick = { date: LocalDate ->
                val at = date.atTime(LocalTime.ofSecondOfDay(oneShotMinute * 60L)).atZone(zone).toInstant().toEpochMilli()
                onChange(trigger.copy(atMillis = at))
            },
        )
    }
    if (showTime) {
        TimePickerPopup(
            initialMinuteOfDay = if (trigger.repeat == RepeatMode.NONE) oneShotMinute else trigger.minuteOfDay,
            onDismiss = { showTime = false },
            onPick = { minute ->
                if (trigger.repeat == RepeatMode.NONE) {
                    val at = oneShot.toLocalDate().atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(zone).toInstant().toEpochMilli()
                    onChange(trigger.copy(atMillis = at, minuteOfDay = minute))
                } else {
                    onChange(trigger.copy(minuteOfDay = minute))
                }
            },
        )
    }
}

@Composable
private fun IncomingEditor(scenario: Scenario, onChange: (Scenario) -> Unit) {
    val trigger = scenario.trigger
    val windowOn = trigger.activeFromMinute != null && trigger.activeToMinute != null
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("특정 시간대에만 반응", style = MaterialTheme.typography.bodyLarge)
                Text("예: 22:00~06:00 (자정을 넘는 범위도 가능)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = windowOn,
                onCheckedChange = { on ->
                    onChange(
                        scenario.copy(
                            trigger = if (on) trigger.copy(activeFromMinute = 22 * 60, activeToMinute = 6 * 60)
                            else trigger.copy(activeFromMinute = null, activeToMinute = null),
                        ),
                    )
                },
            )
        }
        if (windowOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { pickFrom = true }, label = { Text(formatMinuteOfDay(trigger.activeFromMinute ?: 0)) })
                Text("~")
                AssistChip(onClick = { pickTo = true }, label = { Text(formatMinuteOfDay(trigger.activeToMinute ?: 0)) })
            }
        }
        NumberField(
            value = scenario.cooldownMinutes,
            label = "대화가 끝난 뒤 다시 시작하기까지 (분)",
            onChange = { onChange(scenario.copy(cooldownMinutes = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "상대가 연달아 문자를 보내도 이 시간 안에는 새 대화를 시작하지 않습니다. 0이면 매번 시작합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickFrom) {
        TimePickerPopup(
            initialMinuteOfDay = trigger.activeFromMinute ?: 0,
            onDismiss = { pickFrom = false },
            onPick = { onChange(scenario.copy(trigger = trigger.copy(activeFromMinute = it))) },
        )
    }
    if (pickTo) {
        TimePickerPopup(
            initialMinuteOfDay = trigger.activeToMinute ?: 0,
            onDismiss = { pickTo = false },
            onPick = { onChange(scenario.copy(trigger = trigger.copy(activeToMinute = it))) },
        )
    }
}

@Composable
private fun NodeSummaryCard(
    index: Int,
    node: Node,
    isStart: Boolean,
    allNodes: List<Node>,
    onEdit: () -> Unit,
    onSetStart: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.title.ifBlank { "${index + 1}단계" } + if (isStart) "  · 시작" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!isStart) TextButton(onClick = onSetStart) { Text("시작으로") }
                TextButton(onClick = onEdit) { Text("편집") }
                if (allNodes.size > 1) {
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "단계 삭제") }
                }
            }
            Text(
                if (node.message.isBlank()) "(보내지 않고 답장만 기다림)" else node.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            node.transitions.forEach { t ->
                Text(
                    "↳ " + t.keywords.joinToString(", ").ifBlank { "(단어 없음)" } + " → " + nodeLabel(allNodes, t.nextNodeId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val timeout = node.replyTimeoutMinutes
            if (timeout != null) {
                Text(
                    if (timeout <= 0) "↳ 보낸 직후 → " + nodeLabel(allNodes, node.timeoutNextNodeId)
                    else "↳ ${timeout}분 동안 답장 없으면 → " + nodeLabel(allNodes, node.timeoutNextNodeId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun NumberField(value: Int, label: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

internal fun nodeLabel(allNodes: List<Node>, nodeId: String?): String {
    if (nodeId == null) return "대화 종료"
    val index = allNodes.indexOfFirst { it.id == nodeId }
    if (index < 0) return "(삭제된 단계)"
    return allNodes[index].title.ifBlank { "${index + 1}단계" }
}

private fun Scenario.normalized(): Scenario = copy(
    name = name.trim(),
    contactNumber = contactNumber.trim(),
    contactLabel = contactLabel.trim(),
    startNodeId = startNode?.id,
    nodes = nodes.map { n ->
        n.copy(
            title = n.title.trim(),
            message = n.message.trim(),
            transitions = n.transitions.map { t -> t.copy(keywords = t.keywords.map(String::trim).filter(String::isNotEmpty)) }
                .filter { it.keywords.isNotEmpty() },
        )
    },
)

private fun Scenario.validationErrors(nowMillis: Long): List<String> {
    val errors = mutableListOf<String>()
    if (name.isBlank()) errors += "시나리오 이름을 입력하세요."
    if (PhoneNumbers.normalize(contactNumber) == null) errors += "상대 전화번호를 확인하세요."
    if (nodes.isEmpty()) errors += "단계가 하나 이상 필요합니다."
    val start = startNode
    if (trigger.type == TriggerType.SCHEDULED) {
        if (start != null && start.message.isBlank()) errors += "예약 발송 시나리오의 시작 단계에는 보낼 문구가 있어야 합니다."
        when (trigger.repeat) {
            RepeatMode.NONE -> if (trigger.atMillis <= nowMillis) errors += "예약 시각이 이미 지났습니다."
            RepeatMode.WEEKLY -> if (trigger.daysOfWeek.isEmpty()) errors += "매주 반복할 요일을 선택하세요."
            RepeatMode.DAILY -> Unit
        }
    }
    if (expiresAfterMinutes <= 0) errors += "대화 만료 시간은 1분 이상이어야 합니다."
    if (maxAutoMessages <= 0) errors += "최대 발송 수는 1 이상이어야 합니다."
    return errors
}
