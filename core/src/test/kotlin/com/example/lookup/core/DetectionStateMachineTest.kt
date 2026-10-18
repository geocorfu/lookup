package com.example.lookup.core

import com.example.lookup.core.DetectionState.BLOCKING
import com.example.lookup.core.DetectionState.IDLE
import com.example.lookup.core.DetectionState.STILL_COUNTDOWN
import com.example.lookup.core.DetectionState.WALKING_CANDIDATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionStateMachineTest {

    private var machine = DetectionStateMachine(DetectionConfig())
    private var now = 0L

    private fun steps(count: Int, intervalMs: Long = 500): Snapshot {
        var snapshot = machine.snapshot()
        repeat(count) {
            now += intervalMs
            snapshot = machine.handle(Event.StepDetected, now)
        }
        return snapshot
    }

    private fun stand(ms: Long, tickEveryMs: Long = 1_000): Snapshot {
        var snapshot = machine.snapshot()
        var remaining = ms
        while (remaining > 0) {
            val dt = minOf(tickEveryMs, remaining)
            now += dt
            remaining -= dt
            snapshot = machine.handle(Event.Tick, now)
        }
        return snapshot
    }

    private fun send(event: Event): Snapshot = machine.handle(event, now)

    private fun startBlocking(): Snapshot {
        val snapshot = steps(10)
        assertEquals(BLOCKING, snapshot.state)
        return snapshot
    }

    @Test
    fun normalWalkThenStopUnblocksAfterTenStillSeconds() {
        assertEquals(WALKING_CANDIDATE, steps(9).state)
        assertEquals(9, machine.snapshot().stepsSinceWalkStart)

        val blocking = steps(1)
        assertEquals(BLOCKING, blocking.state)
        assertTrue(blocking.isBlocking)

        val countdown = stand(2_000)
        assertEquals(STILL_COUNTDOWN, countdown.state)
        assertTrue(countdown.isBlocking)
        assertEquals(8_000L, countdown.countdownRemainingMs)

        assertEquals(STILL_COUNTDOWN, stand(7_000).state)
        assertEquals(1_000L, machine.snapshot().countdownRemainingMs)

        val released = stand(1_000)
        assertEquals(IDLE, released.state)
        assertFalse(released.isBlocking)
        assertEquals(0, released.stepsSinceWalkStart)
    }

    @Test
    fun flickerBelowThresholdNeverBlocks() {
        assertEquals(WALKING_CANDIDATE, steps(5).state)
        assertEquals(IDLE, stand(3_000).state)

        val again = steps(5)
        assertEquals(WALKING_CANDIDATE, again.state)
        assertEquals(5, again.stepsSinceWalkStart)
    }

    @Test
    fun stepDuringCountdownRestartsTheFullStillPeriod() {
        startBlocking()
        assertEquals(STILL_COUNTDOWN, stand(5_000).state)

        assertEquals(BLOCKING, steps(1).state)

        assertEquals(STILL_COUNTDOWN, stand(9_000).state)
        assertEquals(IDLE, stand(1_000).state)
    }

    @Test
    fun activityStillAloneDoesNotRelease() {
        startBlocking()
        val snapshot = send(Event.ActivityChanged(Activity.STILL))
        assertEquals(BLOCKING, snapshot.state)
        assertEquals(Activity.STILL, snapshot.lastActivity)
    }

    @Test
    fun enteringVehicleReleasesImmediatelyAndStaysReleased() {
        startBlocking()
        assertEquals(IDLE, send(Event.ActivityChanged(Activity.IN_VEHICLE)).state)

        val bumps = steps(20)
        assertEquals(IDLE, bumps.state)
        assertTrue(bumps.suppressed)

        send(Event.ActivityChanged(Activity.WALKING))
        assertEquals(BLOCKING, steps(10).state)
    }

    @Test
    fun vehicleSpeedReleasesAndBothVehicleSignalsMustClear() {
        startBlocking()
        assertEquals(IDLE, send(Event.VehicleSpeedDetected(true)).state)

        send(Event.ActivityChanged(Activity.IN_VEHICLE))
        send(Event.ActivityChanged(Activity.WALKING))
        assertEquals(IDLE, steps(15).state)

        send(Event.VehicleSpeedDetected(false))
        assertEquals(BLOCKING, steps(10).state)
    }

    @Test
    fun enteringZoneMidWalkReleasesAndNeverBlocksInside() {
        steps(5)
        assertEquals(IDLE, send(Event.ZoneChanged(inside = true)).state)
        assertEquals(IDLE, steps(30).state)

        send(Event.ZoneChanged(inside = false))
        assertEquals(BLOCKING, steps(10).state)
    }

    @Test
    fun leavingActiveWindowMidBlockReleasesImmediately() {
        startBlocking()
        assertEquals(IDLE, send(Event.ActiveWindowChanged(active = false)).state)
        assertEquals(IDLE, steps(30).state)

        send(Event.ActiveWindowChanged(active = true))
        assertEquals(BLOCKING, steps(10).state)
    }

    @Test
    fun startingOutsideWindowDoesNotBlock() {
        machine = DetectionStateMachine(DetectionConfig(), windowActive = false)
        assertEquals(IDLE, steps(30).state)
    }

    @Test
    fun stepAfterLongSilenceWithoutTicksStartsANewWalk() {
        startBlocking()
        now += 15_000
        val snapshot = machine.handle(Event.StepDetected, now)
        assertEquals(WALKING_CANDIDATE, snapshot.state)
        assertEquals(1, snapshot.stepsSinceWalkStart)
    }

    @Test
    fun countdownIsNullOutsideStillCountdown() {
        assertNull(steps(3).countdownRemainingMs)
        assertNull(steps(7).countdownRemainingMs)
    }

    @Test
    fun thresholdAndStillDurationAreConfigurable() {
        machine = DetectionStateMachine(DetectionConfig(stepThreshold = 5, stillSeconds = 5))
        assertEquals(BLOCKING, steps(5).state)
        assertEquals(STILL_COUNTDOWN, stand(4_000).state)
        assertEquals(IDLE, stand(1_000).state)
    }
}
