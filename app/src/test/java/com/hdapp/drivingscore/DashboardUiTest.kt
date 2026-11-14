package com.hdapp.drivingscore

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 대시보드 화면 (Robolectric, 에뮬레이터 없이) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
class DashboardUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun trip(id: String, events: List<ScoreItem> = emptyList()) = TripRecord(
        id = id, startMillis = now - 3_600_000, endMillis = now - 1_800_000, title = "오후 출발 · 12.0km", distanceKm = 12.0,
        drivingSec = 1_800, overspeedSec = 0, nightSec = 0,
        events = events.map { DrivingEvent(it, now - 2_000_000, "테스트") },
    )

    private fun show(trips: List<TripRecord>, state: DrivingScoreState = DrivingScoreState(), clicks: MutableList<String> = mutableListOf()) {
        val summary = ScoreReport.summary(trips, Period.MONTH, now)
        compose.setContent {
            Dashboard(
                state = state, summary = summary, period = Period.MONTH, onPeriod = {}, onTrip = { clicks += "trip" },
                scoreOf = { DrivingRules.totalScore(listOf(it)) }, shared = null, onShare = { clicks += "share" },
                onDriver = { clicks += "driver" }, onFixSpeed = { clicks += "fixSpeed" },
                onBadges = { clicks += "badges" }, onAutoShareOff = { clicks += "autoShareOff" },
            )
        }
    }

    @Test
    fun `기록이 없으면 빈 상태와 시작 방법을 보여준다`() {
        show(emptyList())
        compose.onNodeWithText("이번 달엔 아직 기록이 없어요").assertExists()
        compose.onNodeWithText("운전만 하면 돼요").assertExists()
    }

    @Test
    fun `주행이 한 건이면 빈칸에 다음 주행 안내가 보인다`() {
        show(listOf(trip("a")))
        // 3칸 중 주행 1칸 + 빈칸 2칸
        compose.onAllNodesWithText("다음 주행이\n여기에 보여요").assertCountEquals(2)
        compose.onNodeWithText("기록이 쌓이면", substring = true).assertExists()
    }

    @Test
    fun `차속 권한이 없으면 눌러서 허용할 수 있다`() {
        val clicks = mutableListOf<String>()
        show(emptyList(), DrivingScoreState(speedAvailable = false), clicks)
        compose.onNodeWithText("차속 신호 없음 · 눌러서 허용").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("fixSpeed" in clicks)
    }

    @Test
    fun `운전자 이름이 늘 보이고 눌러서 바꿀 수 있다`() {
        val clicks = mutableListOf<String>()
        show(listOf(trip("a")), DrivingScoreState(driver = "엄마", drivers = listOf("나", "엄마"), speedAvailable = true), clicks)
        compose.onNodeWithText("운전자 · 엄마", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("driver" in clicks)
    }

    @Test
    fun `바로 보내기를 켜 두면 끄기 버튼이 보인다`() {
        val clicks = mutableListOf<String>()
        show(listOf(trip("a")), DrivingScoreState(autoShare = true, speedAvailable = true), clicks)
        compose.onNodeWithText("끄기").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("autoShareOff" in clicks)
    }
}
