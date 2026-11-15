package ai.pleos.fornow

import ai.pleos.fornow.core.ClimateSnapshot
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All writes go through public Pleos APIs. No VHAL writes, privileged shell, or simulated fallback. */
class PleosClimate(context: Context, private val event: (String, Any?) -> Unit, private val error: (Throwable) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    internal val hvac: HVAC get() = vehicle.getHVAC()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private lateinit var fanArea: SeatArea
    private lateinit var autoArea: SeatArea
    private lateinit var powerArea: SeatArea
    private lateinit var temperatureArea: SeatArea
    private lateinit var directionArea: SeatArea
    private var maxDefrostArea: SeatArea? = null
    private var frontDefrostArea: WindowArea? = null
    var minFan = 1
        private set
    var maxFan = 8
        private set
    var areaLabel = "운전석 공조"
        private set
    private val unregister = mutableListOf<() -> Unit>()
    private fun dispatch(block: () -> Unit) { main.post { if (!closed) block() } }
    private fun changed(key: String, value: Any?) = dispatch { event(key, value) }
    private fun failed(e: Exception) = dispatch { error(e) }

    private suspend fun <T : Any> request(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(5_000) {
        suspendCancellableCoroutine { continuation ->
            try {
                call({ value -> if (continuation.isActive) { if (value != null) continuation.resume(value) else continuation.resumeWithException(IllegalStateException("차량 값이 비어 있어요.")) } },
                    { if (continuation.isActive) continuation.resumeWithException(it) })
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    }

    private fun area(areas: Collection<SeatArea>): SeatArea = areas
        .filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
        ?: throw IllegalStateException("운전석 공조 기능을 지원하지 않아요.")

    suspend fun connect() {
        vehicle.initialize()
        val fanConfig = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(onComplete = { config, bounds -> ok(config to bounds) }, onFailed = fail)
        }
        check(fanConfig.first.accessType.name == "READ_WRITE") { "풍량 읽기·쓰기를 지원하지 않는 차량이에요." }
        fanArea = area(fanConfig.second.keys)
        val range = fanConfig.second.getValue(fanArea)
        minFan = maxOf(1, range.minValue); maxFan = range.maxValue
        check(minFan <= maxFan) { "지원 풍량 범위를 확인하지 못했어요." }
        areaLabel = if (fanArea.values.size > 1) "공용 공조 · 앞좌석 함께 적용" else "운전석 공조"
        val autoConfig = request<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacAutoCapabilities(onComplete = { config, areas -> ok(config to areas) }, onFailed = fail)
        }
        check(autoConfig.first.accessType.name == "READ_WRITE") { "AUTO 읽기·쓰기를 지원하지 않는 차량이에요." }
        autoArea = area(autoConfig.second)
        powerArea = area(request<List<SeatArea>> { ok, fail -> hvac.checkHvacPowerCapabilities(onComplete = ok, onFailed = fail) })
        temperatureArea = area(request<Map<SeatArea, AreaBounds<Float>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(onComplete = ok, onFailed = fail)
        }.keys)
        directionArea = area(request<Map<SeatArea, List<ai.pleos.playground.vehicle.constant.control.FanDirectionType>>> { ok, fail ->
            hvac.checkHvacFanDirectionCapabilities(onComplete = ok, onFailed = fail)
        }.keys)
        maxDefrostArea = request<List<SeatArea>> { ok, fail -> hvac.checkHvacMaxDefrostCapabilities(onComplete = ok, onFailed = fail) }
            .filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
        frontDefrostArea = request<List<WindowArea>> { ok, fail -> hvac.checkHvacDefrosterCapabilities(onComplete = ok, onFailed = fail) }
            .firstOrNull { WindowValue.FRONT_WINDSHIELD in it.values }
        subscribe()
    }

    suspend fun read(): ClimateSnapshot {
        check(!closed)
        val fan = request<Int> { ok, fail -> hvac.getHvacFanSpeed(fanArea, onComplete = ok, onFailed = fail) }
        val auto = request<Boolean> { ok, fail -> hvac.getHvacAutoState(autoArea, onComplete = ok, onFailed = fail) }
        val power = request<Boolean> { ok, fail -> hvac.getHvacPowerState(powerArea, onComplete = ok, onFailed = fail) }
        val temperature = request<Float> { ok, fail -> hvac.getHvacTemperature(temperatureArea, TemperatureUnit.CELSIUS, onComplete = ok, onFailed = fail) }
        val direction = request<ai.pleos.playground.vehicle.constant.control.FanDirectionType> { ok, fail -> hvac.getHvacFanDirection(directionArea, onComplete = ok, onFailed = fail) }
        val maxDefrost = maxDefrostArea?.let { a -> request<Boolean> { ok, fail -> hvac.getHvacMaxDefrostState(a, onComplete = ok, onFailed = fail) } } ?: false
        val frontDefrost = frontDefrostArea?.let { a -> request<Boolean> { ok, fail -> hvac.getHvacDefrosterState(a, onComplete = ok, onFailed = fail) } } ?: false
        check(fan in 0..maxFan && temperature.isFinite() && direction.name != "UNKNOWN") { "공조 상태를 정확히 읽지 못했어요." }
        return ClimateSnapshot(fan, auto, power, temperature, direction.name, maxDefrost || frontDefrost)
    }

    suspend fun setFan(value: Int) {
        require(value in minFan..maxFan)
        val result = request<Int> { ok, fail -> hvac.setHvacFanSpeed(fanArea, value, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }
        check(result == value) { "요청한 풍량이 적용되지 않았어요." }
    }

    suspend fun setAuto(value: Boolean) {
        val result = request<Boolean> { ok, fail -> hvac.setHvacAuto(autoArea, value, onComplete = { _, actual -> ok(actual) }, onFailed = fail) }
        check(result == value) { "AUTO 변경이 적용되지 않았어요." }
    }

    private fun subscribe() {
        val fan = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(value: Int?) = changed("fan", value)
            override fun onFailed(e: Exception) = failed(e)
        }
        hvac.registerHvacFanSpeed(fanArea, fan); unregister += { hvac.unregisterHvacFanSpeed(fanArea, fan) }
        val auto = object : HvacAutoStateListener {
            override fun onHvacAutoStateUpdated(value: Boolean?) = changed("auto", value)
            override fun onFailed(e: Exception) = failed(e)
        }
        hvac.registerHvacAutoState(autoArea, auto); unregister += { hvac.unregisterHvacAutoState(autoArea, auto) }
        val power = object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(value: Boolean?) = changed("power", value)
            override fun onFailed(e: Exception) = failed(e)
        }
        hvac.registerHvacPowerState(powerArea, power); unregister += { hvac.unregisterHvacPowerState(powerArea, power) }
        val temperature = object : HvacTemperatureValueListener {
            override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) = changed("temperature", celsius)
            override fun onFailed(e: Exception) = failed(e)
        }
        hvac.registerHvacTemperature(temperatureArea, temperature); unregister += { hvac.unregisterHvacTemperature(temperatureArea, temperature) }
        maxDefrostArea?.let { a ->
            val listener = object : HvacMaxDefrostStateListener {
                override fun onHvacMaxDefrostStateUpdated(value: Boolean?) { if (value != false) changed("defrost", value) }
                override fun onFailed(e: Exception) = failed(e)
            }
            hvac.registerHvacMaxDefrostState(a, listener); unregister += { hvac.unregisterHvacMaxDefrostState(a, listener) }
        }
        frontDefrostArea?.let { a ->
            val listener = object : HvacDefrosterStateListener {
                override fun onHvacDefrosterStateUpdated(value: Boolean?) { if (value != false) changed("defrost", value) }
                override fun onFailed(e: Exception) = failed(e)
            }
            hvac.registerHvacDefrosterState(a, listener); unregister += { hvac.unregisterHvacDefrosterState(a, listener) }
        }
    }

    fun close() {
        closed = true
        unregister.forEach { runCatching(it) }; unregister.clear()
        runCatching { vehicle.release() }
    }
}
