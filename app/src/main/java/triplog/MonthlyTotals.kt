package triplog

import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId

data class MonthlyTotals(val trips: Int, val businessKm: BigDecimal, val personalKm: BigDecimal,
                         val unresolvedKm: BigDecimal) {
    val totalKm: BigDecimal get() = businessKm + personalKm + unresolvedKm
}

fun TripLogManager.monthlyTotals(month: YearMonth, zone: ZoneId = ZoneId.of("Asia/Seoul")): MonthlyTotals {
    val trips = list().filter { it.end != null && YearMonth.from(it.start.atZone(zone)) == month }
    val byCategory = trips.groupBy { if (it.confirmed) it.category else Category.UNCLASSIFIED }
    fun km(category: Category) = byCategory[category].orEmpty().fold(BigDecimal.ZERO) { sum, trip ->
        sum + (BigDecimal.valueOf(requireNotNull(trip.endKm)) - BigDecimal.valueOf(trip.startKm))
    }
    return MonthlyTotals(trips.size, km(Category.BUSINESS), km(Category.PERSONAL), km(Category.UNCLASSIFIED))
}
