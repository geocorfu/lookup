package com.example.lookup.core

import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    private val home = Zone("home", "Home", 37.98, 23.72, 100)
    private val work = Zone("work", "Work", 37.97, 23.73, 250)
    private val original = UserSettings(Schedule.DEFAULT, listOf(home))
    private val edited = UserSettings(Schedule.DEFAULT, listOf(home, work))

    @Test
    fun editOutsideWindowAppliesImmediately() {
        val stored = StoredSettings(original).edit(edited, windowActiveNow = false)
        assertEquals(edited, stored.applied)
        assertNull(stored.pending)
    }

    @Test
    fun editInsideWindowWaitsForWindowToEnd() {
        val stored = StoredSettings(original).edit(edited, windowActiveNow = true)
        assertEquals(original, stored.applied)
        assertEquals(edited, stored.pending)

        val promoted = stored.promotePending()
        assertEquals(edited, promoted.applied)
        assertNull(promoted.pending)
    }

    @Test
    fun laterEditReplacesEarlierPendingEdit() {
        val removeAll = UserSettings(Schedule.DEFAULT, emptyList())
        val stored = StoredSettings(original)
            .edit(edited, windowActiveNow = true)
            .edit(removeAll, windowActiveNow = true)
        assertEquals(original, stored.applied)
        assertEquals(removeAll, stored.pending)
    }

    @Test
    fun promoteWithoutPendingKeepsApplied() {
        assertEquals(StoredSettings(original), StoredSettings(original).promotePending())
    }

    @Test
    fun zoneRadiusMustBeWithinRange() {
        assertThrows(IllegalArgumentException::class.java) { home.copy(radiusM = 99) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(radiusM = 301) }
    }

    @Test
    fun zoneTrackerReportsInsideAnyAndOnlyOnChange() {
        val tracker = ZoneTracker()
        assertEquals(true, tracker.update("home", inside = true))
        assertNull(tracker.update("work", inside = true))
        assertNull(tracker.update("home", inside = false))
        assertEquals(false, tracker.update("work", inside = false))
        assertFalse(tracker.insideAny)
        tracker.update("home", inside = true)
        assertTrue(tracker.insideAny)
        tracker.clear()
        assertFalse(tracker.insideAny)
    }

    @Test
    fun codecRoundTripsSettings() {
        val settings = UserSettings(
            Schedule(
                listOf(
                    TimeWindow(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(7, 30), LocalTime.of(9, 0)),
                    TimeWindow(setOf(DayOfWeek.SATURDAY), LocalTime.of(22, 0), LocalTime.of(1, 15)),
                ),
            ),
            listOf(home, work.copy(name = "Work; office | 2")),
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings.schedule, decoded.schedule)
        assertEquals("Work office 2", decoded.zones[1].name)
        assertEquals(work.latitude, decoded.zones[1].latitude, 1e-9)
    }

    @Test
    fun codecFallsBackToDefaultsOnGarbage() {
        val decoded = SettingsCodec.decode("not valid")
        assertEquals(Schedule.DEFAULT, decoded.schedule)
        assertTrue(decoded.zones.isEmpty())
    }
}
