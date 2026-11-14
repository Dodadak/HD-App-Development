package triplog

/** Use one manager per process. Completion is bound to the displayed trip ID. */
class TripReviewFlow(private val manager: TripLogManager) {
    fun finish(id: String, km: Double): Trip? = synchronized(manager) {
        val trip = manager.list().single { it.id == id }
        if (trip.end != null) null else manager.finish(km)
    }

    fun pending() = manager.list().filter { it.end != null && !it.confirmed }

    fun propose(id: String, text: String): Trip? =
        text.trim().takeIf { it.isNotEmpty() }?.let { manager.suggest(id, it) }
}

/** Feed validated, ordered ready-to-drive observations from one vehicle session.
 * Unknown signals clear the edge; attaching to an already-ready vehicle never starts a trip.
 */
class DepartureMonitor(private val manager: TripLogManager) {
    private var previous: Boolean? = null
    @Synchronized fun observe(ready: Boolean?, km: Double?, source: Source): Trip? {
        val before = previous
        if (ready == true && before == false && km != null && km.isFinite() && km >= 0) {
            synchronized(manager) {
                if (manager.list().none { it.end == null }) {
                    val trip = manager.start(km, source)
                    previous = true
                    return trip
                }
            }
        }
        previous = ready
        return null
    }
}
