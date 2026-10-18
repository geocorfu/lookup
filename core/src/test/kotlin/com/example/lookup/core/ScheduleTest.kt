package com.example.lookup.core

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {

    // 2026-10-19 is a Monday.
    private fun mon(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 19, h, m)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private val weekdayCommute = Schedule(
        listOf(
            TimeWindow(setOf(MONDAY, TUESDAY), t(7), t(9)),
            TimeWindow(setOf(MONDAY, TUESDAY), t(17), t(21)),
        ),
    )

    @Test
    fun activeOnlyInsideRangesOnListedDays() {
        assertFalse(weekdayCommute.isActive(mon(6, 59)))
        assertTrue(weekdayCommute.isActive(mon(7)))
        assertTrue(weekdayCommute.isActive(mon(8, 59)))
        assertFalse(weekdayCommute.isActive(mon(9)))
        assertTrue(weekdayCommute.isActive(mon(18)))
        assertFalse(weekdayCommute.isActive(mon(18).plusDays(2))) // Wednesday
    }

    @Test
    fun nextChangeFindsUpcomingStartOrEnd() {
        assertEquals(mon(7), weekdayCommute.nextChange(mon(6)))
        assertEquals(mon(9), weekdayCommute.nextChange(mon(8)))
        assertEquals(mon(17), weekdayCommute.nextChange(mon(9)))
        assertEquals(mon(7).plusDays(1), weekdayCommute.nextChange(mon(21)))
        assertEquals(mon(7).plusDays(7), weekdayCommute.nextChange(mon(22).plusDays(1)))
    }

    @Test
    fun overnightWindowWrapsPastMidnight() {
        val night = Schedule(listOf(TimeWindow(setOf(FRIDAY), t(22), t(2))))
        val friday = LocalDateTime.of(2026, 10, 23, 23, 0)
        assertTrue(night.isActive(friday))
        assertTrue(night.isActive(friday.plusHours(2)))     // Saturday 01:00
        assertFalse(night.isActive(friday.plusHours(3)))    // Saturday 02:00
        assertEquals(friday.plusHours(3), night.nextChange(friday))
    }

    @Test
    fun windowEndingAtMidnightRunsToEndOfDay() {
        val evening = Schedule(listOf(TimeWindow(setOf(SATURDAY), t(20), t(0))))
        val saturday = LocalDateTime.of(2026, 10, 24, 23, 59)
        assertTrue(evening.isActive(saturday))
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 0), evening.nextChange(saturday))
    }

    @Test
    fun adjoiningWindowsMergeIntoOne() {
        val joined = Schedule(
            listOf(
                TimeWindow(setOf(SUNDAY), t(8), t(12)),
                TimeWindow(setOf(SUNDAY), t(12), t(18)),
            ),
        )
        val sunday = LocalDateTime.of(2026, 10, 25, 10, 0)
        assertEquals(sunday.withHour(18), joined.nextChange(sunday))
    }

    @Test
    fun emptyScheduleIsNeverActive() {
        assertFalse(Schedule(emptyList()).isActive(mon(12)))
        assertNull(Schedule(emptyList()).nextChange(mon(12)))
    }

    @Test
    fun invalidWindowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(emptySet(), t(7), t(9)) }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow(setOf(MONDAY), t(7), t(7)) }
    }
}
