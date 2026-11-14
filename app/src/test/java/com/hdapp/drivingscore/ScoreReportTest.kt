package com.hdapp.drivingscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 요약 · 흐름 · 오늘 집계 · 배지 · 밤 운전 · 저장 변환을 에뮬레이터 없이 확인한다 */
class ScoreReportTest {
    private val day = 24 * 3600_000L
    /** 2026년 11월 12일 (목) 오후 3시 — 이번 주는 11월 9일(월)부터, 이번 달은 11월 1일부터 */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 12, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun trip(daysAgo: Double, km: Double = 20.0, events: List<ScoreItem> = emptyList(), nightSec: Int = 0, driver: String = DEFAULT_DRIVER): TripRecord {
        val end = now - (daysAgo * day).toLong()
        return TripRecord(
            id = "t$daysAgo$driver", startMillis = end - 1_800_000, endMillis = end, title = "주행", distanceKm = km,
            drivingSec = 1_800, overspeedSec = 0, nightSec = nightSec, driver = driver,
            events = events.mapIndexed { i, item -> DrivingEvent(item, end - i * 10_000L, "테스트") },
        )
    }

    @Test
    fun `기간은 달력 기준 - 이번 주는 월요일부터, 이번 달은 1일부터`() {
        // 1일 전 11/11, 3일 전 11/9(월), 4일 전 11/8(일, 지난주), 20일 전 10/23(지난달)
        val all = listOf(trip(1.0), trip(3.0), trip(4.0), trip(20.0))
        assertEquals(2, ScoreReport.summary(all, Period.WEEK, now).trips.size)
        assertEquals(3, ScoreReport.summary(all, Period.MONTH, now).trips.size)
        assertEquals(4, ScoreReport.summary(all, Period.QUARTER, now).trips.size)
    }

    @Test
    fun `기간 시작 시각`() {
        val c = Calendar.getInstance()
        c.timeInMillis = ScoreReport.periodStart(Period.WEEK, now)
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, c.get(Calendar.DAY_OF_MONTH))
        c.timeInMillis = ScoreReport.periodStart(Period.MONTH, now, back = 1)
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH))
        assertEquals(1, c.get(Calendar.DAY_OF_MONTH))
        c.timeInMillis = ScoreReport.periodStart(Period.QUARTER, now)
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
    }

    @Test
    fun `이번 달 흐름은 1주부터 5주까지 날짜를 빠짐없이 나눈다`() {
        // 11/1, 11/29, 11/30 주행을 11/30 저녁에 보면 → 1주(1~7일), 5주(29~30일) 두 칸
        fun at(dayOfMonth: Int, hour: Int) = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, dayOfMonth, hour, 0, 0) }.timeInMillis
        val all = listOf(1, 29, 30).map { d -> trip(0.0).copy(id = "d$d", startMillis = at(d, 9), endMillis = at(d, 10)) }
        val trend = ScoreReport.trend(all, Period.MONTH, at(30, 20))
        assertEquals(listOf("1주", "5주"), trend.map { it.first })
    }

    @Test
    fun `지난 기간보다 좋아지면 delta가 양수`() {
        val all = listOf(trip(10.0, events = listOf(ScoreItem.HARD_BRAKE, ScoreItem.HARD_BRAKE)), trip(2.0))
        val s = ScoreReport.summary(all, Period.WEEK, now)
        assertTrue(s.delta!! > 0)
    }

    @Test
    fun `지난 기간 기록이 없으면 delta는 null`() {
        assertNull(ScoreReport.summary(listOf(trip(1.0)), Period.WEEK, now).delta)
    }

    @Test
    fun `흐름은 기록 있는 칸만 나온다`() {
        val trend = ScoreReport.trend(listOf(trip(0.5), trip(2.5)), Period.WEEK, now)
        assertEquals(2, trend.size)
        assertTrue(trend.all { it.second in 0..100 })
    }

    @Test
    fun `오늘 집계는 끝난 주행과 지금 주행을 합친다`() {
        val done = listOf(trip(0.0, km = 5.0, events = listOf(ScoreItem.HARD_ACCEL)))
        val current = trip(0.0, km = 2.0, events = listOf(ScoreItem.SPEEDING))
        val t = ScoreReport.today(done, current)
        assertEquals(2, t.tripCount)
        assertEquals(7.0, t.distanceKm, 0.001)
        assertEquals(1, t.hardAccel)
        assertEquals(1, t.speeding)
        assertTrue(t.liveScore != null)
    }

    @Test
    fun `연속 무감점은 최근부터 센다`() {
        val all = listOf(trip(5.0), trip(4.0, events = listOf(ScoreItem.HARD_BRAKE)), trip(3.0), trip(2.0), trip(1.0))
        assertEquals(3, ScoreReport.streak(all))
        assertEquals(3, ScoreReport.bestStreak(all))
    }

    private fun badge(all: List<TripRecord>, kind: BadgeKind, stored: Map<BadgeKind, Int> = emptyMap()) =
        ScoreReport.badges(all, now, stored).first { it.kind == kind }

    @Test
    fun `배지 - 첫 시동과 로드 러너 단계`() {
        val all = listOf(trip(1.0, km = 60.0), trip(2.0, km = 50.0))
        assertEquals(1, badge(all, BadgeKind.FIRST_IGNITION).tier)
        assertTrue(badge(all, BadgeKind.FIRST_IGNITION).isMax)
        val road = badge(all, BadgeKind.ROAD_RUNNER)
        assertEquals(1, road.tier)
        assertEquals("890km 더", road.remainingText)
        assertEquals(0.11f, road.progress, 0.001f)
    }

    @Test
    fun `배지 - 클린 스트릭 단계는 최고 연속, 진행은 지금 연속`() {
        // 감점 없이 6번 → 감점 1번 → 감점 없이 2번
        val all = (10 downTo 5).map { trip(it.toDouble()) } + trip(4.0, events = listOf(ScoreItem.HARD_BRAKE)) + listOf(trip(3.0), trip(2.0))
        val b = badge(all, BadgeKind.CLEAN_STREAK)
        assertEquals(1, b.tier)
        assertEquals(2, b.value)
        assertEquals("18번 더", b.remainingText)
    }

    @Test
    fun `배지 - 쿨 크루저는 최근 30일에 과속이 있으면 0부터`() {
        val clean = (1..6).map { trip(it.toDouble()) }
        assertEquals(1, badge(clean, BadgeKind.COOL_CRUISER).tier)
        val speeding = clean + trip(0.5, events = listOf(ScoreItem.SPEEDING))
        assertEquals(0, badge(speeding, BadgeKind.COOL_CRUISER).value)
    }

    @Test
    fun `배지 - 나이트 아울은 감점 없는 밤 주행만 센다`() {
        val all = listOf(trip(1.0, nightSec = 600), trip(2.0, nightSec = 600), trip(3.0, nightSec = 600, events = listOf(ScoreItem.HARD_ACCEL)), trip(4.0, nightSec = 600))
        assertEquals(1, badge(all, BadgeKind.NIGHT_OWL).tier)
    }

    @Test
    fun `배지 - 한 번 받은 단계는 기록이 줄어도 내려가지 않는다`() {
        val b = badge(emptyList(), BadgeKind.ROAD_RUNNER, stored = mapOf(BadgeKind.ROAD_RUNNER to 2))
        assertEquals(2, b.tier)
        assertEquals("5000km 더", b.remainingText)
    }

    @Test
    fun `배지 - 받은 배지가 앞에, 다음 배지는 가장 가까운 것`() {
        val badges = ScoreReport.badges(listOf(trip(1.0, km = 90.0)), now)
        assertTrue(badges.first().tier > 0)
        // 첫 시동(최고 단계)은 다음 목표에서 빠지고, 로드 러너가 90% 로 가장 가깝다
        assertEquals(BadgeKind.ROAD_RUNNER, ScoreReport.nextBadge(badges)?.kind)
    }

    @Test
    fun `밤에 감점 없이 달리면 밤 운전 점수는 깎이지 않는다`() {
        assertEquals(100, DrivingRules.itemScore(ScoreItem.NIGHT, listOf(trip(1.0, nightSec = 1_800))))
        val risky = listOf(trip(1.0, nightSec = 1_800, events = listOf(ScoreItem.HARD_BRAKE)))
        assertTrue(DrivingRules.itemScore(ScoreItem.NIGHT, risky) < 100)
    }

    @Test
    fun `저장 변환 - DB 행으로 바꿨다 되돌려도 같다`() {
        val t = trip(1.0, events = listOf(ScoreItem.HARD_ACCEL, ScoreItem.SPEEDING), driver = "엄마")
        assertEquals(t, t.toEntity().toRecord())
    }

    @Test
    fun `예전 기록 옮기기 - 예시 기록은 버린다`() {
        val json = """[
            {"id":"a","start":1,"end":2,"title":"진짜","km":3.0,"drivingSec":60,"overSec":0,"nightSec":0,"sample":false,"events":[{"item":"HARD_BRAKE","at":1,"detail":"x"}]},
            {"id":"b","start":1,"end":2,"title":"예시","km":3.0,"drivingSec":60,"overSec":0,"nightSec":0,"sample":true,"events":[]}
        ]"""
        val trips = TripStore.parseOldTrips(json)
        assertEquals(listOf("a"), trips.map { it.id })
        assertEquals(DEFAULT_DRIVER, trips.first().driver)
        assertEquals(ScoreItem.HARD_BRAKE, trips.first().events.first().item)
    }
}
