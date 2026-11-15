package ai.pleos.fornow.core

import org.junit.Assert.*
import org.junit.Test

class FanSessionTest {
    private val original = ClimateSnapshot(5, true, true, 23f, "FACE", false)
    private fun active(snapshot: ClimateSnapshot = original) = FanSession(snapshot, 1, 60_000, "test").apply {
        assertTrue(confirmApplied(expected, 1_000))
    }

    @Test fun timerStartsOnlyAfterReadbackAndRestoresAuto() {
        val s = FanSession(original, 1, 60_000, "a")
        assertFalse(s.isDue(999_999))
        assertTrue(s.confirmApplied(s.expected, 5_000))
        assertFalse(s.isDue(64_999)); assertTrue(s.isDue(65_000))
        assertTrue(s.beginRestore(s.expected))
        assertTrue(s.confirmRestored(original.copy(fan = 3)))
    }
    @Test fun manualFanChangePermanentlyCancelsRestoreEvenIfValueChangesBack() {
        val s = active(); s.observe(s.expected.copy(fan = 3)); s.observe(s.expected)
        assertEquals(FanPhase.RELEASED, s.phase); assertFalse(s.beginRestore(s.expected))
    }
    @Test fun manualTemperatureChangeCancelsWholeTransaction() {
        val s = active(); s.observe(s.expected.copy(temperature = 24f))
        assertFalse(s.beginRestore(s.expected)); assertEquals(FanPhase.RELEASED, s.phase)
    }
    @Test fun autoPowerDirectionAndDefrostChangesCancelRestore() {
        listOf<(ClimateSnapshot) -> ClimateSnapshot>(
            { it.copy(auto = true) }, { it.copy(power = false) },
            { it.copy(direction = "FLOOR") }, { it.copy(defrost = true) }
        ).forEach { change -> val s = active(); s.observe(change(s.expected)); assertFalse(s.beginRestore(s.expected)) }
    }
    @Test fun finalReadPreventsOverwritingLastMomentChange() {
        val s = active(); assertTrue(s.isDue(61_000))
        assertFalse(s.beginRestore(s.expected.copy(fan = 4)))
    }
    @Test fun unchangedCallbacksDoNotCancelAndDoubleRestoreIsRejected() {
        val s = active(); repeat(20) { s.observe(s.expected) }
        assertTrue(s.beginRestore(s.expected)); assertFalse(s.beginRestore(s.expected))
    }
    @Test fun manualModeMustRestoreTheExactFanSpeed() {
        val s = active(original.copy(auto = false)); assertTrue(s.beginRestore(s.expected))
        assertFalse(s.confirmRestored(original.copy(auto = false, fan = 4)))
        assertEquals(FanPhase.FAILED, s.phase)
    }
    @Test fun keepCurrentSettingNeverRestores() {
        val s = active(); s.release(); assertFalse(s.isDue(Long.MAX_VALUE)); assertFalse(s.beginRestore(s.expected))
    }
    @Test fun lostConnectionNeverRestoresFromStaleState() {
        val s = active(); s.interrupt("connection lost")
        assertFalse(s.beginRestore(s.expected)); assertEquals(FanPhase.INTERRUPTED, s.phase)
    }
    @Test fun invalidOrUnconfirmedWritesNeverStartTimer() {
        val s = FanSession(original, 1, 60_000, "a")
        assertFalse(s.confirmApplied(original, 100)); assertFalse(s.isDue(100_000))
    }
    @Test fun defrostAndPoweredOffSessionsAreRejected() {
        listOf(original.copy(defrost = true), original.copy(direction = "DEFROST_AND_FLOOR"),
            original.copy(power = false), original.copy(temperature = Float.NaN)).forEach {
            assertThrows(IllegalArgumentException::class.java) { FanSession(it, 1, 60_000, "a") }
        }
    }
    @Test fun noOpAndInvalidDurationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FanSession(original, 5, 60_000, "a") }
        assertThrows(IllegalArgumentException::class.java) { FanSession(original, 0, 60_000, "a") }
        assertThrows(IllegalArgumentException::class.java) { FanSession(original, 1, 0, "a") }
    }
}
