package com.example.lookup.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionConfigTest {
    @Test
    fun defaultsMatchSpec() {
        val config = DetectionConfig()
        assertEquals(10, config.stepThreshold)
        assertEquals(10, config.stillSeconds)
    }
}
