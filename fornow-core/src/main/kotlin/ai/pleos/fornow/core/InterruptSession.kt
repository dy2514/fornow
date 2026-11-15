package ai.pleos.fornow.core

import java.util.concurrent.CancellationException

typealias InterruptValues = Map<String, String>
interface InterruptPort {
    val id: String
    suspend fun read(): InterruptValues
    /** Report each verified intermediate state, so a later failed setter can still be rolled back. */
    suspend fun write(target: InterruptValues, expected: InterruptValues, confirmed: (InterruptValues) -> Unit)
    fun restoredMatches(original: InterruptValues, current: InterruptValues): Boolean = original == current
}
data class VehicleStateSnapshot(val values: Map<String, InterruptValues>)
data class InterruptPlan(val port: InterruptPort, val before: InterruptValues, val target: InterruptValues)

/** Own only confirmed writes; any later manual change releases that group permanently. */
class InterruptSession(plans: List<InterruptPlan>) {
    private class Entry(val plan: InterruptPlan) {
        var expected = plan.before.toMap()
        var phase = FanPhase.APPLYING
    }
    private val entries = plans.filter { it.before != it.target }.map { Entry(it.copy(before = it.before.toMap(), target = it.target.toMap())) }
    val snapshot = VehicleStateSnapshot(plans.associate { it.port.id to it.before.toMap() })
    val phases get() = entries.associate { it.plan.port.id to it.phase }
    val failures = mutableListOf<String>()

    suspend fun apply(shouldContinue: () -> Boolean = { true }) {
        for (e in entries) {
            if (!shouldContinue()) break
            try {
                if (e.plan.port.read() != e.expected) { e.phase = FanPhase.RELEASED; continue }
                e.plan.port.write(e.plan.target, e.expected) { e.expected = it.toMap() }
                check(e.plan.port.read() == e.plan.target) { "적용 확인 실패" }
                e.expected = e.plan.target.toMap(); e.phase = FanPhase.ACTIVE
            } catch (x: Exception) {
                if (x is CancellationException) throw x
                failures += "${e.plan.port.id}: ${x.message.orEmpty().take(80)}"
                // A failed multi-step write may have already changed AUTO, recirculation or a seat stage.
                if (e.expected != e.plan.before) { e.phase = FanPhase.ACTIVE; restore(e) }
                else e.phase = FanPhase.FAILED
            }
        }
    }
    fun observe(id: String, property: String, value: String?) {
        entries.filter { it.plan.port.id == id && it.phase == FanPhase.ACTIVE }.forEach {
            if (property in it.expected && value == null) {
                it.phase = FanPhase.FAILED; failures += "$id: 상태 신호 미제공 · 차량 설정 확인"
            } else if (property in it.expected && it.expected[property] != value) it.phase = FanPhase.RELEASED
        }
    }
    suspend fun poll() {
        for (e in entries.filter { it.phase == FanPhase.ACTIVE }) try {
            if (e.plan.port.read() != e.expected) e.phase = FanPhase.RELEASED
        } catch (x: Exception) {
            if (x is CancellationException) throw x
            e.phase = FanPhase.FAILED; failures += "${e.plan.port.id}: 연결 확인 필요"
        }
    }
    suspend fun restore() { for (e in entries.asReversed()) if (e.phase == FanPhase.ACTIVE) restore(e) }
    private suspend fun restore(e: Entry) {
        try {
            if (e.plan.port.read() != e.expected) { e.phase = FanPhase.RELEASED; return }
            e.phase = FanPhase.RESTORING
            e.plan.port.write(e.plan.before, e.expected) { e.expected = it.toMap() }
            e.phase = if (e.plan.port.restoredMatches(e.plan.before, e.plan.port.read())) FanPhase.RESTORED else FanPhase.FAILED
            if (e.phase == FanPhase.FAILED) failures += "${e.plan.port.id}: 복원 확인 필요"
        } catch (x: Exception) {
            if (x is CancellationException) throw x
            e.phase = FanPhase.FAILED; failures += "${e.plan.port.id}: 복원 실패 · 차량 설정 확인"
        }
    }
    fun release() { entries.forEach { if (it.phase == FanPhase.ACTIVE) it.phase = FanPhase.RELEASED } }
}
