package ai.pleos.fornow.core
import org.junit.Assert.*
import org.junit.Test

class SettingLeaseTest {
    private fun lease(key: String = "music") = SettingLease(key, mapOf("value" to "8"), mapOf("value" to "0")).apply {
        assertTrue(confirmApplied(mapOf("value" to "0")))
    }
    @Test fun manualChangeIsStickyEvenIfItReturnsToTheTemporaryValue() {
        val s = lease(); s.observeProperty("value", "3"); s.observeProperty("value", "0")
        assertFalse(s.beginRestore(mapOf("value" to "0")))
    }
    @Test fun changingMusicDoesNotCancelSeatRestoration() {
        val music = lease(); val seat = lease("seat")
        music.observeProperty("value", "5")
        assertTrue(seat.beginRestore(mapOf("value" to "0")))
        assertEquals(FanPhase.RELEASED, music.phase)
    }
    @Test fun aMissingValueCannotBeRestored() {
        val s = lease(); assertFalse(s.beginRestore(emptyMap()))
    }
    @Test fun aFinalFreshReadWinsOverTheCachedValue() {
        val s = lease(); assertFalse(s.beginRestore(mapOf("value" to "4")))
    }
    @Test fun unchangedEventsDoNotLoseOwnership() {
        val s = lease(); s.observeProperty("value", "0"); assertTrue(s.beginRestore(mapOf("value" to "0")))
        s.restored(true); assertEquals(FanPhase.RESTORED, s.phase)
    }
    @Test fun unconfirmedWriteCannotBeRestored() {
        val s = SettingLease("a", mapOf("value" to "4"), mapOf("value" to "1"))
        assertFalse(s.confirmApplied(mapOf("value" to "3"))); assertFalse(s.beginRestore(mapOf("value" to "1")))
    }
    @Test fun guardedPropertiesAlsoCancelRestoration() {
        val s = SettingLease("seat", mapOf("heat" to "0", "vent" to "0"), mapOf("heat" to "1", "vent" to "0"))
        s.confirmApplied(s.expected); s.observeProperty("vent", "2")
        assertEquals(FanPhase.RELEASED, s.phase)
    }
    @Test fun releaseAndDoubleRestoreNeverWriteAgain() {
        val a = lease(); a.release(); assertFalse(a.beginRestore(a.expected))
        val b = lease(); assertTrue(b.beginRestore(b.expected)); assertFalse(b.beginRestore(b.expected))
    }
}
