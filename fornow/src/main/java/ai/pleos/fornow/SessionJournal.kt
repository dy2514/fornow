package ai.pleos.fornow

import ai.pleos.fornow.core.SettingLease
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable intent is written BEFORE touching the car. An interrupted transaction is never replayed. */
class SessionJournal(context: Context) {
    private val prefs = context.getSharedPreferences("temporary_hvac_v1", Context.MODE_PRIVATE)
    fun pending(): Boolean = prefs.contains("pending")
    fun recordInterrupt(snapshot: ai.pleos.fornow.core.VehicleStateSnapshot, mode: String): Boolean = prefs.edit()
        .putString("pending", JSONObject().apply {
            put("mode", mode); put("createdAt", System.currentTimeMillis())
            put("snapshot", JSONObject().apply { snapshot.values.forEach { (id, values) -> put(id, JSONObject(values)) } })
        }.toString()).commit()
    fun recordPlan(leases: List<SettingLease>, duration: Long): Boolean = prefs.edit().putString("pending", JSONObject().apply {
        put("durationMs", duration); put("createdAt", System.currentTimeMillis())
        put("controls", JSONArray().apply { leases.forEach { lease -> put(JSONObject().apply {
            put("id", lease.key); put("original", JSONObject(lease.original)); put("temporary", JSONObject(lease.expected))
        }) } })
    }.toString()).commit()
    fun finish(message: String): Boolean {
        val entries = listOf(message) + history()
        return prefs.edit().remove("pending").putString("history", JSONArray(entries.take(4)).toString()).commit()
    }
    fun history(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
}
