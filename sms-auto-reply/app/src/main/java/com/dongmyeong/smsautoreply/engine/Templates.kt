package com.dongmyeong.smsautoreply.engine

import com.dongmyeong.smsautoreply.data.Scenario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Replaces `{이름}`, `{시각}`, `{날짜}` placeholders in outgoing messages. */
object Templates {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("M월 d일")

    fun render(template: String, scenario: Scenario, nowMillis: Long, zone: ZoneId): String {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val name = scenario.contactLabel.ifBlank { scenario.contactNumber }
        return template
            .replace("{이름}", name)
            .replace("{시각}", now.format(timeFormat))
            .replace("{날짜}", now.format(dateFormat))
    }
}
