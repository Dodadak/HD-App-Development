package com.hdapp.drivingscore

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room DB: 넣기 · 지우기 · 운전자 이름 바꾸기/지우기 (Robolectric, 에뮬레이터 없이) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDbTest {
    private lateinit var db: DrivingScoreDb
    private lateinit var dao: TripDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DrivingScoreDb::class.java)
            .allowMainThreadQueries().build()
        dao = db.trips()
    }

    @After
    fun tearDown() = db.close()

    private fun trip(id: String, driver: String = DEFAULT_DRIVER, start: Long = 1_000L) = TripRecord(
        id = id, startMillis = start, endMillis = start + 60_000, title = "주행 $id", distanceKm = 3.0,
        drivingSec = 60, overspeedSec = 0, nightSec = 0, driver = driver,
        events = listOf(DrivingEvent(ScoreItem.HARD_BRAKE, start + 10_000, "테스트")),
    )

    @Test
    fun `넣은 순서와 상관없이 출발 시각 순으로 읽는다`() = runTest {
        dao.insert(trip("b", start = 2_000).toEntity())
        dao.insert(trip("a", start = 1_000).toEntity())
        assertEquals(listOf("a", "b"), dao.all().map { it.id })
        assertEquals(trip("a", start = 1_000), dao.all().first().toRecord())
    }

    @Test
    fun `같은 id로 다시 넣으면 덮어쓴다 (다른 운전자로 옮기기)`() = runTest {
        dao.insert(trip("a").toEntity())
        dao.insert(trip("a", driver = "엄마").toEntity())
        assertEquals(listOf("엄마"), dao.all().map { it.driver })
    }

    @Test
    fun `한 건 지우기`() = runTest {
        dao.insertAll(listOf(trip("a").toEntity(), trip("b").toEntity()))
        dao.delete("a")
        assertEquals(listOf("b"), dao.all().map { it.id })
    }

    @Test
    fun `운전자 이름 바꾸기와 지우기`() = runTest {
        dao.insertAll(listOf(trip("a", "나").toEntity(), trip("b", "아빠").toEntity(), trip("c", "아빠").toEntity()))
        dao.renameDriver("아빠", "Dad")
        assertEquals(setOf("나", "Dad"), dao.all().map { it.driver }.toSet())
        dao.deleteDriver("Dad")
        assertEquals(listOf("a"), dao.all().map { it.id })
    }
}
