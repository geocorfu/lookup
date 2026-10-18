package com.example.lookup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class PermissionStep(
    val title: String,
    val explanation: String,
    val permissions: List<String>,
)

/** Requested in this order, as SPEC.md and the build guide describe. */
val PERMISSION_STEPS = listOf(
    PermissionStep(
        title = "Physical activity",
        explanation = "Look Up counts your steps and recognises whether you are walking, standing " +
            "still, or riding in a vehicle. Android calls this \"physical activity\".",
        permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
    ),
    PermissionStep(
        title = "Notifications",
        explanation = "Detection runs as a foreground service, and Android shows a small permanent " +
            "notification while it runs. Allow notifications so you can see it.",
        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
    ),
    PermissionStep(
        title = "Location",
        explanation = "Location will tell a bus from a walk by speed, and recognise your Home and " +
            "Work zones. Nothing ever leaves the phone.",
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    ),
    PermissionStep(
        title = "Location all the time",
        explanation = "Home and Work zones and scheduled starts only work if Android lets Look Up use " +
            "location in the background. On the next screen choose \"Allow all the time\".",
        permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    ),
)

fun firstMissingStep(context: Context): PermissionStep? = PERMISSION_STEPS.firstOrNull { step ->
    step.permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}

@Composable
fun OnboardingScreen(step: PermissionStep, onChanged: () -> Unit) {
    val context = LocalContext.current
    var denied by remember(step) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        onChanged()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(step.title, style = MaterialTheme.typography.headlineMedium)
        Text(step.explanation, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { launcher.launch(step.permissions.toTypedArray()) }) { Text("Allow") }
        if (denied) {
            Text(
                "Android did not grant it. Open the app settings, allow it there, then tap Check again.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Open app settings") }
            OutlinedButton(onClick = onChanged) { Text("Check again") }
        }
    }
}
