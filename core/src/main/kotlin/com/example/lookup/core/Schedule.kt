package com.example.lookup.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A daily range on the given days. If [end] is not after [start] the range runs past midnight
 * and belongs to the day it starts on (Fri 22:00–02:00 ends Saturday 02:00).
 */
data class TimeWindow(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime) {
    init {
        require(days.isNotEmpty()) { "a window needs at least one day" }
        require(start != end) { "a window cannot start and end at the same time" }
    }
}

data class Schedule(val windows: List<TimeWindow>) {

    fun isActive(at: LocalDateTime): Boolean = intervalsAround(at).any { !at.isBefore(it.first) && at.isBefore(it.second) }

    /** The next moment strictly after [after] when the schedule switches on or off, or null if never. */
    fun nextChange(after: LocalDateTime): LocalDateTime? =
        intervalsAround(after).flatMap { listOf(it.first, it.second) }.filter { it.isAfter(after) }.minOrNull()

    /** Merged intervals covering the day before [at] through the following week. */
    private fun intervalsAround(at: LocalDateTime): List<Pair<LocalDateTime, LocalDateTime>> {
        val raw = (-1L..8L).flatMap { offset ->
            val date = at.toLocalDate().plusDays(offset)
            windows.filter { date.dayOfWeek in it.days }.map { window ->
                val start = date.atTime(window.start)
                val end = if (window.end.isAfter(window.start)) date.atTime(window.end) else date.plusDays(1).atTime(window.end)
                start to end
            }
        }.sortedBy { it.first }

        val merged = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for (interval in raw) {
            val last = merged.lastOrNull()
            if (last != null && !interval.first.isAfter(last.second)) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, interval.second)
            } else {
                merged += interval
            }
        }
        return merged
    }

    companion object {
        val DEFAULT = Schedule(listOf(TimeWindow(DayOfWeek.entries.toSet(), LocalTime.of(7, 0), LocalTime.of(23, 0))))
    }
}
