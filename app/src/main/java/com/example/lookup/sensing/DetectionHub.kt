package com.example.lookup.sensing

import com.example.lookup.core.Event
import com.example.lookup.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DebugStatus(
    val serviceRunning: Boolean = false,
    val snapshot: Snapshot? = null,
    val lastRawActivity: String = "none yet",
    val stepSensor: String = "not registered",
    val gps: String = "off",
    val vehicleSpeedActive: Boolean = false,
    val windowStatus: String = "unknown",
    val zones: String = "not registered",
)

/** Connects broadcast receivers and the UI to the running service. */
object DetectionHub {
    private val _status = MutableStateFlow(DebugStatus())
    val status: StateFlow<DebugStatus> = _status

    /** Set by DetectionService while it runs; events arriving without a service are only logged. */
    @Volatile var sink: ((Event) -> Unit)? = null

    /** Geofence transitions (zone id, inside) while the service runs. */
    @Volatile var zoneSink: ((String, Boolean) -> Unit)? = null

    fun update(transform: (DebugStatus) -> DebugStatus) {
        _status.value = transform(_status.value)
    }
}
