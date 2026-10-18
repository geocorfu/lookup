package com.example.lookup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedDetectorTest {

    private val detector = VehicleSpeedDetector(VehicleConfig())
    private var now = 0L
    private val changes = mutableListOf<Pair<Long, Boolean>>()

    /** Feeds one GPS sample per second at each listed speed (km/h). */
    private fun drive(vararg kmh: Double, accuracyMps: Float? = 1f) {
        for (speed in kmh) {
            now += 1_000
            detector.onSample(now, speed / 3.6, accuracyMps)?.let { changes += now to it }
        }
    }

    private fun repeatSpeed(kmh: Double, seconds: Int, accuracyMps: Float? = 1f) =
        drive(*DoubleArray(seconds) { kmh }, accuracyMps = accuracyMps)

    @Test
    fun busPullingAwayActivatesAfterTenSecondsAboveThreshold() {
        drive(0.0, 5.0, 12.0, 18.0)
        repeatSpeed(25.0, 10)
        assertFalse(detector.active)
        repeatSpeed(25.0, 1)
        assertTrue(detector.active)
        assertEquals(listOf(15_000L to true), changes)
    }

    @Test
    fun shortGpsSpikeDoesNotActivate() {
        repeatSpeed(5.0, 10)
        repeatSpeed(30.0, 4)
        repeatSpeed(5.0, 10)
        assertFalse(detector.active)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun runningNeverCountsAsVehicle() {
        repeatSpeed(12.0, 120)
        assertFalse(detector.active)
    }

    @Test
    fun redLightStopKeepsVehicleActive() {
        repeatSpeed(30.0, 11)
        assertTrue(detector.active)
        repeatSpeed(0.0, 45)
        assertTrue(detector.active)
        repeatSpeed(30.0, 20)
        assertTrue(detector.active)
    }

    @Test
    fun crawlingInTrafficKeepsVehicleActiveIndefinitely() {
        repeatSpeed(30.0, 11)
        repeatSpeed(15.0, 300)
        assertTrue(detector.active)
    }

    @Test
    fun gettingOffAndWalkingReleasesAfterSixtySlowSeconds() {
        repeatSpeed(30.0, 11)
        repeatSpeed(0.0, 10)
        repeatSpeed(5.0, 50)
        assertTrue(detector.active)
        repeatSpeed(5.0, 1)
        assertFalse(detector.active)
        assertEquals(false, changes.last().second)
    }

    @Test
    fun inaccurateSpeedSamplesAreIgnored() {
        repeatSpeed(40.0, 30, accuracyMps = 8f)
        assertFalse(detector.active)
    }

    @Test
    fun samplesWithoutAccuracyAreAccepted() {
        repeatSpeed(40.0, 11, accuracyMps = null)
        assertTrue(detector.active)
    }

    @Test
    fun gapInSamplesRestartsTheActivationTimer() {
        repeatSpeed(30.0, 8)
        now += 10_000
        repeatSpeed(30.0, 8)
        assertFalse(detector.active)
        repeatSpeed(30.0, 3)
        assertTrue(detector.active)
    }

    @Test
    fun losingGpsWhileActiveReleasesAfterSixtySeconds() {
        repeatSpeed(30.0, 11)
        now += 59_000
        assertNull(detector.onTick(now))
        now += 1_000
        assertEquals(false, detector.onTick(now))
        assertFalse(detector.active)
    }

    @Test
    fun feedsStateMachineSoBumpsOnABusNeverBlock() {
        val machine = DetectionStateMachine(DetectionConfig())
        repeatSpeed(30.0, 11)
        machine.handle(Event.VehicleSpeedDetected(detector.active), now)
        repeat(30) {
            now += 500
            machine.handle(Event.StepDetected, now)
        }
        assertEquals(DetectionState.IDLE, machine.snapshot().state)
    }
}
