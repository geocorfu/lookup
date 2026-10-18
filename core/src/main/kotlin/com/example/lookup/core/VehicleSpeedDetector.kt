package com.example.lookup.core

/** All vehicle-speed tuning values live here. Initial guesses; tune from bus/car field logs. */
data class VehicleConfig(
    /** Above this for [onDurationMs] counts as a vehicle. Running tops out around 12–15 km/h. */
    val onSpeedKmh: Double = 20.0,
    val onDurationMs: Long = 10_000,
    /** Below this for [offDurationMs] means the vehicle has stopped carrying you. Covers red lights. */
    val offSpeedKmh: Double = 10.0,
    val offDurationMs: Long = 60_000,
    /** Samples whose reported speed accuracy is worse than this are ignored. */
    val maxSpeedAccuracyMps: Float = 3f,
    /** A gap longer than this between samples restarts the activation timer. */
    val maxSampleGapMs: Long = 5_000,
)

/**
 * Turns GPS speed samples into a sustained "vehicle movement" signal with hysteresis.
 * Between offSpeed and onSpeed (crawling traffic) the current state is held.
 */
class VehicleSpeedDetector(private val config: VehicleConfig = VehicleConfig()) {

    var active = false
        private set

    private var fastSinceMs: Long? = null
    private var slowSinceMs: Long? = null
    private var lastSampleMs: Long? = null

    /** Returns the new value of [active] if it changed, otherwise null. */
    fun onSample(nowMs: Long, speedMps: Double, speedAccuracyMps: Float?): Boolean? {
        if (speedAccuracyMps != null && speedAccuracyMps > config.maxSpeedAccuracyMps) return null
        val previous = lastSampleMs
        lastSampleMs = nowMs
        if (previous != null && nowMs - previous > config.maxSampleGapMs) fastSinceMs = null

        val kmh = speedMps * 3.6
        when {
            kmh >= config.onSpeedKmh -> {
                slowSinceMs = null
                val since = fastSinceMs ?: nowMs.also { fastSinceMs = it }
                if (!active && nowMs - since >= config.onDurationMs) return set(true)
            }
            kmh < config.offSpeedKmh -> {
                fastSinceMs = null
                val since = slowSinceMs ?: nowMs.also { slowSinceMs = it }
                if (active && nowMs - since >= config.offDurationMs) return set(false)
            }
            else -> {
                fastSinceMs = null
                slowSinceMs = null
            }
        }
        return null
    }

    /** Releases the vehicle signal when GPS has gone silent for [VehicleConfig.offDurationMs]. */
    fun onTick(nowMs: Long): Boolean? {
        val last = lastSampleMs ?: return null
        return if (active && nowMs - last >= config.offDurationMs) set(false) else null
    }

    private fun set(value: Boolean): Boolean {
        active = value
        fastSinceMs = null
        slowSinceMs = null
        return value
    }
}
