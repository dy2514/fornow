package ai.pleos.fornow

import ai.pleos.fornow.core.FanPhase
import ai.pleos.fornow.core.SettingLease
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ForNowState(
    val connecting: Boolean = true, val connected: Boolean = false, val parked: Boolean = false,
    val busy: Boolean = false, val active: Boolean = false,
    val controls: List<ControlInfo> = emptyList(),
    val remainingSeconds: Long = 0, val durationMs: Long = 60_000,
    val message: String = "차량 기능을 확인하고 있어요.", val detail: String = "",
    val history: List<String> = emptyList(), val selected: Set<String> = emptySet(),
    val automatic: AutomaticModeState = AutomaticModeState(),
)

class ForNowService : Service() {
    inner class LocalBinder : Binder() { val service: ForNowService get() = this@ForNowService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ForNowState())
    val state = mutable.asStateFlow()
    private var climate: PleosClimate? = null
    private var accessories: AccessoryControls? = null
    private var gate: CarGate? = null
    private var automatic: AutomaticModes? = null
    private var loop: Job? = null
    private lateinit var journal: SessionJournal
    private var foreground = false
    private var voiceInFlight = false
    private var deadline = 0L
    private val ports = linkedMapOf<String, TemporaryControl>()
    private val leases = linkedMapOf<String, SettingLease>()

    override fun onCreate() {
        super.onCreate()
        journal = SessionJournal(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "임시 설정 타이머", NotificationManager.IMPORTANCE_LOW))
        if (journal.pending()) journal.finish("이전 설정 제어가 중단됐어요. 현재 설정을 보호하기 위해 자동 복원하지 않았어요.")
        mutable.value = mutable.value.copy(history = journal.history())
        reconnect()
    }
    override fun onBind(intent: Intent) = binder

    fun reconnect() {
        if (mutable.value.busy || mutable.value.active || mutable.value.automatic.armed) return
        loop?.cancel(); automatic?.close(); automatic = null; climate?.close(); accessories?.close(); gate?.close(); ports.clear(); leases.clear()
        mutable.value = mutable.value.copy(connecting = true, connected = false, parked = false, controls = emptyList(), detail = "")
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            mutable.value = mutable.value.copy(connecting = false, message = "Pleos 차량 또는 에뮬레이터에서 실행해 주세요.")
            return
        }
        gate = CarGate(this) { mutable.value = mutable.value.copy(parked = it) }.also { it.start() }
        loop = scope.launch {
            val discovered = mutableListOf<ControlInfo>()
            val client = PleosClimate(this@ForNowService, { key, value -> onEvent("fan", key, value?.toString()) },
                { error -> onFeatureError("fan", error) })
            climate = client
            try {
                client.connect(); val port = FanControl(client); val value = port.read()
                ports[port.id] = port
                discovered += ControlInfo(port.id, port.title, port.description, true, port.format(value))
            } catch (e: Exception) {
                rethrowCancellation(e)
                discovered += ControlInfo("fan", "바람 조용하게", "공조 풍량을 낮춰요", false, reason = readable(e))
            }
            mutable.value = mutable.value.copy(controls = discovered.toList())
            val extras = AccessoryControls(this@ForNowService, ::onEvent); accessories = extras
            try { extras.discover(); ports.putAll(extras.ports); discovered.addAll(extras.info) }
            catch (e: Exception) { rethrowCancellation(e); Log.e(TAG, "Accessory discovery failed", e) }
            val modes = AutomaticModes(this@ForNowService, scope, climate.takeIf { "fan" in ports }, extras, journal) { value ->
                mutable.value = mutable.value.copy(automatic = value)
                if (foreground && value.armed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(value.message))
            }
            automatic = modes
            try { modes.discover() } catch (e: Exception) { rethrowCancellation(e); Log.e(TAG, "Automatic mode discovery failed", e) }
            val order = listOf("fan", "media", "seatHeat", "seatCool", "wheel", "ambient")
            mutable.value = mutable.value.copy(connecting = false, connected = ports.isNotEmpty() || modes.state.ready,
                controls = discovered.sortedBy { order.indexOf(it.id) },
                message = if (journal.history().isNotEmpty()) journal.history().first() else "필요한 만큼만 바꾸고, 시간이 지나면 원래대로.")
            while (isActive) {
                delay(1_000)
                lock.withLock {
                    for ((id, port) in ports.toMap()) {
                        try {
                            val value = port.read()
                            leases[id]?.observe(value)
                            updateInfo(id, current = port.format(value), phase = phaseLabel(leases[id]))
                        } catch (e: Exception) { rethrowCancellation(e); onFeatureError(id, e) }
                    }
                    if (mutable.value.active) {
                        if (leases.values.none { it.phase == FanPhase.ACTIVE }) finish(
                            if (leases.values.any { it.phase == FanPhase.FAILED }) "일부 기능의 연결이 끊겨 복원을 중단했어요. 차량 설정을 확인해 주세요."
                            else "직접 바뀐 설정을 유지하고 타이머를 끝냈어요.")
                        else if (SystemClock.elapsedRealtime() >= deadline) restoreAll()
                        else updateCountdown()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in ForNowVoiceCommand.actions) {
            receiveGleo(intent!!)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            START, ARM -> {
                if (!foreground) try {
                    startForeground(NOTIFICATION, notification("처음 설정을 기억하고 있어요."),
                        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                    foreground = true
                } catch (e: Exception) {
                    mutable.value = mutable.value.copy(message = "타이머를 시작하지 못했어요.", detail = readable(e))
                    stopSelf(); return START_NOT_STICKY
                }
                val duration = intent.getLongExtra(DURATION, 60_000)
                val selected = intent.getStringArrayListExtra(CONTROLS)?.toSet().orEmpty()
                scope.launch { lock.withLock {
                    if (intent.action == ARM) {
                        if (!mutable.value.parked || mutable.value.active || mutable.value.busy || mutable.value.connecting || automatic?.state?.ready != true) stopIfIdle()
                        else { leases.clear(); automatic?.arm() }
                    } else startTimer(duration, selected)
                } }
            }
            RESTORE -> scope.launch { lock.withLock {
                if (mutable.value.automatic.armed) { automatic?.disarm(true); stopIfIdle() }
                else if (mutable.value.active) restoreAll() else stopIfIdle()
            } }
            KEEP -> scope.launch { lock.withLock {
                if (mutable.value.automatic.armed) { automatic?.disarm(false); stopIfIdle() }
                else if (mutable.value.active) { leases.values.forEach { it.release() }; finish("현재 설정을 유지하고 타이머를 끝냈어요.") }
                else stopIfIdle()
            } }
        }
        return START_NOT_STICKY
    }

    private fun receiveGleo(intent: Intent) {
        val call = GleoCall(this, intent)
        call.startDeadline()
        try {
            if (!foreground) {
                startForeground(NOTIFICATION, notification("음성 요청을 확인하고 있어요."),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                foreground = true
            }
        } catch (_: Exception) {
            call.reply(GleoResult("fail", "ForNow could not start its temporary control service.")); stopIfIdle(); return
        }
        if (voiceInFlight) {
            call.reply(GleoResult("fail", "A previous ForNow request is still being processed.")); return
        }
        voiceInFlight = true
        scope.launch {
            try {
                val command = ForNowVoiceCommand.parse(call.action, call.parameters(ForNowVoiceCommand.parameters))
                val result = when (command) {
                    is ForNowVoiceCommand.Temporary -> withTimeout(5_000) {
                        state.first { !it.connecting }
                        lock.withLock {
                            if (!call.canBegin()) return@withLock GleoResult("fail", "The request expired before execution.")
                            if (mutable.value.active || mutable.value.busy || mutable.value.automatic.armed)
                                GleoResult("fail", "A ForNow session is already active; restore it before starting another setting.")
                            else {
                                startTimer(command.durationMs, setOf(command.control), voice = true)
                                if (mutable.value.active) GleoResult("success", "The requested setting is confirmed and its restoration timer is running.",
                                    listOf(mapOf("control" to command.control, "durationSeconds" to command.durationMs / 1000)))
                                else GleoResult("fail", "The requested setting was not started. It may already be applied or unavailable; check the app status.")
                            }
                        }
                    }
                    is ForNowVoiceCommand.Automatic -> {
                        withTimeout(5_000) { state.first { !it.connecting } }
                        lock.withLock {
                            if (!call.canBegin()) return@withLock GleoResult("fail", "The request expired before execution.")
                            if (!command.enabled) {
                                automatic?.disarm(true)
                                GleoResult("success", "Automatic detection is off; check the app for individual restoration results.")
                            } else if (!mutable.value.parked || mutable.value.active || mutable.value.busy || automatic?.state?.ready != true)
                                GleoResult("fail", "Enable automatic detection while parked with no active timer and supported vehicle signals.")
                            else { leases.clear(); automatic?.arm(); GleoResult("success", "Automatic call and drive-through detection is enabled.") }
                        }
                    }
                    ForNowVoiceCommand.Restore -> lock.withLock {
                        if (!call.canBegin()) return@withLock GleoResult("fail", "The request expired before execution.")
                        if (mutable.value.automatic.armed) {
                            automatic?.disarm(true)
                            GleoResult("success", "Automatic detection is off; check the app for individual restoration results.")
                        } else if (mutable.value.active) {
                            restoreAll()
                            if (leases.values.any { it.phase == FanPhase.FAILED }) GleoResult("fail", "Some settings could not be restored. Check the vehicle settings.")
                            else GleoResult("success", "The timer has ended and unchanged settings were restored; manual changes were preserved.")
                        } else GleoResult("success", "There is no active ForNow session to restore.")
                    }
                }
                call.reply(result)
            } catch (e: GleoInput) { call.reply(GleoResult(e.status, e.message)) }
            catch (_: TimeoutCancellationException) { call.reply(GleoResult("fail", "The setting could not be confirmed in time. Check the app status before retrying.")) }
            catch (e: CancellationException) { call.reply(GleoResult("fail", "The ForNow operation was interrupted; check the vehicle settings.")); throw e }
            catch (_: Exception) { call.reply(GleoResult("fail", "The requested ForNow operation could not be completed.")) }
            finally { voiceInFlight = false; stopIfIdle() }
        }
    }

    private suspend fun startTimer(duration: Long, selected: Set<String>, voice: Boolean = false) {
        if (mutable.value.active || mutable.value.busy || mutable.value.automatic.armed) return
        if ((!voice && !mutable.value.parked) || duration !in ALLOWED_DURATIONS || selected.isEmpty() ||
            selected.any { it !in ports || mutable.value.controls.none { row -> row.id == it && row.available } } ||
            ("seatHeat" in selected && "seatCool" in selected)) {
            mutable.value = mutable.value.copy(message = "주차 상태에서 사용 가능한 기능을 선택해 주세요.")
            stopTimerService(); return
        }
        mutable.value = mutable.value.copy(busy = true, selected = selected, message = "처음 설정을 기억하고 있어요.", detail = "")
        leases.clear()
        try {
            for (id in selected) {
                val port = ports.getValue(id); val before = port.read(); val target = port.target(before)
                if (before != target) leases[id] = SettingLease(id, before, target)
                else updateInfo(id, phase = "이미 적용된 설정")
            }
            check(leases.isNotEmpty()) { "선택한 기능이 이미 같은 설정이에요." }
            check(withContext(Dispatchers.IO) { journal.recordPlan(leases.values.toList(), duration) }) { "설정 기록을 저장하지 못했어요." }
            for ((id, lease) in leases) {
                check(voice || mutable.value.parked) { "주행이 시작돼 추가 변경을 멈췄어요." }
                val port = ports.getValue(id)
                check(port.read() == lease.original) { "${port.title}: 시작 전 설정이 바뀌었어요." }
                port.write(lease.expected, lease.original)
                check(lease.confirmApplied(port.read())) { "${port.title}: 적용 결과를 확인하지 못했어요." }
                updateInfo(id, current = port.format(lease.expected), phase = "복원 예약")
                Log.i(TAG, "APPLIED $id ${lease.original} -> ${lease.expected}")
            }
            deadline = SystemClock.elapsedRealtime() + duration
            mutable.value = mutable.value.copy(busy = false, active = true, durationMs = duration,
                remainingSeconds = duration / 1_000, message = "직접 바꾼 항목은 그대로 두고, 나머지만 복원해요.")
            updateCountdown()
        } catch (e: Exception) {
            Log.e(TAG, "Apply interrupted", e)
            withContext(NonCancellable) {
                for ((id, lease) in leases) if (lease.phase == FanPhase.ACTIVE) restoreOne(id, lease)
                for ((id, lease) in leases) if (lease.phase == FanPhase.APPLYING) { lease.fail(); updateInfo(id, phase = "적용 확인 필요") }
                finish("시작을 완료하지 못했어요. 적용된 항목의 상태를 확인해 주세요.")
                mutable.value = mutable.value.copy(detail = readable(e))
            }
            if (e is CancellationException) throw e
        }
    }

    private suspend fun restoreOne(id: String, lease: SettingLease) {
        val port = ports.getValue(id)
        try {
            if (lease.beginRestore(port.read())) {
                port.write(lease.original, lease.expected)
                val actual = port.read(); lease.restored(port.restoredMatches(lease.original, actual))
                updateInfo(id, current = port.format(actual))
                Log.i(TAG, "RESTORED $id ${lease.phase} $actual")
            }
        } catch (e: Exception) { rethrowCancellation(e); lease.fail(); Log.e(TAG, "Restore failed: $id", e) }
        updateInfo(id, phase = phaseLabel(lease))
    }

    private suspend fun restoreAll() {
        mutable.value = mutable.value.copy(busy = true, message = "처음 설정으로 돌아가고 있어요.")
        for ((id, lease) in leases) if (lease.phase == FanPhase.ACTIVE) restoreOne(id, lease)
        val failed = leases.values.count { it.phase == FanPhase.FAILED }
        val skipped = leases.values.count { it.phase == FanPhase.RELEASED }
        val restored = leases.values.count { it.phase == FanPhase.RESTORED }
        finish(when {
            failed > 0 -> "${failed}개 항목의 복원을 확인하지 못했어요. 차량 설정을 확인해 주세요."
            skipped > 0 -> "${restored}개 복원 · 직접 바뀐 ${skipped}개 항목은 그대로 유지했어요."
            else -> "${restored}개 항목을 처음 설정으로 돌려놓았어요."
        })
    }

    private fun onEvent(id: String, property: String, value: String?) {
        automatic?.observe(id, property, value)
        leases[id]?.let { lease ->
            if (value == null && lease.phase == FanPhase.ACTIVE) {
                lease.fail(); updateInfo(id, phase = "연결 확인 필요"); return
            }
            lease.observeProperty(property, value)
            if (lease.phase == FanPhase.RELEASED) {
                updateInfo(id, phase = "변경 감지 · 복원 제외")
                Log.i(TAG, "OVERRIDE $id $property")
            }
        }
    }
    private fun onFeatureError(id: String, error: Throwable) {
        leases[id]?.takeIf { it.phase == FanPhase.ACTIVE }?.fail()
        mutable.value = mutable.value.copy(controls = mutable.value.controls.map {
            if (it.id == id) it.copy(available = false, reason = "현재 상태를 확인하지 못했어요.", phase = "연결 확인 필요") else it
        })
        if (!mutable.value.active && !mutable.value.busy) ports.remove(id)
        Log.w(TAG, "$id unavailable: ${readable(error)}")
    }
    private fun updateInfo(id: String, current: String? = null, phase: String? = null) {
        mutable.value = mutable.value.copy(controls = mutable.value.controls.map {
            if (it.id == id) it.copy(current = current ?: it.current, phase = phase ?: it.phase) else it
        })
    }
    private fun phaseLabel(lease: SettingLease?): String = when (lease?.phase) {
        FanPhase.ACTIVE -> "복원 예약"
        FanPhase.RELEASED -> "변경 감지 · 복원 제외"
        FanPhase.RESTORED -> "복원 완료"
        FanPhase.FAILED -> "설정 확인 필요"
        else -> ""
    }
    private suspend fun finish(message: String) {
        withContext(Dispatchers.IO) { journal.finish(message) }
        mutable.value = mutable.value.copy(busy = false, active = false, remainingSeconds = 0,
            message = message, history = journal.history())
        stopTimerService()
    }
    private fun updateCountdown() {
        val seconds = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1_000
        mutable.value = mutable.value.copy(remainingSeconds = seconds)
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
            notification("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} 후 복원 · ${leases.values.count { it.phase == FanPhase.ACTIVE }}개 항목"))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, ForNowService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_fornow).setContentTitle("ForNow · 임시 설정")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "지금 복원", action(RESTORE, 1)).build())
            .addAction(Notification.Action.Builder(null, "이대로 유지", action(KEEP, 2)).build()).build()
    }
    private fun stopIfIdle() { if (!mutable.value.active && !mutable.value.busy && !mutable.value.automatic.armed) stopTimerService() }
    private fun stopTimerService() { if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }; stopSelf() }
    private fun readable(e: Throwable) = (e.message ?: e.javaClass.simpleName).take(160)
    private fun rethrowCancellation(e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e }
    override fun onDestroy() { loop?.cancel(); automatic?.close(); scope.cancel(); climate?.close(); accessories?.close(); gate?.close(); super.onDestroy() }
    companion object {
        const val START = "ai.pleos.fornow.START"
        const val ARM = "ai.pleos.fornow.ARM_AUTOMATIC"
        const val RESTORE = "ai.pleos.fornow.RESTORE"
        const val KEEP = "ai.pleos.fornow.KEEP"
        const val DURATION = "durationMs"
        const val CONTROLS = "controls"
        private const val TAG = "ForNowControls"
        private const val CHANNEL = "temporary_settings"
        private const val NOTIFICATION = 41
        val ALLOWED_DURATIONS = setOf(60_000L, 180_000L, 300_000L)
    }
}
