package com.example.lookup.core

enum class Activity { WALKING, STILL, IN_VEHICLE, OTHER }

sealed interface Event {
    data object StepDetected : Event
    data class ActivityChanged(val activity: Activity) : Event
    data class VehicleSpeedDetected(val active: Boolean) : Event
    data class ZoneChanged(val inside: Boolean) : Event
    data class ActiveWindowChanged(val active: Boolean) : Event
    /** Lets time-based transitions fire when no other event arrives. */
    data object Tick : Event
}
