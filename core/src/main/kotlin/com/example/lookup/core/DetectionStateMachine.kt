package com.example.lookup.core

enum class DetectionState { IDLE, WALKING_CANDIDATE, BLOCKING, STILL_COUNTDOWN }

data class Snapshot(
    val state: DetectionState,
    val stepsSinceWalkStart: Int,
    val countdownRemainingMs: Long?,
    val lastActivity: Activity,
    val suppressed: Boolean,
) {
    val isBlocking: Boolean
        get() = state == DetectionState.BLOCKING || state == DetectionState.STILL_COUNTDOWN
}

/**
 * Pure detection logic. Steps drive walking and stillness timing; a vehicle, a zone,
 * or being outside the active window suppresses blocking entirely.
 * Time is passed in with every event so behavior is deterministic.
 */
class DetectionStateMachine(
    private val config: DetectionConfig = DetectionConfig(),
    windowActive: Boolean = true,
) {
    private var state = DetectionState.IDLE
    private var steps = 0
    private var lastStepMs = 0L
    private var nowMs = 0L
    private var activity = Activity.OTHER
    private var vehicleSpeed = false
    private var inZone = false
    private var windowActive = windowActive

    private val suppressed: Boolean
        get() = activity == Activity.IN_VEHICLE || vehicleSpeed || inZone || !windowActive

    fun handle(event: Event, nowMs: Long): Snapshot {
        this.nowMs = nowMs
        applyTimeouts()
        when (event) {
            Event.StepDetected -> onStep()
            is Event.ActivityChanged -> activity = event.activity
            is Event.VehicleSpeedDetected -> vehicleSpeed = event.active
            is Event.ZoneChanged -> inZone = event.inside
            is Event.ActiveWindowChanged -> windowActive = event.active
            Event.Tick -> Unit
        }
        if (suppressed) reset()
        return snapshot()
    }

    fun snapshot(): Snapshot = Snapshot(
        state = state,
        stepsSinceWalkStart = steps,
        countdownRemainingMs = if (state == DetectionState.STILL_COUNTDOWN) {
            (config.stillMs - (nowMs - lastStepMs)).coerceAtLeast(0)
        } else {
            null
        },
        lastActivity = activity,
        suppressed = suppressed,
    )

    private fun onStep() {
        if (suppressed) return
        lastStepMs = nowMs
        steps++
        state = when (state) {
            DetectionState.IDLE -> DetectionState.WALKING_CANDIDATE
            DetectionState.WALKING_CANDIDATE ->
                if (steps >= config.stepThreshold) DetectionState.BLOCKING else DetectionState.WALKING_CANDIDATE
            DetectionState.BLOCKING, DetectionState.STILL_COUNTDOWN -> DetectionState.BLOCKING
        }
    }

    private fun applyTimeouts() {
        val silence = nowMs - lastStepMs
        when (state) {
            DetectionState.IDLE -> Unit
            DetectionState.WALKING_CANDIDATE -> if (silence >= config.candidateResetMs) reset()
            DetectionState.BLOCKING, DetectionState.STILL_COUNTDOWN -> when {
                silence >= config.stillMs -> reset()
                silence >= config.stillGapMs -> state = DetectionState.STILL_COUNTDOWN
            }
        }
    }

    private fun reset() {
        state = DetectionState.IDLE
        steps = 0
    }
}
