package com.example.lookup.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lookup.log.EventLog
import com.example.lookup.sensing.DetectionHub
import com.example.lookup.sensing.DetectionService
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Starts the detection service inside active windows and stops it outside them.
 * Called from the UI, from settings edits, from boot, and from an exact alarm at every window edge.
 */
object WindowScheduler {
    private val display = DateTimeFormatter.ofPattern("EEE HH:mm")

    fun sync(context: Context) {
        EventLog.init(context)
        val now = LocalDateTime.now()
        var stored = SettingsRepository.load(context)
        val schedule = stored.applied.schedule
        if (!schedule.isActive(now) && stored.pending != null) {
            stored = stored.promotePending()
            SettingsRepository.save(context, stored)
            EventLog.log("pending settings applied (outside window)")
        }
        val active = stored.applied.schedule.isActive(now)
        val next = stored.applied.schedule.nextChange(now)
        scheduleAlarm(context, next)

        val status = buildString {
            append(if (active) "active" else "inactive")
            next?.let { append(", ${if (active) "ends" else "starts"} ${it.format(display)}") }
            if (stored.pending != null) append(", changes pending")
        }
        EventLog.log("window sync: $status")
        DetectionHub.update { it.copy(windowStatus = status) }

        if (active) DetectionService.start(context) else DetectionService.stop(context)
    }

    private fun scheduleAlarm(context: Context, at: LocalDateTime?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WindowAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            alarms.cancel(intent)
            return
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            EventLog.log("exact alarms not allowed; window edge may be late")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }
}

class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        EventLog.log("window alarm fired")
        WindowScheduler.sync(context)
    }
}

/** Alarms are cleared on reboot and on reinstall, so both resync the schedule. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        EventLog.log("resync after ${intent.action}")
        WindowScheduler.sync(context)
    }
}
