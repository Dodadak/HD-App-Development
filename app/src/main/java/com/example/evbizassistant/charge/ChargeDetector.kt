package com.example.evbizassistant.charge

import android.util.Log
import java.util.UUID

/**
 * 충전 스위치와 배터리 % 값을 받아 충전 시작/종료를 판단한다.
 * SDK를 직접 부르지 않으므로 숫자만 넣어서 테스트할 수 있다.
 * 모든 함수는 메인 스레드에서 호출한다고 가정한다.
 */
class ChargeDetector(
    private val isDemo: Boolean,
    private val onStarted: (startTimeMs: Long, startPercent: Double?) -> Unit = { _, _ -> },
    private val onFinished: (ChargeSession) -> Unit,
    private val onDiscarded: (reason: String) -> Unit = {},
    private val onPercent: (percent: Double) -> Unit = {}   // [변경] 배터리 % 변화 알림
) {
    // [변경] 밖에서 읽을 수 있게 (바꾸는 건 이 클래스 안에서만)
    var latestPercent: Double? = null
        private set
    private var lastSwitchOn: Boolean? = null

    // 진행 중인 충전 (null이면 충전 중 아님)
    private var startTimeMs: Long? = null
    private var startPercent: Double? = null

    val isCharging: Boolean
        get() = startTimeMs != null

    /** 배터리 % 값이 들어올 때마다 호출 */
    fun onBatteryPercent(percent: Double) {
        latestPercent = percent
        // 충전은 시작됐는데 시작 % 를 아직 못 받았던 경우 지금 값으로 채운다
        if (isCharging && startPercent == null) {
            startPercent = percent
        }
        onPercent(percent)   // [변경]
    }

    /** 충전 스위치 값이 들어올 때마다 호출 (같은 값이 반복돼도 괜찮음) */
    fun onChargeSwitch(isOn: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val previous = lastSwitchOn
        lastSwitchOn = isOn

        if (previous == null) {
            // 감시 시작 시점에 이미 충전 중이면 지금부터 충전으로 본다
            if (isOn) start(nowMs)
            return
        }
        if (!previous && isOn) start(nowMs)
        if (previous && !isOn) finish(nowMs)
    }

    private fun start(nowMs: Long) {
        if (isCharging) return  // 중복 시작 방지
        startTimeMs = nowMs
        startPercent = latestPercent
        Log.d(TAG, "충전 시작: $startPercent%")
        onStarted(nowMs, startPercent)
    }

    private fun finish(nowMs: Long) {
        val sTime = startTimeMs ?: return
        val sPercent = startPercent
        val ePercent = latestPercent
        // 진행 중 상태 초기화 (먼저 비워서 중복 저장 방지)
        startTimeMs = null
        startPercent = null

        if (sPercent == null || ePercent == null) {
            onDiscarded("배터리 값을 받지 못해 기록하지 않음")
            return
        }
        if (ePercent - sPercent <= 0.0) {
            onDiscarded("충전량이 0 이하라 기록하지 않음 ($sPercent% → $ePercent%)")
            return
        }
        val session = ChargeSession(
            id = UUID.randomUUID().toString(),
            startTimeMs = sTime,
            endTimeMs = nowMs,
            startPercent = sPercent,
            endPercent = ePercent,
            isDemo = isDemo
        )
        Log.d(TAG, "충전 종료: $sPercent% → $ePercent%")
        onFinished(session)
    }

    private companion object {
        const val TAG = "EvCharge"
    }
}