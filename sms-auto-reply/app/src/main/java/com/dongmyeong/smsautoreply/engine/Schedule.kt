package com.dongmyeong.smsautoreply.engine

import com.dongmyeong.smsautoreply.data.RepeatMode
import com.dongmyeong.smsautoreply.data.Trigger
import com.dongmyeong.smsautoreply.data.TriggerType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object Schedule {
    /** Next time a SCHEDULED trigger should fire strictly after [nowMillis], or null if never. */
    fun nextFireTime(trigger: Trigger, nowMillis: Long, zone: ZoneId): Long? {
        if (trigger.type != TriggerType.SCHEDULED) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = LocalTime.ofSecondOfDay(trigger.minuteOfDay.coerceIn(0, 24 * 60 - 1) * 60L)

        return when (trigger.repeat) {
            RepeatMode.NONE -> trigger.atMillis.takeIf { it > nowMillis }

            RepeatMode.DAILY -> {
                var candidate = now.toLocalDate().atTime(time).atZone(zone)
                if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
                candidate.toInstant().toEpochMilli()
            }

            RepeatMode.WEEKLY -> {
                if (trigger.daysOfWeek.isEmpty()) return null
                (0..7L).asSequence()
                    .map { offset -> now.toLocalDate().plusDays(offset).atTime(time).atZone(zone) }
                    .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in trigger.daysOfWeek }
                    ?.toInstant()?.toEpochMilli()
            }
        }
    }

    /** True if [nowMillis] falls in the trigger's active window (always true when no window is set). */
    fun isWithinActiveWindow(trigger: Trigger, nowMillis: Long, zone: ZoneId): Boolean {
        val from = trigger.activeFromMinute ?: return true
        val to = trigger.activeToMinute ?: return true
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minute = now.hour * 60 + now.minute
        return if (from <= to) minute in from..to else minute >= from || minute <= to // overnight window
    }

    fun dayName(day: Int): String = when (DayOfWeek.of(day)) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }
}
