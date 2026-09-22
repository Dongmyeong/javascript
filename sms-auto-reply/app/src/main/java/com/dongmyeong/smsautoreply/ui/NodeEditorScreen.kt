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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.dongmyeong.smsautoreply.data.NoMatchAction
import com.dongmyeong.smsautoreply.data.Node
import com.dongmyeong.smsautoreply.data.Transition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditorScreen(
    node: Node,
    allNodes: List<Node>,
    onBack: () -> Unit,
    onSave: (Node) -> Unit,
) {
    var draft by remember { mutableStateOf(node) }
    // Keyword text is edited as a comma-separated string per transition.
    var keywordTexts by remember { mutableStateOf(node.transitions.map { it.keywords.joinToString(", ") }) }
    var timeoutText by remember { mutableStateOf(node.replyTimeoutMinutes?.toString() ?: "") }

    BackHandler(onBack = onBack)

    val nodeOptions: List<Pair<String?, String>> =
        allNodes.map { n -> n.id to nodeLabel(allNodes, n.id) } + (null to "대화 종료")

    fun buildResult(): Node {
        val transitions = draft.transitions.mapIndexed { i, t ->
            t.copy(keywords = keywordTexts.getOrElse(i) { "" }.split(",").map(String::trim).filter(String::isNotEmpty))
        }.filter { it.keywords.isNotEmpty() }
        return draft.copy(
            transitions = transitions,
            replyTimeoutMinutes = timeoutText.toIntOrNull(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("단계 편집") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = { TextButton(onClick = { onSave(buildResult()) }) { Text("완료") } },
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
            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                label = { Text("단계 이름") },
                placeholder = { Text("예: 저녁 물어보기") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("이 단계에서 보낼 문구")
            OutlinedTextField(
                value = draft.message,
                onValueChange = { draft = draft.copy(message = it) },
                label = { Text("보낼 문구 (비워두면 보내지 않고 답장만 기다림)") },
                placeholder = { Text("{이름}, 저녁 먹었어?") },
                supportingText = { Text("{이름}, {시각}, {날짜}를 쓰면 자동으로 채워집니다.") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("답장 규칙 (위에서부터 순서대로 검사)")
            Text(
                "답장에 단어 중 하나라도 포함되면 해당 단계로 넘어갑니다. 단어는 쉼표로 구분하고, * 는 어떤 답장이든 해당됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            draft.transitions.forEachIndexed { index, transition ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keywordTexts.getOrElse(index) { "" },
                                onValueChange = { text ->
                                    keywordTexts = keywordTexts.toMutableList().also { list ->
                                        while (list.size <= index) list.add("")
                                        list[index] = text
                                    }
                                },
                                label = { Text("포함 단어") },
                                placeholder = { Text("먹었, 응, 네") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                draft = draft.copy(transitions = draft.transitions.filterIndexed { i, _ -> i != index })
                                keywordTexts = keywordTexts.filterIndexed { i, _ -> i != index }
                            }) { Icon(Icons.Default.Delete, contentDescription = "규칙 삭제") }
                        }
                        DropdownField(
                            label = "그러면 이동할 단계",
                            selected = transition.nextNodeId,
                            options = nodeOptions,
                            onSelect = { next ->
                                draft = draft.copy(
                                    transitions = draft.transitions.mapIndexed { i, t -> if (i == index) t.copy(nextNodeId = next) else t },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    draft = draft.copy(transitions = draft.transitions + Transition(emptyList(), null))
                    keywordTexts = keywordTexts + ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("규칙 추가") }

            SectionLabel("규칙에 맞지 않는 답장이 오면")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.noMatchAction == NoMatchAction.WAIT,
                    onClick = { draft = draft.copy(noMatchAction = NoMatchAction.WAIT) },
                    label = { Text("계속 기다리기") },
                )
                FilterChip(
                    selected = draft.noMatchAction == NoMatchAction.END,
                    onClick = { draft = draft.copy(noMatchAction = NoMatchAction.END) },
                    label = { Text("대화 종료") },
                )
                FilterChip(
                    selected = draft.noMatchAction == NoMatchAction.GOTO,
                    onClick = { draft = draft.copy(noMatchAction = NoMatchAction.GOTO) },
                    label = { Text("다른 단계로") },
                )
            }
            if (draft.noMatchAction == NoMatchAction.GOTO) {
                DropdownField(
                    label = "이동할 단계",
                    selected = draft.noMatchNextNodeId,
                    options = nodeOptions,
                    onSelect = { draft = draft.copy(noMatchNextNodeId = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionLabel("답장을 기다리는 시간")
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it.filter(Char::isDigit).take(5) },
                label = { Text("분 (비워두면 대화 만료까지 기다림)") },
                supportingText = { Text("0을 넣으면 답장을 기다리지 않고 바로 아래 단계로 넘어가서 문자를 연달아 보냅니다.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (timeoutText.isNotEmpty()) {
                DropdownField(
                    label = if ((timeoutText.toIntOrNull() ?: 0) <= 0) "보낸 직후 이동할 단계" else "시간이 지나면 이동할 단계",
                    selected = draft.timeoutNextNodeId,
                    options = nodeOptions,
                    onSelect = { draft = draft.copy(timeoutNextNodeId = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
