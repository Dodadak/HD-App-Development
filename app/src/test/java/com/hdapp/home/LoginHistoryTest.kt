package com.hdapp.home

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 로그인 기록 · 시연용 QR 로그인 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val user = KakaoUser("mock-01012345678", "홍길동", "01012345678")

    @Test
    fun `기록은 시간·이름·번호·카카오ID·구분·내용으로 남고 최근 것부터 읽힌다`() {
        LoginHistory.file(context).delete()
        LoginHistory.add(context, user, "로그인", "카카오 QR 로그인", at = 1_000L)
        LoginHistory.add(context, user, "주행", "오후 3시 출발 · 92점 · 12.0km · 급가속 1, 차선 변경 1", at = 2_000L)
        val lines = LoginHistory.recent(context)
        assertEquals(listOf("주행", "로그인"), lines.map { it.kind })
        assertEquals("홍길동", lines[0].name)
        assertEquals("01012345678", lines[0].phone)
        // 쉼표가 든 내용도 한 칸으로 읽힌다
        assertEquals("오후 3시 출발 · 92점 · 12.0km · 급가속 1, 차선 변경 1", lines[0].detail)
        assertEquals("시간,이름,번호,카카오ID,구분,내용", LoginHistory.file(context).readLines().first())
    }

    @Test
    fun `로그아웃해도 기록은 남고 세션만 비워진다`() {
        LoginHistory.file(context).delete()
        LoginSession.signIn(context, user)
        LoginSession.signOut(context, "앱을 다시 엶")
        assertNull(LoginSession.user)
        assertEquals(listOf("로그아웃", "로그인"), LoginHistory.recent(context).map { it.kind })
    }

    @Test
    fun `시연용 QR 로그인 - 휴대폰 쪽을 마치면 그 세션에서 사용자가 나온다`() = runBlocking {
        val s = MockKakaoQrAuth.startSession()
        assertNull(MockKakaoQrAuth.poll(s))
        MockKakaoQrAuth.complete(s, "홍길동", "010-1234-5678")
        val u = MockKakaoQrAuth.poll(s)!!
        assertEquals("홍길동·5678", u.driverName)
        assertEquals("010-****-5678", maskPhone(u.phone))
    }

    @Test
    fun `번호 로그인 - 시연용에서는 관리자 선택이 되고 기록에 방식이 남는다`() {
        LoginHistory.file(context).delete()
        val admin = phoneLogin("박관리", "010-5555-6666", demoAdmin = true)
        val normal = phoneLogin("최직원", "01077778888", demoAdmin = false)
        assertEquals(true, admin.isAdmin)
        assertEquals(false, normal.isAdmin)
        assertEquals("휴대폰 번호", normal.via)
        assertEquals("01055556666", admin.phone)
        LoginSession.signIn(context, admin)
        assertEquals("휴대폰 번호 로그인 · 관리자", LoginHistory.recent(context).first().detail)
        LoginSession.signOut(context, "테스트")
    }

    @Test
    fun `계정 목록 - 로그인한 사람만, 최근 순, 같은 사람은 한 번`() {
        LoginHistory.file(context).delete()
        val a = phoneLogin("김일", "01011112222", demoAdmin = false)
        val b = phoneLogin("이이", "01033334444", demoAdmin = false)
        LoginSession.signIn(context, a); LoginSession.signOut(context, "t")
        LoginSession.signIn(context, b); LoginSession.signOut(context, "t")
        LoginSession.signIn(context, a); LoginSession.signOut(context, "t")
        val list = LoginHistory.accounts(context)
        assertEquals(listOf("김일", "이이"), list.map { it.name })
        assertEquals("휴대폰 번호", list.first().via)
        assertEquals("01033334444", list[1].phone)
    }
}
