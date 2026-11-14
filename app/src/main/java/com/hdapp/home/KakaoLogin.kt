package com.hdapp.home

import android.content.Context
import com.hdapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * 카카오 QR 로그인 — 기능 파일
 *
 *   KakaoQrAuth     QR 세션 만들기 → 휴대폰에서 카카오 로그인 → 차에서 결과 받기
 *     ServerKakaoQrAuth  실제 카카오 연동 (server/kakao-qr 서버 필요, BuildConfig.KAKAO_QR_SERVER)
 *     MockKakaoQrAuth    서버가 없을 때 시연용 (휴대폰 대신 화면에서 이름·번호를 넣어 로그인한 것처럼)
 *   LoginSession    지금 로그인한 사람. 메모리에만 두어 앱을 다시 열면 매번 새로 로그인한다 (자동 로그인 없음)
 *   LoginHistory    {시간},{이름},{번호},{카카오ID},{구분},{내용} 기록. 로그아웃해도 남는다
 */

/**
 * 로그인한 사용자. phone 은 카카오 비즈 앱 검수 전에는 비어 있을 수 있다.
 * via = "카카오 QR" 또는 "휴대폰 번호", isAdmin = 로그인 기록을 볼 수 있는 관리자
 */
data class KakaoUser(
    val id: String,
    val name: String,
    val phone: String,
    val via: String = "카카오 QR",
    val isAdmin: Boolean = false,
) {
    /** 운전 점수·운행일지에서 쓰는 이름. 같은 이름이 있어도 섞이지 않게 번호 뒤 4자리를 붙인다 */
    val driverName get() = if (phone.length >= 4) "$name·${phone.takeLast(4)}" else name
}

/** 차 화면에 띄울 QR 한 장 */
data class QrSession(val id: String, val qrUrl: String)

interface KakaoQrAuth {
    /** 서버 없이 화면에서 로그인을 흉내 내는 시연용인지 */
    val isMock: Boolean

    suspend fun startSession(): QrSession

    /** 휴대폰에서 로그인을 마쳤으면 사용자, 아직이면 null. 세션이 만료되면 예외 */
    suspend fun poll(session: QrSession): KakaoUser?

    companion object {
        /** BuildConfig.KAKAO_QR_SERVER 가 비어 있으면 시연용 */
        fun create(): KakaoQrAuth =
            if (BuildConfig.KAKAO_QR_SERVER.isBlank()) MockKakaoQrAuth else ServerKakaoQrAuth(BuildConfig.KAKAO_QR_SERVER)
    }
}

/**
 * 실제 카카오 연동. 카카오는 TV·차량용 "기기 로그인"을 따로 주지 않아서, 작은 서버가 중간에서 받는다.
 *   POST {server}/qr-sessions          → {"id": "...", "url": "https://.../qr/{id}"}   (url 을 QR로 띄움)
 *   GET  {server}/qr-sessions/{id}     → {"status": "pending"} 또는 {"status": "done", "user": {...}}
 * 휴대폰이 url 을 열면 서버가 카카오 로그인으로 보내고, 끝나면 사용자 정보를 그 세션에 넣어 둔다.
 */
class ServerKakaoQrAuth(private val server: String) : KakaoQrAuth {
    override val isMock = false

    override suspend fun startSession(): QrSession = withContext(Dispatchers.IO) {
        val o = request("POST", "$server/qr-sessions")
        QrSession(o.getString("id"), o.getString("url"))
    }

    override suspend fun poll(session: QrSession): KakaoUser? = withContext(Dispatchers.IO) {
        val o = request("GET", "$server/qr-sessions/${session.id}")
        when (o.getString("status")) {
            "done" -> o.getJSONObject("user").let {
                val phone = it.optString("phone")
                KakaoUser(it.getString("id"), it.getString("name"), phone, isAdmin = Admins.isAdmin(phone))
            }
            "pending" -> null
            else -> throw IllegalStateException("QR이 만료됐어요. 새 QR로 다시 시도해 주세요.")
        }
    }

    private fun request(method: String, url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("로그인 서버 응답 ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}

/**
 * 시연용. QR 주소는 실제 서버 형식과 같게 만들지만 아무 데도 연결되지 않는다.
 * 화면의 "휴대폰에서 로그인한 것으로 하기"가 [complete]를 불러 휴대폰 쪽을 대신한다.
 * shortcut: 카카오 인증 없이 입력값을 그대로 믿음, 카카오 앱 키와 server/kakao-qr 서버가 준비되면 ServerKakaoQrAuth 로 교체
 */
object MockKakaoQrAuth : KakaoQrAuth {
    override val isMock = true
    private val done = mutableMapOf<String, KakaoUser>()

    override suspend fun startSession(): QrSession {
        val id = UUID.randomUUID().toString().take(8)
        return QrSession(id, "https://hdapp.example/qr/$id")
    }

    override suspend fun poll(session: QrSession): KakaoUser? {
        delay(300)
        return synchronized(done) { done.remove(session.id) }
    }

    /** 휴대폰에서 카카오 로그인을 마친 것처럼 처리 */
    fun complete(session: QrSession, name: String, phone: String) {
        val digits = phone.filter { it.isDigit() }
        synchronized(done) { done[session.id] = KakaoUser("mock-$digits", name.trim(), digits, isAdmin = Admins.isAdmin(digits)) }
    }
}

/**
 * 관리자 명단. local.properties 의 adminPhones=01011112222,01033334444 (쉼표로 구분)로 정한다.
 * 시연용(서버 없음)에서는 번호 로그인 화면의 "관리자로 로그인"으로도 관리자가 될 수 있다
 */
object Admins {
    private val phones get() = BuildConfig.ADMIN_PHONES.split(',').map { it.filter(Char::isDigit) }.filter { it.isNotEmpty() }

    fun isAdmin(phone: String) = phone.filter(Char::isDigit) in phones
}

/**
 * 다른 아이디로 로그인: 이름과 휴대폰 번호로 로그인한다.
 * shortcut: 문자(SMS) 인증 없이 입력한 번호를 그대로 믿음, 실제 서비스 전에 휴대폰 본인 인증을 붙일 것
 */
fun phoneLogin(name: String, phone: String, demoAdmin: Boolean): KakaoUser {
    val digits = phone.filter(Char::isDigit)
    val admin = Admins.isAdmin(digits) || (demoAdmin && BuildConfig.KAKAO_QR_SERVER.isBlank())
    return KakaoUser("phone-$digits", name.trim(), digits, via = "휴대폰 번호", isAdmin = admin)
}

/** 지금 로그인한 사람. 파일에 저장하지 않으므로 앱을 다시 열면 비어 있다 (매번 로그인) */
object LoginSession {
    @Volatile var user: KakaoUser? = null
        private set

    fun signIn(context: Context, u: KakaoUser) {
        user = u
        LoginHistory.add(context, u, "로그인", "${u.via} 로그인" + if (u.isAdmin) " · 관리자" else "")
    }

    fun signOut(context: Context, reason: String) {
        val u = user ?: return
        user = null
        LoginHistory.add(context, u, "로그아웃", reason)
    }
}

/** 한 줄 기록 */
data class HistoryLine(val time: String, val name: String, val phone: String, val kakaoId: String, val kind: String, val detail: String)

/**
 * 로그인·로그아웃·주행 기록. 앱 안 파일(login_history.csv)에 한 줄씩 쌓고 지우지 않는다.
 * 형식: 시간,이름,번호,카카오ID,구분,내용  (쉼표가 든 값은 큰따옴표로 감싼다)
 */
object LoginHistory {
    private const val FILE = "login_history.csv"
    private const val HEADER = "시간,이름,번호,카카오ID,구분,내용"
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun add(context: Context, user: KakaoUser?, kind: String, detail: String, at: Long = System.currentTimeMillis()) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) f.writeText(HEADER + "\n")
        val cols = listOf(time.format(Date(at)), user?.name ?: "미로그인", user?.phone ?: "", user?.id ?: "", kind, detail)
        f.appendText(cols.joinToString(",") { csv(it) } + "\n")
    }

    /** 최근 기록부터 */
    @Synchronized
    fun recent(context: Context, limit: Int = 50): List<HistoryLine> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.takeLast(limit).reversed().mapNotNull { line ->
            parse(line).takeIf { it.size >= 6 }?.let { HistoryLine(it[0], it[1], it[2], it[3], it[4], it[5]) }
        }
    }

    fun file(context: Context) = File(context.filesDir, FILE)

    /** 이 차에서 로그인한 적 있는 계정 (이름·번호 기준, 최근 로그인 순) */
    @Synchronized
    fun accounts(context: Context): List<KakaoUser> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).reversed().mapNotNull { line ->
            parse(line).takeIf { it.size >= 6 && it[4] == "로그인" && it[1] != "미로그인" }
                ?.let { KakaoUser(it[3], it[1], it[2], via = it[5].substringBefore(" 로그인")) }
        }.distinctBy { it.name to it.phone }
    }

    internal fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"" else v

    internal fun parse(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
