package com.example.lookup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.lookup.log.EventLog
import com.example.lookup.ui.DebugScreen
import com.example.lookup.ui.OnboardingScreen
import com.example.lookup.ui.PermissionStep
import com.example.lookup.ui.SettingsScreen
import com.example.lookup.ui.firstMissingStep

class MainActivity : ComponentActivity() {

    private var missingStep by mutableStateOf<PermissionStep?>(null)
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        refresh()
        setContent {
            MaterialTheme {
                Surface {
                    val step = missingStep
                    when {
                        step != null -> OnboardingScreen(step, onChanged = ::refresh)
                        showSettings -> SettingsScreen(onBack = { showSettings = false })
                        else -> DebugScreen(onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        missingStep = firstMissingStep(this)
    }
}
