package com.hdapp.drivingscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 에뮬레이터 없이 감점 판정과 점수 계산을 확인한다 */
class DrivingRulesTest {

    /** (초, 속도, 기어) 목록을 0.5초 간격 샘플로 펼쳐서 기록기에 넣는다 */
    private fun drive(vararg points: Triple<Double, Float, Gear>, limit: Int? = 80): Pair<List<DrivingEvent>, TripRecord?> {
        val events = mutableListOf<DrivingEvent>()
        var trip: TripRecord? = null
        val recorder = TripRecorder(onEvent = { events += it }, onTripEnd = { trip = it })
        val t0 = 1_700_000_000_000L
        var sec = 0.0
        while (sec <= points.last().first) {
            val (a, b) = points.toList().zipWithNext().first { (a, b) -> sec >= a.first && sec <= b.first }
            val f = if (b.first == a.first) 1.0 else (sec - a.first) / (b.first - a.first)
            val speed = (a.second + (b.second - a.second) * f).toFloat()
            val gear = if (sec >= points.last().first) points.last().third else b.third
            recorder.feed(DrivingSample(t0 + (sec * 1000).toLong(), speed, gear, false, limit))
            sec += 0.5
        }
        return events to trip
    }

    @Test
    fun `2초에 30km 빨라지면 급출발`() {
        val (events, _) = drive(Triple(0.0, 0f, Gear.D), Triple(5.0, 30f, Gear.D), Triple(7.0, 60f, Gear.D), Triple(12.0, 60f, Gear.D))
        assertTrue(events.any { it.item == ScoreItem.HARD_ACCEL })
    }

    @Test
    fun `2초에 30km 느려지면 급정거`() {
        val (events, _) = drive(Triple(0.0, 60f, Gear.D), Triple(5.0, 60f, Gear.D), Triple(7.0, 30f, Gear.D), Triple(12.0, 30f, Gear.D))
        assertTrue(events.any { it.item == ScoreItem.HARD_BRAKE })
    }

    @Test
    fun `부드럽게 달리면 감점 없음`() {
        val (events, _) = drive(Triple(0.0, 0f, Gear.D), Triple(20.0, 60f, Gear.D), Triple(40.0, 60f, Gear.D), Triple(60.0, 0f, Gear.D))
        assertEquals(emptyList<DrivingEvent>(), events)
    }

    @Test
    fun `제한 80에서 105로 5초 넘게 달리면 과속 1회 (제한+20 초과)`() {
        val (events, _) = drive(Triple(0.0, 95f, Gear.D), Triple(10.0, 95f, Gear.D), Triple(30.0, 95f, Gear.D), limit = 80)
        assertTrue(events.none { it.item == ScoreItem.SPEEDING })
        val (fast, _) = drive(Triple(0.0, 105f, Gear.D), Triple(12.0, 105f, Gear.D), limit = 80)
        assertEquals(1, fast.count { it.item == ScoreItem.SPEEDING })
    }

    @Test
    fun `기어를 P로 바꾸면 주행이 저장된다`() {
        val (_, trip) = drive(Triple(0.0, 0f, Gear.D), Triple(20.0, 50f, Gear.D), Triple(40.0, 0f, Gear.D), Triple(41.0, 0f, Gear.P))
        assertNotNull(trip)
        assertTrue(trip!!.distanceKm > 0.1)
    }

    @Test
    fun `기록이 없으면 점수 대신 빈 상태를 보여줄 수 있다`() {
        // 화면은 hasData=false 일 때 점수를 숨긴다. 규칙 자체는 빈 목록에도 안전해야 한다
        assertEquals(100, DrivingRules.totalScore(emptyList()))
    }

    @Test
    fun `점수는 항상 0~100 사이`() {
        val (_, trip) = drive(
            Triple(0.0, 0f, Gear.D), Triple(3.0, 40f, Gear.D), Triple(4.0, 70f, Gear.D), Triple(20.0, 110f, Gear.D),
            Triple(22.0, 60f, Gear.D), Triple(30.0, 0f, Gear.D), Triple(31.0, 0f, Gear.P), limit = 50,
        )
        val trips = listOf(trip!!)
        assertTrue(DrivingRules.totalScore(trips) in 0..100)
        ScoreItem.entries.forEach { assertTrue(DrivingRules.itemScore(it, trips) in 0..100) }
        println("거친 주행 ${DrivingRules.totalScore(trips)}점 · 감점 ${trip.events.map { it.item.label }}")
    }

    @Test
    fun `센서 값이 튀면 감점하지 않는다`() {
        // 1초에 70→200km: 실제 차가 낼 수 없는 값
        val (events, _) = drive(Triple(0.0, 70f, Gear.D), Triple(5.0, 70f, Gear.D), Triple(6.0, 200f, Gear.D), Triple(7.0, 70f, Gear.D), Triple(12.0, 70f, Gear.D), limit = 250)
        assertTrue(events.none { it.item == ScoreItem.HARD_ACCEL || it.item == ScoreItem.HARD_BRAKE })
    }

    @Test
    fun `임시 저장한 주행을 이어 받아 P에서 저장한다`() {
        var saved: TripRecord? = null
        val first = TripRecorder(onEvent = {}, onTripEnd = {})
        val t0 = 1_700_000_000_000L
        (0..60).forEach { i -> first.feed(DrivingSample(t0 + i * 500L, 60f, Gear.D, false, 80)) }
        val checkpoint = first.current()!!

        // 앱이 꺼졌다 켜짐 → 새 기록기가 이어 받는다
        val second = TripRecorder(onEvent = {}, onTripEnd = { saved = it })
        second.resume(checkpoint)
        assertTrue(second.isRecording)
        (0..20).forEach { i -> second.feed(DrivingSample(t0 + 40_000L + i * 500L, 60f, Gear.D, false, 80)) }
        second.feed(DrivingSample(t0 + 52_000L, 0f, Gear.P, false, 80))

        assertNotNull(saved)
        assertEquals(checkpoint.startMillis, saved!!.startMillis)
        assertTrue(saved!!.distanceKm > checkpoint.distanceKm)
    }

    /** 시속 [speed]로 달리며 0.5초마다 (페달%, 깜빡이, 핸들 각도)를 넣는다 */
    private fun feedSeq(rec: TripRecorder, speed: Float, seq: List<Triple<Float?, TurnSignal, Float?>>) {
        val t0 = 1_700_000_000_000L
        rec.feed(DrivingSample(t0, speed, Gear.D, false, 100))
        seq.forEachIndexed { i, (pedal, sig, steer) ->
            rec.feed(DrivingSample(t0 + (i + 1) * 500L, speed, Gear.D, false, 100, pedal, sig, steer))
        }
    }

    private fun recorder(events: MutableList<DrivingEvent>) = TripRecorder(onEvent = { events += it }, onTripEnd = {})

    @Test
    fun `가속 페달이 200을 넘으면 급가속 1회 (넘는 순간만)`() {
        val events = mutableListOf<DrivingEvent>()
        // 70% = 179 → 80% = 204 (200 초과) → 계속 밟고 있어도 다시 세지 않음
        feedSeq(recorder(events), 30f, listOf(70f, 80f, 85f, 90f).map { Triple(it, TurnSignal.OFF, 0f) })
        assertEquals(1, events.count { it.item == ScoreItem.HARD_ACCEL })
    }

    @Test
    fun `가속 페달이 200 이하면 속도가 빨리 올라도 급가속 아님`() {
        val events = mutableListOf<DrivingEvent>()
        val rec = recorder(events)
        val t0 = 1_700_000_000_000L
        // 1초에 30km/h씩 올라도 페달은 60% (153)
        (0..6).forEach { i -> rec.feed(DrivingSample(t0 + i * 500L, 10f + i * 15f, Gear.D, false, 200, 60f)) }
        assertTrue(events.none { it.item == ScoreItem.HARD_ACCEL })
    }

    @Test
    fun `깜빡이 없이 S자로 핸들을 움직이면 차선 변경 감점`() {
        val events = mutableListOf<DrivingEvent>()
        val steer = listOf(0f, 3f, 7f, 8f, 4f, -2f, -6f, -3f, 0f)
        feedSeq(recorder(events), 80f, steer.map { Triple(null, TurnSignal.OFF, it) })
        assertEquals(1, events.count { it.item == ScoreItem.LANE_CHANGE })
    }

    @Test
    fun `깜빡이를 켜고 바꾸면 감점 없음`() {
        val events = mutableListOf<DrivingEvent>()
        val steer = listOf(0f, 0f, 3f, 7f, 8f, 4f, -2f, -6f, -3f, 0f)
        val sig = listOf(TurnSignal.LEFT, TurnSignal.LEFT, TurnSignal.LEFT, TurnSignal.OFF) + List(6) { TurnSignal.OFF }
        feedSeq(recorder(events), 80f, steer.zip(sig).map { (a, s) -> Triple(null, s, a) })
        assertTrue(events.none { it.item == ScoreItem.LANE_CHANGE })
    }

    @Test
    fun `한쪽으로만 오래 꺾인 굽은 길은 차선 변경 아님`() {
        val events = mutableListOf<DrivingEvent>()
        // 8초 동안 왼쪽으로 꺾여 있다가 반대로 → 6초 창을 넘어서 굽은 길로 봄
        val steer = List(16) { 9f } + listOf(-6f)
        feedSeq(recorder(events), 80f, steer.map { Triple(null, TurnSignal.OFF, it) })
        assertTrue(events.none { it.item == ScoreItem.LANE_CHANGE })
    }

    @Test
    fun `시속 40km 미만에서는 차선 변경을 보지 않음`() {
        val events = mutableListOf<DrivingEvent>()
        val steer = listOf(0f, 7f, 8f, -6f, -7f, 0f)
        feedSeq(recorder(events), 30f, steer.map { Triple(null, TurnSignal.OFF, it) })
        assertTrue(events.none { it.item == ScoreItem.LANE_CHANGE })
    }
}
