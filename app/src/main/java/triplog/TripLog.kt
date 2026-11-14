package triplog

import java.io.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.security.MessageDigest

enum class Role { DRIVER, ADMIN }
enum class Category { BUSINESS, PERSONAL, UNCLASSIFIED }
enum class RecordingMode { SEGMENT, MANUAL_GROUP }
enum class Source { SIMULATED, EMULATOR, VEHICLE }
/** Legacy serialized layout, retained only to read old files and discard credentials. */
data class Account(val id: String, val role: Role, val salt: ByteArray, val hash: ByteArray): Serializable
data class Trip(
    val id: String, val driverId: String, val start: Instant, val end: Instant?,
    val startKm: Double, val endKm: Double?, val mode: RecordingMode, val source: Source,
    val purpose: String = "", val category: Category = Category.UNCLASSIFIED,
    val suggested: Category = Category.UNCLASSIFIED, val classifier: String = "none",
    val confirmed: Boolean = false
): Serializable { val distanceKm: Double? get() = endKm?.minus(startKm) }
data class State(
    val accounts: List<Account> = emptyList(), val trips: List<Trip> = emptyList(),
    val modes: Map<String, RecordingMode> = emptyMap()
): Serializable

/** Implement this port with Room for Android. Call the facade from a background dispatcher. */
interface StateStore { fun load(): State; fun save(state: State) }
class MemoryStore: StateStore {
    private var state = State()
    override fun load() = state
    override fun save(state: State) { this.state = state }
}
/** Local JVM test storage only. Never import untrusted serialized files. Single writer/process. */
class FileStateStore(private val file: File): StateStore {
    private var expectedDigest: ByteArray? = null
    private fun digest(): ByteArray? = if (file.exists()) MessageDigest.getInstance("SHA-256").digest(file.readBytes()) else null
    @Synchronized override fun load(): State {
        if (!file.exists()) { expectedDigest = null; return State() }
        val bytes = file.readBytes()
        val loaded = ObjectInputStream(bytes.inputStream().buffered()).use { it.readObject() as State }
        expectedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return loaded
    }
    @Synchronized override fun save(state: State) {
        val parent = requireNotNull(file.absoluteFile.parentFile) {
            "Data file must have a parent directory"
        }
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create data directory" }
        java.io.RandomAccessFile(File(file.absolutePath + ".lock"), "rw").use { lockFile ->
            val lock = try { lockFile.channel.tryLock() }
                catch (_: java.nio.channels.OverlappingFileLockException) { null }
            check(lock != null) { "Another writer is saving this file" }
            try {
                check(java.util.Arrays.equals(expectedDigest, digest())) {
                    "State changed in another process; reopen and supply the host user again"
                }
                val temp = File.createTempFile("triplog-", ".tmp", parent)
                try {
                    FileOutputStream(temp).use { raw ->
                        val out = ObjectOutputStream(raw)
                        out.writeObject(state)
                        out.flush()
                        raw.fd.sync()
                    }
                    java.nio.file.Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    expectedDigest = digest()
                } finally { temp.delete() }
            } finally { lock.release() }
        }
    }
}
data class Classification(val category: Category, val engine: String)
interface PurposeClassifier { fun classify(text: String): Classification }
/** Deterministic fallback; this is NOT an LLM. Mixed/unknown language remains unclassified. */
class KeywordClassifier: PurposeClassifier {
    override fun classify(text: String): Classification {
        val business = listOf("거래처", "미팅", "견적", "계약", "납품", "출장", "회사 복귀").any { it in text }
        val personal = listOf("장보기", "개인", "마트", "영화", "친구").any { it in text }
        return Classification(when { business && !personal -> Category.BUSINESS
            personal && !business -> Category.PERSONAL
            else -> Category.UNCLASSIFIED }, "keyword-fallback")
    }
}
interface SpeechPort {
    fun speak(text: String)
    fun recognize(): String?
}
/** Identity and permission supplied by the authenticated host app. Never persisted as credentials. */
data class TripLogUser(val id: String, val displayName: String, val isAdmin: Boolean = false) {
    init {
        require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) { "Invalid user ID" }
        require(displayName.isNotBlank() && displayName.length <= 200 && displayName.none { it.isISOControl() }) { "Invalid display name" }
    }
    val role: Role get() = if (isAdmin) Role.ADMIN else Role.DRIVER
}

/** Host supplies identity. Ownership checks apply to every caller, including the console. */
class TripLogManager(private val store: StateStore,
    private val classifier: PurposeClassifier = ManualClassifier(),
    private val now: () -> Instant = { Instant.now() }) {
    private var state = store.load()
    private var user: TripLogUser? = null
    private fun commit(next: State) { store.save(next.copy(accounts = emptyList())); state = next.copy(accounts = emptyList()) }
    private fun actor(): TripLogUser = requireNotNull(user) { "Host user is required" }
    private fun admin() { check(actor().isAdmin) { "Admin required" } }
    private fun access(t: Trip) { val a = actor(); check(a.isAdmin || a.id == t.driverId) { "Access denied" } }

    /** Call only with the host's authenticated identity. Recoveries require owner or administrator. */
    @Synchronized fun useUser(value: TripLogUser) {
        val active = state.trips.singleOrNull { it.end == null }
        check(active == null || active.driverId == value.id || value.isAdmin) { "Another driver owns active trip" }
        // Remove legacy credentials on first attachment; retain trip IDs, history and recording modes.
        if (state.accounts.isNotEmpty()) commit(state.copy(accounts = emptyList()))
        user = value
    }
    @Synchronized fun currentUser(): TripLogUser = actor()
    @Synchronized fun recordPurpose(id: String, text: String): Trip {
        val trip = trip(id)
        check(trip.end != null) { "Trip still active" }
        val purpose = text.trim()
        require(purpose.isNotEmpty() && purpose.length <= 2000) { "Purpose: 1..2000 characters" }
        return replace(trip.copy(purpose = purpose, category = Category.UNCLASSIFIED,
            suggested = Category.UNCLASSIFIED, classifier = "manual", confirmed = false))
    }
    @Synchronized fun approve(id: String, purpose: String, category: Category): Trip {
        val trip = trip(id)
        check(trip.end != null) { "Trip still active" }
        require(purpose.isNotBlank() && purpose.trim().length <= 2000)
        require(category != Category.UNCLASSIFIED) { "Choose BUSINESS or PERSONAL, or defer" }
        return replace(trip.copy(purpose = purpose.trim(), category = category, suggested = Category.UNCLASSIFIED,
            classifier = "manual", confirmed = true))
    }
    @Synchronized fun recordingMode(): RecordingMode = state.modes[actor().id] ?: RecordingMode.SEGMENT
    @Synchronized fun setMode(mode: RecordingMode) {
        val a = actor(); commit(state.copy(modes = state.modes + (a.id to mode)))
    }
    @Synchronized fun start(km: Double, source: Source): Trip {
        val a = actor(); require(km.isFinite() && km >= 0) { "Invalid odometer" }
        check(state.trips.none { it.end == null }) { "Active trip already exists" }
        val trip = Trip(java.util.UUID.randomUUID().toString(), a.id, now(), null, km, null,
            state.modes[a.id] ?: RecordingMode.SEGMENT, source)
        commit(state.copy(trips = state.trips + trip)); return trip
    }
    @Synchronized fun finish(km: Double): Trip {
        val t = state.trips.find { it.end == null } ?: error("No active trip")
        access(t); require(km.isFinite() && km >= t.startKm) { "Odometer decreased/invalid" }
        val end = now(); require(!end.isBefore(t.start)) { "Clock moved backwards" }
        return replace(t.copy(end = end, endKm = km))
    }
    private fun replace(t: Trip): Trip { commit(state.copy(trips = state.trips.map { if (it.id == t.id) t else it })); return t }
    private fun trip(id: String): Trip = state.trips.find { it.id == id }?.also { access(it) } ?: error("Unknown trip")
    @Synchronized fun suggest(id: String, purpose: String): Trip {
        val t = trip(id); check(t.end != null) { "Trip still active" }; require(purpose.length <= 2000)
        val result = try { classifier.classify(purpose) } catch (_: Exception) { Classification(Category.UNCLASSIFIED, "failed") }
        return replace(t.copy(purpose = purpose, suggested = result.category, classifier = result.engine,
            category = Category.UNCLASSIFIED, confirmed = false))
    }
    // Speech and inference can block: UI adapters must invoke these on a worker dispatcher.
    fun capturePurpose(id: String, speech: SpeechPort): Trip {
        synchronized(this) { val t = trip(id); check(t.end != null) }
        val text = try { speech.recognize() } catch (_: Exception) { null }
        return suggest(id, text.orEmpty())
    }
    @Synchronized fun confirm(id: String, category: Category): Trip {
        val t = trip(id); check(t.end != null); return replace(t.copy(category = category, confirmed = true))
    }
    @Synchronized fun list(): List<Trip> { val a = actor(); return state.trips.filter { a.role == Role.ADMIN || it.driverId == a.id }.toList() }
    @Synchronized fun deleteTrip(id: String) { admin(); val t = trip(id); check(t.end != null); commit(state.copy(trips = state.trips.filterNot { it.id == id })) }
    @Synchronized fun csv(month: YearMonth, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
        fun cell(s: String): String { val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n')) "'$s" else s
            return "\"" + safe.replace("\"", "\"\"") + "\"" }
        val header = "id,driver,start,end,start_km,end_km,distance_km,purpose,category,suggestion,confirmed,mode,source,classifier"
        val rows = list().filter { it.end != null && YearMonth.from(it.start.atZone(zone)) == month }.map {
            listOf(it.id,it.driverId,it.start.atZone(zone).toString(),it.end!!.atZone(zone).toString(),
                it.startKm.toString(),it.endKm.toString(),it.distanceKm.toString(),it.purpose,it.category.name,
                it.suggested.name,it.confirmed.toString(),it.mode.name,it.source.name,it.classifier).joinToString(",", transform = ::cell)
        }
        return "\uFEFF" + (listOf(header) + rows).joinToString("\r\n") + "\r\n"
    }
}

/** Vehicle adapters call this AFTER validating timestamps/units/signal freshness.
 * This initial policy needs explicit arrival confirmation; a parking-brake event alone never ends a trip.
 * An Android/Pleos adapter is intentionally not implemented in this JVM milestone.
 */
class TripRecorder(private val manager: TripLogManager) {
    fun onDeparture(odometerKm: Double, source: Source) = manager.start(odometerKm, source)
    fun onArrivalCandidate(): String = "정차 후보입니다. 운행 종료를 확인해 주세요."
    fun confirmArrival(odometerKm: Double): Trip? {
        val active = manager.list().find { it.end == null } ?: error("No active trip")
        return if (active.mode == RecordingMode.SEGMENT) manager.finish(odometerKm) else null
    }
    fun finishGroup(odometerKm: Double) = manager.finish(odometerKm)
}
