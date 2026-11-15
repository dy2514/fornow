package ai.pleos.fornow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemporaryControlTest {
    private fun settings(value: Int) = mapOf("value" to value.toString())
    @Test fun interruptAdapterRestoresSignedCoolingSnapshot() = runBlocking {
        var actual = -3
        val control = IntegerControl("seatHeat", "seat", "", { actual }, { value -> require(value in -3..3); actual = value }, { 2 })
        val port = AccessoryInterruptPort(control)
        val before = port.read()
        val session = ai.pleos.fornow.core.InterruptSession(listOf(ai.pleos.fornow.core.InterruptPlan(port, before, settings(2))))
        session.apply(); assertEquals(2, actual)
        session.restore(); assertEquals(-3, actual)
        assertEquals(ai.pleos.fornow.core.FanPhase.RESTORED, session.phases[port.id])
    }

    @Test fun manualChangeBetweenEngineCheckAndAdapterWriteIsNotOverwritten() = runBlocking {
        var actual = 3
        var writes = 0
        val control = IntegerControl("wheel", "wheel", "", { actual }, { actual = it; writes++ }, { 1 })
        val original = control.read()
        actual = 2 // Driver changes the value after the engine's read.
        try { control.write(control.target(original), original); fail("Must reject stale state") }
        catch (_: IllegalStateException) { }
        assertEquals(2, actual)
        assertEquals(0, writes)
    }

    @Test fun restorationAlsoChecksTheTemporaryValueImmediatelyBeforeWriting() = runBlocking {
        var actual = 3
        val control = IntegerControl("wheel", "wheel", "", { actual }, { actual = it }, { 1 })
        val original = control.read()
        val temporary = control.target(original)
        control.write(temporary, original)
        assertEquals(1, actual)
        actual = 2
        try { control.write(original, temporary); fail("Must preserve manual setting") }
        catch (_: IllegalStateException) { }
        assertEquals(2, actual)
    }

    @Test fun unchangedTemporarySettingRestoresTheCapturedOriginal() = runBlocking {
        var actual = 3
        val control = IntegerControl("wheel", "wheel", "", { actual }, { actual = it }, { 1 })
        val original = control.read()
        val temporary = control.target(original)
        control.write(temporary, original)
        control.write(original, temporary)
        assertEquals(settings(3), control.read())
    }
}
