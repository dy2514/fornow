package ai.pleos.fornow.core

import kotlin.math.abs

/** A confirmed, complete HVAC sample. Never substitute defaults for unavailable properties. */
data class ClimateSnapshot(
    val fan: Int,
    val auto: Boolean,
    val power: Boolean,
    val temperature: Float,
    val direction: String,
    val defrost: Boolean,
) {
    fun sameContext(other: ClimateSnapshot): Boolean = power == other.power &&
        temperature.isFinite() && other.temperature.isFinite() &&
        abs(temperature - other.temperature) < 0.05f && direction == other.direction && defrost == other.defrost

    fun sameSettings(other: ClimateSnapshot): Boolean = sameContext(other) && fan == other.fan && auto == other.auto
}

enum class FanPhase { APPLYING, ACTIVE, RESTORING, RESTORED, RELEASED, INTERRUPTED, FAILED }

/** Monotonic deadlines and sticky loss of ownership prevent an old timer overwriting a new choice. */
class FanSession(
    val original: ClimateSnapshot,
    val quietFan: Int,
    val durationMs: Long,
    val id: String,
) {
    init {
        require(original.power && !original.defrost && !original.direction.contains("DEFROST"))
        require(original.temperature.isFinite() && original.direction != "UNKNOWN")
        require(quietFan >= 1 && quietFan < original.fan)
        require(durationMs in 1_000L..1_800_000L)
    }
    val expected = original.copy(fan = quietFan, auto = false)
    var phase: FanPhase = FanPhase.APPLYING
        private set
    var deadlineElapsedMs: Long = 0
        private set
    var reason: String = ""
        private set

    fun confirmApplied(observed: ClimateSnapshot, elapsedMs: Long): Boolean {
        if (phase != FanPhase.APPLYING) return false
        if (!expected.sameSettings(observed)) {
            fail("임시 설정 적용을 확인하지 못했어요.")
            return false
        }
        phase = FanPhase.ACTIVE
        deadlineElapsedMs = elapsedMs + durationMs
        return true
    }

    fun observe(observed: ClimateSnapshot) {
        if (phase == FanPhase.ACTIVE && !expected.sameSettings(observed)) {
            phase = FanPhase.RELEASED
            reason = "차량에서 설정이 바뀌어 자동 복원을 취소했어요."
        }
    }

    fun remainingMs(elapsedMs: Long): Long = (deadlineElapsedMs - elapsedMs).coerceAtLeast(0)
    fun isDue(elapsedMs: Long): Boolean = phase == FanPhase.ACTIVE && elapsedMs >= deadlineElapsedMs

    /** Always supply a fresh read immediately before issuing a restore command. */
    fun beginRestore(observed: ClimateSnapshot): Boolean {
        observe(observed)
        if (phase != FanPhase.ACTIVE) return false
        phase = FanPhase.RESTORING
        return true
    }

    fun confirmRestored(observed: ClimateSnapshot): Boolean {
        if (phase != FanPhase.RESTORING) return false
        // AUTO may choose a different fan speed as soon as it is re-enabled.
        val restored = original.sameContext(observed) && original.auto == observed.auto &&
            (original.auto || original.fan == observed.fan)
        if (restored) { phase = FanPhase.RESTORED; reason = "처음 공조 설정으로 돌아왔어요." }
        else fail("복원 결과를 확인하지 못했어요. 차량 공조 화면을 확인해 주세요.")
        return restored
    }

    fun release() {
        if (phase == FanPhase.ACTIVE) {
            phase = FanPhase.RELEASED
            reason = "현재 설정을 유지하고 타이머를 끝냈어요."
        }
    }

    fun interrupt(message: String) {
        if (phase in listOf(FanPhase.APPLYING, FanPhase.ACTIVE, FanPhase.RESTORING)) {
            phase = FanPhase.INTERRUPTED
            reason = message
        }
    }

    fun fail(message: String) { phase = FanPhase.FAILED; reason = message }
}
