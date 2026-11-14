package com.example.evbizassistant.charge.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.evbizassistant.charge.ChargeApp
import com.example.evbizassistant.charge.ChargeListener
import com.example.evbizassistant.charge.ChargeSession
import com.example.evbizassistant.charge.ChargeState
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * D안 충전 화면 (다크 분할형).
 * 왼쪽: 지금 충전 상태 / 오른쪽: 이번 달 요약 + 최근 기록 + 버튼
 * 사용: setContentView(ChargeDashboardView(this, ChargeHost.app(applicationContext)))
 */
class ChargeDashboardView(
    context: Context,
    private val charge: ChargeApp
) : LinearLayout(context), ChargeListener {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    // ---- 왼쪽 카드 ----
    private val statusDot = View(context)
    private val statusText = makeText(22f, ChargeColors.TEXT, bold = true)
    private val ring = BatteryRingView(context)
    private val percentText = makeText(60f, ChargeColors.TEXT, bold = true)
    private val subText = makeText(18f, ChargeColors.SUB)
    private val row1Label = makeText(19f, ChargeColors.SUB)
    private val row1Value = makeText(19f, ChargeColors.TEXT, bold = true)
    private val row2Label = makeText(19f, ChargeColors.SUB)
    private val row2Value = makeText(19f, ChargeColors.TEXT, bold = true)

    // ---- 오른쪽 ----
    private val countText = makeText(40f, ChargeColors.TEXT, bold = true)
    private val totalText = makeText(40f, ChargeColors.ACCENT, bold = true)
    private val recentList = LinearLayout(context).apply { orientation = VERTICAL }

    /** 충전 중일 때 "충전 시간 n분"을 30초마다 갱신 */
    private val ticker = object : Runnable {
        override fun run() {
            renderState(charge.state)
            postDelayed(this, 30_000L)
        }
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ChargeColors.BG)
        val pad = context.dp(32)
        setPadding(pad, pad, pad, pad)

        addView(buildLeft(), LayoutParams(0, LayoutParams.MATCH_PARENT, 4f).apply {
            marginEnd = context.dp(20)
        })
        addView(buildRight(), LayoutParams(0, LayoutParams.MATCH_PARENT, 6f))
    }

    // ===================== 화면 만들기 =====================

    private fun buildLeft(): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(ChargeColors.CARD, context.dp(28).toFloat())
            val p = context.dp(32)
            setPadding(p, p, p, p)
        }

        // 상태 표시 (점 + 글자)
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot.background = roundedBg(ChargeColors.SUB, context.dp(6).toFloat())
        statusRow.addView(statusDot, LayoutParams(context.dp(12), context.dp(12)).apply {
            marginEnd = context.dp(10)
        })
        statusRow.addView(statusText)
        card.addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 원형 게이지 + 가운데 글자
        val ringBox = FrameLayout(context)
        ringBox.addView(ring, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val center = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        percentText.gravity = Gravity.CENTER
        subText.gravity = Gravity.CENTER
        center.addView(percentText)
        center.addView(subText)
        ringBox.addView(center, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.addView(ringBox, LayoutParams(context.dp(240), context.dp(240)).apply {
            topMargin = context.dp(20)
        })

        // 남는 공간 (아래 정보를 카드 바닥으로 밀어냄)
        card.addView(View(context), LayoutParams(0, 0, 1f))

        // 구분선 + 정보 2줄
        card.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)))
        card.addView(infoRow(row1Label, row1Value), LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16) })
        card.addView(infoRow(row2Label, row2Value), LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
        return card
    }

    private fun buildRight(): View {
        val column = LinearLayout(context).apply { orientation = VERTICAL }

        // 요약 카드 2개
        val tiles = LinearLayout(context).apply { orientation = HORIZONTAL }
        tiles.addView(tile("이번 달 충전", countText), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = context.dp(20)
        })
        tiles.addView(tile("이번 달 충전량", totalText), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        column.addView(tiles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 최근 기록 카드
        val recentCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = roundedBg(ChargeColors.CARD, context.dp(24).toFloat())
            setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
        }
        recentCard.addView(makeText(22f, ChargeColors.TEXT, bold = true).apply { text = "최근 기록" })
        recentCard.addView(recentList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
        })
        column.addView(recentCard, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = context.dp(20)
        })

        // 버튼 2개
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        val allButton = bigButton("전체 기록 보기", ChargeColors.ACCENT, ChargeColors.BG) {
            showAllRecordsDialog()
        }
        val settleButton = bigButton("충전비 정산 (확장 예정)", ChargeColors.LINE, ChargeColors.TEXT) {
            Toast.makeText(context, "충전비 정산은 확장 예정 기능이에요", Toast.LENGTH_SHORT).show()
        }
        buttons.addView(allButton, LayoutParams(0, context.dp(64), 1f).apply { marginEnd = context.dp(16) })
        buttons.addView(settleButton, LayoutParams(0, context.dp(64), 1f))
        column.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(20)
        })
        return column
    }

    // ===================== 값 채우기 =====================

    /** 왼쪽 카드: 지금 충전 상태 */
    private fun renderState(state: ChargeState) {
        when (state) {
            is ChargeState.Charging -> {
                setDotColor(ChargeColors.GREEN)
                statusText.text = "충전 중"
                ring.percent = (state.currentPercent ?: 0.0).toFloat()
                percentText.text = "${formatPercent(state.currentPercent)}%"
                subText.text = "+${formatPercent(state.gainedPercent)}% 충전됨"
                row1Label.text = "시작"
                row1Value.text = "${timeFormat.format(Date(state.startTimeMs))} · ${formatPercent(state.startPercent)}%"
                val minutes = (System.currentTimeMillis() - state.startTimeMs) / 60_000L
                row2Label.text = "충전 시간"
                row2Value.text = "${minutes}분"
            }
            is ChargeState.Idle -> {
                setDotColor(ChargeColors.SUB)
                statusText.text = "대기 중"
                ring.percent = (state.currentPercent ?: 0.0).toFloat()
                percentText.text = "${formatPercent(state.currentPercent)}%"
                subText.text = "배터리"
                val last = charge.records().firstOrNull()
                row1Label.text = "마지막 충전"
                row1Value.text = last?.let { dateFormat.format(Date(it.endTimeMs)) } ?: "기록 없음"
                row2Label.text = "마지막 충전량"
                row2Value.text = last?.let { "+${formatPercent(it.chargedPercent)}%" } ?: "-"
            }
        }
    }

    /** 오른쪽: 이번 달 요약 + 최근 기록 3건 */
    private fun renderRecords() {
        val today = LocalDate.now()
        val summary = charge.monthlySummary(today.year, today.monthValue)
        countText.text = "${summary.count}건"
        totalText.text = "+${formatPercent(summary.totalChargedPercent)}%"

        recentList.removeAllViews()
        val recent = charge.records().take(3)
        if (recent.isEmpty()) {
            recentList.addView(makeText(18f, ChargeColors.SUB).apply {
                text = "아직 충전 기록이 없어요"
                setPadding(0, context.dp(16), 0, 0)
            })
        }
        recent.forEach { recentList.addView(recordRow(it)) }
    }

    // ===================== ChargeListener =====================

    override fun onStateChanged(state: ChargeState) = renderState(state)

    override fun onSessionSaved(session: ChargeSession) {
        renderRecords()
        renderState(charge.state)
        Toast.makeText(context, "충전 기록 저장됨 (+${formatPercent(session.chargedPercent)}%)", Toast.LENGTH_SHORT).show()
    }

    override fun onDiscarded(reason: String) {
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
    }

    // 화면에 붙을 때 알림 연결, 떨어질 때 해제
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        charge.setListener(this)
        renderRecords()
        postDelayed(ticker, 30_000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        charge.setListener(null)
        super.onDetachedFromWindow()
    }

    // ===================== 전체 기록 / 삭제 =====================

    private fun showAllRecordsDialog() {
        val records = charge.records()
        val message = if (records.isEmpty()) {
            "저장된 충전 기록이 없어요"
        } else {
            records.joinToString("\n") {
                "${dateFormat.format(Date(it.startTimeMs))}   " +
                        "${formatPercent(it.startPercent)}% → ${formatPercent(it.endPercent)}%   " +
                        "(+${formatPercent(it.chargedPercent)}%)"
            }
        }
        AlertDialog.Builder(context)
            .setTitle("전체 충전 기록 (${records.size}건)")
            .setMessage(message)
            .setPositiveButton("닫기", null)
            .setNegativeButton("모두 삭제") { _, _ -> confirmDeleteAll() }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(context)
            .setMessage("모든 충전 기록을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                charge.deleteAll()
                renderRecords()
                renderState(charge.state)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ===================== 작은 부품들 =====================

    private fun makeText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun divider(): View = View(context).apply { setBackgroundColor(ChargeColors.LINE) }

    private fun setDotColor(color: Int) {
        (statusDot.background as GradientDrawable).setColor(color)
    }

    private fun infoRow(label: TextView, value: TextView): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }

    private fun tile(label: String, value: TextView): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            background = roundedBg(ChargeColors.CARD, context.dp(24).toFloat())
            val p = context.dp(24)
            setPadding(p, p, p, p)
            addView(makeText(17f, ChargeColors.SUB).apply { text = label })
            addView(value)
        }

    private fun recordRow(s: ChargeSession): View {
        val wrapper = LinearLayout(context).apply { orientation = VERTICAL }
        wrapper.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(10)
        })
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(12), 0, context.dp(2))
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(makeText(20f, ChargeColors.TEXT, bold = true).apply {
            text = dateFormat.format(Date(s.startTimeMs))
        })
        texts.addView(makeText(17f, ChargeColors.SUB).apply {
            text = "${formatPercent(s.startPercent)}% → ${formatPercent(s.endPercent)}%"
        })
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeText(26f, ChargeColors.ACCENT, bold = true).apply {
            text = "+${formatPercent(s.chargedPercent)}%"
        })
        wrapper.addView(row)
        return wrapper
    }

    private fun bigButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 20f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            background = roundedBg(bg, context.dp(18).toFloat())
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
}