package com.example.evbizassistant.charge.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.util.Locale

/** D안 색상 */
object ChargeColors {
    val BG = Color.parseColor("#101318")      // 화면 배경
    val CARD = Color.parseColor("#1B1F26")    // 카드 배경
    val LINE = Color.parseColor("#2C323B")    // 구분선, 게이지 빈 부분, 보조 버튼
    val TEXT = Color.parseColor("#F2F4F6")    // 기본 글자
    val SUB = Color.parseColor("#A6AFBA")     // 보조 글자
    val ACCENT = Color.parseColor("#64A8FF")  // 강조 (파랑)
    val GREEN = Color.parseColor("#3DDC97")   // 충전 중 표시
}

/** dp 값을 화면 픽셀로 바꾼다 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

/** 모서리가 둥근 단색 배경 */
internal fun roundedBg(color: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

/** 85.0 → "85", 85.5 → "85.5", null → "--" */
internal fun formatPercent(value: Double?): String = when {
    value == null -> "--"
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> String.format(Locale.KOREA, "%.1f", value)
}