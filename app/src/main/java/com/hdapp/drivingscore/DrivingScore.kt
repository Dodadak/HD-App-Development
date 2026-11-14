package com.hdapp.drivingscore

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 운전 점수 (보험 연동 UBI) — 기능 파일
 *
 * 사용법:
 *   val score = DrivingScore.get(context)   // 앱 전체에서 하나 (백그라운드 서비스와 화면이 같이 씀)
 *   DrivingScoreService.start(context)      // 앱을 닫아도 계속 기록 (서비스가 startCollecting 호출)
 *   score.startCollecting()                 // 차량 신호 구독 시작 (기어 P→D 면 주행 기록 자동 시작)
 *   score.state                             // 실시간 상태 (StateFlow): 주행 중 여부, 이번 주행 점수, 최근 감점
 *   score.summary(Period.MONTH)             // 기간별 종합 점수 · 항목별 점수 · 고칠 점 한 가지
 *   score.trips(Period.MONTH)               // 기간 안의 주행 목록
 *   score.scoreOf(trip)                     // 주행 한 건 점수
 *   score.shareWithInsurer(Period.MONTH)    // 보험사 공유 (Mock)
 *   score.today()                           // 오늘 실시간 집계 (state.today 로도 0.5초마다 갱신)
 *   score.selectDriver("엄마") / addDriver   // 운전자별로 점수를 따로 모음
 *   score.setAutoShare(false)               // 보험사 "묻지 않고 바로 보내기" 끄기 (앱을 다시 켜도 유지)
 *   score.deleteTrip(trip) / moveTrip(trip, "엄마") / renameDriver / removeDriver
 *   score.events                            // 감점이 생길 때마다
 *   score.release()
 *
 * 차량 신호 출처 (Pleos 문서·SDK로 확인):
 *   기어       Vehicle SDK · Gear.registerGearSelection          (pleos.car.permission.CAR_DRIVE)
 *   브레이크   Vehicle SDK · Brake.registerBrakePedalStatus      (pleos.car.permission.CAR_DRIVE)
 *   차속       Android Automotive · PERF_VEHICLE_SPEED           (android.car.permission.CAR_SPEED, 런타임 권한)
 *              → Vehicle SDK에 차속 API가 없어서 표준 API를 씀
 *   제한속도   NaviHelper · onCurrentRoadSpeedLimit              (pleos.car.permission.NAVI_ROUTE)
 *
 * 저장·설정·백그라운드 서비스는 DrivingScoreBackground.kt
 */

internal const val TAG = "DrivingScore"
internal fun log(msg: String) { Log.d(TAG, msg) }

/** 운전자를 따로 만들지 않았을 때의 이름 */
const val DEFAULT_DRIVER = "나"


// ───────────────────────── 1. 데이터 ─────────────────────────

/** 감점 항목. weight = 종합 점수에서 차지하는 비중 */
enum class ScoreItem(val label: String, val weight: Double) {
    HARD_ACCEL("급가속", 0.20),
    HARD_BRAKE("급정거", 0.25),
    SPEEDING("과속", 0.25),
    /** 깜빡이 없이 바꾼 차선 변경 (화면 칸이 좁아 이름은 짧게) */
    LANE_CHANGE("차선 변경", 0.15),
    NIGHT("밤 운전", 0.15),
}

/** 방향지시등 (Vehicle SDK TurnSignalValue 를 앱 안에서 쓰는 모양) */
enum class TurnSignal { OFF, LEFT, RIGHT }

enum class Gear { P, R, N, D, UNKNOWN }

/** 기간 (달력 기준: 이번 주는 월요일부터, 이번 달은 1일부터, 최근 3개월은 두 달 전 1일부터) */
enum class Period(val label: String) {
    WEEK("이번 주"),
    MONTH("이번 달"),
    QUARTER("최근 3개월"),
}

/** 0.5초마다 들어오는 차량 신호 한 묶음 */
data class DrivingSample(
    val atMillis: Long,
    val speedKmh: Float,
    val gear: Gear,
    val brakePressed: Boolean,
    /** 내비가 알려준 도로 제한속도. 모르면 null */
    val speedLimitKmh: Int?,
    /** 가속 페달 밟은 양 0~100%. 차가 안 주면 null (그때는 속도 변화로 급가속을 본다) */
    val acceleratorPct: Float? = null,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    /** 핸들 각도(도). 차가 안 주면 null (차선 변경 판정을 안 함) */
    val steeringDeg: Float? = null,
)

/** 감점 이벤트 한 건 */
data class DrivingEvent(
    val item: ScoreItem,
    val atMillis: Long,
    /** 사람이 읽는 설명. 예: "시속 32→71km, 2초" */
    val detail: String,
)

/** 끝난 주행 한 건 */
data class TripRecord(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val distanceKm: Double,
    val drivingSec: Int,
    val overspeedSec: Int,
    val nightSec: Int,
    val events: List<DrivingEvent>,
    /** 누가 운전했는지 (가족이 같이 타는 차) */
    val driver: String = DEFAULT_DRIVER,
) {
    fun count(item: ScoreItem) = events.count { it.item == item }
}

/** 항목 하나의 점수 */
data class ItemScore(
    val item: ScoreItem,
    val score: Int,
    /** 기간 안 횟수 (밤 운전은 분) */
    val amount: Int,
    val amountText: String,
    /** "좋아요" / "조금 잦아요" 처럼 숫자 없이 읽히는 말 */
    val word: String,
    val isWeak: Boolean,
)

/** 고칠 점 한 가지 */
data class Tip(val title: String, val body: String, val item: ScoreItem?)

/** 기간별 요약 (화면 한 장에 필요한 것 전부) */
data class ScoreSummary(
    val period: Period,
    val score: Int,
    val word: String,
    /** 바로 전 같은 길이 기간 대비 변화. 비교할 기록이 없으면 null */
    val delta: Int?,
    val items: List<ItemScore>,
    val tip: Tip,
    val trips: List<TripRecord>,
    /** 기간별 흐름 (막대 그래프용): 라벨 → 점수 */
    val trend: List<Pair<String, Int>>,
    val totalKm: Double,
    /** 기간 안에 실제 주행 기록이 있는지. 없으면 화면은 빈 상태를 보여준다 */
    val hasData: Boolean,
    /** 최근부터 감점 없이 이어진 주행 수 */
    val streak: Int = 0,
    val badges: List<Badge> = emptyList(),
)

/**
 * 배지 종류. 단계마다 모양이 진화한다 (1단계 샌드 → 2단계 블루 → 3단계 골드). 첫 시동은 1단계뿐.
 * need = 1·2·3단계 기준값, unit = 남은 양 뒤에 붙는 말, how = 무엇을 하면 오르는지
 */
enum class BadgeKind(val label: String, val need: List<Int>, val unit: String, val how: String) {
    CLEAN_STREAK("클린 스트릭", listOf(5, 20, 50), "번", "감점 없이 연속으로 달리기"),
    ROAD_RUNNER("로드 러너", listOf(100, 1_000, 5_000), "km", "모두 합쳐 달린 거리"),
    COOL_CRUISER("쿨 크루저", listOf(5, 15, 30), "번", "최근 30일 과속 없이 달리기"),
    NIGHT_OWL("나이트 아울", listOf(3, 10, 30), "번", "밤 10시~6시 감점 없이"),
    BUTTER_DRIVER("버터 드라이버", listOf(1, 30, 100), "번", "감점 없는 주행 모으기"),
    FIRST_IGNITION("첫 시동", listOf(1), "번", "첫 주행 마치기"),
}

/**
 * 배지 하나의 지금 모습.
 * tier = 받은 단계 (0 = 아직 못 받음). 한 번 받은 단계는 기록을 지워도 내려가지 않는다.
 * value = 다음 단계까지 센 값 (클린 스트릭은 지금 이어지는 연속 횟수)
 */
data class Badge(val kind: BadgeKind, val tier: Int, val value: Int) {
    val maxTier get() = kind.need.size
    val isMax get() = tier >= maxTier
    /** 다음 단계 기준값. 최고 단계면 null */
    val nextNeed get() = kind.need.getOrNull(tier)
    /** 다음 단계까지 0~1 */
    val progress get() = nextNeed?.let { (value.toFloat() / it).coerceIn(0f, 1f) } ?: 1f
    /** 예: "18번 더", "400km 더". 최고 단계면 "최고 단계" */
    val remainingText get() = nextNeed?.let { "${(it - value).coerceAtLeast(1)}${kind.unit} 더" } ?: "최고 단계"
}

/** 방금 오른 배지 (주차 후 축하 화면에 한 번 보여준다) */
data class BadgeUp(val kind: BadgeKind, val from: Int, val to: Int, val driver: String)

/** 오늘 실시간 집계 (지금 달리는 주행까지 포함, 0.5초마다 갱신) */
data class TodayStats(
    val tripCount: Int = 0,
    val distanceKm: Double = 0.0,
    val driveMinutes: Int = 0,
    val hardAccel: Int = 0,
    val hardBrake: Int = 0,
    val speeding: Int = 0,
    val laneChange: Int = 0,
    /** 오늘 점수. 오늘 기록이 없으면 null */
    val score: Int? = null,
    /** 지금 주행 중이면 이번 주행 점수 */
    val liveScore: Int? = null,
)

/** 지금 주행 중인 기록 */
data class LiveTrip(
    val startMillis: Long,
    val speedKmh: Float,
    val distanceKm: Double,
    val score: Int,
    val events: List<DrivingEvent>,
    val lastEvent: DrivingEvent?,
    /** 내비가 알려준 제한속도. 모르면 null (화면에 "제한속도 정보 없음") */
    val speedLimitKmh: Int?,
    /** 지금 주행을 끝난 주행처럼 본 모습 (오늘 집계에 씀) */
    val trip: TripRecord,
)

/** 화면이 구독하는 실시간 상태 */
data class DrivingScoreState(
    val gear: Gear = Gear.UNKNOWN,
    val live: LiveTrip? = null,
    val sourceName: String = "대기 중",
    val speedAvailable: Boolean = false,
    /** 주행 기록이 바뀔 때마다 1씩 늘어남 → 화면이 요약을 다시 계산하는 신호 */
    val tripsVersion: Int = 0,
    /** 오늘 실시간 집계 */
    val today: TodayStats = TodayStats(),
    val driver: String = DEFAULT_DRIVER,
    val drivers: List<String> = listOf(DEFAULT_DRIVER),
    /** 보험사에 묻지 않고 바로 보내기 (앱을 다시 켜도 유지) */
    val autoShare: Boolean = false,
    /** 방금 오른 배지들. 주차 중에 축하 화면으로 보여주고 ackBadges()로 비운다 */
    val newBadges: List<BadgeUp> = emptyList(),
)

data class ShareResult(val ok: Boolean, val message: String, val sentScore: Int)

// ───────────────────────── 2. 판정 규칙 ─────────────────────────

/**
 * 점수 규칙.
 * 과속 기준(제한속도 + 20km/h)은 한국교통안전공단 위험운전 11대 유형의 과속 정의를 따른다.
 * 급정거 값은 공개 자료로 확인하지 못한 추정치 → 보험사 기준을 받으면 바꾼다.
 */
object DrivingRules {
    /**
     * 급가속: 가속 페달 값이 이보다 크면 (0~255 기준, 200 ≈ 78%).
     * 차는 페달을 0~100%로 주므로 255 기준으로 바꿔 비교한다.
     */
    const val HARD_ACCEL_PEDAL = 200
    const val PEDAL_FULL_SCALE = 255
    /** 페달 신호가 없는 차에서만 쓰는 대체 기준: 1초 동안 이만큼 빨라지면 급가속 (km/h) */
    const val HARD_ACCEL_KMH_PER_SEC = 10f
    /** 차선 변경 판정은 이 속도 이상에서만 (시내 저속 회전·주차 무시) */
    const val LANE_MIN_SPEED = 40f
    /** 핸들을 한쪽으로 이만큼(도) 꺾으면 차선 변경 시작 후보 */
    const val LANE_STEER_DEG = 5f
    /** 한쪽으로 꺾었다가 반대로 되돌리는 S자가 이 시간 안에 끝나야 차선 변경 (넘으면 굽은 길로 봄) */
    const val LANE_WINDOW_MS = 6_000L
    /** 핸들을 꺾기 이 시간 전부터 깜빡이가 켜져 있었으면 정상 차선 변경 */
    const val SIGNAL_GRACE_MS = 5_000L
    /** 1초 동안 이만큼 느려지면 급정거 (km/h) */
    const val HARD_BRAKE_KMH_PER_SEC = 12f
    /** 판정은 이 속도 이상에서만 (주차장 저속 무시) */
    const val MIN_SPEED_FOR_EVENT = 8f
    /** 제한속도 + 이만큼 넘으면 과속 (교통안전공단 과속 정의) */
    const val OVERSPEED_MARGIN_KMH = 20
    /** 과속이 이만큼 이어지면 과속 1회 */
    const val OVERSPEED_MIN_SEC = 5
    /**
     * 제한속도를 모를 때 쓰는 값.
     * 더 낮추면 내비 없이 고속도로를 달릴 때 억울한 과속이 잡혀서 100으로 둔다 (→ 120 넘으면 과속).
     * 대신 화면에 "제한속도 정보 없음 · 내비를 켜면 더 정확해요"를 보여준다.
     */
    const val DEFAULT_SPEED_LIMIT = 100
    /**
     * 1초에 이보다 크게 바뀌면 실제 차가 낼 수 없는 값 → 센서가 튄 것으로 보고 감점하지 않는다.
     * (아주 강한 급제동이 약 1g ≈ 35km/h/s)
     */
    const val MAX_PLAUSIBLE_KMH_PER_SEC = 40f
    /** 같은 종류 이벤트 사이 최소 간격 */
    const val EVENT_COOLDOWN_MS = 4_000L

    fun isNight(millis: Long): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 6
    }

    /** 점수를 숫자 없이 읽히는 말로 */
    fun wordOf(score: Int) = when {
        score >= 90 -> "아주 좋아요"
        score >= 80 -> "좋아요"
        score >= 60 -> "보통이에요"
        else -> "주의가 필요해요"
    }

    /** 항목별 점수: 100km당 횟수(밤 운전은 비율)로 계산해서 짧은 주행·긴 주행을 공평하게 */
    fun itemScore(item: ScoreItem, trips: List<TripRecord>): Int {
        if (trips.isEmpty()) return 100
        // 짧은 주행 한 번의 감점이 과하게 커지지 않도록 최소 10km로 계산
        val km = max(trips.sumOf { it.distanceKm }, 10.0)
        val per100 = { n: Int -> n / km * 100 }
        val raw = when (item) {
            ScoreItem.HARD_ACCEL -> 100 - per100(trips.sumOf { it.count(item) }) * 2.5
            ScoreItem.HARD_BRAKE -> 100 - per100(trips.sumOf { it.count(item) }) * 3.0
            ScoreItem.LANE_CHANGE -> 100 - per100(trips.sumOf { it.count(item) }) * 3.0
            ScoreItem.SPEEDING -> 100 - per100(trips.sumOf { it.count(item) }) * 2.0 -
                trips.sumOf { it.overspeedSec } / km * 100 / 60.0 * 1.5
            ScoreItem.NIGHT -> {
                // 밤에 감점 없이 조심히 달린 주행은 깎지 않는다. 감점이 나온 주행의 밤 시간만 센다
                val riskyNight = trips.filter { it.events.isNotEmpty() }.sumOf { it.nightSec }
                val ratio = riskyNight.toDouble() / max(trips.sumOf { it.drivingSec }, 1)
                100 - ratio * 60
            }
        }
        return raw.roundToInt().coerceIn(0, 100)
    }

    fun totalScore(trips: List<TripRecord>): Int =
        ScoreItem.entries.sumOf { itemScore(it, trips) * it.weight }.roundToInt().coerceIn(0, 100)

    fun itemWord(item: ScoreItem, score: Int) = when {
        score >= 80 -> "좋아요"
        item == ScoreItem.NIGHT -> "조심하세요"
        item == ScoreItem.SPEEDING -> "조금 있어요"
        else -> "조금 잦아요"
    }

    fun tipFor(items: List<ItemScore>): Tip {
        val weak = items.filter { it.isWeak }.minByOrNull { it.score }
            ?: return Tip("지금처럼만 운전하세요", "모든 항목이 좋아요. 이대로면 보험료 할인에 유리해요.", null)
        return when (weak.item) {
            ScoreItem.HARD_BRAKE -> Tip("급정거가 조금 잦아요", "신호 앞에서 브레이크를 미리, 천천히 밟아 보세요.", weak.item)
            ScoreItem.HARD_ACCEL -> Tip("급가속이 조금 잦아요", "가속 페달을 끝까지 밟지 말고 부드럽게 밟아 보세요.", weak.item)
            ScoreItem.LANE_CHANGE -> Tip("깜빡이 없이 차선을 바꿨어요", "차선을 바꾸기 전에 깜빡이를 먼저 켜 주세요.", weak.item)
            ScoreItem.SPEEDING -> Tip("과속이 조금 있어요", "제한속도만 지켜도 점수가 바로 올라가요.", weak.item)
            ScoreItem.NIGHT -> Tip("밤 운전 중 감점이 있어요", "밤엔 더 천천히 출발하고 멈춰 보세요. 감점 없는 밤 운전은 깎지 않아요.", weak.item)
        }
    }
}

// ───────────────────────── 3. 실시간 주행 기록기 ─────────────────────────

/**
 * 차량 신호를 받아 주행을 시작·종료하고 감점 이벤트를 찾는다.
 * 기어가 P가 아니면 주행 시작, 다시 P가 되면 주행 끝.
 */
class TripRecorder(
    private val onEvent: (DrivingEvent) -> Unit,
    private val onTripEnd: (TripRecord) -> Unit,
) {
    private var start = 0L
    private var distanceKm = 0.0
    private var drivingSec = 0.0
    private var nightSec = 0.0
    private var overspeedSec = 0.0
    private var overspeedRun = 0.0
    private var overspeedCounted = false
    private var limit: Int? = null
    private val events = mutableListOf<DrivingEvent>()
    private val window = ArrayDeque<DrivingSample>()
    private val lastEventAt = mutableMapOf<ScoreItem, Long>()
    private var last: DrivingSample? = null
    /** 핸들을 한쪽으로 꺾기 시작한 방향(+1/-1)과 시각. 반대로 되돌리면 차선 변경 */
    private var steerSide = 0
    private var steerStartAt = 0L
    /** 굽은 길로 판정된 방향. 핸들이 가운데로 돌아오면 0 */
    private var curveSide = 0
    private var lastSignalOnAt = Long.MIN_VALUE
    private var lastPedal: Float? = null

    val isRecording get() = start != 0L

    /**
     * 앱이 강제로 꺼지기 전에 임시 저장해 둔 주행을 이어서 기록한다.
     * 다음 신호가 P면 그대로 끝내서 저장하고, 계속 달리는 중이면 이어 붙인다.
     */
    fun resume(saved: TripRecord) {
        start = saved.startMillis
        distanceKm = saved.distanceKm
        drivingSec = saved.drivingSec.toDouble()
        nightSec = saved.nightSec.toDouble()
        overspeedSec = saved.overspeedSec.toDouble()
        overspeedRun = 0.0; overspeedCounted = false; limit = null
        events.clear(); events += saved.events
        window.clear(); lastEventAt.clear()
        last = null
        log("임시 저장된 주행을 이어서 기록해요 (${"%.1f".format(distanceKm)}km)")
    }

    fun feed(s: DrivingSample) {
        val prev = last
        last = s
        val moving = s.gear != Gear.P && s.gear != Gear.UNKNOWN
        if (!isRecording && moving) begin(s)
        if (isRecording && s.gear == Gear.P) {
            finish(s.atMillis)
            return
        }
        if (!isRecording || prev == null) return

        val dt = ((s.atMillis - prev.atMillis) / 1000.0).coerceIn(0.0, 5.0)
        distanceKm += s.speedKmh * dt / 3600
        if (s.speedKmh > 1) {
            drivingSec += dt
            if (DrivingRules.isNight(s.atMillis)) nightSec += dt
        }
        s.speedLimitKmh?.let { limit = it }

        // 1초 전과 비교해서 가속도(km/h/s) 계산
        window.addLast(s)
        while (window.size > 2 && s.atMillis - window[1].atMillis >= 1_000) window.removeFirst()
        val ref = window.first()
        val span = (s.atMillis - ref.atMillis) / 1000f
        if (span >= 0.8f) {
            val accel = (s.speedKmh - ref.speedKmh) / span
            val fast = max(s.speedKmh, ref.speedKmh) >= DrivingRules.MIN_SPEED_FOR_EVENT
            val glitch = abs(accel) > DrivingRules.MAX_PLAUSIBLE_KMH_PER_SEC
            if (glitch && fast) log("센서 값이 튐 (시속 ${ref.speedKmh.roundToInt()}→${s.speedKmh.roundToInt()}km, ${"%.1f".format(span)}초) — 감점하지 않아요")
            // 페달 신호가 없는 차에서만 속도 변화로 급가속을 본다
            if (s.acceleratorPct == null && fast && !glitch && accel >= DrivingRules.HARD_ACCEL_KMH_PER_SEC) {
                emit(ScoreItem.HARD_ACCEL, s.atMillis, "시속 ${ref.speedKmh.roundToInt()}→${s.speedKmh.roundToInt()}km를 ${"%.1f".format(span)}초 만에")
            }
            if (fast && !glitch && -accel >= DrivingRules.HARD_BRAKE_KMH_PER_SEC) {
                val pedal = if (s.brakePressed || ref.brakePressed) " · 브레이크" else ""
                emit(ScoreItem.HARD_BRAKE, s.atMillis, "시속 ${ref.speedKmh.roundToInt()}→${s.speedKmh.roundToInt()}km를 ${"%.1f".format(span)}초 만에$pedal")
            }
        }

        // 급가속: 가속 페달이 200(0~255 기준)을 넘는 순간 1회
        s.acceleratorPct?.let { pct ->
            val raw = pedalRaw(pct)
            val before = lastPedal?.let { pedalRaw(it) } ?: 0
            if (raw > DrivingRules.HARD_ACCEL_PEDAL && before <= DrivingRules.HARD_ACCEL_PEDAL) {
                emit(ScoreItem.HARD_ACCEL, s.atMillis, "가속 페달 $raw/${DrivingRules.PEDAL_FULL_SCALE} (${DrivingRules.HARD_ACCEL_PEDAL} 초과) · 시속 ${s.speedKmh.roundToInt()}km")
            }
        }
        lastPedal = s.acceleratorPct

        checkLaneChange(s)

        // 과속: 제한속도 + 여유를 넘은 상태가 일정 시간 이어지면 1회
        val lim = limit ?: DrivingRules.DEFAULT_SPEED_LIMIT
        if (s.speedKmh > lim + DrivingRules.OVERSPEED_MARGIN_KMH) {
            overspeedRun += dt
            overspeedSec += dt
            if (!overspeedCounted && overspeedRun >= DrivingRules.OVERSPEED_MIN_SEC) {
                overspeedCounted = true
                emit(ScoreItem.SPEEDING, s.atMillis, "제한 ${lim}km 도로에서 시속 ${s.speedKmh.roundToInt()}km", cooldown = false)
            }
        } else {
            overspeedRun = 0.0
            overspeedCounted = false
        }
    }

    /**
     * 깜빡이 없는 차선 변경 (추정).
     * Pleos SDK에 차선 정보가 없어서 핸들 각도로 본다: 시속 40km 이상에서 핸들을 한쪽으로 5도 넘게 꺾었다가
     * 6초 안에 반대쪽으로 5도 넘게 되돌리면(S자) 차선 변경으로 보고, 그 앞 5초부터 깜빡이가 한 번도 안 켜졌으면 감점.
     * 깜빡이 방향은 따지지 않는다 (차마다 핸들 각도의 +/− 방향이 달라서).
     * shortcut: 굽은 길이 연달아 반대로 휘면 차선 변경으로 잘못 볼 수 있음, 실차 주행 기록으로 기준값을 맞출 때 보완
     */
    private fun checkLaneChange(s: DrivingSample) {
        if (s.turnSignal != TurnSignal.OFF) lastSignalOnAt = s.atMillis
        val angle = s.steeringDeg
        if (angle == null || s.speedKmh < DrivingRules.LANE_MIN_SPEED) {
            steerSide = 0; curveSide = 0
            return
        }
        val side = when {
            angle >= DrivingRules.LANE_STEER_DEG -> 1
            angle <= -DrivingRules.LANE_STEER_DEG -> -1
            else -> 0
        }
        // 한쪽으로 오래 꺾여 있으면 굽은 길: 핸들이 가운데로 돌아올 때까지 그쪽은 차선 변경 후보로 보지 않는다
        if (steerSide != 0 && s.atMillis - steerStartAt > DrivingRules.LANE_WINDOW_MS) {
            curveSide = steerSide
            steerSide = 0
        }
        if (side == 0) curveSide = 0
        if (side == 0 || side == curveSide) return
        if (steerSide == 0) {
            steerSide = side
            steerStartAt = s.atMillis
        } else if (side == -steerSide) {
            val signalled = lastSignalOnAt >= steerStartAt - DrivingRules.SIGNAL_GRACE_MS
            if (!signalled) {
                val sec = (s.atMillis - steerStartAt) / 1000.0
                emit(ScoreItem.LANE_CHANGE, s.atMillis, "시속 ${s.speedKmh.roundToInt()}km에서 깜빡이 없이 ${"%.1f".format(sec)}초 만에 차선 변경")
            }
            steerSide = 0
        }
    }

    private fun pedalRaw(pct: Float) = (pct.coerceIn(0f, 100f) / 100f * DrivingRules.PEDAL_FULL_SCALE).roundToInt()

    /** 지금 달리는 주행을 끝난 주행처럼 본 모습 (실시간 집계용) */
    fun current(): TripRecord? = if (!isRecording) null else last?.let { snapshot(it.atMillis, "지금 주행", LIVE_ID) }

    /** 지금 주행의 실시간 모습 */
    fun live(): LiveTrip? {
        if (!isRecording) return null
        val s = last ?: return null
        val partial = snapshot(s.atMillis, "지금 주행", LIVE_ID)
        return LiveTrip(start, s.speedKmh, distanceKm, DrivingRules.totalScore(listOf(partial)), partial.events, partial.events.lastOrNull(), limit, partial)
    }

    private fun begin(s: DrivingSample) {
        start = s.atMillis
        distanceKm = 0.0; drivingSec = 0.0; nightSec = 0.0; overspeedSec = 0.0
        overspeedRun = 0.0; overspeedCounted = false; limit = null
        events.clear(); window.clear(); lastEventAt.clear()
        steerSide = 0; curveSide = 0; lastSignalOnAt = Long.MIN_VALUE; lastPedal = null
        log("주행 시작 (기어 ${s.gear})")
    }

    private fun finish(end: Long) {
        val trip = snapshot(end, titleFor(start, distanceKm))
        start = 0L
        log("주행 끝 · ${"%.1f".format(trip.distanceKm)}km · ${DrivingRules.totalScore(listOf(trip))}점 · 감점 ${trip.events.size}건")
        if (trip.drivingSec >= 5) onTripEnd(trip) else log("(5초 미만 주행이라 저장하지 않아요)")
    }

    private fun snapshot(end: Long, title: String, id: String = UUID.randomUUID().toString()) = TripRecord(
        id = id,
        startMillis = start,
        endMillis = end,
        title = title,
        distanceKm = distanceKm,
        drivingSec = drivingSec.roundToInt(),
        overspeedSec = overspeedSec.roundToInt(),
        nightSec = nightSec.roundToInt(),
        events = events.toList(),
    )

    private fun emit(item: ScoreItem, at: Long, detail: String, cooldown: Boolean = true) {
        if (cooldown && at - (lastEventAt[item] ?: 0L) < DrivingRules.EVENT_COOLDOWN_MS) return
        lastEventAt[item] = at
        val e = DrivingEvent(item, at, detail)
        events += e
        log("${item.label} — $detail")
        onEvent(e)
    }

    companion object {
        /** 실시간 모습은 0.5초마다 만들어지니 매번 UUID를 만들지 않는다 */
        private const val LIVE_ID = "live"

        fun titleFor(start: Long, km: Double): String {
            val c = Calendar.getInstance().apply { timeInMillis = start }
            val h = c.get(Calendar.HOUR_OF_DAY)
            val ampm = if (h < 12) "오전" else "오후"
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$ampm ${h12}시 ${c.get(Calendar.MINUTE)}분 출발 · ${"%.1f".format(km)}km"
        }
    }
}

// ───────────────────────── 4. 기록 계산 (화면·테스트 공용) ─────────────────────────

/**
 * 저장된 주행 목록으로 요약·흐름·오늘 집계·배지를 계산한다.
 * Context 없이 동작하는 순수 함수라서 단위 테스트로 확인할 수 있다.
 */
object ScoreReport {
    private const val DAY = 24 * 3600_000L

    /** 기간이 시작하는 시각. back = 1 이면 바로 전 기간 (지난주, 지난달, 그 전 3개월) */
    fun periodStart(period: Period, now: Long, back: Int = 0): Long {
        val c = Calendar.getInstance().apply { timeInMillis = midnight(now) }
        when (period) {
            Period.WEEK -> {
                // 월요일=0 … 일요일=6
                c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7))
                c.add(Calendar.DAY_OF_MONTH, -7 * back)
            }
            Period.MONTH -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, -back)
            }
            Period.QUARTER -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, -2 - 3 * back)
            }
        }
        return c.timeInMillis
    }

    /** 기간 안의 주행 목록 (최근 순) */
    fun trips(all: List<TripRecord>, period: Period, now: Long): List<TripRecord> {
        val from = periodStart(period, now)
        return all.filter { it.endMillis >= from }.sortedByDescending { it.startMillis }
    }

    /** 기간별 종합 점수 · 항목별 점수 · 고칠 점 · 흐름 · 연속 기록 · 배지 */
    fun summary(all: List<TripRecord>, period: Period, now: Long, storedBadges: Map<BadgeKind, Int> = emptyMap()): ScoreSummary {
        val inPeriod = trips(all, period, now)
        val previous = all.filter { it.endMillis in periodStart(period, now, back = 1) until periodStart(period, now) }
        val items = ScoreItem.entries.map { item ->
            val s = DrivingRules.itemScore(item, inPeriod)
            val amount = if (item == ScoreItem.NIGHT) inPeriod.sumOf { it.nightSec } / 60 else inPeriod.sumOf { it.count(item) }
            val amountText = if (item == ScoreItem.NIGHT) "${amount}분" else "${amount}회"
            ItemScore(item, s, amount, amountText, DrivingRules.itemWord(item, s), s < 80)
        }
        val score = DrivingRules.totalScore(inPeriod)
        return ScoreSummary(
            period = period,
            score = score,
            word = DrivingRules.wordOf(score),
            delta = if (previous.isEmpty() || inPeriod.isEmpty()) null else score - DrivingRules.totalScore(previous),
            items = items,
            tip = DrivingRules.tipFor(items),
            trips = inPeriod,
            trend = trend(all, period, now),
            totalKm = inPeriod.sumOf { it.distanceKm },
            hasData = inPeriod.isNotEmpty(),
            streak = streak(all),
            badges = badges(all, now, storedBadges),
        )
    }

    /** 기간을 칸으로 나눈 점수 흐름 (이번 주: 요일, 이번 달: 1주~5주, 3개월: 월). 기록 없는 칸은 뺀다 */
    fun trend(all: List<TripRecord>, period: Period, now: Long): List<Pair<String, Int>> {
        val c = Calendar.getInstance().apply { timeInMillis = periodStart(period, now) }
        val buckets = mutableListOf<Triple<String, Long, Long>>()
        val (count, field, step) = when (period) {
            Period.WEEK -> Triple(7, Calendar.DAY_OF_MONTH, 1)
            Period.MONTH -> Triple(5, Calendar.DAY_OF_MONTH, 7)
            Period.QUARTER -> Triple(3, Calendar.MONTH, 1)
        }
        val monthOfStart = c.get(Calendar.MONTH)
        for (i in 0 until count) {
            val from = c.timeInMillis
            val label = when (period) {
                Period.WEEK -> listOf("월", "화", "수", "목", "금", "토", "일")[i]
                Period.MONTH -> "${i + 1}주"
                Period.QUARTER -> "${c.get(Calendar.MONTH) + 1}월"
            }
            c.add(field, step)
            // 이번 달의 마지막 칸은 다음 달 1일에서 끊는다
            if (period == Period.MONTH && c.get(Calendar.MONTH) != monthOfStart) {
                c.set(Calendar.DAY_OF_MONTH, 1)
            }
            buckets += Triple(label, from, c.timeInMillis)
            if (period == Period.MONTH && c.get(Calendar.DAY_OF_MONTH) == 1) break
        }
        return buckets.mapNotNull { (label, from, to) ->
            val list = all.filter { it.endMillis in from until to }
            if (list.isEmpty()) null else label to DrivingRules.totalScore(list)
        }
    }

    fun midnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 오늘 집계: 오늘 끝난 주행(done) + 지금 달리는 주행(current) */
    fun today(done: List<TripRecord>, current: TripRecord?): TodayStats {
        val all = done + listOfNotNull(current)
        return TodayStats(
            tripCount = all.size,
            distanceKm = all.sumOf { it.distanceKm },
            driveMinutes = all.sumOf { it.drivingSec } / 60,
            hardAccel = all.sumOf { it.count(ScoreItem.HARD_ACCEL) },
            hardBrake = all.sumOf { it.count(ScoreItem.HARD_BRAKE) },
            speeding = all.sumOf { it.count(ScoreItem.SPEEDING) },
            laneChange = all.sumOf { it.count(ScoreItem.LANE_CHANGE) },
            score = if (all.isEmpty()) null else DrivingRules.totalScore(all),
            liveScore = current?.let { DrivingRules.totalScore(listOf(it)) },
        )
    }

    /** 가장 최근 주행부터 감점 없이 이어진 주행 수 */
    fun streak(all: List<TripRecord>): Int =
        all.sortedByDescending { it.startMillis }.takeWhile { it.events.isEmpty() }.size

    /** 지금까지 가장 길었던 연속 무감점 주행 수 */
    fun bestStreak(all: List<TripRecord>): Int {
        var best = 0
        var run = 0
        all.sortedBy { it.startMillis }.forEach { t ->
            run = if (t.events.isEmpty()) run + 1 else 0
            best = max(best, run)
        }
        return best
    }

    /**
     * 배지마다 (단계를 정하는 값, 다음 단계까지 진행 값).
     * 클린 스트릭만 둘이 다르다: 단계는 가장 길었던 연속, 진행은 지금 이어지는 연속.
     */
    private fun badgeValues(all: List<TripRecord>, now: Long): Map<BadgeKind, Pair<Int, Int>> {
        val clean = all.filter { it.events.isEmpty() }
        val recent = all.filter { it.endMillis >= now - 30 * DAY }
        // 최근 30일에 과속이 한 번이라도 있으면 다시 0부터
        val cruise = if (recent.any { it.count(ScoreItem.SPEEDING) > 0 }) 0 else recent.size
        val night = clean.count { it.nightSec > 0 }
        val km = all.sumOf { it.distanceKm }.toInt()
        return mapOf(
            BadgeKind.CLEAN_STREAK to (bestStreak(all) to streak(all)),
            BadgeKind.ROAD_RUNNER to (km to km),
            BadgeKind.COOL_CRUISER to (cruise to cruise),
            BadgeKind.NIGHT_OWL to (night to night),
            BadgeKind.BUTTER_DRIVER to (clean.size to clean.size),
            BadgeKind.FIRST_IGNITION to (all.size to all.size),
        )
    }

    /** 기록만 보고 계산한 배지 단계 (저장된 단계와 합칠 때 쓴다) */
    fun badgeTiers(all: List<TripRecord>, now: Long): Map<BadgeKind, Int> =
        badgeValues(all, now).mapValues { (kind, v) -> kind.need.count { v.first >= it } }

    /**
     * 배지 목록. stored = 예전에 받아서 저장해 둔 단계 (기록을 지워도 내려가지 않게).
     * 받은 배지 → 다음 단계에 가까운 배지 → 시작 전 배지 순으로 정렬
     */
    fun badges(all: List<TripRecord>, now: Long, stored: Map<BadgeKind, Int> = emptyMap()): List<Badge> {
        val values = badgeValues(all, now)
        val computed = badgeTiers(all, now)
        return BadgeKind.entries.map { kind ->
            Badge(kind, max(stored[kind] ?: 0, computed[kind] ?: 0), values.getValue(kind).second)
        }.sortedWith(compareByDescending<Badge> { it.tier > 0 }.thenByDescending { if (it.isMax) -1f else it.progress })
    }

    /** 다음에 받기 가장 가까운 배지 (최고 단계는 빼고). 모두 최고 단계면 null */
    fun nextBadge(badges: List<Badge>): Badge? = badges.filterNot { it.isMax }.maxByOrNull { it.progress }
}

// ───────────────────────── 5. 차량 신호 ─────────────────────────

/** 차량 신호를 0.5초마다 DrivingSample로 넘겨주는 곳 */
interface DrivingSignalSource {
    val name: String
    val speedAvailable: Boolean
    fun start(scope: CoroutineScope, onSample: (DrivingSample) -> Unit)
    fun stop()
}

/** 실제 차량(에뮬레이터 포함) 신호: Pleos Vehicle SDK + Android 차속 + NaviHelper 제한속도 */
class VehicleSignalSource(context: Context) : DrivingSignalSource {
    private val app = context.applicationContext
    override val name = "차량 신호"
    override var speedAvailable = false
        private set

    @Volatile private var gear = Gear.UNKNOWN
    @Volatile private var brake = false
    @Volatile private var speedKmh = 0f
    @Volatile private var limit: Int? = null
    @Volatile private var pedalPct: Float? = null
    @Volatile private var turnSignal = TurnSignal.OFF
    @Volatile private var steeringDeg: Float? = null

    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var job: Job? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) { gear = p0.toGear() }
        override fun onFailed(e: Exception) { log("기어 구독 오류: ${e.message}") }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) { brake = p0 == true }
        override fun onFailed(e: Exception) { log("브레이크 구독 오류: ${e.message}") }
    }
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // PERF_VEHICLE_SPEED 단위는 m/s
            (value.value as? Float)?.let { speedKmh = max(0f, it * 3.6f) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            log("차속 신호 오류 (prop=$propId)")
        }
    }
    private val pedalCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) { (value.value as? Float)?.let { pedalPct = it } }
        override fun onErrorEvent(propId: Int, zone: Int) { log("가속 페달 신호 오류 (prop=$propId)") }
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) {
            turnSignal = when (p0) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
        }
        override fun onFailed(e: Exception) { log("깜빡이 구독 오류: ${e.message}") }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) { steeringDeg = p0 }
        override fun onFailed(e: Exception) { log("핸들 각도 구독 오류: ${e.message}") }
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { limit = speedLimit.takeIf { it > 0 } }
    }

    override fun start(scope: CoroutineScope, onSample: (DrivingSample) -> Unit) {
        val v = Vehicle(app).also { it.initialize() }
        vehicle = v
        navi = NaviHelper(app).also { n ->
            n.initialize({ n.addListener(naviListener) }, { log("내비 연결 실패: ${it.message}") })
        }
        connectSpeed()
        job = scope.launch {
            delay(1_500) // Vehicle.initialize()에 연결 완료 콜백이 없어서 잠시 기다렸다 구독
            try {
                v.getGear().registerGearSelection(gearListener)
                v.getBrake().registerBrakePedalStatus(brakeListener)
            } catch (e: Exception) {
                log("기어·브레이크 구독 실패: ${e.message}")
            }
            // 차선 변경 판정용. 없는 차에서도 나머지 기능은 그대로 동작한다
            runCatching { v.getTurnSignal().registerTurnSignalState(turnSignalListener) }
                .onFailure { log("깜빡이 구독 실패: ${it.message}") }
            runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
                .onFailure { log("핸들 각도 구독 실패: ${it.message}") }
            var tick = 0
            while (isActive) {
                // 리스너가 늦게 붙는 경우를 대비해 2초마다 현재 기어도 직접 읽는다
                if (tick++ % 4 == 0) v.getGear().getCurrentGearSelectionState({ gear = it.toGear() }, { })
                // 안전장치: 기어가 P면 속도 신호가 늦게 와도 0으로 본다
                val speed = if (gear == Gear.P) 0f else speedKmh
                onSample(DrivingSample(System.currentTimeMillis(), speed, gear, brake, limit, pedalPct, turnSignal, steeringDeg))
                delay(500)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun connectSpeed() {
        try {
            val c = Car.createCar(app) ?: run { log("Car 서비스 없음 — 차속을 받을 수 없어요"); return }
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            speedAvailable = pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            log(if (speedAvailable) "차속 구독 (PERF_VEHICLE_SPEED)" else "차속 구독 실패")
            // 가속 페달 (Android 15의 ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE). 없는 차는 속도 변화로 급가속을 본다
            val pedalOk = runCatching {
                pm.registerCallback(pedalCallback, ACCELERATOR_PEDAL_PROP, CarPropertyManager.SENSOR_RATE_NORMAL)
            }.getOrDefault(false)
            log(if (pedalOk) "가속 페달 구독" else "가속 페달 신호 없음 → 속도 변화로 급가속 판정")
        } catch (e: SecurityException) {
            log("차속 권한 없음 (android.car.permission.CAR_SPEED)")
        } catch (e: Throwable) {
            log("차속 연결 실패: ${e.message}")
        }
    }

    override fun stop() {
        job?.cancel()
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { propertyManager?.unregisterCallback(pedalCallback) }
        runCatching { car?.disconnect() }
        runCatching {
            vehicle?.getGear()?.unregisterGearSelection(gearListener)
            vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener)
            vehicle?.getTurnSignal()?.unregisterTurnSignalState(turnSignalListener)
            vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steeringListener)
            vehicle?.release()
        }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
    }

    private companion object {
        /** VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE (compileSdk 34에는 상수가 없어 숫자로 씀) */
        const val ACCELERATOR_PEDAL_PROP = 291504911
    }

    private fun VehicleGear?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.D
    }
}

// ───────────────────────── 6. 보험사 연동 ─────────────────────────

/** 보험사 공유. TODO: 보험사 API 계약 후 실제 구현 (점수만 보내고 위치·경로는 보내지 않기) */
interface InsuranceApi {
    suspend fun sendScore(score: Int, period: Period, items: List<ItemScore>): ShareResult
}

class MockInsuranceApi : InsuranceApi {
    override suspend fun sendScore(score: Int, period: Period, items: List<ItemScore>): ShareResult {
        delay(800)
        return ShareResult(true, "보험사에 ${period.label} 점수 ${score}점을 보냈어요", score)
    }
}

// ───────────────────────── 7. 공개 함수 ─────────────────────────

/**
 * 운전 점수 기능의 입구. 화면이나 다른 기능은 이 클래스의 함수만 부르면 된다.
 * 앱 전체에서 하나만 쓴다 (DrivingScore.get) → 화면을 닫아도 백그라운드 서비스가 같은 기록기로 계속 기록한다.
 */
class DrivingScore(
    context: Context,
    private val scope: CoroutineScope,
    private val insurance: InsuranceApi = MockInsuranceApi(),
) {
    private val appContext = context.applicationContext
    private val store = TripStore(appContext)
    private val settings = ScoreSettings(appContext)
    /** 모든 운전자의 끝난 주행 (DB에서 불러온 뒤 채워진다) */
    private val allTrips = mutableListOf<TripRecord>()
    private var source: DrivingSignalSource? = null
    private var lastCheckpointAt = 0L
    /** DB에서 기록을 다 불러왔는지. 그 전엔 배지를 계산하지 않는다 (덜 불러온 기록으로 잘못 축하하지 않게) */
    private var loaded = false

    /** 오늘 끝난 주행 캐시. 0.5초마다 전체 기록을 다시 훑지 않도록 기록·날짜·운전자가 바뀔 때만 다시 만든다 */
    private var todayCache: Triple<Long, Int, String>? = null
    private var todayDone: List<TripRecord> = emptyList()

    private val _events = MutableSharedFlow<DrivingEvent>(extraBufferCapacity = 8)

    /** 감점이 생길 때마다 하나씩 */
    val events: SharedFlow<DrivingEvent> = _events.asSharedFlow()

    private val _finishedTrips = MutableSharedFlow<TripRecord>(extraBufferCapacity = 8)

    /** 주행이 끝나 저장될 때마다 하나씩 (로그인 기록에 남기려고 메인 앱이 구독) */
    val finishedTrips: SharedFlow<TripRecord> = _finishedTrips.asSharedFlow()

    private val recorder = TripRecorder(
        onEvent = { _events.tryEmit(it) },
        onTripEnd = { finished ->
            settings.liveCheckpoint = null
            val trip = finished.copy(driver = settings.currentDriver)
            allTrips += trip
            scope.launch { withContext(Dispatchers.IO) { store.add(trip) } }
            _finishedTrips.tryEmit(trip)
            syncBadges(celebrate = true)
            bumpTrips()
        },
    )

    // recorder 를 먼저 만든 뒤에 만들어야 computeToday()가 동작한다
    private val _state = MutableStateFlow(
        DrivingScoreState(
            driver = settings.currentDriver, drivers = settings.drivers,
            autoShare = settings.autoShare, newBadges = settings.pendingBadges,
        ),
    )

    /** 실시간 상태 (주행 중 여부, 이번 주행 점수, 최근 감점, 운전자) */
    val state: StateFlow<DrivingScoreState> = _state.asStateFlow()

    /** 차량 신호를 받고 있는지 */
    val isCollecting get() = source != null

    init {
        restoreCheckpoint()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            // 불러오는 사이에 끝난 주행이 있으면 겹치지 않게 합친다
            val ids = loaded.map { it.id }.toSet()
            allTrips.removeAll { it.id in ids }
            allTrips.addAll(0, loaded)
            log("주행 기록 ${loaded.size}건 불러옴")
            this@DrivingScore.loaded = true
            // 예전 기록으로 이미 받을 배지는 축하 없이 조용히 채운다
            syncBadges(celebrate = false)
            bumpTrips()
        }
    }

    /** 차량 신호로 기록 시작. 기어가 P에서 바뀌면 주행이 자동으로 시작된다 */
    fun startCollecting() = switchSource(VehicleSignalSource(appContext))

    fun stopCollecting() {
        source?.stop()
        source = null
        _state.update { it.copy(sourceName = "대기 중", live = null) }
    }

    /** 지금 운전자의 기간별 요약 */
    fun summary(period: Period, now: Long = System.currentTimeMillis()): ScoreSummary =
        ScoreReport.summary(mine(), period, now, settings.badgeTiers(settings.currentDriver))

    /** 축하 화면을 봤다 → 방금 오른 배지 목록 비우기 */
    fun ackBadges() {
        settings.pendingBadges = emptyList()
        _state.update { it.copy(newBadges = emptyList()) }
    }

    /** 지금 운전자의 기간 안 주행 목록 (최근 순) */
    fun trips(period: Period, now: Long = System.currentTimeMillis()): List<TripRecord> = ScoreReport.trips(mine(), period, now)

    /** 주행 한 건 점수 */
    fun scoreOf(trip: TripRecord): Int = DrivingRules.totalScore(listOf(trip))

    /** 보험사에 점수 보내기 (점수와 항목 점수만, 위치·경로 제외) */
    suspend fun shareWithInsurer(period: Period): ShareResult {
        val s = summary(period)
        log("보험사 공유 요청 — ${settings.currentDriver} · ${period.label} ${s.score}점")
        return insurance.sendScore(s.score, period, s.items).also { log("   ${it.message}") }
    }

    /** 보험사에 묻지 않고 바로 보내기 켜기/끄기 (앱을 다시 켜도 유지) */
    fun setAutoShare(on: Boolean) {
        settings.autoShare = on
        _state.update { it.copy(autoShare = on) }
    }

    /** 운전자 바꾸기. 지금 달리는 주행도 이 사람 기록으로 저장된다 */
    fun selectDriver(name: String) {
        if (name !in settings.drivers) return
        settings.currentDriver = name
        _state.update { it.copy(driver = name) }
        bumpTrips()
    }

    /** 운전자 추가 (최대 4명). 추가하면 바로 그 사람으로 바뀐다. 이미 있거나 꽉 차면 false */
    fun addDriver(name: String): Boolean {
        val n = name.trim()
        val list = settings.drivers
        if (n.isEmpty() || n in list || list.size >= MAX_DRIVERS) return false
        settings.drivers = list + n
        _state.update { it.copy(drivers = settings.drivers) }
        selectDriver(n)
        return true
    }

    /**
     * 로그인한 사람을 운전자로 쓴다. 처음 보는 사람이면 운전자 목록에 넣는다
     * (로그인으로 들어온 사람은 가족 4명 제한을 받지 않는다).
     */
    fun useLoginDriver(name: String) {
        val n = name.trim().ifEmpty { return }
        if (n !in settings.drivers) {
            settings.drivers = settings.drivers + n
            _state.update { it.copy(drivers = settings.drivers) }
        }
        selectDriver(n)
    }

    /** 운전자 이름 바꾸기 (그 사람 기록도 같이). 빈 이름이나 겹치는 이름이면 false */
    fun renameDriver(old: String, new: String): Boolean {
        val n = new.trim()
        val list = settings.drivers
        if (old !in list || n.isEmpty() || (n != old && n in list)) return false
        if (n == old) return true
        settings.drivers = list.map { if (it == old) n else it }
        if (settings.currentDriver == old || settings.currentDriver == n) settings.currentDriver = n
        allTrips.replaceAll { if (it.driver == old) it.copy(driver = n) else it }
        settings.setBadgeTiers(n, settings.badgeTiers(old))
        settings.setBadgeTiers(old, emptyMap())
        scope.launch { withContext(Dispatchers.IO) { store.renameDriver(old, n) } }
        _state.update { it.copy(drivers = settings.drivers, driver = settings.currentDriver) }
        bumpTrips()
        return true
    }

    /** 운전자 지우기 (그 사람 기록도 지운다). 한 명만 남았으면 지울 수 없다 */
    fun removeDriver(name: String): Boolean {
        val list = settings.drivers
        if (name !in list || list.size <= 1) return false
        settings.drivers = list - name
        if (settings.currentDriver == name) settings.currentDriver = settings.drivers.first()
        allTrips.removeAll { it.driver == name }
        settings.setBadgeTiers(name, emptyMap())
        scope.launch { withContext(Dispatchers.IO) { store.deleteDriver(name) } }
        _state.update { it.copy(drivers = settings.drivers, driver = settings.currentDriver) }
        bumpTrips()
        return true
    }

    /** 주행 한 건 지우기 (다른 사람이 운전했는데 잘못 들어간 기록 등) */
    fun deleteTrip(trip: TripRecord) {
        allTrips.removeAll { it.id == trip.id }
        scope.launch { withContext(Dispatchers.IO) { store.delete(trip.id) } }
        bumpTrips()
    }

    /** 주행 한 건을 다른 운전자 기록으로 옮기기 */
    fun moveTrip(trip: TripRecord, driver: String) {
        if (driver !in settings.drivers) return
        val moved = trip.copy(driver = driver)
        allTrips.replaceAll { if (it.id == trip.id) moved else it }
        scope.launch { withContext(Dispatchers.IO) { store.add(moved) } }
        // 옮겨 받은 사람의 배지가 오를 수 있다 (축하는 주행을 마칠 때만)
        syncBadges(celebrate = false)
        bumpTrips()
    }

    /** 오늘 실시간 집계 (오늘 끝난 주행 + 지금 달리는 주행) */
    fun today(): TodayStats = computeToday(recorder.current())

    /** 저장된 주행 기록을 모두 지운다 (모든 운전자) */
    fun clearAllTrips() {
        allTrips.clear()
        scope.launch { withContext(Dispatchers.IO) { store.clear() } }
        bumpTrips()
    }

    fun release() = stopCollecting()

    private fun mine() = allTrips.filter { it.driver == settings.currentDriver }

    /**
     * 운전자마다 배지 단계를 다시 계산해서 저장한다. 단계는 오르기만 한다.
     * celebrate = true 면 오른 배지를 축하 목록에 넣는다 (주행을 마쳤을 때)
     */
    private fun syncBadges(celebrate: Boolean, now: Long = System.currentTimeMillis()) {
        if (!loaded) return
        val ups = mutableListOf<BadgeUp>()
        settings.drivers.forEach { driver ->
            val stored = settings.badgeTiers(driver)
            val computed = ScoreReport.badgeTiers(allTrips.filter { it.driver == driver }, now)
            val merged = BadgeKind.entries.associateWith { max(stored[it] ?: 0, computed[it] ?: 0) }
            if (celebrate) {
                BadgeKind.entries.forEach { k ->
                    val from = stored[k] ?: 0
                    val to = merged.getValue(k)
                    if (to > from) ups += BadgeUp(k, from, to, driver)
                }
            }
            if (merged != stored) settings.setBadgeTiers(driver, merged)
        }
        if (ups.isNotEmpty()) {
            log("배지 올라감: ${ups.joinToString { "${it.kind.label} ${it.to}단계" }}")
            settings.pendingBadges = settings.pendingBadges + ups
            _state.update { it.copy(newBadges = settings.pendingBadges) }
        }
    }

    private fun bumpTrips() {
        todayCache = null
        _state.update { it.copy(tripsVersion = it.tripsVersion + 1, today = computeToday(recorder.current())) }
    }

    private fun switchSource(next: DrivingSignalSource) {
        source?.stop()
        source = next
        _state.update { it.copy(sourceName = next.name, live = null) }
        log("신호: ${next.name}")
        next.start(scope) { sample ->
            recorder.feed(sample)
            val live = recorder.live()
            if (live != null && sample.atMillis - lastCheckpointAt >= CHECKPOINT_MS) {
                settings.liveCheckpoint = live.trip.toJson()
                lastCheckpointAt = sample.atMillis
            }
            _state.update {
                it.copy(gear = sample.gear, live = live, speedAvailable = next.speedAvailable, today = computeToday(live?.trip))
            }
        }
    }

    /**
     * 앱이 강제로 꺼지면서 남긴 임시 주행이 있으면 살린다.
     * 꺼진 지 오래됐으면(10분 넘게) 그때 끝난 주행으로 저장, 아니면 이어서 기록한다.
     */
    private fun restoreCheckpoint(now: Long = System.currentTimeMillis()) {
        val saved = settings.liveCheckpoint?.let { runCatching { tripFromJson(it) }.getOrNull() } ?: return
        if (now - saved.endMillis > RESUME_LIMIT_MS) {
            settings.liveCheckpoint = null
            if (saved.drivingSec < 5) return
            val trip = saved.copy(id = UUID.randomUUID().toString(), title = TripRecorder.titleFor(saved.startMillis, saved.distanceKm), driver = settings.currentDriver)
            allTrips += trip
            scope.launch { withContext(Dispatchers.IO) { store.add(trip) } }
            log("앱이 꺼지기 전 주행을 저장했어요 (${"%.1f".format(trip.distanceKm)}km)")
        } else {
            recorder.resume(saved)
        }
    }

    private fun computeToday(current: TripRecord?, now: Long = System.currentTimeMillis()): TodayStats {
        val key = Triple(ScoreReport.midnight(now), allTrips.size, settings.currentDriver)
        if (todayCache != key) {
            todayDone = mine().filter { it.endMillis >= key.first }
            todayCache = key
        }
        return ScoreReport.today(todayDone, current)
    }

    companion object {
        const val MAX_DRIVERS = 4
        /** 달리는 주행을 이 간격으로 임시 저장 */
        private const val CHECKPOINT_MS = 30_000L
        /** 꺼졌다 켜진 사이가 이보다 짧으면 같은 주행으로 이어 붙인다 */
        private const val RESUME_LIMIT_MS = 10 * 60_000L

        @Volatile private var instance: DrivingScore? = null

        /** 앱 전체에서 하나만 쓴다 (화면과 백그라운드 서비스가 공유) */
        fun get(context: Context): DrivingScore = instance ?: synchronized(this) {
            instance ?: DrivingScore(context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                .also { instance = it }
        }
    }
}
