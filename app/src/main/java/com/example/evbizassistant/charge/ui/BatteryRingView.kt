package com.example.evbizassistant.charge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** 원형 배터리 게이지. percent(0~100)만큼 링을 채운다 */
class BatteryRingView(context: Context) : View(context) {

    var percent: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()   // 값이 바뀌면 다시 그리기
        }

    var progressColor: Int = ChargeColors.ACCENT
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = 20f * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ChargeColors.LINE
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val left = (width - size) / 2 + stroke / 2
        val top = (height - size) / 2 + stroke / 2
        rect.set(left, top, left + size - stroke, top + size - stroke)

        canvas.drawArc(rect, 0f, 360f, false, trackPaint)              // 빈 링
        progressPaint.color = progressColor
        canvas.drawArc(rect, -90f, 360f * percent / 100f, false, progressPaint) // 12시 방향부터 채움
    }
}