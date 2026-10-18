package com.example.lookup.settings

import android.content.Context
import com.example.lookup.core.SettingsCodec
import com.example.lookup.core.StoredSettings
import com.example.lookup.core.UserSettings
import com.example.lookup.log.EventLog
import java.time.LocalDateTime

/** Applied and pending settings, stored as plain text in SharedPreferences. */
object SettingsRepository {
    private const val PREFS = "settings"
    private const val KEY_APPLIED = "applied"
    private const val KEY_PENDING = "pending"

    fun load(context: Context): StoredSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = SettingsCodec.decode(prefs.getString(KEY_APPLIED, null))
        val pending = prefs.getString(KEY_PENDING, null)?.let { SettingsCodec.decode(it) }
        return StoredSettings(applied, pending)
    }

    fun save(context: Context, stored: StoredSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APPLIED, SettingsCodec.encode(stored.applied))
            .putString(KEY_PENDING, stored.pending?.let { SettingsCodec.encode(it) })
            .apply()
    }

    /** Saves an edit under the next-window rule and resyncs the schedule. */
    fun edit(context: Context, new: UserSettings) {
        val stored = load(context)
        val activeNow = stored.applied.schedule.isActive(LocalDateTime.now())
        save(context, stored.edit(new, activeNow))
        EventLog.log("settings edited (${if (activeNow) "pending until window ends" else "applied now"})")
        WindowScheduler.sync(context)
    }
}
