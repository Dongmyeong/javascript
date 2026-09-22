package com.dongmyeong.smsautoreply

import com.dongmyeong.smsautoreply.data.RepeatMode
import com.dongmyeong.smsautoreply.data.Trigger
import com.dongmyeong.smsautoreply.data.TriggerType
import com.dongmyeong.smsautoreply.engine.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `one shot fires only if still in the future`() {
        val t = Trigger(type = TriggerType.SCHEDULED, repeat = RepeatMode.NONE, atMillis = at(2026, 10, 1, 18, 0))
        assertEquals(at(2026, 10, 1, 18, 0), Schedule.nextFireTime(t, at(2026, 10, 1, 17, 59), zone))
        assertNull(Schedule.nextFireTime(t, at(2026, 10, 1, 18, 0), zone))
    }

    @Test
    fun `daily picks today if the time has not passed, otherwise tomorrow`() {
        val t = Trigger(type = TriggerType.SCHEDULED, repeat = RepeatMode.DAILY, minuteOfDay = 9 * 60)
        assertEquals(at(2026, 10, 1, 9, 0), Schedule.nextFireTime(t, at(2026, 10, 1, 8, 0), zone))
        assertEquals(at(2026, 10, 2, 9, 0), Schedule.nextFireTime(t, at(2026, 10, 1, 9, 0), zone))
    }

    @Test
    fun `weekly finds the next selected weekday`() {
        // 2026-10-01 is a Thursday.
        val t = Trigger(
            type = TriggerType.SCHEDULED, repeat = RepeatMode.WEEKLY, minuteOfDay = 20 * 60,
            daysOfWeek = setOf(DayOfWeek.MONDAY.value, DayOfWeek.FRIDAY.value),
        )
        val next = Schedule.nextFireTime(t, at(2026, 10, 1, 12, 0), zone)!!
        assertEquals(at(2026, 10, 2, 20, 0), next)
        val afterFriday = Schedule.nextFireTime(t, at(2026, 10, 2, 20, 0), zone)!!
        assertEquals(at(2026, 10, 5, 20, 0), afterFriday)
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochMilli(afterFriday).atZone(zone).dayOfWeek)
    }

    @Test
    fun `weekly with no days never fires`() {
        val t = Trigger(type = TriggerType.SCHEDULED, repeat = RepeatMode.WEEKLY, minuteOfDay = 0)
        assertNull(Schedule.nextFireTime(t, at(2026, 10, 1, 12, 0), zone))
    }

    @Test
    fun `on incoming triggers never schedule`() {
        assertNull(Schedule.nextFireTime(Trigger(type = TriggerType.ON_INCOMING), at(2026, 10, 1, 12, 0), zone))
    }

    @Test
    fun `active window handles overnight ranges`() {
        val t = Trigger(type = TriggerType.ON_INCOMING, activeFromMinute = 22 * 60, activeToMinute = 6 * 60)
        assertTrue(Schedule.isWithinActiveWindow(t, at(2026, 10, 1, 23, 0), zone))
        assertTrue(Schedule.isWithinActiveWindow(t, at(2026, 10, 2, 5, 30), zone))
        assertFalse(Schedule.isWithinActiveWindow(t, at(2026, 10, 1, 12, 0), zone))
        assertTrue(Schedule.isWithinActiveWindow(Trigger(type = TriggerType.ON_INCOMING), at(2026, 10, 1, 12, 0), zone))
    }
}
