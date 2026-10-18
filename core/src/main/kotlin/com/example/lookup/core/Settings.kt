package com.example.lookup.core

import java.time.DayOfWeek
import java.time.LocalTime

data class Zone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
) {
    init {
        require(radiusM in RADIUS_RANGE_M) { "radius must be in $RADIUS_RANGE_M m" }
    }

    companion object {
        const val DEFAULT_RADIUS_M = 100
        val RADIUS_RANGE_M = 100..300
    }
}

data class UserSettings(
    val schedule: Schedule = Schedule.DEFAULT,
    val zones: List<Zone> = emptyList(),
)

/**
 * Edits made during an active window are held as [pending] and applied when that window ends,
 * so a window in progress cannot be shortened or zoned away (SPEC: no escape hatch).
 */
data class StoredSettings(val applied: UserSettings, val pending: UserSettings? = null) {

    fun edit(new: UserSettings, windowActiveNow: Boolean): StoredSettings =
        if (windowActiveNow) copy(pending = new) else StoredSettings(new)

    fun promotePending(): StoredSettings = pending?.let { StoredSettings(it) } ?: this
}

/** Tracks which zones the phone is inside; reports only changes of "inside any zone". */
class ZoneTracker {
    private val inside = mutableSetOf<String>()

    val insideAny: Boolean get() = inside.isNotEmpty()

    /** Returns the new [insideAny] if it changed, otherwise null. */
    fun update(zoneId: String, inside: Boolean): Boolean? {
        val before = insideAny
        if (inside) this.inside += zoneId else this.inside -= zoneId
        return if (insideAny != before) insideAny else null
    }

    fun clear() = inside.clear()
}

/** Plain-text storage format, one record per line. Unreadable input falls back to defaults. */
object SettingsCodec {
    private const val HEADER = "lookup-settings-v1"

    fun encode(settings: UserSettings): String = buildString {
        appendLine(HEADER)
        for (w in settings.schedule.windows) {
            appendLine("W|${w.days.sorted().joinToString(",") { it.name }}|${w.start}|${w.end}")
        }
        for (z in settings.zones) {
            appendLine("Z|${clean(z.id)}|${clean(z.name)}|${z.latitude}|${z.longitude}|${z.radiusM}")
        }
    }

    fun decode(text: String?): UserSettings {
        val lines = text?.lines()?.filter { it.isNotBlank() } ?: return UserSettings()
        if (lines.firstOrNull() != HEADER) return UserSettings()
        return runCatching {
            val windows = mutableListOf<TimeWindow>()
            val zones = mutableListOf<Zone>()
            for (line in lines.drop(1)) {
                val parts = line.split("|")
                when (parts[0]) {
                    "W" -> windows += TimeWindow(
                        parts[1].split(",").map { DayOfWeek.valueOf(it) }.toSet(),
                        LocalTime.parse(parts[2]),
                        LocalTime.parse(parts[3]),
                    )
                    "Z" -> zones += Zone(parts[1], parts[2], parts[3].toDouble(), parts[4].toDouble(), parts[5].toInt())
                }
            }
            UserSettings(Schedule(windows), zones)
        }.getOrDefault(UserSettings())
    }

    private fun clean(value: String) = value.replace(Regex("[|;\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim()
}
