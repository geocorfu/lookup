package com.example.lookup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DetectionConfigTest {
    @Test
    fun defaultsMatchSpec() {
        val config = DetectionConfig()
        assertEquals(10, config.stepThreshold)
        assertEquals(10, config.stillSeconds)
    }

    @Test
    fun valuesOutsideAllowedRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(stepThreshold = 4) }
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(stepThreshold = 31) }
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(stillSeconds = 4) }
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(stillSeconds = 31) }
    }
}
