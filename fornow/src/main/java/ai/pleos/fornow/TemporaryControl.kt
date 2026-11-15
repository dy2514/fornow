package ai.pleos.fornow

import ai.pleos.fornow.core.ClimateSnapshot
import ai.pleos.fornow.core.FanSession

typealias Settings = Map<String, String>

interface TemporaryControl {
    val id: String
    val title: String
    val description: String
    suspend fun read(): Settings
    fun target(before: Settings): Settings
    suspend fun write(values: Settings, expectedBefore: Settings)
    suspend fun writeTracked(values: Settings, expectedBefore: Settings, confirmed: (Settings) -> Unit) {
        write(values, expectedBefore)
        confirmed(values)
    }
    fun restoredMatches(before: Settings, current: Settings): Boolean = before == current
    fun format(values: Settings): String
}

class FanControl(private val client: PleosClimate) : TemporaryControl {
    override val id = "fan"
    override val title = "바람 조용하게"
    override val description get() = "풍량 ${client.minFan}단계 · ${client.areaLabel}"
    override suspend fun read() = encode(client.read())
    override fun target(before: Settings): Settings {
        if (before.getValue("fan").toInt() <= client.minFan) return before
        // Reuse the strictly validated fan policy; it rejects defrost, power-off and no-op requests.
        val s = FanSession(decode(before), client.minFan, 60_000, "validation")
        return encode(s.expected)
    }
    override suspend fun write(values: Settings, expectedBefore: Settings) {
        val desired = decode(values)
        val current = client.read()
        check(encode(current) == expectedBefore) { "직접 바뀐 공조 설정을 유지해요." }
        check(desired.sameContext(current)) { "공조 상태가 바뀌어 추가 변경을 멈췄어요." }
        if (current.auto) client.setAuto(false)
        val beforeFan = client.read()
        check(desired.sameContext(beforeFan) && !beforeFan.auto) { "공조 상태가 바뀌었어요." }
        client.setFan(desired.fan)
        val afterFan = client.read()
        check(desired.sameContext(afterFan) && !afterFan.auto && afterFan.fan == desired.fan) { "풍량 변경을 확인하지 못했어요." }
        if (desired.auto) client.setAuto(true)
    }
    override fun restoredMatches(before: Settings, current: Settings): Boolean {
        val old = decode(before); val now = decode(current)
        return old.sameContext(now) && old.auto == now.auto && (old.auto || old.fan == now.fan)
    }
    override fun format(values: Settings) = "풍량 ${values["fan"]} · ${if (values["auto"] == "true") "AUTO" else "수동"}"
    companion object {
        fun encode(s: ClimateSnapshot): Settings = mapOf("fan" to s.fan.toString(), "auto" to s.auto.toString(),
            "power" to s.power.toString(), "temperature" to s.temperature.toString(), "direction" to s.direction, "defrost" to s.defrost.toString())
        fun decode(v: Settings) = ClimateSnapshot(v.getValue("fan").toInt(), v.getValue("auto").toBooleanStrict(),
            v.getValue("power").toBooleanStrict(), v.getValue("temperature").toFloat(), v.getValue("direction"), v.getValue("defrost").toBooleanStrict())
    }
}

class IntegerControl(
    override val id: String,
    override val title: String,
    override val description: String,
    private val get: suspend () -> Int,
    private val set: suspend (Int) -> Unit,
    private val choose: (Int) -> Int,
    private val display: (Int) -> String = { "$it 단계" },
) : TemporaryControl {
    override suspend fun read(): Settings = mapOf("value" to get().toString())
    override fun target(before: Settings): Settings = mapOf("value" to choose(before.getValue("value").toInt()).toString())
    override suspend fun write(values: Settings, expectedBefore: Settings) {
        check(read() == expectedBefore) { "직접 바뀐 설정을 유지해요." }
        set(values.getValue("value").toInt())
    }
    override fun format(values: Settings) = display(values.getValue("value").toInt())
}
