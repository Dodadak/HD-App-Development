package com.example.evbizassistant.charge

import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * SDK에서 배터리 %(변화 알림)와 충전 스위치(주기적 확인)를 받아 ChargeDetector에 넘긴다.
 * SDK 결과는 다른 스레드에서 올 수 있으므로 전부 메인 스레드로 옮겨서 넘긴다.
 */
class ChargeMonitor(
    private val evBattery: EvBattery,
    private val detector: ChargeDetector,
    private val switchPollIntervalMs: Long = 5_000L
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            if (value == null) return
            mainHandler.post { detector.onBatteryPercent(value.toDouble()) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "배터리 변화 알림 실패", e)
        }
    }

    private val pollSwitch = object : Runnable {
        override fun run() {
            readChargeSwitch(evBattery) { isOn ->
                if (isOn != null) {
                    mainHandler.post { detector.onChargeSwitch(isOn) }
                }
            }
            if (running) mainHandler.postDelayed(this, switchPollIntervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        // 값이 안 바뀌면 알림이 안 올 수 있으니 현재 값을 먼저 한 번 읽어둔다
        readBatteryPercent(evBattery) { percent ->
            if (percent != null) {
                mainHandler.post { detector.onBatteryPercent(percent.toDouble()) }
            }
        }
        evBattery.registerEvBatteryStateOfCharge(socListener)
        mainHandler.post(pollSwitch)
        Log.d(TAG, "충전 감시 시작")
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(pollSwitch)
        evBattery.unregisterEvBatteryStateOfCharge(socListener)
        Log.d(TAG, "충전 감시 중지")
    }

    private companion object {
        const val TAG = "EvCharge"
    }
}