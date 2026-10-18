package com.example.lookup.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lookup.core.Activity
import com.example.lookup.core.Event
import com.example.lookup.log.EventLog
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (transition in result.transitionEvents) {
            val raw = "${activityName(transition.activityType)} ${transitionName(transition.transitionType)}"
            EventLog.log("AR raw: $raw (elapsed ${transition.elapsedRealTimeNanos / 1_000_000}ms)")
            DetectionHub.update { it.copy(lastRawActivity = raw) }
            val activity = toCoreActivity(transition.activityType, transition.transitionType) ?: continue
            DetectionHub.sink?.invoke(Event.ActivityChanged(activity))
                ?: EventLog.log("AR event dropped: service not running")
        }
    }

    companion object {
        val MONITORED = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
        )

        /** Only ENTER transitions change the activity, except leaving a vehicle. */
        fun toCoreActivity(type: Int, transition: Int): Activity? {
            if (transition == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                return if (type == DetectedActivity.IN_VEHICLE) Activity.OTHER else null
            }
            return when (type) {
                DetectedActivity.WALKING, DetectedActivity.RUNNING -> Activity.WALKING
                DetectedActivity.STILL -> Activity.STILL
                DetectedActivity.IN_VEHICLE -> Activity.IN_VEHICLE
                else -> Activity.OTHER
            }
        }

        fun activityName(type: Int) = when (type) {
            DetectedActivity.WALKING -> "WALKING"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.ON_FOOT -> "ON_FOOT"
            else -> "TYPE_$type"
        }

        private fun transitionName(type: Int) =
            if (type == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"
    }
}
