package com.example.evbizassistant.charge

import ai.pleos.playground.vehicle.api.EvBattery
import android.util.Log

private const val TAG = "EvCharge"

/** 배터리 잔량(%)을 한 번 읽는다. 실패하면 null을 돌려준다. */
fun readBatteryPercent(evBattery: EvBattery, onResult: (Int?) -> Unit) {
    evBattery.getEvBatteryStateOfCharge(
        onComplete = { percent -> onResult(percent) },
        onFailed = { e ->
            Log.w(TAG, "배터리 잔량 읽기 실패", e)
            onResult(null)
        }
    )
}

/** 충전 스위치 값을 한 번 읽는다. 실패하면 null을 돌려준다. */
fun readChargeSwitch(evBattery: EvBattery, onResult: (Boolean?) -> Unit) {
    evBattery.getEvChargeSwitchState(
        onComplete = { isOn -> onResult(isOn) },
        onFailed = { e ->
            Log.w(TAG, "충전 스위치 읽기 실패", e)
            onResult(null)
        }
    )
}