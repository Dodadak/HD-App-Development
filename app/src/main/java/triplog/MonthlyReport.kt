package triplog

import java.time.YearMonth
import java.time.ZoneId
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.File
import java.util.Properties

data class ReportDetails(val company: String, val businessNumber: String, val vehicleType: String,
                         val plate: String, val department: String, val name: String, val commute: Boolean) {
    fun validate() {
        require(listOf(company, businessNumber, vehicleType, plate, department, name).all {
            it.isNotBlank() && it.length <= 200 && it.none { ch -> ch == '\u0000' }
        }) { "Complete company, business number, vehicle type, plate, department and name (1..200 characters)" }
    }
}

/** Additional per-trip snapshots; the legacy trip/account file is kept byte-compatible.
 * Invoke through the shared facade, which enforces per-trip access.
 */
class ReportDetailStore(private val file: File) {
    @Synchronized fun load(): Map<String, ReportDetails> {
        if (!file.exists()) return emptyMap()
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val ids = p.stringPropertyNames().filter { it.endsWith(".plate") }.map { it.removeSuffix(".plate") }
        return ids.associateWith { id ->
            fun value(key: String) = requireNotNull(p.getProperty("$id.$key")) { "Incomplete report metadata" }
            ReportDetails(value("company"),value("businessNumber"),value("vehicleType"),value("plate"),
                value("department"),value("name"),value("commute").toBooleanStrict()).also { it.validate() }
        }
    }
    @Synchronized fun put(id: String, details: ReportDetails) {
        details.validate()
        val parent = requireNotNull(file.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        java.io.RandomAccessFile(File(file.absolutePath + ".lock"),"rw").use { lockFile ->
            val lock = try { lockFile.channel.tryLock() }
                catch (_: java.nio.channels.OverlappingFileLockException) { null }
            check(lock != null) { "Another writer is saving report details" }
            try {
            val all = load() + (id to details)
            val p = Properties()
            for ((tripId, d) in all) {
                val values = mapOf("company" to d.company, "businessNumber" to d.businessNumber,
                    "vehicleType" to d.vehicleType, "plate" to d.plate, "department" to d.department,
                    "name" to d.name, "commute" to d.commute.toString())
                for ((key, value) in values) p.setProperty("$tripId.$key",value)
            }
            val temp = File.createTempFile("report-", ".tmp", parent)
            try {
                java.io.FileOutputStream(temp).use { p.store(it, "Trip report snapshots"); it.fd.sync() }
                java.nio.file.Files.move(temp.toPath(),file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            } finally { lock.release() }
        }
    }
}

object MonthlyReport {
    private fun h(s: Any?) = s.toString().replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    fun render(manager: TripLogManager, month: YearMonth, details: Map<String, ReportDetails>): String =
        synchronized(manager) {
            val zone = ZoneId.of("Asia/Seoul")
            val visible = manager.list()
            check(visible.none { it.end == null && !it.start.atZone(zone).toLocalDate().isAfter(month.atEndOfMonth()) }) {
                "An unfinished trip may belong to this report"
            }
            val trips = visible.filter { it.end != null && YearMonth.from(it.start.atZone(zone)) == month }
                .sortedBy { it.start }
            require(trips.isNotEmpty()) { "No completed trips for $month" }
            for (t in trips) {
                check(t.confirmed && t.category != Category.UNCLASSIFIED && t.purpose.isNotBlank()) { "Review required: ${t.id}" }
                val d = details[t.id] ?: error("Report details required: ${t.id}")
                d.validate()
                check(!d.commute || t.category == Category.BUSINESS) { "Commute flag conflicts with PERSONAL: ${t.id}" }
            }
            val pages = trips.groupBy { t -> details.getValue(t.id).let { listOf(it.company,it.businessNumber,it.plate) } }
            val body = pages.entries.joinToString("\n") { (vehicle, rows) ->
                val d = details.getValue(rows.first().id)
                check(rows.all { details.getValue(it.id).vehicleType == d.vehicleType }) { "Vehicle type differs for same plate" }
                // A shared vehicle must have a consistent odometer across drivers.
                for ((before, after) in rows.zipWithNext()) {
                    check(!after.start.isBefore(requireNotNull(before.end)) && after.startKm >= requireNotNull(before.endKm)) {
                        "Overlapping trips or odometer decrease: ${after.id}"
                    }
                }
                fun distance(t: Trip) = BigDecimal.valueOf(requireNotNull(t.endKm)) - BigDecimal.valueOf(t.startKm)
                val total = rows.fold(BigDecimal.ZERO) { a, t -> a + distance(t) }
                val business = rows.filter { it.category == Category.BUSINESS }.fold(BigDecimal.ZERO) { a,t -> a + distance(t) }
                val ratio = if (total.signum() == 0) "0" else (business * BigDecimal(100)).divide(total,2,RoundingMode.HALF_UP).toPlainString()
                val content = rows.joinToString("") { t ->
                    val m = details.getValue(t.id)
                    val km = distance(t)
                    val commute = if (t.category == Category.BUSINESS && m.commute) km else BigDecimal.ZERO
                    val work = if (t.category == Category.BUSINESS && !m.commute) km else BigDecimal.ZERO
                    val cells = listOf(t.start.atZone(zone).toLocalDate(),m.department,m.name,t.startKm,t.endKm,
                        km,commute,work,t.purpose,t.category,t.source,t.id)
                    "<tr>" + cells.joinToString("") { "<td>${h(it)}</td>" } + "</tr>"
                }
                """<section><h1>업무용승용차 운행기록부</h1><p>집계기간 ${h(month)} · 월간 관리용</p>
                <p>법인명 ${h(vehicle[0])} · 사업자등록번호 ${h(vehicle[1])}</p>
                <p>차종 ${h(d.vehicleType)} · 자동차등록번호 ${h(vehicle[2])}</p>
                <table><thead><tr>${listOf("사용일자","부서","성명","주행 전 km","주행 후 km","주행거리 km","출퇴근용 km","일반 업무용 km","목적","분류","데이터 출처","운행 ID").joinToString("") { "<th>${h(it)}</th>" }}</tr></thead>
                <tbody>$content</tbody></table>
                <p>기록 합계 ${h(total)} km · 업무용 ${h(business)} km · 기록 기준 업무사용비율 ${h(ratio)}%</p>
                <p>운행 시작일의 한국시간으로 월을 구분합니다. 월경계 운행은 시작월에 포함됩니다.
                누락된 운행은 합계에 포함되지 않습니다. 사업연도 집계와 업무 목적 증빙은 별도 확인하세요.</p></section>"""
            }
            """<!doctype html><html lang="ko"><meta charset="utf-8"><title>${h(month)} 운행기록부</title>
            <style>body{font:14px/1.5 sans-serif;margin:24px;color:#111}table{border-collapse:collapse;width:100%}td,th{border:1px solid #777;padding:6px;overflow-wrap:anywhere}section{margin-bottom:40px}@media print{@page{size:A4 landscape;margin:12mm}section{break-after:page}thead{display:table-header-group}}</style>$body</html>"""
        }
}
