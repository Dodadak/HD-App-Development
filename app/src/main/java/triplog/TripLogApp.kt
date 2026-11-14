package triplog

import java.io.File
import java.time.Instant
import java.time.YearMonth

/** One application instance per state file. Calls that access disk run on a worker thread. */
class TripLogApp(val trips: TripLogManager, val signals: SignalRecorder = SignalRecorder(trips),
                 private val details: ReportDetailStore? = null) {
    val motion by lazy { MotionTripRecorder(trips) }

    val distanceMotion by lazy {
        MotionTripRecorder(trips, evidence = MotionEvidence.ODOMETER_INCREASE)
    }

    @Synchronized fun useUser(user: TripLogUser) {
        if (runCatching { trips.currentUser() }.getOrNull() == user) return
        trips.useUser(user)
        signals.reset()
        motion.reset()
        distanceMotion.reset()
    }
    fun start(km: Double, source: Source) = synchronized(this) {
        signals.reset()
        motion.reset()
        distanceMotion.reset()
        trips.start(km, source)
    }
    fun finish(id: String, km: Double) = synchronized(this) {
        TripReviewFlow(trips).finish(id, km).also { signals.reset(); motion.reset(); distanceMotion.reset() }
    }
    fun review(id: String, text: String, category: Category) = trips.approve(id, text, category)
    fun defer(id: String, text: String) = trips.recordPurpose(id, text)
    fun pending() = TripReviewFlow(trips).pending()
    fun list() = trips.list()
    fun reportDetails(id: String): ReportDetails? = synchronized(trips) {
        check(trips.list().any { it.id == id }) { "Trip access denied" }
        requireNotNull(details) { "Report detail store is required" }.load()[id]
    }
    fun reportDetails(id: String, value: ReportDetails) = synchronized(trips) {
        check(trips.list().any { it.id == id }) { "Trip access denied" }
        requireNotNull(details) { "Report detail store is required" }.put(id,value)
    }
    fun report(month: YearMonth) = MonthlyReport.render(trips, month,
        requireNotNull(details) { "Report detail store is required" }.load())
    fun export(month: YearMonth, details: Map<String, ReportDetails>) =
        MonthlyReport.render(trips, month, details)

    companion object {
        fun open(file: File, user: TripLogUser): TripLogApp {
            val manager = TripLogManager(FileStateStore(file), ManualClassifier())
            return TripLogApp(manager, details = ReportDetailStore(File(file.absolutePath + ".report.properties"))).also { it.useUser(user) }
        }
    }
}

data class VehicleSample(val at: Instant, val km: Double?, val ready: Boolean?,
                         val parking: Boolean?, val speedKph: Double?, val source: Source)
data class SignalResult(val status: String, val trip: Trip? = null)

/** Input times use the app clock. Vehicle adapters must validate the age of each raw property.
 * A complete stop requires ready OFF, actual parking ON and zero speed for the configured dwell.
 */
class SignalRecorder(private val manager: TripLogManager,
                     private val now: () -> Instant = { Instant.now() },
                     private val autoStop: Boolean = true,
                     private val dwellMillis: Long = 3000,
                     private val maxAgeMillis: Long = 5000) {
    private var last: VehicleSample? = null
    private var stoppedAt: Instant? = null
    private var stoppingId: String? = null

    init { require(dwellMillis >= 0 && maxAgeMillis > 0) }
    @Synchronized fun reset() { last = null; stoppedAt = null; stoppingId = null }

    @Synchronized fun accept(sample: VehicleSample): SignalResult = synchronized(manager) {
        val clock = now()
        val previous = last
        // Distinct observations can share a wall-clock tick on Windows.
        // Accept their arrival order; equal timestamps contribute zero dwell time.
        val age = java.time.Duration.between(sample.at, clock).toMillis()
        val km = sample.km
        val speed = sample.speedKph
        if (age !in 0..maxAgeMillis || km == null || !km.isFinite() || km < 0 ||
            sample.ready == null || sample.parking == null || speed == null || !speed.isFinite() || speed < 0 ||
            (previous != null && sample.at.isBefore(previous.at))) {
            reset()
            return@synchronized SignalResult("INVALID_OR_STALE")
        }
        val active = manager.list().singleOrNull { it.end == null }
        if (active != null && (active.source != sample.source || km < active.startKm)) {
            reset()
            return@synchronized SignalResult("SOURCE_OR_DISTANCE_MISMATCH")
        }
        if (previous != null && previous.source == sample.source && km < requireNotNull(previous.km)) {
            reset()
            return@synchronized SignalResult("ODOMETER_DECREASE")
        }
        last = sample
        if (active == null) {
            stoppedAt = null; stoppingId = null
            if (previous?.ready == false && sample.ready && previous.source == sample.source &&
                java.time.Duration.between(previous.at, sample.at).toMillis() <= maxAgeMillis) {
                val started = manager.start(km, sample.source)
                return@synchronized SignalResult("STARTED", started)
            }
            return@synchronized SignalResult("WAITING_READY_EDGE")
        }
        val completeStop = !sample.ready && sample.parking && speed == 0.0
        if (!completeStop) {
            stoppedAt = null; stoppingId = null
            return@synchronized SignalResult(if (sample.parking) "ARRIVAL_CANDIDATE" else "RECORDING", active)
        }
        // Recovered trips may finish after stable stop samples without manufacturing another start.
        if (stoppingId != active.id || previous == null ||
            java.time.Duration.between(previous.at, sample.at).toMillis() > maxAgeMillis) {
            stoppingId = active.id; stoppedAt = sample.at
        }
        if (java.time.Duration.between(requireNotNull(stoppedAt), sample.at).toMillis() < dwellMillis)
            return@synchronized SignalResult("STOP_DWELL", active)
        if (!autoStop || active.mode == RecordingMode.MANUAL_GROUP)
            return@synchronized SignalResult("ARRIVAL_CANDIDATE", active)
        val finished = manager.finish(km)
        stoppedAt = null; stoppingId = null
        SignalResult("FINISHED_REVIEW_REQUIRED", finished)
    }
}
