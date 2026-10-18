package com.example.lookup.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lookup.core.Schedule
import com.example.lookup.core.TimeWindow
import com.example.lookup.core.UserSettings
import com.example.lookup.core.Zone
import com.example.lookup.settings.SettingsRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

private data class WindowDraft(val days: Set<DayOfWeek>, val start: String, val end: String)

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val stored = remember { SettingsRepository.load(context) }
    val draft = stored.pending ?: stored.applied
    val windowActive = remember { stored.applied.schedule.isActive(LocalDateTime.now()) }

    val windows = remember {
        draft.schedule.windows.map { WindowDraft(it.days, it.start.toString(), it.end.toString()) }.toMutableStateList()
    }
    val zones = remember { draft.zones.toMutableStateList() }
    val errors = remember { mutableStateListOf<String>() }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        if (windowActive) {
            Text(
                "An active window is running. Changes you save now apply after it ends.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (stored.pending != null) {
            Text("You have saved changes waiting for the current window to end.", style = MaterialTheme.typography.bodyMedium)
        }

        Text("Active time windows", style = MaterialTheme.typography.titleMedium)
        Text("Look Up only runs inside these windows. Times are 24-hour HH:mm.", style = MaterialTheme.typography.bodySmall)
        windows.forEachIndexed { index, window ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEachIndexed { i, day ->
                            FilterChip(
                                selected = day in window.days,
                                onClick = {
                                    val days = if (day in window.days) window.days - day else window.days + day
                                    windows[index] = window.copy(days = days)
                                },
                                label = { Text(DAY_LABELS[i]) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = window.start,
                            onValueChange = { windows[index] = window.copy(start = it) },
                            label = { Text("From") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                        OutlinedTextField(
                            value = window.end,
                            onValueChange = { windows[index] = window.copy(end = it) },
                            label = { Text("To") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                        TextButton(onClick = { windows.removeAt(index) }) { Text("Remove") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { windows += WindowDraft(DayOfWeek.entries.toSet(), "07:00", "23:00") }) {
            Text("Add window")
        }

        Text("Zones", style = MaterialTheme.typography.titleMedium)
        Text("No blocking inside these areas.", style = MaterialTheme.typography.bodySmall)
        zones.forEachIndexed { index, zone ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = zone.name,
                        onValueChange = { zones[index] = zone.copy(name = it) },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Text("%.5f, %.5f".format(zone.latitude, zone.longitude), style = MaterialTheme.typography.bodySmall)
                    Text("Radius ${zone.radiusM} m")
                    Slider(
                        value = zone.radiusM.toFloat(),
                        onValueChange = { zones[index] = zone.copy(radiusM = (it / 10).toInt() * 10) },
                        valueRange = Zone.RADIUS_RANGE_M.first.toFloat()..Zone.RADIUS_RANGE_M.last.toFloat(),
                    )
                    TextButton(onClick = { zones.removeAt(index) }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = {
            message = "Getting your location..."
            addZoneAtCurrentLocation(context) { zone ->
                if (zone == null) {
                    message = "Could not get a location fix. Try again outside or near a window."
                } else {
                    zones += zone.copy(name = "Zone ${zones.size + 1}")
                    message = "Zone added. Rename it and tap Save."
                }
            }
        }) { Text("Add zone at my current location") }

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = {
            errors.clear()
            val parsed = windows.mapIndexedNotNull { i, w ->
                val start = runCatching { LocalTime.parse(w.start) }.getOrNull()
                val end = runCatching { LocalTime.parse(w.end) }.getOrNull()
                when {
                    start == null || end == null -> null.also { errors += "Window ${i + 1}: use HH:mm, e.g. 07:30." }
                    w.days.isEmpty() -> null.also { errors += "Window ${i + 1}: pick at least one day." }
                    start == end -> null.also { errors += "Window ${i + 1}: start and end must differ." }
                    else -> TimeWindow(w.days, start, end)
                }
            }
            if (errors.isEmpty()) {
                SettingsRepository.edit(context, UserSettings(Schedule(parsed), zones.toList()))
                onBack()
            }
        }) { Text("Save") }
    }
}

@SuppressLint("MissingPermission") // Onboarding guarantees fine location before this screen is reachable.
private fun addZoneAtCurrentLocation(context: android.content.Context, onResult: (Zone?) -> Unit) {
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { location ->
            onResult(location?.let { Zone(UUID.randomUUID().toString(), "Zone", it.latitude, it.longitude) })
        }
        .addOnFailureListener { onResult(null) }
}
