package com.example.evbizassistant.charge

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.time.ZoneId

/**
 * 충전 파트의 입구. 다른 파트는 이것만 알면 된다.
 *   val charge = ChargeHost.app(applicationContext)
 */
object ChargeHost {
    @Volatile
    private var instance: ChargeApp? = null

    /** 앱 전체에서 하나만 만들어 같이 쓴다 */
    fun app(context: Context): ChargeApp =
        instance ?: synchronized(this) {
            instance ?: ChargeApp(context.applicationContext).also { instance = it }
        }
}

/** 충전 감지·기록·조회 기능을 모아둔 클래스. ChargeHost.app()으로만 얻는다 */
class ChargeApp internal constructor(context: Context) {

    private val vehicle = Vehicle(context)
    private val store = ChargeStore(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: ChargeListener? = null
    private var monitor: ChargeMonitor? = null
    private var vehicleInitialized = false
    private var started = false

    /** 지금 충전 상태 */
    var state: ChargeState = ChargeState.Idle(null)
        private set

    private val detector = ChargeDetector(
        isDemo = false,
        onStarted = { time, percent ->
            updateState(ChargeState.Charging(time, percent, percent))
        },
        onFinished = { session ->
            store.save(session)
            updateState(ChargeState.Idle(session.endPercent))
            listener?.onSessionSaved(session)
        },
        onDiscarded = { reason ->
            updateState(ChargeState.Idle(currentPercentOf(state)))
            listener?.onDiscarded(reason)
        },
        onPercent = { percent ->
            val now = state
            updateState(
                when (now) {
                    is ChargeState.Charging -> now.copy(
                        startPercent = now.startPercent ?: percent,
                        currentPercent = percent
                    )
                    is ChargeState.Idle -> ChargeState.Idle(percent)
                }
            )
        }
    )

    /** 화면에서 변화를 받고 싶을 때 등록. 등록하는 즉시 현재 상태를 한 번 알려준다 */
    fun setListener(newListener: ChargeListener?) {
        listener = newListener
        newListener?.onStateChanged(state)
    }

    /** 충전 자동 감지 시작 (여러 번 불러도 한 번만 동작) */
    fun start() {
        if (started) return
        started = true
        if (!vehicleInitialized) {
            vehicle.initialize()
            vehicleInitialized = true
        }
        // SDK 연결에 시간이 조금 걸려서, 잠깐 기다린 뒤 감시를 시작한다
        mainHandler.postDelayed({
            if (started && monitor == null) {
                monitor = ChargeMonitor(vehicle.getEvBattery(), detector).also { it.start() }
            }
        }, START_DELAY_MS)
    }

    /** 감지 중지 (Vehicle 연결은 유지) */
    fun stop() {
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        monitor?.stop()
        monitor = null
    }

    /** 앱을 완전히 끝낼 때. 감지 중지 + Vehicle 연결 해제 */
    fun release() {
        stop()
        if (vehicleInitialized) {
            vehicle.release()
            vehicleInitialized = false
        }
    }

    /** 저장된 충전 기록, 최근 것부터 */
    fun records(): List<ChargeSession> =
        store.loadAll().sortedByDescending { it.startTimeMs }

    /** 특정 달의 요약 (month는 1~12) */
    fun monthlySummary(year: Int, month: Int): ChargeMonthSummary {
        val inMonth = store.loadAll().filter { s ->
            val date = Instant.ofEpochMilli(s.startTimeMs).atZone(ZoneId.systemDefault())
            date.year == year && date.monthValue == month
        }
        return ChargeMonthSummary(
            year = year,
            month = month,
            count = inMonth.size,
            totalChargedPercent = inMonth.sumOf { it.chargedPercent }
        )
    }

    fun delete(id: String) = store.delete(id)

    fun deleteAll() = store.deleteAll()

    private fun updateState(newState: ChargeState) {
        state = newState
        listener?.onStateChanged(newState)
    }

    private fun currentPercentOf(s: ChargeState): Double? = when (s) {
        is ChargeState.Charging -> s.currentPercent
        is ChargeState.Idle -> s.currentPercent
    }

    private companion object {
        const val START_DELAY_MS = 1_500L
    }
}