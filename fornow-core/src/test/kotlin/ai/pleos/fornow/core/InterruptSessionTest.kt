package ai.pleos.fornow.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class InterruptSessionTest {
    private class Port(override val id: String = "climate") : InterruptPort {
        var value = mapOf("fan" to "6", "auto" to "true")
        var failSecondStep = false
        var unavailable = false
        val writes = mutableListOf<InterruptValues>()
        override suspend fun read(): InterruptValues { check(!unavailable); return value }
        override suspend fun write(target: InterruptValues, expected: InterruptValues, confirmed: (InterruptValues) -> Unit) {
            check(value == expected)
            value = value + ("auto" to target.getValue("auto")); writes += value; confirmed(value)
            if (failSecondStep) { failSecondStep = false; error("fan setter failed") }
            value = target; writes += value; confirmed(value)
        }
    }
    private val target = mapOf("fan" to "2", "auto" to "false")
    private fun session(p: Port) = InterruptSession(listOf(InterruptPlan(p, p.value.toMap(), target)))
    private fun run(block: suspend () -> Unit) {
        var result: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> { override val context = EmptyCoroutineContext; override fun resumeWith(r: Result<Unit>) { result = r } })
        checkNotNull(result).getOrThrow()
    }
    @Test fun completeSnapshotAndRestore() = run {
        val p = Port(); val s = session(p); val original = p.value
        s.apply(); assertEquals(target, p.value); assertEquals(original, s.snapshot.values[p.id])
        s.restore(); assertEquals(original, p.value); assertEquals(FanPhase.RESTORED, s.phases[p.id])
    }
    @Test fun partialFailureRestoresAcknowledgedAutoChange() = run {
        val p = Port(); val original = p.value; p.failSecondStep = true
        val s = session(p); s.apply(); assertEquals(original, p.value); assertTrue(s.failures.isNotEmpty())
    }
    @Test fun manualChangeIsNeverOverwrittenEvenIfItReturnsToTarget() = run {
        val p = Port(); val s = session(p); s.apply()
        s.observe(p.id, "fan", "4"); p.value = target
        val count = p.writes.size; s.restore(); assertEquals(count, p.writes.size); assertEquals(FanPhase.RELEASED, s.phases[p.id])
    }
    @Test fun changeBetweenSnapshotAndApplicationIsProtected() = run {
        val p = Port(); val s = session(p); p.value = p.value + ("fan" to "5")
        s.apply(); assertTrue(p.writes.isEmpty()); assertEquals(FanPhase.RELEASED, s.phases[p.id])
    }
    @Test fun pollingProtectsManualChangeWithoutCallback() = run {
        val p = Port(); val s = session(p); s.apply(); p.value = p.value + ("fan" to "4"); s.poll(); s.restore()
        assertEquals("4", p.value["fan"]); assertEquals(FanPhase.RELEASED, s.phases[p.id])
    }
    @Test fun readFailureBlocksBlindRollback() = run {
        val p = Port(); val s = session(p); s.apply(); val count = p.writes.size; p.unavailable = true
        s.restore(); assertEquals(count, p.writes.size); assertEquals(FanPhase.FAILED, s.phases[p.id])
    }
    @Test fun unmodifiedGroupsAreNeverWritten() = run {
        val p = Port(); val s = InterruptSession(listOf(InterruptPlan(p, p.value, p.value))); s.apply(); s.restore(); assertTrue(p.writes.isEmpty())
    }
    @Test fun keepCurrentReleasesAllOwnership() = run {
        val p = Port(); val s = session(p); s.apply(); s.release(); s.restore(); assertEquals(target, p.value)
    }
    @Test fun originalMapsAreCopied() = run {
        val p = Port(); val input = p.value.toMutableMap(); val s = InterruptSession(listOf(InterruptPlan(p, input, target)))
        input["fan"] = "1"; s.apply(); s.restore(); assertEquals("6", p.value["fan"])
    }
    @Test fun missingEventIsReportedAsFailureInsteadOfManualOverride() = run {
        val p = Port(); val s = session(p); s.apply(); s.observe(p.id, "fan", null)
        s.restore(); assertEquals(FanPhase.FAILED, s.phases[p.id]); assertTrue(s.failures.isNotEmpty())
    }
}
