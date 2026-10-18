package com.example.lookup.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lookup.log.EventLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            EventLog.log("geofence error ${event.errorCode}")
            return
        }
        val inside = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        for (fence in event.triggeringGeofences.orEmpty()) {
            EventLog.log("geofence ${fence.requestId} ${if (inside) "ENTER" else "EXIT"}")
            DetectionHub.zoneSink?.invoke(fence.requestId, inside)
                ?: EventLog.log("geofence event dropped: service not running")
        }
    }
}
