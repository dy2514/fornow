package ai.pleos.fornow.core

import org.junit.Assert.*
import org.junit.Test

class InterruptEngineTest {
    private fun frame(time: Long, window: Int? = 0, speed: Float? = 0f, d: Boolean? = true, system: Boolean = false, brake: Float? = null) =
        DriveFrame(time, d, speed, window, brake, system)
    private fun drive() = InterruptEngine().also { it.drive(frame(0)); assertNotNull(it.drive(frame(250, 50))) }

    @Test fun ringingAndOffhookEnterImmediately() {
        for (signal in listOf(CallSignal.RINGING, CallSignal.OFFHOOK, CallSignal.PRESENT)) {
            val e = InterruptEngine(); assertEquals(InterruptMode.STATE_INTERRUPT_CALL, e.call(signal, 0)!!.to)
            assertNull(e.call(signal, 100)); assertEquals(TtsPolicy.MUTE_TO_TOAST, e.ttsPolicy)
        }
    }
    @Test fun idleRequiresFull1500ms() {
        val e = InterruptEngine(); e.call(CallSignal.RINGING, 0)
        for (t in listOf(100L, 600L, 1100L, 1599L)) assertNull(e.call(CallSignal.IDLE, t))
        assertEquals(ExitReason.CALL_ENDED, e.call(CallSignal.IDLE, 1600)!!.reason)
    }
    @Test fun newCallCancelsPendingRollback() {
        val e = InterruptEngine(); e.call(CallSignal.OFFHOOK, 0); e.call(CallSignal.IDLE, 100)
        e.call(CallSignal.RINGING, 900); e.call(CallSignal.IDLE, 1000)
        e.call(CallSignal.IDLE, 1500); assertNull(e.call(CallSignal.IDLE, 2000))
        assertNotNull(e.call(CallSignal.IDLE, 2500))
    }
    @Test fun missingCallSignalDoesNotMeanIdle() {
        val e = InterruptEngine(); e.call(CallSignal.OFFHOOK, 0); e.call(CallSignal.IDLE, 100)
        e.call(CallSignal.UNKNOWN, 600); e.call(CallSignal.IDLE, 1100)
        assertNull(e.call(CallSignal.IDLE, 1600)); assertEquals(InterruptMode.STATE_INTERRUPT_CALL, e.mode)
    }
    @Test fun missingSamplesDoNotCountAsContinuousIdle() {
        val e = InterruptEngine(); e.call(CallSignal.OFFHOOK, 0); e.call(CallSignal.IDLE, 100)
        assertNull(e.call(CallSignal.IDLE, 5000))
    }
    @Test fun openingRequiresAllFourConditions() {
        val cases = listOf(frame(250, 50, 16f), frame(250, 50, 0f, false), frame(250, 50, null), frame(250, 50, 0f, true, true))
        for (f in cases) { val e = InterruptEngine(); e.drive(frame(0)); assertNull(e.drive(f)) }
        assertEquals(InterruptMode.STATE_INTERRUPT_DRIVETHRU, drive().mode)
    }
    @Test fun actualPedalPercentageCanSatisfyAlternative() {
        val e = InterruptEngine(); e.drive(frame(0)); assertNotNull(e.drive(frame(250, 50, 20f, brake = 10f)))
        val n = InterruptEngine(); n.drive(frame(0)); assertNull(n.drive(frame(250, 50, 20f, brake = 9.9f)))
    }
    @Test fun strictWindowThresholdAndDelta() {
        val e = InterruptEngine(); e.drive(frame(0, 20)); assertNull(e.drive(frame(250, 50)))
        val n = InterruptEngine(); n.drive(frame(0, 19)); assertNull(n.drive(frame(250, 49))); assertNotNull(n.drive(frame(500, 50)))
    }
    @Test fun openingMustOccurWithinThreeSeconds() {
        val e = InterruptEngine(); e.drive(frame(0, 0))
        for (t in 500L..3000L step 500) e.drive(frame(t, 30))
        assertNull(e.drive(frame(3001, 50)))
    }
    @Test fun boundaryThreeSecondsAcceptedWithContinuousSamples() {
        val e = InterruptEngine(); e.drive(frame(0))
        for (t in 500L..2500L step 500) e.drive(frame(t, 30))
        assertNotNull(e.drive(frame(3000, 50, 15f)))
    }
    @Test fun interruptedSamplingResetsOpeningEvidence() {
        val e = InterruptEngine(); e.drive(frame(0)); assertNull(e.drive(frame(2000, 50)))
        val n = InterruptEngine(); n.drive(frame(1000)); assertNull(n.drive(frame(900, 50)))
    }
    @Test fun openingAtWrongGearCannotTriggerLater() {
        val e = InterruptEngine(); e.drive(frame(0)); e.drive(frame(250, 50, d = false))
        assertNull(e.drive(frame(500, 50, d = true)))
    }
    @Test fun windowAtTenExitsImmediately() { assertEquals(ExitReason.WINDOW_CLOSED, drive().drive(frame(500, 10))!!.reason) }
    @Test fun speedThirtyRequiresFiveContinuousSeconds() {
        val e = drive()
        for (t in 500L..5000L step 500) assertNull(e.drive(frame(t, 70, 30f)))
        assertEquals(ExitReason.DRIVING_RESUMED, e.drive(frame(5500, 70, 30f))!!.reason)
    }
    @Test fun speedDipOrUnknownResetsFiveSecondTimer() {
        for (speed in listOf(29.9f, null, Float.NaN)) {
            val e = drive()
            for (t in 500L..4000L step 500) e.drive(frame(t, 70, 35f))
            e.drive(frame(4500, 70, speed)); assertNull(e.drive(frame(5000, 70, 35f))); assertNull(e.drive(frame(5500, 70, 35f)))
        }
    }
    @Test fun callPreemptsDriveWithoutNestedMode() {
        val e = drive(); val change = e.call(CallSignal.RINGING, 500)!!
        assertEquals(ExitReason.CALL_PRIORITY, change.reason)
        assertEquals(InterruptMode.STATE_INTERRUPT_DRIVETHRU, change.from)
        assertNull(e.drive(frame(600, 0)))
    }
    @Test fun callReturnDoesNotReuseOldWindowOpening() {
        val e = InterruptEngine(); e.drive(frame(0)); e.call(CallSignal.RINGING, 100)
        for (t in 200L..1700L step 500) e.call(CallSignal.IDLE, t)
        assertNull(e.drive(frame(1800, 60))); assertEquals(InterruptMode.STATE_NORMAL, e.mode)
    }
    @Test fun resetClearsAllEvidence() {
        val e = drive(); e.reset(); assertEquals(TtsPolicy.NORMAL, e.ttsPolicy)
        assertNull(e.drive(frame(600, 70)))
    }
    @Test fun fanDefaultClampAndNoIncrease() {
        assertEquals(2, InterruptPolicy.callFan(7, 1, 8)); assertEquals(1, InterruptPolicy.callFan(1, 1, 8))
        assertEquals(3, InterruptPolicy.callFan(7, 3, 8)); assertEquals(4, InterruptPolicy.callFan(4, 1, 8, 9))
    }
    @Test fun thermalBoundariesAndColdAcConflict() {
        assertEquals(ThermalMode.HEATING, InterruptPolicy.callThermal(10f, true))
        assertEquals(ThermalMode.COOLING, InterruptPolicy.callThermal(24f))
        assertEquals(ThermalMode.NONE, InterruptPolicy.callThermal(null))
        assertEquals(ThermalMode.NONE, InterruptPolicy.callThermal(18f, true, true))
        assertEquals(ThermalMode.HEATING, InterruptPolicy.driveThermal(12f))
        assertEquals(ThermalMode.COOLING, InterruptPolicy.driveThermal(26f))
    }
    @Test fun temperatureRespectsHardwareAndSixteenDegreeFloor() {
        assertEquals(20.5f, InterruptPolicy.temperature(22f, ThermalMode.COOLING, 16f, 30f, .5f))
        assertEquals(16f, InterruptPolicy.temperature(17f, ThermalMode.COOLING, 16f, 30f, .5f))
        assertEquals(30f, InterruptPolicy.temperature(29.5f, ThermalMode.HEATING, 16f, 30f, .5f))
        assertEquals(16.4f, InterruptPolicy.temperature(16.4f, ThermalMode.COOLING, 15.7f, 29.7f, .7f), .001f)
    }
    @Test fun seatStagesAreExclusiveAndNeverReduceStrongerStage() {
        assertEquals(mapOf("heat" to "0", "vent" to "3"), InterruptPolicy.seat(1, 3, ThermalMode.COOLING, 2, 3, 3))
        assertEquals(mapOf("heat" to "2", "vent" to "0"), InterruptPolicy.seat(0, 2, ThermalMode.HEATING, 3, 2, 3))
    }
    @Test fun signedSeatTemperatureSupportsCoolingAndHardwareBounds() {
        assertEquals(-2, InterruptPolicy.signedSeat(1, ThermalMode.COOLING, 2, -3, 3))
        assertEquals(-3, InterruptPolicy.signedSeat(-3, ThermalMode.COOLING, 2, -3, 3))
        assertEquals(2, InterruptPolicy.signedSeat(-3, ThermalMode.HEATING, 3, -3, 2))
        assertEquals(-3, InterruptPolicy.signedSeat(-3, ThermalMode.NONE, 3, -3, 3))
    }
}
