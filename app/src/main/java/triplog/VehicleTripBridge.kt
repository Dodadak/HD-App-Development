package triplog

/** Normalized fresh observation. Android adapter owns unit conversion and signal freshness.
 * A single confirmed stop is a candidate, never automatic proof of trip completion.
 */
class VehicleTripBridge(private val manager: TripLogManager) {
    private var candidateKm: Double? = null
    private var lastKm: Double? = null
    @Synchronized fun beginConfirmed(km: Double, source: Source): Trip {
        val trip = manager.start(km, source)
        candidateKm = null; lastKm = km
        return trip
    }
    @Synchronized fun observe(km: Double?, parkingBrake: Boolean?): Boolean {
        candidateKm = null
        if (km == null || !km.isFinite() || km < 0 || parkingBrake == null) return false
        val active = manager.list().find { it.end == null } ?: return false
        if (km < active.startKm || (lastKm != null && km < requireNotNull(lastKm))) return false
        lastKm = km
        if (parkingBrake && km > active.startKm) candidateKm = km
        return candidateKm != null
    }
    @Synchronized fun confirmArrival(): Trip? {
        val km = candidateKm ?: error("No valid arrival candidate; read fresh signals first")
        candidateKm = null
        val active = manager.list().find { it.end == null } ?: error("No active trip")
        return if (active.mode == RecordingMode.SEGMENT) manager.finish(km) else null
    }
}

/** Calibration is restricted to known emulator fixture values, not production unit discovery. */
object EmulatorDistanceCalibration {
    fun multiplier(raw: Double, expectedKm: Double): Double {
        require(expectedKm >= 100 && raw.isFinite())
        return when {
            kotlin.math.abs(raw - expectedKm) < 0.01 -> 1.0
            kotlin.math.abs(raw / 1000.0 - expectedKm) < 0.01 -> 0.001
            else -> error("Console/SDK distance mismatch: raw=$raw expectedKm=$expectedKm; do not record")
        }
    }
}
