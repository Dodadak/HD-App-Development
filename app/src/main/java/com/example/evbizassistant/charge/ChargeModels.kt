package com.example.evbizassistant.charge

/**
 * 충전 기록 1건.
 * 시간은 epoch 밀리초, 배터리는 0~100 %.
 * isDemo: 직접 만든 시연용 데이터면 true, SDK(실차/에뮬레이터)에서 받은 값이면 false.
 */
data class ChargeSession(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startPercent: Double,
    val endPercent: Double,
    val latitude: Double? = null,   // 위치는 NaviHelper 연동 후 채움
    val longitude: Double? = null,
    val isDemo: Boolean
) {
    val chargedPercent: Double
        get() = endPercent - startPercent
}

/** 지금 충전 상태. 화면 왼쪽(게이지)에 쓰는 값 */
sealed class ChargeState {
    /** 충전 중 아님. currentPercent: 현재 배터리(아직 못 받았으면 null) */
    data class Idle(val currentPercent: Double?) : ChargeState()

    /** 충전 중 */
    data class Charging(
        val startTimeMs: Long,
        val startPercent: Double?,
        val currentPercent: Double?
    ) : ChargeState() {
        /** 지금까지 충전된 % (값이 없으면 null) */
        val gainedPercent: Double?
            get() = if (startPercent != null && currentPercent != null) currentPercent - startPercent else null
    }
}

/** 한 달 요약. 화면 오른쪽 위 카드 2개에 쓰는 값 */
data class ChargeMonthSummary(
    val year: Int,
    val month: Int,
    val count: Int,
    val totalChargedPercent: Double
)

/** 충전 관련 변화를 화면에 알려주는 틀. 필요한 함수만 골라서 구현하면 됨 */
interface ChargeListener {
    /** 충전 상태나 배터리 %가 바뀔 때 */
    fun onStateChanged(state: ChargeState) {}
    /** 충전이 끝나서 기록 1건이 저장됐을 때 */
    fun onSessionSaved(session: ChargeSession) {}
    /** 충전이 끝났지만 기록하지 않았을 때 (충전량 0 등) */
    fun onDiscarded(reason: String) {}
}