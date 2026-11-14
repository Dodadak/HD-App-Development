package triplog

import java.time.Duration
import java.time.Instant

enum class MotionEvidence { SPEED, ODOMETER_INCREASE }

enum class SelectedGear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
data class MotionSample(val at: Instant, val km: Double?, val gear: SelectedGear,
                        val speedKph: Double?, val source: Source)

/** Explicit selected-gear policy with a host-chosen movement evidence source.
 * Records from a recently observed stationary baseline and asks for arrival confirmation.
 */
class MotionTripRecorder(private val manager: TripLogManager,
                         private val now: () -> Instant = { Instant.now() },
                         private val maxAgeMillis: Long = 5000,
                         private val stopDwellMillis: Long = 5000,
                         private val evidence: MotionEvidence = MotionEvidence.SPEED) {
    private var last: MotionSample? = null
    private var baseline: MotionSample? = null
    private var stopSince: Instant? = null
    private var stopTripId: String? = null
    private var candidate: Pair<String, MotionSample>? = null
    private var owner: String? = null
    init { require(maxAgeMillis > 0 && stopDwellMillis >= 0) }

    @Synchronized fun reset() {
        last = null; baseline = null; stopSince = null; stopTripId = null; candidate = null; owner = null
    }
    @Synchronized fun accept(sample: MotionSample): SignalResult = synchronized(manager) {
        val actor = manager.currentUser().id
        if (owner != actor) reset()
        owner = actor
        val before = last
        val km = sample.km
        val speed = sample.speedKph
        val age = Duration.between(sample.at, now()).toMillis()
        candidate = null
        val distanceEvidence = evidence == MotionEvidence.ODOMETER_INCREASE
        val stationary = speed == 0.0 || (distanceEvidence && speed == null)
        if (km == null || !km.isFinite() || km < 0 ||
            (!distanceEvidence && speed == null) || (speed != null && (!speed.isFinite() || speed < 0)) ||
            sample.gear == SelectedGear.UNKNOWN || age !in 0..maxAgeMillis ||
            (before != null && (sample.at.isBefore(before.at) || sample.source != before.source ||
                km < requireNotNull(before.km) || Duration.between(before.at,sample.at).toMillis() > maxAgeMillis))) {
            reset()
            return@synchronized SignalResult("MOTION_INVALID_OR_STALE")
        }
        last = sample
        val active = manager.list().singleOrNull { it.end == null }
        if (active != null && (active.source != sample.source || km < active.startKm)) {
            reset()
            return@synchronized SignalResult("MOTION_SOURCE_OR_DISTANCE_MISMATCH")
        }
        if (active == null) {
            stopSince = null; stopTripId = null
            if (sample.gear == SelectedGear.PARK && stationary) {
                baseline = sample
                return@synchronized SignalResult("MOTION_BASELINE_READY")
            }
            val start = baseline
            val driving = sample.gear == SelectedGear.DRIVE || sample.gear == SelectedGear.REVERSE
            val movement = if (distanceEvidence) start != null && km > requireNotNull(start.km)
                else speed != null && speed > 0.0
            if (start != null && driving && movement) {
                val trip = manager.start(requireNotNull(start.km),sample.source)
                baseline = null
                return@synchronized SignalResult("MOTION_STARTED",trip)
            }
            return@synchronized SignalResult("MOTION_WAITING_BASELINE_OR_MOVEMENT")
        }
        baseline = null
        if (sample.gear != SelectedGear.PARK || !stationary) {
            stopSince = null; stopTripId = null
            return@synchronized SignalResult("MOTION_RECORDING",active)
        }
        if (stopTripId != active.id || before == null || before.gear != SelectedGear.PARK || (before.speedKph != 0.0 && !(distanceEvidence && before.speedKph == null)) ||
            before.km != km || stopSince == null) {
            stopSince = sample.at
            stopTripId = active.id
        }
        if (Duration.between(requireNotNull(stopSince),sample.at).toMillis() < stopDwellMillis)
            return@synchronized SignalResult("MOTION_STOP_DWELL",active)
        candidate = active.id to sample
        SignalResult("MOTION_ARRIVAL_CONFIRM_REQUIRED",active)
    }

    @JvmOverloads
    @Synchronized fun confirmArrival(finishGroup: Boolean = false): SignalResult = synchronized(manager) {
        val (id, sample) = candidate ?: error("Read a valid stationary arrival candidate first")
        val age = Duration.between(sample.at, now()).toMillis()
        if (age !in 0..maxAgeMillis) { reset(); error("Arrival observation expired; read signals again") }
        val active = manager.list().singleOrNull { it.id == id && it.end == null }
            ?: error("The candidate trip is no longer active")
        if (active.mode == RecordingMode.MANUAL_GROUP && !finishGroup)
            return@synchronized SignalResult("MOTION_GROUP_CONTINUES",active)
        val finished = manager.finish(requireNotNull(sample.km))
        reset()
        SignalResult("FINISHED_REVIEW_REQUIRED",finished)
    }
}
