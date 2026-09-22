package com.dongmyeong.smsautoreply.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dongmyeong.smsautoreply.PhoneNumbers
import com.dongmyeong.smsautoreply.data.Rule
import java.util.UUID

@Composable
fun RuleEditorDialog(
    initial: Rule?,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    var phone by remember { mutableStateOf(initial?.phoneNumber ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var message by remember { mutableStateOf(initial?.replyMessage ?: "") }
    var cooldown by remember { mutableStateOf((initial?.cooldownMinutes ?: 60).toString()) }

    val phoneValid = PhoneNumbers.normalize(phone) != null
    val messageValid = message.isNotBlank()
    val cooldownValue = cooldown.toIntOrNull()
    val cooldownValid = cooldownValue != null && cooldownValue >= 0
    val canSave = phoneValid && messageValid && cooldownValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "번호 추가" else "번호 수정") },
        text = {
            Column {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("전화번호") },
                    placeholder = { Text("010-1234-5678") },
                    isError = phone.isNotEmpty() && !phoneValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("이름 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("자동응답 문구") },
                    placeholder = { Text("지금은 답장이 어려워요. 나중에 연락드릴게요.") },
                    isError = message.isNotEmpty() && !messageValid,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit).take(5) },
                    label = { Text("재응답 대기 시간 (분)") },
                    supportingText = { Text("같은 번호에는 이 시간 안에 한 번만 답장합니다. 0이면 매번 답장합니다.") },
                    isError = !cooldownValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        Rule(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            phoneNumber = phone.trim(),
                            label = label.trim(),
                            replyMessage = message.trim(),
                            cooldownMinutes = cooldownValue ?: 60,
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
