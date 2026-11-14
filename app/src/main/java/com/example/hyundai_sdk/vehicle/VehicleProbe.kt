package com.example.hyundai_sdk.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Raw read-only diagnostics. Do not feed unvalidated values into trip recording.
 * All methods must run on a worker thread. No UI and no vehicle writes.
 */
data class Reading<T>(val value: T? = null, val error: String? = null) {
    val ok: Boolean get() = error == null && value != null
}
data class ProbeSnapshot(
    val distanceRaw: Reading<Float>,
    val pedalPressed: Reading<Boolean>,
    val parkingBrakeOn: Reading<Boolean>
)
class VehicleProbe(context: Context): AutoCloseable {
    private val context = context.applicationContext
    private val vehicle = Vehicle(this.context)
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var parkingConnectionError: String? = null
    private var started = false
    private var closed = false

    private fun workerOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run VehicleProbe on a worker thread" }
    }
    fun open() {
        workerOnly(); check(!started && !closed) { "Probe already opened or closed" }
        vehicle.initialize()
        started = true
        // Keep AAOS failure separate: SDK reads must still be diagnosable.
        try {
            car = Car.createCar(context)
            properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (properties == null) parkingConnectionError = "AAOS property service unavailable"
        } catch (e: Exception) { parkingConnectionError = describe(e) }
    }
    fun sample(): ProbeSnapshot {
        workerOnly(); check(started && !closed) { "Probe is not open" }
        val distance = await<Float> { success, failure ->
            vehicle.getOdometer().getCurrentDistanceDriven(success, failure)
        }
        val pedal = await<Boolean> { success, failure ->
            vehicle.getBrake().getCurrentBrakePedalStatus(success, failure)
        }
        val parking = try {
            val manager = properties
            if (manager == null) Reading<Boolean>(error = parkingConnectionError ?: "AAOS not connected")
            else {
                // A missing/denied/unavailable property is never converted to false.
                val property = manager.getProperty<Boolean>(VehiclePropertyIds.PARKING_BRAKE_ON, 0)
                if (property == null || property.status != 0)
                    Reading<Boolean>(error = "PARKING_BRAKE_ON unavailable; status=${property?.status}")
                else Reading(value = property.value)
            }
        } catch (e: Exception) { Reading<Boolean>(error = describe(e)) }
        return ProbeSnapshot(distance, pedal, parking)
    }
    private fun <T> await(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): Reading<T> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Reading<T>?>(null)
        fun complete(value: Reading<T>) {
            if (result.compareAndSet(null, value)) latch.countDown()
        }
        try {
            call({ value -> complete(if (value == null) Reading(error = "SDK returned null") else Reading(value = value)) },
                { e -> complete(Reading(error = describe(e))) })
        } catch (e: Exception) { complete(Reading(error = describe(e))) }
        if (!latch.await(3, TimeUnit.SECONDS)) complete(Reading(error = "SDK callback timeout (3s)"))
        return requireNotNull(result.get())
    }
    override fun close() {
        workerOnly()
        if (closed) return
        closed = true
        try { if (started) vehicle.release() } finally { car?.disconnect() }
    }
    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message}"
}
