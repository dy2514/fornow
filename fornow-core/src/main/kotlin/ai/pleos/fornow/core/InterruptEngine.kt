package ai.pleos.fornow.core

enum class InterruptMode { STATE_NORMAL, STATE_INTERRUPT_CALL, STATE_INTERRUPT_DRIVETHRU }
enum class CallSignal { UNKNOWN, IDLE, RINGING, OFFHOOK, PRESENT }
enum class TtsPolicy { NORMAL, MUTE_TO_TOAST, ESSENTIAL_ONLY }
enum class ExitReason { CALL_ENDED, WINDOW_CLOSED, DRIVING_RESUMED, CALL_PRIORITY }
data class InterruptTransition(val from: InterruptMode, val to: InterruptMode, val reason: ExitReason? = null)
data class DriveFrame(
    val timeMs: Long, val gearD: Boolean?, val speedKmh: Float?, val driverWindow: Int?,
    val brakePercent: Float? = null, val systemWindowControl: Boolean = false,
)

/** Uses monotonic observation times, never a wall clock or a fabricated sensor value. */
class InterruptEngine {
    var mode = InterruptMode.STATE_NORMAL
        private set
    val ttsPolicy get() = when (mode) {
        InterruptMode.STATE_NORMAL -> TtsPolicy.NORMAL
        InterruptMode.STATE_INTERRUPT_CALL -> TtsPolicy.MUTE_TO_TOAST
        InterruptMode.STATE_INTERRUPT_DRIVETHRU -> TtsPolicy.ESSENTIAL_ONLY
    }
    private var idleSince: Long? = null
    private var fastSince: Long? = null
    private var lowWindow: Pair<Long, Int>? = null
    private var lastDriveTime: Long? = null
    private var lastCallTime: Long? = null

    fun call(signal: CallSignal, now: Long): InterruptTransition? {
        if (lastCallTime?.let { now < it || now - it > 1_000 } == true) idleSince = null
        lastCallTime = now
        if (signal in listOf(CallSignal.RINGING, CallSignal.OFFHOOK, CallSignal.PRESENT)) {
            idleSince = null
            if (mode != InterruptMode.STATE_INTERRUPT_CALL) return move(InterruptMode.STATE_INTERRUPT_CALL,
                if (mode == InterruptMode.STATE_INTERRUPT_DRIVETHRU) ExitReason.CALL_PRIORITY else null)
        } else if (mode == InterruptMode.STATE_INTERRUPT_CALL) {
            if (signal != CallSignal.IDLE) idleSince = null
            else {
                if (idleSince == null) idleSince = now
                if (now - idleSince!! >= 1_500) return move(InterruptMode.STATE_NORMAL, ExitReason.CALL_ENDED)
            }
        }
        return null
    }

    fun drive(frame: DriveFrame): InterruptTransition? {
        val time = frame.timeMs
        if (lastDriveTime?.let { time <= it || time - it > 1_000 } == true) {
            lowWindow = null; fastSince = null
        }
        lastDriveTime = time
        val window = frame.driverWindow?.takeIf { it in 0..100 }
        val speed = frame.speedKmh?.takeIf { it.isFinite() && it >= 0 }
        if (mode == InterruptMode.STATE_INTERRUPT_DRIVETHRU) {
            if (window != null && window <= 10) return move(InterruptMode.STATE_NORMAL, ExitReason.WINDOW_CLOSED)
            if (speed != null && speed >= 30) {
                if (fastSince == null) fastSince = time
                if (time - fastSince!! >= 5_000) return move(InterruptMode.STATE_NORMAL, ExitReason.DRIVING_RESUMED)
            } else fastSince = null
            return null
        }
        if (mode != InterruptMode.STATE_NORMAL || frame.systemWindowControl || window == null) {
            lowWindow = null; return null
        }
        val low = lowWindow
        if (low != null && time - low.first > 3_000) lowWindow = null
        if (window < 20) lowWindow = time to window
        else if (window >= 50) {
            val start = lowWindow
            // Consume an opening edge even if the gear/speed condition does not qualify.
            lowWindow = null
            val brake = frame.brakePercent?.takeIf { it.isFinite() && it in 0f..100f }
            if (start != null && time - start.first in 0..3_000 && window - start.second >= 30 &&
                frame.gearD == true && ((speed != null && speed <= 15) || (brake != null && brake >= 10))) {
                return move(InterruptMode.STATE_INTERRUPT_DRIVETHRU)
            }
        }
        return null
    }

    fun reset() { mode = InterruptMode.STATE_NORMAL; idleSince = null; fastSince = null; lowWindow = null; lastDriveTime = null; lastCallTime = null }
    private fun move(next: InterruptMode, reason: ExitReason? = null): InterruptTransition {
        val result = InterruptTransition(mode, next, reason)
        mode = next; idleSince = null; fastSince = null; lowWindow = null
        return result
    }
}

enum class ThermalMode { NONE, COOLING, HEATING }
object InterruptPolicy {
    /** Heating wins in cold weather: A/C can also be running for dehumidification. */
    fun callThermal(outdoor: Float?, cooling: Boolean = false, heating: Boolean = false): ThermalMode = when {
        outdoor?.let { it.isFinite() && it <= 10 } == true -> ThermalMode.HEATING
        outdoor?.let { it.isFinite() && it >= 24 } == true -> ThermalMode.COOLING
        cooling && !heating -> ThermalMode.COOLING
        heating && !cooling -> ThermalMode.HEATING
        else -> ThermalMode.NONE
    }
    fun driveThermal(outdoor: Float?): ThermalMode = when {
        outdoor == null || !outdoor.isFinite() -> ThermalMode.NONE
        outdoor <= 12 -> ThermalMode.HEATING
        outdoor >= 26 -> ThermalMode.COOLING
        else -> ThermalMode.NONE
    }
    fun callFan(current: Int, minimum: Int, maximum: Int, learned: Int? = null): Int {
        require(minimum <= maximum)
        return if (current <= 2) current else minOf(current, (learned ?: 2).coerceIn(minimum, maximum))
    }
    fun temperature(original: Float, mode: ThermalMode, minimum: Float, maximum: Float, step: Float): Float {
        require(original.isFinite() && minimum.isFinite() && maximum.isFinite() && step.isFinite() && step > 0 && minimum <= maximum)
        if (mode == ThermalMode.NONE) return original
        val floor = maxOf(16f, minimum)
        if (floor > maximum) return original
        val requested = (original + if (mode == ThermalMode.COOLING) -1.5f else 1.5f).coerceIn(floor, maximum)
        val first = kotlin.math.ceil((floor - minimum) / step)
        val last = kotlin.math.floor((maximum - minimum) / step)
        if (first > last) return original
        val index = kotlin.math.round((requested - minimum) / step).coerceIn(first, last)
        return minimum + index * step
    }
    /** Boost without lowering an already stronger setting. Heat and ventilation are separate SDK properties. */
    fun seat(heat: Int, vent: Int, thermal: ThermalMode, level: Int, maxHeat: Int, maxVent: Int): Map<String, String> = when (thermal) {
        ThermalMode.HEATING -> mapOf("heat" to maxOf(heat, minOf(level, maxHeat)).toString(), "vent" to "0")
        ThermalMode.COOLING -> mapOf("heat" to "0", "vent" to maxOf(vent, minOf(level, maxVent)).toString())
        ThermalMode.NONE -> mapOf("heat" to heat.toString(), "vent" to vent.toString())
    }
    /** HVAC_SEAT_TEMPERATURE may expose cooling as a negative level on the same property. */
    fun signedSeat(current: Int, thermal: ThermalMode, level: Int, minimum: Int, maximum: Int): Int {
        require(minimum <= 0 && maximum >= 0 && current in minimum..maximum && level >= 0)
        return when (thermal) {
            ThermalMode.COOLING -> minOf(current, -minOf(level, -minimum))
            ThermalMode.HEATING -> maxOf(current, minOf(level, maximum))
            ThermalMode.NONE -> current
        }
    }
}
