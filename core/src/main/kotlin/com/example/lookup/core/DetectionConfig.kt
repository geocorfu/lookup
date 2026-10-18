package com.example.lookup.core

data class DetectionConfig(
    val stepThreshold: Int = 10,
    val stillSeconds: Int = 10,
    /** Step silence after which BLOCKING shows the still countdown. Tune in field tests. */
    val stillGapMs: Long = 2_000,
    /** Step silence after which an unconfirmed walk is discarded. Tune in field tests. */
    val candidateResetMs: Long = 3_000,
) {
    init {
        require(stepThreshold in STEP_THRESHOLD_RANGE) { "stepThreshold must be in $STEP_THRESHOLD_RANGE" }
        require(stillSeconds in STILL_SECONDS_RANGE) { "stillSeconds must be in $STILL_SECONDS_RANGE" }
    }

    val stillMs: Long get() = stillSeconds * 1_000L

    companion object {
        val STEP_THRESHOLD_RANGE = 5..30
        val STILL_SECONDS_RANGE = 5..30
    }
}
