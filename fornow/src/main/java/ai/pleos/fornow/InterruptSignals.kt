package ai.pleos.fornow

import ai.pleos.fornow.core.CallSignal
import android.Manifest
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RoadSignals(val gearD: Boolean?, val speedKmh: Float?, val outdoor: Float?)

/** Public AAOS reads only. A Boolean brake switch is deliberately not converted to pedal percentage. */
internal class InterruptRoadSignals(context: Context) {
    private var running = true
    @Volatile private var properties: CarPropertyManager? = null
    private var car: Car? = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
        if (!running) c.disconnect()
        else properties = if (ready) runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() else null
    }
    suspend fun read(): RoadSignals = withContext(Dispatchers.IO) {
        fun <T> value(type: Class<T>, id: Int, maxAgeMs: Long? = null): T? = runCatching {
            val property = properties?.getProperty(type, id, 0) ?: return@runCatching null
            if (property.status != CarPropertyValue.STATUS_AVAILABLE) return@runCatching null
            if (maxAgeMs != null) {
                val age = (SystemClock.elapsedRealtimeNanos() - property.timestamp) / 1_000_000
                if (property.timestamp <= 0 || age !in 0..maxAgeMs) return@runCatching null
            }
            property.value
        }.getOrNull()
        val gear = value(Int::class.javaObjectType, VehiclePropertyIds.GEAR_SELECTION)
        val speed = value(Float::class.javaObjectType, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1_000)?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) * 3.6f }
        val outside = value(Float::class.javaObjectType, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 30_000)?.takeIf { it.isFinite() && it in -60f..65f }
        RoadSignals(gear?.let { it == VehicleGear.GEAR_DRIVE }, speed, outside)
    }
    fun close() { running = false; properties = null; runCatching { car?.disconnect() }; car = null }
}

/** No numbers, contacts, microphone recording, dialer role or hidden Bluetooth API. */
internal class InterruptCallSignals(private val context: Context, private val wake: () -> Unit) {
    private var detail = CallSignal.UNKNOWN
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private var registered = false
    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            detail = when (state) { TelephonyManager.CALL_STATE_IDLE -> CallSignal.IDLE; TelephonyManager.CALL_STATE_RINGING -> CallSignal.RINGING; TelephonyManager.CALL_STATE_OFFHOOK -> CallSignal.OFFHOOK; else -> CallSignal.UNKNOWN }
            wake()
        }
    }
    val available get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM) && telecom != null
    @SuppressLint("MissingPermission")
    fun start() {
        if (available && context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            registered = runCatching { telephony?.registerTelephonyCallback(context.mainExecutor, callback); telephony != null }.getOrDefault(false)
        }
    }
    @SuppressLint("MissingPermission")
    fun read(): CallSignal {
        if (!available) return CallSignal.UNKNOWN
        return try {
            if (telecom!!.isInCall) {
                if (detail in listOf(CallSignal.RINGING, CallSignal.OFFHOOK)) detail else CallSignal.PRESENT
            } else if (detail in listOf(CallSignal.RINGING, CallSignal.OFFHOOK)) detail else CallSignal.IDLE
        } catch (_: Exception) { CallSignal.UNKNOWN }
    }
    fun close() { if (registered) runCatching { telephony?.unregisterTelephonyCallback(callback) }; registered = false; detail = CallSignal.UNKNOWN }
}
