package com.example.hyundai_sdk

import android.content.Context
import com.example.hyundai_sdk.vehicle.PleosTripController
import triplog.SignalResult
import triplog.Source
import triplog.TripLogUser
import java.util.concurrent.Executors

/**
 * 운행일지 화면을 닫거나 주행 중 화면이 막혀도 운행을 계속 기록한다.
 * 메인 앱이 로그인 시 [start]를 부른다. 화면(TripLogActivity)은 [listener]로 결과만 받는다.
 * 차를 다시 켜면(앱 프로세스가 새로 뜨면) 로그인이 비어 있으므로, 백그라운드 서비스가 [resume]으로
 * 마지막에 로그인했던 운전자 이름으로 이어서 기록한다. 다른 사람이 로그인하면 그 사람으로 바뀐다.
 * 기록기(PleosTripController)는 앱에 하나만 돌린다: 둘이 같은 기록을 번갈아 받으면 순서가 꼬여 운행이 초기화된다.
 */
object TripLogBackground {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var controller: PleosTripController? = null
    @Volatile private var userId: String? = null

    /** 화면이 열려 있을 때만 붙는다: (결과, 이번 운행 누적거리 km) */
    @Volatile var listener: ((SignalResult, Double?) -> Unit)? = null

    val isRunning get() = controller != null

    @Volatile private var current: TripLogUser? = null

    fun start(context: Context, user: TripLogUser) {
        val app = context.applicationContext
        worker.execute {
            if (userId == user.id && controller != null) return@execute
            val before = current
            closeNow()
            if (open(app, user)) {
                remember(app, user)
            } else if (before != null && before.id != user.id) {
                // 예: 앞 사람의 운행이 아직 안 끝났으면 다른 사람으로 못 바꾼다 → 앞 사람 기록을 끊지 않고 이어 간다
                open(app, before)
            }
        }
    }

    /** 로그인 없이 다시 켜졌을 때: 이미 돌고 있으면 그대로, 아니면 마지막 운전자로 이어서 기록 */
    fun resume(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (controller != null) return@execute
            val last = lastUser(app) ?: return@execute
            if (open(app, last)) android.util.Log.d("TripLogBackground", "다시 켜짐 → ${last.displayName} 이름으로 운행일지 기록 이어감")
        }
    }

    private fun open(app: Context, user: TripLogUser): Boolean = runCatching {
        val trip = TripLogHost.app(app)
        trip.useUser(user)
        controller = PleosTripController(app, trip, Source.EMULATOR, { null }) { result ->
            listener?.invoke(result, controller?.latestOdometerKm)
        }.useDistanceGearPolicy().also { it.start() }
        userId = user.id
        current = user
        true
    }.getOrElse { android.util.Log.w("TripLogBackground", "운행일지 기록 시작 실패", it); false }

    // 마지막 운전자: 이름·ID만 저장한다 (비밀번호·토큰 없음). 관리자 권한은 저장하지 않는다
    private fun prefs(app: Context) = app.getSharedPreferences("triplog_background", Context.MODE_PRIVATE)

    private fun remember(app: Context, user: TripLogUser) {
        prefs(app).edit().putString("lastId", user.id).putString("lastName", user.displayName).apply()
    }

    private fun lastUser(app: Context): TripLogUser? {
        val p = prefs(app)
        val id = p.getString("lastId", null) ?: return null
        val name = p.getString("lastName", null) ?: return null
        return runCatching { TripLogUser(id, name) }.getOrNull()
    }

    fun stop() {
        worker.execute { closeNow() }
    }

    private fun closeNow() {
        val c = controller ?: return
        controller = null
        userId = null
        current = null
        runCatching { c.close() }.onFailure { android.util.Log.w("TripLogBackground", "운행일지 기록 종료 실패", it) }
    }
}
