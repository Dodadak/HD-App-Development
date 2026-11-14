package com.hdapp.drivingscore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.hdapp.R
import com.example.evbizassistant.charge.ChargeHost
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * 운전 점수 — 기능 파일 2 (저장 · 설정 · 백그라운드)
 *
 *   TripStore            끝난 주행을 Room DB에 한 건씩 저장 (예전 SharedPreferences 기록은 처음 한 번 옮김)
 *   ScoreSettings        작은 설정: 보험사 바로 보내기, 운전자 목록, 지금 운전자
 *   DrivingScoreService  앱 화면을 닫아도 주행을 계속 기록하는 Foreground Service
 *   DrivingScoreBootReceiver  차가 켜지면(부팅) 서비스 자동 시작
 *   (충전 기록 감지도 DrivingScoreService 가 같이 켠다)
 */

// ───────────────────────── 1. 주행 기록 DB (Room) ─────────────────────────

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val distanceKm: Double,
    val drivingSec: Int,
    val overspeedSec: Int,
    val nightSec: Int,
    val driver: String,
    /** 감점 이벤트 목록 (JSON 배열) */
    val eventsJson: String,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startMillis")
    suspend fun all(): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trip: TripEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(trips: List<TripEntity>)

    @Query("DELETE FROM trips")
    suspend fun clear()

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trips WHERE driver = :driver")
    suspend fun deleteDriver(driver: String)

    @Query("UPDATE trips SET driver = :newName WHERE driver = :oldName")
    suspend fun renameDriver(oldName: String, newName: String)
}

/**
 * 표 구조를 바꿀 때: version 을 1 올리고, MIGRATIONS 에 옮기는 방법을 추가한다.
 * 표 구조는 app/schemas 에 버전별로 저장된다 (exportSchema). 옮기는 방법 없이 버전만 올리면 기존 사용자 앱이 꺼진다.
 */
@Database(entities = [TripEntity::class], version = 1, exportSchema = true)
abstract class DrivingScoreDb : RoomDatabase() {
    abstract fun trips(): TripDao

    companion object {
        /** 예: object : Migration(1, 2) { override fun migrate(db) { db.execSQL("ALTER TABLE trips ADD COLUMN ...") } } */
        val MIGRATIONS = arrayOf<Migration>()

        @Volatile private var instance: DrivingScoreDb? = null

        fun get(context: Context): DrivingScoreDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DrivingScoreDb::class.java, "driving_score.db")
                .addMigrations(*MIGRATIONS)
                .build().also { instance = it }
        }
    }
}

fun TripRecord.toEntity() = TripEntity(
    id = id, startMillis = startMillis, endMillis = endMillis, title = title, distanceKm = distanceKm,
    drivingSec = drivingSec, overspeedSec = overspeedSec, nightSec = nightSec, driver = driver,
    eventsJson = JSONArray().apply {
        events.forEach { e -> put(JSONObject().apply { put("item", e.item.name); put("at", e.atMillis); put("detail", e.detail) }) }
    }.toString(),
)

/** 주행 한 건을 JSON 글자로 (달리는 주행 임시 저장용) */
fun TripRecord.toJson(): String = toEntity().let { e ->
    JSONObject().apply {
        put("id", e.id); put("start", e.startMillis); put("end", e.endMillis); put("title", e.title)
        put("km", e.distanceKm); put("drivingSec", e.drivingSec); put("overSec", e.overspeedSec)
        put("nightSec", e.nightSec); put("driver", e.driver); put("events", JSONArray(e.eventsJson))
    }.toString()
}

fun tripFromJson(json: String): TripRecord {
    val o = JSONObject(json)
    return TripEntity(
        id = o.getString("id"), startMillis = o.getLong("start"), endMillis = o.getLong("end"), title = o.getString("title"),
        distanceKm = o.getDouble("km"), drivingSec = o.getInt("drivingSec"), overspeedSec = o.getInt("overSec"),
        nightSec = o.getInt("nightSec"), driver = o.optString("driver", DEFAULT_DRIVER), eventsJson = o.getJSONArray("events").toString(),
    ).toRecord()
}

fun TripEntity.toRecord(): TripRecord {
    val ev = JSONArray(eventsJson)
    return TripRecord(
        id = id, startMillis = startMillis, endMillis = endMillis, title = title, distanceKm = distanceKm,
        drivingSec = drivingSec, overspeedSec = overspeedSec, nightSec = nightSec, driver = driver,
        events = (0 until ev.length()).map { i ->
            val e = ev.getJSONObject(i)
            DrivingEvent(ScoreItem.valueOf(e.getString("item")), e.getLong("at"), e.getString("detail"))
        },
    )
}

/** 끝난 주행 저장소. 한 건씩 넣으니 기록이 쌓여도 저장이 느려지지 않는다 */
class TripStore(context: Context) {
    private val app = context.applicationContext
    private val dao = DrivingScoreDb.get(app).trips()

    /** 전체 기록 (처음 한 번은 예전 SharedPreferences 기록을 DB로 옮긴다) */
    suspend fun load(): List<TripRecord> {
        migrateOldPrefs()
        return dao.all().map { it.toRecord() }
    }

    suspend fun add(trip: TripRecord) = dao.insert(trip.toEntity())

    suspend fun clear() = dao.clear()

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteDriver(driver: String) = dao.deleteDriver(driver)

    suspend fun renameDriver(old: String, new: String) = dao.renameDriver(old, new)

    private suspend fun migrateOldPrefs() {
        val prefs = app.getSharedPreferences("driving_score", Context.MODE_PRIVATE)
        val json = prefs.getString("trips", null) ?: return
        val old = parseOldTrips(json)
        if (old.isNotEmpty()) dao.insertAll(old.map { it.toEntity() })
        prefs.edit().remove("trips").apply()
        log("예전 기록 ${old.size}건을 DB로 옮겼어요")
    }

    companion object {
        /** 예전 버전(SharedPreferences JSON) 기록 읽기. 화면 채우기용 예시 기록은 버린다 */
        fun parseOldTrips(json: String): List<TripRecord> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getJSONObject(it) }.filterNot { it.optBoolean("sample") }.map { o ->
                val ev = o.getJSONArray("events")
                TripRecord(
                    id = o.getString("id"), startMillis = o.getLong("start"), endMillis = o.getLong("end"),
                    title = o.getString("title"), distanceKm = o.getDouble("km"), drivingSec = o.getInt("drivingSec"),
                    overspeedSec = o.getInt("overSec"), nightSec = o.getInt("nightSec"),
                    events = (0 until ev.length()).map { i ->
                        val e = ev.getJSONObject(i)
                        DrivingEvent(ScoreItem.valueOf(e.getString("item")), e.getLong("at"), e.getString("detail"))
                    },
                )
            }
        }
    }
}

// ───────────────────────── 2. 설정 ─────────────────────────

/** 작은 설정 값. 앱을 다시 켜도 남는다 */
class ScoreSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("driving_score_settings", Context.MODE_PRIVATE)

    /** 보험사에 묻지 않고 바로 보내기 */
    var autoShare: Boolean
        get() = prefs.getBoolean("autoShare", false)
        set(v) = prefs.edit().putBoolean("autoShare", v).apply()

    /** 운전자 이름 목록 (처음엔 "나" 한 명) */
    var drivers: List<String>
        get() = prefs.getString("drivers", null)?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOf(DEFAULT_DRIVER)
        set(v) = prefs.edit().putString("drivers", v.joinToString("\n")).apply()

    var currentDriver: String
        get() = prefs.getString("currentDriver", null)?.takeIf { it in drivers } ?: drivers.first()
        set(v) = prefs.edit().putString("currentDriver", v).apply()

    /** 운전자가 받은 배지 단계 (종류 → 단계). 한 번 받으면 내려가지 않게 저장해 둔다 */
    fun badgeTiers(driver: String): Map<BadgeKind, Int> {
        val o = JSONObject(prefs.getString("badges", null) ?: "{}").optJSONObject(driver) ?: return emptyMap()
        return BadgeKind.entries.associateWith { o.optInt(it.name, 0) }.filterValues { it > 0 }
    }

    fun setBadgeTiers(driver: String, tiers: Map<BadgeKind, Int>) {
        val all = JSONObject(prefs.getString("badges", null) ?: "{}")
        if (tiers.isEmpty()) all.remove(driver)
        else all.put(driver, JSONObject().apply { tiers.forEach { (k, v) -> if (v > 0) put(k.name, v) } })
        prefs.edit().putString("badges", all.toString()).apply()
    }

    /** 아직 축하 화면을 안 본 배지. 앱을 닫고 있었어도 다음에 열면 보여준다 */
    var pendingBadges: List<BadgeUp>
        get() {
            val arr = JSONArray(prefs.getString("pendingBadges", null) ?: "[]")
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = BadgeKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
                BadgeUp(kind, o.getInt("from"), o.getInt("to"), o.getString("driver"))
            }
        }
        set(v) = prefs.edit().putString(
            "pendingBadges",
            JSONArray().apply {
                v.forEach { put(JSONObject().apply { put("kind", it.kind.name); put("from", it.from); put("to", it.to); put("driver", it.driver) }) }
            }.toString(),
        ).apply()

    /** 달리는 중인 주행 임시 저장 (JSON). 앱이 강제로 꺼져도 주행을 살리기 위해 */
    var liveCheckpoint: String?
        get() = prefs.getString("liveCheckpoint", null)
        set(v) = prefs.edit().apply { if (v == null) remove("liveCheckpoint") else putString("liveCheckpoint", v) }.apply()
}

// ───────────────────────── 3. 백그라운드 기록 ─────────────────────────

/**
 * 앱 화면을 닫거나 내비로 넘어가도 주행을 계속 기록한다.
 * 알림 영역에 "운전 점수 기록 중"이 떠 있는 동안 동작한다.
 */
class DrivingScoreService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "운전 점수 기록", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("기어를 D로 바꾸면 자동으로 기록해요"), type)

        val score = DrivingScore.get(this)
        if (!score.isCollecting) score.startCollecting()
        // 충전 감지도 여기서 같이 켠다 (충전 화면을 안 열어도, 앱을 닫아도 충전기를 꽂고 빼는 걸 기록)
        ChargeHost.app(this).start()
        // 운행일지: 차를 다시 켜서 로그인이 비어 있어도 마지막 운전자로 이어서 기록 (로그인하면 그 사람으로 바뀜)
        com.example.hyundai_sdk.TripLogBackground.resume(this)
        // 알림 문구는 주행 시작·끝, 점수가 바뀔 때만 고친다
        scope.launch {
            score.state
                .map { s -> s.live?.let { "${s.driver} 주행 기록 중 · 이번 주행 ${it.score}점" } ?: "${s.driver} · 기어를 D로 바꾸면 자동으로 기록해요" }
                .distinctUntilChanged()
                .collect { nm.notify(NOTIFICATION_ID, notification(it)) }
        }
        log("백그라운드 기록 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        DrivingScore.get(this).stopCollecting()
        ChargeHost.app(this).release()
        log("백그라운드 기록 끝")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DrivingScoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle("운전 점수")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "driving_score"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DrivingScoreService::class.java))
        }
    }
}

/** 차가 켜지면(부팅 완료) 앱을 열지 않아도 기록을 시작한다 */
class DrivingScoreBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DrivingScoreService.start(context)
    }
}
