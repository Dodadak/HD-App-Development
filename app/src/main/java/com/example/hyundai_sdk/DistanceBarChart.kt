package com.example.hyundai_sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

internal data class DistanceBar(val label: String, val km: Double, val color: Int)

internal class DistanceBarChart(
    context: Context,
    private val bars: List<DistanceBar>,
    labelColor: Int,
    valueColor: Int
) : View(context) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }
    private val valuePaint = Paint(labelPaint).apply {
        color = valueColor
        typeface = Typeface.DEFAULT_BOLD
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val values = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.let { format -> bars.map { "${format.format(it.km)} km" } }
    private val peak = (bars.maxOfOrNull { it.km } ?: 0.0).coerceAtLeast(0.1)
    private val inset = dp(12f)

    init {
        contentDescription = bars.zip(values).joinToString(", ", prefix = "주행거리 그래프. ") { (bar, value) ->
            "${bar.label} $value"
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val widestText = bars.indices.maxOfOrNull { index ->
            maxOf(labelPaint.measureText(bars[index].label), valuePaint.measureText(values[index]))
        } ?: 0f
        minimumWidth = ceil(inset * 2 + bars.size * (widestText + dp(12f))).toInt()
        minimumHeight = ceil(
            dp(124f) + labelPaint.descent() - labelPaint.ascent() +
                valuePaint.descent() - valuePaint.ascent()
        ).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val slot = (width - inset * 2) / bars.size
        val bottom = height - inset - (labelPaint.descent() - labelPaint.ascent()) - dp(8f)
        val plotHeight = bottom - inset - (valuePaint.descent() - valuePaint.ascent()) - dp(6f)
        if (slot <= 0f || plotHeight <= 0f) return

        val barWidth = minOf(slot * 0.58f, dp(72f))
        val labelY = bottom + dp(8f) - labelPaint.ascent()

        bars.forEachIndexed { index, bar ->
            val x = inset + slot * (index + 0.5f)
            val barHeight = (bar.km / peak).toFloat() * plotHeight
            val top = bottom - barHeight
            if (barHeight > 0f) {
                barPaint.color = bar.color
                val radius = minOf(dp(6f), barWidth / 2f, barHeight / 2f)
                canvas.drawRoundRect(x - barWidth / 2f, top, x + barWidth / 2f, bottom, radius, radius, barPaint)
            }
            canvas.drawText(values[index], x, top - dp(6f) - valuePaint.descent(), valuePaint)
            canvas.drawText(bar.label, x, labelY, labelPaint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
