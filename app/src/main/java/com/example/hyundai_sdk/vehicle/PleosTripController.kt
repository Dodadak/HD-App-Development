package com.example.hyundai_sdk.vehicle

import android.car.VehicleGear
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Looper
import triplog.*
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Host owns this lifecycle. Start after login, close before logout or Activity/service destruction.
 * Callbacks run on a dedicated worker. Use the main dispatcher to update a screen.
 * ignitionToReady is supplied after testing the target vehicle's ignition semantics.
 */
class PleosTripController(
    context: Context,
    private val app: TripLogApp,
    private val source: Source,
    private val ignitionToReady: (Int) -> Boolean?,
    private val callback: (SignalResult) -> Unit
) : AutoCloseable {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var probe: VehicleProbe? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var started = false
    private var motionPolicy = false
    private var distanceEvidence = false
    @Volatile internal var latestOdometerKm: Double? = null
        private set

    /** Enable only after selected-gear and vehicle-speed transitions pass on the target. */
    @Synchronized fun useMotionPolicy(): PleosTripController {
        check(!started && !executor.isShutdown)
        motionPolicy = true
        distanceEvidence = false
        return this
    }

    /** Use after selected gear and SDK odometer changes have passed on this AVD.
     * Stop results ask for confirmation. No synthetic speed is supplied.
     */
    @Synchronized fun useDistanceGearPolicy(): PleosTripController {
        check(!started && !executor.isShutdown)
        motionPolicy = true
        distanceEvidence = true
        return this
    }

    @Synchronized fun start() {
        check(!started && !executor.isShutdown) { "Controller already started or closed" }
        require(source != Source.SIMULATED) { "Use SignalRecorder for simulated inputs" }
        started = true
        executor.scheduleWithFixedDelay({
            val observedAt = Instant.now()
            var observedKm: Double? = null
            val result = try {
                if (properties == null) {
                    car?.disconnect()
                    val connected = Car.createCar(context) ?: error("Car service unavailable")
                    car = connected
                    properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        ?: error("Car property manager unavailable")
                }
                val p = requireNotNull(properties)
                fun <T> read(id: Int): T? {
                    val value = p.getProperty<T>(id,0)
                    return if (value != null && value.status == 0) value.value else null
                }
                val vehicle = probe ?: VehicleProbe(context).also { it.open(); probe = it }
                val sample = vehicle.sample()
                val km = sample.distanceRaw.value?.toDouble()
                observedKm = km?.takeIf { it.isFinite() && it >= 0.0 }
                val speed = if (distanceEvidence) null else read<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED)?.toDouble()?.let { kotlin.math.abs(it) * 3.6 }
                if (motionPolicy) {
                    val gear = when (read<Int>(VehiclePropertyIds.GEAR_SELECTION)) {
                        VehicleGear.GEAR_PARK -> SelectedGear.PARK
                        VehicleGear.GEAR_DRIVE -> SelectedGear.DRIVE
                        VehicleGear.GEAR_REVERSE -> SelectedGear.REVERSE
                        VehicleGear.GEAR_NEUTRAL -> SelectedGear.NEUTRAL
                        else -> SelectedGear.UNKNOWN
                    }
                    val recorder = if (distanceEvidence) app.distanceMotion else app.motion
                    recorder.accept(MotionSample(observedAt, km, gear, speed, source))
                } else {
                    val ignition = read<Int>(VehiclePropertyIds.IGNITION_STATE)
                    app.signals.accept(VehicleSample(observedAt,km,ignition?.let(ignitionToReady),
                        sample.parkingBrakeOn.value,speed,source))
                }
            } catch (e: Exception) {
                app.signals.reset()
                app.motion.reset()
                app.distanceMotion.reset()
                SignalResult("SIGNAL_UNAVAILABLE: ${e.javaClass.simpleName}: ${e.message}")
            }
            latestOdometerKm = when (result.status) {
                "MOTION_STARTED", "MOTION_RECORDING", "MOTION_STOP_DWELL", "MOTION_ARRIVAL_CONFIRM_REQUIRED" ->
                    observedKm?.takeIf { result.trip?.source == source && result.trip.end == null }
                else -> null
            }
            try { callback(result) } catch (e: Exception) {
                android.util.Log.e("PleosTripController","Result listener failed",e)
            }
        },0,1,TimeUnit.SECONDS)
    }

    override fun close() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Close controller on a worker thread" }
        executor.shutdown()
        check(executor.awaitTermination(15,TimeUnit.SECONDS)) { "Vehicle sampler is still stopping" }
        probe?.close()
        car?.disconnect()
        app.signals.reset()
        app.motion.reset()
        app.distanceMotion.reset()
    }
}
