package ai.pleos.fornow

import ai.pleos.fornow.core.*
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.*
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T : Any> vehicleRequest(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T = try {
    withTimeout(4_000) {
        suspendCancellableCoroutine { c ->
            try { call({ v -> if (c.isActive) {
                if (v != null) c.resume(v) else c.resumeWithException(IllegalStateException("차량 값 미제공"))
            } }, { if (c.isActive) c.resumeWithException(it) }) }
            catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
        }
    }
} catch (e: TimeoutCancellationException) { throw IllegalStateException("차량 응답 시간 초과", e) }

internal suspend fun <T> optionalHardware(block: suspend () -> T): T? = try { block() }
catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; null }

internal class AccessoryInterruptPort(private val port: TemporaryControl) : InterruptPort {
    override val id get() = port.id
    override suspend fun read() = try { port.read() }
        catch (e: TimeoutCancellationException) { throw IllegalStateException("차량 응답 시간 초과", e) }
    override suspend fun write(target: InterruptValues, expected: InterruptValues, confirmed: (InterruptValues) -> Unit) {
        try { port.writeTracked(target, expected, confirmed) }
        catch (e: TimeoutCancellationException) { throw IllegalStateException("차량 응답 시간 초과", e) }
    }
    override fun restoredMatches(original: InterruptValues, current: InterruptValues) = port.restoredMatches(original, current)
}

/** A climate group protects power/defrost/context and records every acknowledged intermediate write. */
internal class InterruptClimateControl(private val client: PleosClimate) : InterruptPort {
    override val id = "climate"
    private val api get() = client.hvac
    private var temperature: Pair<SeatArea, HvacTemperatureConfigArray>? = null
    private var direction: SeatArea? = null
    private var recirculation: SeatArea? = null
    private var autoRecirculation: SeatArea? = null
    private var autoRecirculationAbsent = false
    private var ac: SeatArea? = null
    val support get() = listOfNotNull("풍량", direction?.let { "송풍 방향" }, temperature?.let { "목표 온도" }, recirculation?.let { "내기 순환" }).joinToString(" · ")
    private fun driver(areas: Collection<SeatArea>) = areas.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }

    suspend fun discover() {
        temperature = optionalHardware {
            val data = vehicleRequest<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>> { ok, fail ->
                api.checkHvacTemperatureCapabilities(onComplete = { c, b, units -> ok(Triple(c, b, units)) }, onFailed = fail)
            }
            check(data.first.accessType.name == "READ_WRITE")
            val area = driver(data.second.keys) ?: error("운전석 개별 온도 미지원")
            val units = checkNotNull(data.third) { "온도 증분 미제공" }
            check(units.incrementCelsius > 0 && units.incrementCelsius.isFinite() && units.minCelsius.isFinite() && units.maxCelsius.isFinite())
            check(units.minCelsius <= units.maxCelsius)
            check(units.minCelsius >= data.second.getValue(area).minValue && units.maxCelsius <= data.second.getValue(area).maxValue)
            area to units
        }
        direction = optionalHardware {
            val data = vehicleRequest<Pair<CapabilityConfig, Map<SeatArea, List<FanDirectionType>>>> { ok, fail ->
                api.checkHvacFanDirectionCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail)
            }
            check(data.first.accessType.name == "READ_WRITE")
            driver(data.second.keys)?.takeIf { FanDirectionType.FLOOR in data.second.getValue(it) }
        }
        // Auto recirculation is part of the snapshot; without its state, do not fight a vehicle controller.
        autoRecirculation = optionalHardware {
            val data = vehicleRequest<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                api.checkHvacAutoRecirculationCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail)
            }
            if (data.first.accessType == AccessType.NONE && data.second.isEmpty()) {
                autoRecirculationAbsent = true
                return@optionalHardware null
            }
            check(data.first.accessType.name == "READ_WRITE")
            data.second.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
        }
        recirculation = optionalHardware {
            val data = vehicleRequest<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                api.checkHvacRecirculationCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail)
            }
            check(data.first.accessType.name == "READ_WRITE" && (autoRecirculation != null || autoRecirculationAbsent))
            data.second.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
        }
        ac = optionalHardware {
            val data = vehicleRequest<List<SeatArea>> { ok, fail -> api.checkHvacAcCapabilities(onComplete = ok, onFailed = fail) }
            data.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
        }
    }
    suspend fun cooling(): Boolean = optionalHardware {
        ac?.let { area -> vehicleRequest<Boolean> { ok, fail -> api.getHvacAcState(area, onComplete = ok, onFailed = fail) } }
    } == true
    override suspend fun read(): InterruptValues {
        val result = try { FanControl.encode(client.read()).toMutableMap() }
            catch (e: TimeoutCancellationException) { throw IllegalStateException("공조 응답 시간 초과", e) }
        recirculation?.let { a -> result["recirculation"] = vehicleRequest<Boolean> { ok, fail -> api.getHvacRecirculationState(a, onComplete = ok, onFailed = fail) }.toString() }
        if (recirculation != null) autoRecirculation?.let { a -> result["autoRecirculation"] = vehicleRequest<Boolean> { ok, fail -> api.getHvacAutoRecirculationState(a, onComplete = ok, onFailed = fail) }.toString() }
        return result
    }
    fun target(before: InterruptValues, mode: InterruptMode, thermal: ThermalMode): InterruptValues {
        if (before["power"] != "true" || before["defrost"] != "false" || before.getValue("direction").contains("DEFROST")) return before
        val v = before.toMutableMap()
        val fan = before.getValue("fan").toInt()
        // Never switch on HVAC or increase an already lower fan just to reach an interrupt preset.
        v["fan"] = (if (mode == InterruptMode.STATE_INTERRUPT_CALL) InterruptPolicy.callFan(fan, client.minFan, client.maxFan)
            else minOf(fan, client.minFan)).toString()
        if (mode == InterruptMode.STATE_INTERRUPT_CALL) {
            if (direction != null && before["direction"] == "FACE") v["direction"] = "FLOOR"
            temperature?.second?.let { t ->
                v["temperature"] = InterruptPolicy.temperature(before.getValue("temperature").toFloat(), thermal, t.minCelsius, t.maxCelsius, t.incrementCelsius).toString()
            }
        } else if ("recirculation" in before) {
            v["recirculation"] = "true"
            if ("autoRecirculation" in before) v["autoRecirculation"] = "false"
        }
        if (v != before) v["auto"] = "false"
        return v
    }
    override suspend fun write(target: InterruptValues, expected: InterruptValues, confirmed: (InterruptValues) -> Unit) {
        var progress = expected.toMap()
        suspend fun step(key: String, action: suspend () -> Unit) {
            if (progress[key] == target[key]) return
            val actual = read()
            // AUTO recirculation may choose a new inlet state immediately when restored.
            if (progress["autoRecirculation"] == "true" && target["autoRecirculation"] == "true") {
                check(actual - "recirculation" == progress - "recirculation") { "공조 직접 변경 감지" }
                progress = actual
            } else check(actual == progress) { "공조 직접 변경 감지" }
            check(progress["power"] == "true" && progress["defrost"] == "false" && !progress.getValue("direction").contains("DEFROST")) { "제상·전원 설정 보호" }
            action()
            progress = progress + (key to target.getValue(key)); confirmed(progress)
        }
        suspend fun climateRequest(action: suspend () -> Unit) {
            try { action() } catch (e: TimeoutCancellationException) { throw IllegalStateException("공조 응답 시간 초과", e) }
        }
        if (target["auto"] == "false") step("auto") { climateRequest { client.setAuto(false) } }
        if (target["autoRecirculation"] == "false") step("autoRecirculation") {
            val v = vehicleRequest<Boolean> { ok, fail -> api.setHvacAutoRecirculation(autoRecirculation!!, false, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }; check(!v)
        }
        step("fan") { climateRequest { client.setFan(target.getValue("fan").toInt()) } }
        step("direction") {
            val value = FanDirectionType.valueOf(target.getValue("direction"))
            val result = vehicleRequest<FanDirectionType> { ok, fail -> api.setHvacFanDirection(direction!!, value, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }; check(result == value)
        }
        step("temperature") {
            val value = target.getValue("temperature").toFloat()
            val result = vehicleRequest<Float> { ok, fail -> api.setHvacTemperature(temperature!!.first, value, TemperatureUnit.CELSIUS, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }; check(result == value)
        }
        if ("recirculation" in target) step("recirculation") {
            val value = target.getValue("recirculation").toBooleanStrict()
            val result = vehicleRequest<Boolean> { ok, fail -> api.setHvacRecirculation(recirculation!!, value, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }; check(result == value)
        }
        if (target["autoRecirculation"] == "true") step("autoRecirculation") {
            val v = vehicleRequest<Boolean> { ok, fail -> api.setHvacAutoRecirculation(autoRecirculation!!, true, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }; check(v)
        }
        if (target["auto"] == "true") step("auto") { climateRequest { client.setAuto(true) } }
    }
    override fun restoredMatches(original: InterruptValues, current: InterruptValues): Boolean {
        val ignored = buildSet { if (original["auto"] == "true") add("fan"); if (original["autoRecirculation"] == "true") add("recirculation") }
        return original.filterKeys { it !in ignored } == current.filterKeys { it !in ignored }
    }
}

internal class InterruptWindows(context: Context, private val event: (String, String, String?) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val cleanup = mutableListOf<() -> Unit>()
    private var closed = false
    val ports = linkedMapOf<String, InterruptPort>()
    var driver: InterruptPort? = null
        private set
    var systemControlling = false
        private set
    var suppressOpeningUntil = 0L
        private set
    suspend fun discover() {
        vehicle.initialize()
        val api = vehicle.getWindow()
        val data = vehicleRequest<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
            api.checkWindowPositionCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = fail)
        }
        check(data.first.accessType.name in listOf("READ", "READ_ONLY", "READ_WRITE"))
        for ((a, bounds) in data.second) {
            if (a.values.size != 1 || !a.values[0].name.startsWith("ROW_") || bounds.minValue != 0 || bounds.maxValue != 100) continue
            val key = "window_${a.values[0].name}"
            val p = object : InterruptPort {
                override val id = key
                override suspend fun read(): InterruptValues {
                    val value = vehicleRequest<Int> { ok, fail -> api.getCurrentWindowPosition(a, onComplete = ok, onFailed = fail) }
                    check(value in 0..100)
                    return mapOf("value" to value.toString())
                }
                override suspend fun write(target: InterruptValues, expected: InterruptValues, confirmed: (InterruptValues) -> Unit) {
                    check(data.first.accessType.name == "READ_WRITE") { "창문 쓰기 권한 미지원" }
                    check(read() == expected) { "창문 직접 변경 감지" }
                    val value = target.getValue("value").toInt(); require(value in 0..100)
                    systemControlling = true
                    try {
                        val actual = vehicleRequest<Int> { ok, fail -> api.setWindowPosition(a, value, onComplete = { _, v -> ok(v) }, onFailed = fail) }
                        check(actual == value) { "창문 명령 확인 실패" }
                        // Ack is not motion completion. Bound the settling period and stop on reverse movement.
                        val deadline = SystemClock.elapsedRealtime() + 4_000
                        var previous = expected.getValue("value").toInt()
                        do {
                            val current = read().getValue("value").toInt()
                            if (current == value) { confirmed(target); return }
                            check(if (value < previous) current in value..previous else current in previous..value) { "창문 이동 방향 변경 · 직접 설정 유지" }
                            previous = current; delay(100)
                        } while (SystemClock.elapsedRealtime() < deadline)
                        error("창문 이동 완료 미확인")
                    } finally {
                        systemControlling = false
                        suppressOpeningUntil = SystemClock.elapsedRealtime() + 3_000
                    }
                }
            }
            if (a.values[0] == WindowValue.ROW_1_LEFT) driver = p
            if (data.first.accessType.name == "READ_WRITE") ports[key] = p
            val listener = object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) { main.post { if (!closed) event(key, "value", value?.toString()) } }
                override fun onFailed(e: Exception) { main.post { if (!closed) event(key, "value", null) } }
            }
            api.registerWindowPosition(a, listener); cleanup += { api.unregisterWindowPosition(a, listener) }
        }
    }
    suspend fun driverPosition(): Int? = optionalHardware { driver?.read()?.get("value")?.toInt() }
    fun close() { closed = true; cleanup.forEach { runCatching(it) }; cleanup.clear(); runCatching { vehicle.release() } }
}
