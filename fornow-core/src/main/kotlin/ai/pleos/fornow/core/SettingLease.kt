package ai.pleos.fornow.core

/** One independently restorable group. A changed value permanently releases this group. */
class SettingLease(val key: String, original: Map<String, String>, expected: Map<String, String>) {
    val original = original.toMap()
    val expected = expected.toMap()
    var phase = FanPhase.APPLYING
        private set
    init { require(original.isNotEmpty() && original.keys == expected.keys) }
    fun confirmApplied(current: Map<String, String>): Boolean {
        if (phase != FanPhase.APPLYING) return false
        phase = if (current == expected) FanPhase.ACTIVE else FanPhase.FAILED
        return phase == FanPhase.ACTIVE
    }
    fun observe(current: Map<String, String>) {
        if (phase == FanPhase.ACTIVE && current != expected) phase = FanPhase.RELEASED
    }
    fun observeProperty(property: String, value: String?) {
        if (phase == FanPhase.ACTIVE && property in expected && expected[property] != value) phase = FanPhase.RELEASED
    }
    fun beginRestore(current: Map<String, String>): Boolean {
        observe(current)
        if (phase != FanPhase.ACTIVE) return false
        phase = FanPhase.RESTORING
        return true
    }
    fun restored(success: Boolean) { if (phase == FanPhase.RESTORING) phase = if (success) FanPhase.RESTORED else FanPhase.FAILED }
    fun release() { if (phase == FanPhase.ACTIVE) phase = FanPhase.RELEASED }
    fun fail() { phase = FanPhase.FAILED }
}
