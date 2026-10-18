package com.example.lookup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lookup.sensing.DetectionHub
import com.example.lookup.sensing.DetectionService
import com.example.lookup.settings.WindowScheduler

@Composable
fun DebugScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val status by DetectionHub.status.collectAsState()
    val snapshot = status.snapshot

    LaunchedEffect(Unit) { WindowScheduler.sync(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Detection debug", style = MaterialTheme.typography.titleMedium)
        Text(
            snapshot?.state?.name ?: "NO DATA",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        snapshot?.countdownRemainingMs?.let { ms ->
            Text("Stand still... ${(ms + 999) / 1_000}", style = MaterialTheme.typography.headlineMedium)
        }
        Field("Steps since walk start", snapshot?.stepsSinceWalkStart?.toString() ?: "-")
        Field("Last raw activity", status.lastRawActivity)
        Field("Activity used", snapshot?.lastActivity?.name ?: "-")
        Field("Suppressed (vehicle/zone/window)", snapshot?.suppressed?.toString() ?: "-")
        Field("GPS", status.gps)
        Field("Vehicle by speed", status.vehicleSpeedActive.toString())
        Field("Active window", status.windowStatus)
        Field("Zones", status.zones)
        Field("Service running", status.serviceRunning.toString())
        Field("Step sensor", status.stepSensor)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { WindowScheduler.sync(context) }) { Text("Sync") }
            OutlinedButton(onClick = { DetectionService.stop(context) }) { Text("Stop (debug)") }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
