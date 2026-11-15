package ai.pleos.fornow

import ai.pleos.fornow.core.*
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutomaticModeState(
    val ready: Boolean = false, val armed: Boolean = false, val busy: Boolean = false,
    val mode: InterruptMode = InterruptMode.STATE_NORMAL,
    val message: String = "자동 모드 준비 중", val support: String = "",
    val callStatus: String = "통화 신호 확인 중", val detail: String = "",
)

internal class AutomaticModes(
    context: Context, private val scope: CoroutineScope, private val climate: PleosClimate?,
    private val extras: AccessoryControls, private val journal: SessionJournal,
    private val changed: (AutomaticModeState) -> Unit,
) {
    var state = AutomaticModeState()
        private set
    private val engine = InterruptEngine()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val calls = InterruptCallSignals(context) { wake.trySend(Unit) }
    private val road = InterruptRoadSignals(context)
    private val windows = InterruptWindows(context, ::observe)
    private var climatePort: InterruptClimateControl? = null
    private val comfort = linkedMapOf<String, InterruptPort>()
    private var session: InterruptSession? = null
    private var job: Job? = null
    private var baseline: Pair<Long, Map<String, InterruptValues>>? = null
    private var lastPoll = 0L
    private var callSignal = CallSignal.UNKNOWN
    private var stopping = false
    private fun publish(value: AutomaticModeState) { state = value; changed(value) }

    suspend fun discover() {
        climate?.let { c -> optionalHardware { InterruptClimateControl(c).also { it.discover(); it.read() } }?.let {
            climatePort = it; comfort[it.id] = it
        } }
        extras.ports["seatHeat"]?.let { comfort["seatHeat"] = AccessoryInterruptPort(it) }
        extras.ports["wheel"]?.let { comfort["wheel"] = AccessoryInterruptPort(it) }
        val windowOk = optionalHardware { windows.discover(); true } == true
        val labels = listOfNotNull(climatePort?.support, comfort["seatHeat"]?.let { "좌석 열선·통풍" }, comfort["wheel"]?.let { "핸들 열선" },
            if (windowOk && windows.ports.isNotEmpty()) "창문 ${windows.ports.size}개" else null)
        val usable = (calls.available && (comfort.isNotEmpty() || windows.ports.isNotEmpty())) || (windowOk && windows.driver != null && comfort.isNotEmpty())
        publish(state.copy(ready = usable, message = if (usable) "주차 중 자동 모드를 켜면 통화·창문 변화를 감지해요." else "통화·창문 감지 또는 차량 제어를 사용할 수 없어요. 권한과 지원 기능을 확인해 주세요.",
            support = labels.joinToString(" / "), callStatus = if (calls.available) "통화 상태 접근 가능 · 연결 휴대전화 신호는 차량에 따라 달라요" else "자동 통화 감지 미지원 또는 전화 상태 권한 없음",
            detail = "미디어 음량: 통화는 시스템 오디오 포커스에 위임. 창구 모드의 미디어 2단 제어는 미지원. 브레이크 % 미제공으로 서행 조건을 사용해요."))
    }
    fun arm() {
        if (!state.ready || state.armed) return
        engine.reset(); baseline = null; stopping = false; calls.start()
        publish(state.copy(armed = true, message = "자동 감지 대기 중 · 통화 / 드라이브스루·정산소"))
        job = scope.launch {
            while (isActive && state.armed && !stopping) {
                try { mutex.withLock { if (!stopping) tick() } }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    publish(state.copy(busy = false, message = "차량 신호 확인 필요 · 설정을 확인해 주세요.", detail = e.message.orEmpty().take(160)))
                }
                withTimeoutOrNull(250) { wake.receive() }
            }
        }
    }
    private suspend fun tick() {
        callSignal = calls.read()
        engine.call(callSignal, SystemClock.elapsedRealtime())?.let { transition(it, null) }
        if (engine.mode != InterruptMode.STATE_INTERRUPT_CALL) {
            val start = SystemClock.elapsedRealtime()
            val signals = road.read()
            val window = windows.driverPosition()
            val now = SystemClock.elapsedRealtime()
            val fresh = now - start <= 1_000
            val frame = DriveFrame(now, signals.gearD.takeIf { fresh }, signals.speedKmh.takeIf { fresh }, window,
                systemWindowControl = windows.systemControlling || now < windows.suppressOpeningUntil)
            engine.drive(frame)?.let { transition(it, signals) }
            if (engine.mode == InterruptMode.STATE_NORMAL && window != null && window < 20 && !frame.systemWindowControl) {
                val snapshot = readComfort()
                if (windows.driverPosition()?.let { it < 20 } == true) baseline = SystemClock.elapsedRealtime() to snapshot
                else baseline = null
            }
        }
        if (SystemClock.elapsedRealtime() - lastPoll >= 750) {
            session?.poll(); lastPoll = SystemClock.elapsedRealtime()
            val released = session?.phases?.values?.count { it == FanPhase.RELEASED } ?: 0
            val failed = session?.phases?.values?.count { it == FanPhase.FAILED } ?: 0
            if (released > 0 || failed > 0) publish(state.copy(message = "${label(engine.mode)} · 직접 변경 ${released}개 유지 · 확인 필요 ${failed}개"))
        }
        val callText = when (callSignal) {
            CallSignal.UNKNOWN -> "통화 신호 미제공 · 자동 종료를 추정하지 않아요"
            CallSignal.IDLE -> "통화 없음"
            CallSignal.RINGING -> "전화 수신 중"
            CallSignal.OFFHOOK -> "통화 연결 중"
            CallSignal.PRESENT -> "시스템 통화 감지"
        }
        if (state.callStatus != callText) publish(state.copy(callStatus = callText))
    }
    private suspend fun readComfort(): Map<String, InterruptValues> = buildMap {
        for ((id, port) in comfort) optionalHardware { port.read() }?.let { put(id, it) }
    }
    private suspend fun transition(change: InterruptTransition, signals: RoadSignals?) {
        publish(state.copy(busy = true, mode = change.to, message = "${label(change.to)} · 설정 확인 중"))
        val previous = session
        previous?.restore()
        val previousErrors = previous?.failures.orEmpty().toList()
        if (previous != null) withContext(Dispatchers.IO) { journal.finish(if (previousErrors.isEmpty()) "${label(change.from)} 종료 · 직접 변경은 유지했어요." else previousErrors.joinToString(" / ")) }
        session = null
        if (change.to == InterruptMode.STATE_NORMAL) {
            baseline = null
            publish(state.copy(busy = false, message = when {
                previousErrors.isNotEmpty() -> "일부 복원 확인 필요 · 차량 설정을 확인해 주세요."
                change.reason == ExitReason.DRIVING_RESUMED -> "주행 재개 · 공조와 좌석을 복원하고 열린 창문은 유지했어요."
                else -> "모드 종료 · 직접 바꾸지 않은 설정을 복원했어요."
            }, detail = previousErrors.joinToString(" / ").ifBlank { state.detail }))
            return
        }
        val roadNow = signals ?: road.read()
        val before = if (change.to == InterruptMode.STATE_INTERRUPT_DRIVETHRU) {
            baseline?.takeIf { SystemClock.elapsedRealtime() - it.first <= 3_000 }?.second.orEmpty()
        } else readComfort()
        baseline = null
        val thermal = if (change.to == InterruptMode.STATE_INTERRUPT_CALL)
            InterruptPolicy.callThermal(roadNow.outdoor, climatePort?.cooling() == true) else InterruptPolicy.driveThermal(roadNow.outdoor)
        val plans = mutableListOf<InterruptPlan>()
        if (change.to == InterruptMode.STATE_INTERRUPT_CALL) for (p in windows.ports.values) optionalHardware { p.read() }?.let {
            plans += InterruptPlan(p, it, mapOf("value" to "0"))
        }
        for ((id, values) in before) {
            val p = comfort[id] ?: continue
            val target = when (id) {
                "climate" -> climatePort!!.target(values, change.to, thermal)
                "seatHeat" -> if (thermal == ThermalMode.NONE) values else if ("value" in values) {
                    mapOf("value" to InterruptPolicy.signedSeat(values.getValue("value").toInt(), thermal,
                        if (change.to == InterruptMode.STATE_INTERRUPT_CALL) 2 else 3, extras.minSeatHeat, extras.maxSeatHeat).toString())
                } else if (extras.minSeatHeat < 0) {
                    val temperature = values.getValue("heat").toInt()
                    val ventilation = values.getValue("vent").toInt()
                    val level = maxOf(if (change.to == InterruptMode.STATE_INTERRUPT_CALL) 2 else 3,
                        if (thermal == ThermalMode.COOLING) ventilation else 0)
                    mapOf("heat" to InterruptPolicy.signedSeat(temperature, thermal, level, extras.minSeatHeat, extras.maxSeatHeat).toString(), "vent" to "0")
                } else InterruptPolicy.seat(values.getValue("heat").toInt(), values.getValue("vent").toInt(), thermal,
                    if (change.to == InterruptMode.STATE_INTERRUPT_CALL) 2 else 3, extras.maxSeatHeat, extras.maxSeatVent)
                "wheel" -> if (change.to == InterruptMode.STATE_INTERRUPT_DRIVETHRU && thermal == ThermalMode.HEATING)
                    mapOf("value" to extras.maxWheelHeat.toString()) else values
                else -> values
            }
            plans += InterruptPlan(p, values, target)
        }
        val next = InterruptSession(plans)
        check(withContext(Dispatchers.IO) { journal.recordInterrupt(next.snapshot, change.to.name) }) { "복원 기록 저장 실패 · 제어하지 않았어요." }
        session = next
        next.apply { !stopping }
        val active = next.phases.values.count { it == FanPhase.ACTIVE }
        publish(state.copy(busy = false, message = "${label(change.to)} · ${active}개 설정 적용" + if (next.failures.isEmpty()) "" else " · 일부 확인 필요",
            detail = (previousErrors + next.failures).joinToString(" / ").ifBlank { state.detail }))
    }
    fun observe(id: String, property: String, value: String?) {
        val normalized = when (id) { "fan" -> "climate"; "seatCool" -> "seatHeat"; else -> id }
        session?.observe(normalized, property, value)
        if (id.startsWith("window_")) wake.trySend(Unit)
    }
    suspend fun disarm(restore: Boolean) {
        // Finish the in-flight, bounded transaction before stopping; cancelling a setter loses ownership.
        stopping = true
        mutex.withLock {
            job?.cancel(); job = null
            publish(state.copy(busy = true))
            if (restore) session?.restore() else session?.release()
            val failed = session?.phases?.values?.any { it == FanPhase.FAILED } == true
            val message = if (failed) "자동 감지 꺼짐 · 일부 복원 확인 필요" else if (restore) "자동 감지를 끄고 설정을 복원했어요." else "현재 설정을 유지하고 자동 감지를 껐어요."
            withContext(Dispatchers.IO) { journal.finish(message) }
            session = null; engine.reset(); baseline = null; calls.close()
            publish(state.copy(armed = false, busy = false, mode = engine.mode, message = message, callStatus = "감지 꺼짐 · 다음 시작 때 통화 상태를 다시 확인해요."))
        }
    }
    fun close() { job?.cancel(); calls.close(); windows.close(); road.close(); wake.close() }
    companion object {
        fun label(mode: InterruptMode) = when (mode) {
            InterruptMode.STATE_NORMAL -> "자동 감지 대기"
            InterruptMode.STATE_INTERRUPT_CALL -> "핸즈프리 통화"
            InterruptMode.STATE_INTERRUPT_DRIVETHRU -> "드라이브스루·정산소"
        }
    }
}
