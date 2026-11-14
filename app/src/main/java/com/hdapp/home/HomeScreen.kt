package com.hdapp.home

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evbizassistant.charge.ChargeApp
import com.example.evbizassistant.charge.ChargeHost
import com.example.evbizassistant.charge.ui.ChargeDashboardView
import com.example.hyundai_sdk.TripLogBackground
import com.example.hyundai_sdk.TripLogScreen
import com.google.zxing.BarcodeFormat
import com.hdapp.R
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.key
import com.google.zxing.qrcode.QRCodeWriter
import com.hdapp.drivingscore.DrivingScore
import com.hdapp.drivingscore.DrivingScoreActivity
import com.hdapp.drivingscore.DrivingScoreService
import com.hdapp.drivingscore.Period
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import triplog.TripLogUser

/*
 * 메인 앱 — UI 파일
 *   LoginActivity   앱을 열 때마다 카카오 QR 로그인 (자동 로그인 없음)
 *   HomeActivity    로그인한 사람의 메인 페이지: 운전 점수 · 충전 기록 · 운행일지 + 로그인 기록
 *   ChargeActivity  충전 기록 화면 (팀 충전 파트)
 *   HdApp           주행이 끝날 때마다 로그인 기록에 한 줄 남김
 */

private object H {
    val Bg = Color(0xFF060B14)
    val Panel = Color(0xFF0F1A2A)
    val Panel2 = Color(0xFF16243A)
    val Line = Color(0xFF2A3C58)
    val Text = Color(0xFFF2F4F6)
    val Sub = Color(0xFFB8C2CF)
    val Accent = Color(0xFF00AAD2)
    val OnAccent = Color(0xFF031421)
    val Blue = Color(0xFF002C5F)
    val Kakao = Color(0xFFFEE500)
    val OnKakao = Color(0xFF191600)
    val Warn = Color(0xFFFFC56B)
}

// ───────────────────────── 앱 ─────────────────────────

class HdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 주행이 끝나면 지금 로그인한 사람 이름으로 기록을 남긴다 (화면을 닫고 있어도)
        val score = DrivingScore.get(this)
        MainScope().launch {
            score.finishedTrips.collect { t ->
                val events = t.events.groupBy { it.item.label }.entries.joinToString(" ") { "${it.key} ${it.value.size}" }.ifEmpty { "감점 없음" }
                LoginHistory.add(
                    this@HdApp, LoginSession.user, "주행",
                    "${t.title} · ${score.scoreOf(t)}점 · $events",
                    at = t.endMillis,
                )
            }
        }
    }
}

// ───────────────────────── 로그인 ─────────────────────────

/** 앱 첫 화면. 앱을 다시 열거나 메인에서 돌아오면 로그아웃하고 새 QR을 띄운다 */
class LoginActivity : ComponentActivity() {
    private var qrKey by mutableIntStateOf(0)
    /** 운전 점수에서 고른 운전자 이름. 로그인 화면에 "OO님, 카카오로 로그인해 주세요"로 보여준다 */
    private var hint by mutableStateOf<String?>(null)
    /** 계정 변경으로 들어오면: 번호 로그인 화면을 이름·번호를 채운 채로 연다 */
    private var prefill by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        val auth = KakaoQrAuth.create()
        setContent { Scaled { LoginScreen(auth, qrKey, hint, prefill, ::onLogin) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent) {
        hint = i.getStringExtra(EXTRA_HINT)
        val name = i.getStringExtra(EXTRA_NAME)
        val phone = i.getStringExtra(EXTRA_PHONE)
        prefill = if (name != null && phone != null) name to phone else null
    }

    companion object {
        private const val EXTRA_HINT = "login.hint"
        private const val EXTRA_NAME = "login.name"
        private const val EXTRA_PHONE = "login.phone"

        /** 계정 변경: 그 사람의 이름·번호를 채운 번호 로그인 화면으로 (로그인은 직접 눌러야 함) */
        fun switchAccount(context: android.content.Context, account: KakaoUser): Intent =
            switchUser(context, account.name).putExtra(EXTRA_NAME, account.name).putExtra(EXTRA_PHONE, account.phone)

        /** 다른 운전자로 바꾸기: 로그인 화면을 맨 앞으로 (위에 쌓인 화면은 닫힘 → 지금 사람은 로그아웃) */
        fun switchUser(context: android.content.Context, name: String?): Intent =
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_HINT, name)
    }

    override fun onResume() {
        super.onResume()
        // 자동 로그인 없음: 이 화면이 다시 보이면 이전 로그인은 끝낸다
        if (LoginSession.user != null) {
            LoginSession.signOut(this, if (hint != null) "운전자 바꾸기 ($hint)" else "로그인 화면으로 돌아옴")
            // 운행일지 기록은 끄지 않는다: 다음 사람이 로그인할 때까지 앞 사람 이름으로 계속 기록 (주행 중 끊김 방지)
            qrKey++
        }
    }

    private fun onLogin(u: KakaoUser) {
        LoginSession.signIn(this, u)
        DrivingScore.get(this).useLoginDriver(u.driverName)
        // 운행일지도 로그인한 사람으로 백그라운드 기록 시작 (화면을 안 열어도, 주행 중 화면이 막혀도 기록)
        TripLogBackground.start(this, tripUser(u))
        startActivity(Intent(this, HomeActivity::class.java))
    }
}

@Composable
private fun LoginScreen(auth: KakaoQrAuth, qrKey: Int, hint: String?, prefill: Pair<String, String>?, onLogin: (KakaoUser) -> Unit) {
    // QR 화면 ↔ 다른 아이디(휴대폰 번호) 로그인 화면. 계정 변경으로 오면 번호 화면부터, 아니면 QR부터
    var byPhone by remember(qrKey, prefill) { mutableStateOf(prefill != null) }
    if (byPhone) {
        key(qrKey, prefill) {
            PhoneLoginScreen(demo = auth.isMock, prefill = prefill, onBack = { byPhone = false }, onLogin = onLogin)
        }
        return
    }
    var session by remember { mutableStateOf<QrSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(qrKey, round) {
        error = null
        session = runCatching { auth.startSession() }.getOrElse { error = "로그인 서버에 연결할 수 없어요"; null }
        val s = session ?: return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < QR_LIFETIME_MS) {
            val u = runCatching { auth.poll(s) }.getOrElse { error = it.message; return@LaunchedEffect }
            if (u != null) {
                onLogin(u)
                return@LaunchedEffect
            }
            delay(if (auth.isMock) 300 else 2_000)
        }
        error = "QR 유효 시간(3분)이 지났어요"
    }

    Row(Modifier.fillMaxSize().background(H.Bg).padding(horizontal = 48.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T("카로그", 22.sp, H.Accent, FontWeight.Bold)
            T(if (hint != null) "${hint.substringBefore('·')}님, 카카오로 로그인" else "카카오로 로그인", 44.sp, weight = FontWeight.ExtraBold)
            T("휴대폰 카메라나 카카오톡으로 QR을 찍고\n카카오 로그인을 마치면 바로 시작돼요.", 24.sp, H.Sub, lineHeight = 36.sp)
            Spacer(Modifier.height(6.dp))
            Step("1", "휴대폰으로 오른쪽 QR 찍기")
            Step("2", "카카오 계정으로 로그인·동의")
            Step("3", "차 화면이 자동으로 넘어가요")
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(4.dp))
            Btn("다른 아이디로 로그인 (휴대폰 번호)", H.Panel2, H.Text, width = 520) { byPhone = true }
            T("자동 로그인 없이 앱을 열 때마다 로그인해요.", 18.sp, H.Sub)
        }
        Spacer(Modifier.width(40.dp))
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(32.dp)).background(H.Panel).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val s = session
            Box(Modifier.size(300.dp).clip(RoundedCornerShape(20.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                if (s != null && error == null) {
                    val bmp = remember(s.qrUrl) { qrBitmap(s.qrUrl, 560) }
                    Image(bmp.asImageBitmap(), contentDescription = "카카오 로그인 QR", modifier = Modifier.size(270.dp))
                } else {
                    T(error ?: "QR 만드는 중…", 20.sp, Color(0xFF55657A))
                }
            }
            if (error != null) {
                Btn("새 QR 만들기", H.Accent, H.OnAccent) { round++ }
            } else {
                T(if (auth.isMock) "시연용 QR · 실제 카카오와 연결되지 않아요\n시연은 왼쪽 '다른 아이디로 로그인'으로" else "QR은 3분 동안 쓸 수 있어요", 17.sp, H.Sub)
            }
        }
    }
}

/**
 * 다른 아이디로 로그인: 이름 + 휴대폰 번호. 입력칸은 화면 위쪽에 둔다 (차 화면 키보드가 아래 절반을 덮음)
 */
@Composable
private fun PhoneLoginScreen(demo: Boolean, prefill: Pair<String, String>?, onBack: () -> Unit, onLogin: (KakaoUser) -> Unit) {
    var name by remember { mutableStateOf(prefill?.first ?: "") }
    var phone by remember { mutableStateOf(prefill?.second ?: "") }
    var admin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        when {
            name.isBlank() -> error = "이름을 넣어 주세요"
            phone.length !in 10..11 -> error = "휴대폰 번호 10~11자리를 넣어 주세요"
            else -> onLogin(phoneLogin(name, phone, admin))
        }
    }
    Column(Modifier.fillMaxSize().background(H.Bg).padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(16.dp))
            Column {
                T(if (prefill != null) "${prefill.first}님 계정으로 로그인" else "다른 아이디로 로그인", 34.sp, weight = FontWeight.ExtraBold)
                T(if (prefill != null) "이름과 번호가 맞으면 로그인을 눌러 주세요" else "이름과 휴대폰 번호로 로그인해요", 20.sp, H.Sub)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(name, { name = it.take(10); error = null }, "이름") }
            Box(Modifier.weight(1.4f)) { Field(phone, { phone = it.filter { c -> c.isDigit() }.take(11); error = null }, "휴대폰 번호 (숫자만)", KeyboardType.Phone, onDone = submit) }
            Btn("로그인", H.Kakao, H.OnKakao, width = 160) { submit() }
        }
        error?.let { T(it, 18.sp, H.Warn) }
        if (demo) {
            // 서버가 없는 시연용에서만: 관리자 명단(adminPhones) 대신 직접 고른다
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox) { admin = !admin }.heightIn(min = 56.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (admin) H.Accent else H.Panel2),
                    contentAlignment = Alignment.Center,
                ) { if (admin) T("✓", 20.sp, H.OnAccent, FontWeight.ExtraBold) }
                Spacer(Modifier.width(12.dp))
                T("관리자로 로그인 (시연용 · 로그인 기록을 볼 수 있어요)", 20.sp)
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(H.Panel2)
            .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "뒤로 가기" },
        contentAlignment = Alignment.Center,
    ) { T("‹", 34.sp, weight = FontWeight.Bold) }
}

@Composable
private fun Step(no: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(H.Kakao), contentAlignment = Alignment.Center) {
            T(no, 20.sp, H.OnKakao, FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(14.dp))
        T(text, 22.sp, weight = FontWeight.Bold)
    }
}

// ───────────────────────── 메인 ─────────────────────────

/** 로그인한 사람만 들어온다. 운전 점수 · 충전 기록 · 운행일지로 간다 */
class HomeActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = LoginSession.user ?: run { finish(); return }
        DrivingScoreService.start(this)
        askCarPermissions()
        setContent {
            Scaled {
                HomeScreen(
                    user = user,
                    refresh = refresh,
                    onDriving = { startActivity(Intent(this, DrivingScoreActivity::class.java)) },
                    onCharge = { startActivity(Intent(this, ChargeActivity::class.java)) },
                    onTripLog = {
                        // 운행일지는 로그인한 카카오 ID로 기록 주인을 구분한다
                        startActivity(TripLogScreen.intent(this, tripUser(user)))
                    },
                    onLogout = { finish() }, // 로그인 화면으로 돌아가면 그 화면이 로그아웃을 기록한다
                    onSwitch = { account ->
                        startActivity(if (account != null) LoginActivity.switchAccount(this, account) else LoginActivity.switchUser(this, null))
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (LoginSession.user == null) finish() else refresh++
    }

    private fun askCarPermissions() {
        val needed = buildList {
            add("android.car.permission.CAR_SPEED")
            add("android.car.permission.CAR_POWERTRAIN")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 차속·페달 권한을 방금 받았으면 차량 신호를 다시 연결한다
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) DrivingScore.get(this).startCollecting()
    }
}

@Composable
private fun HomeScreen(
    user: KakaoUser,
    refresh: Int,
    onDriving: () -> Unit,
    onCharge: () -> Unit,
    onTripLog: () -> Unit,
    onLogout: () -> Unit,
    /** 계정 변경. null = 새 계정으로 로그인 */
    onSwitch: (KakaoUser?) -> Unit,
) {
    var switching by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val score = remember(refresh) { DrivingScore.get(context).summary(Period.MONTH) }
    val charges = remember(refresh) { runCatching { ChargeHost.app(context).records().size }.getOrDefault(0) }
    // 로그인 기록은 관리자만 본다
    val history = remember(refresh) { if (user.isAdmin) LoginHistory.recent(context, 30) else emptyList() }

    Column(Modifier.fillMaxSize().background(H.Bg).padding(horizontal = 36.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("카로그", 20.sp, H.Accent, FontWeight.Bold)
                T("${user.name}님, 안녕하세요", 34.sp, weight = FontWeight.ExtraBold)
            }
            if (user.isAdmin) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(H.Kakao).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    T("관리자", 18.sp, H.OnKakao, FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
            }
            // 누가 로그인했는지 늘 보이게. 눌러서 다른 이름·번호 계정으로 바꾼다
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(H.Panel2)
                    .clickable(role = Role.Button) { switching = true }
                    .heightIn(min = 64.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(user.name, 40)
                Spacer(Modifier.width(12.dp))
                Column {
                    T(user.name, 19.sp, weight = FontWeight.Bold, maxLines = 1)
                    T(if (user.phone.isNotEmpty()) maskPhone(user.phone) else user.via, 16.sp, H.Sub)
                }
                Spacer(Modifier.width(14.dp))
                T("계정 변경 ▾", 18.sp, H.Accent, FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Btn("로그아웃", Color(0xFF3A1E1E), Color(0xFFFF9C8C), width = 170) { onLogout() }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            FeatureCard(
                Modifier.weight(1f), R.drawable.ic_feature_score, "운전 점수", "보험 연동 안전운전",
                if (score.hasData) "${score.score}점" else "—", if (score.hasData) "이번 달 · ${score.word}" else "아직 이번 달 기록이 없어요",
                H.Blue, onDriving,
            )
            FeatureCard(Modifier.weight(1f), R.drawable.ic_feature_charge, "충전 기록", "충전기를 꽂으면 자동 기록", "${charges}건", "저장된 충전 기록", H.Panel2, onCharge)
            FeatureCard(Modifier.weight(1f), R.drawable.ic_feature_triplog, "운행일지", "업무용·개인용 운행 기록", "열기", "출발·도착·목적 기록", H.Panel2, onTripLog)
        }
        if (switching) {
            AccountDialog(user, onPick = { switching = false; onSwitch(it) }, onClose = { switching = false })
        }
        if (!user.isAdmin) return@Column
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(26.dp)).background(H.Panel).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row {
                T("로그인 기록 · 관리자만 보여요", 20.sp, H.Sub, FontWeight.Bold, Modifier.weight(1f))
                T("시간 · 이름 · 번호 · 구분 · 내용", 16.sp, H.Sub)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(history) { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(h.time, 16.sp, H.Sub, modifier = Modifier.width(190.dp))
                        T(h.name, 17.sp, weight = FontWeight.Bold, modifier = Modifier.width(110.dp), maxLines = 1)
                        T(maskPhone(h.phone), 16.sp, H.Sub, modifier = Modifier.width(150.dp))
                        T(h.kind, 16.sp, if (h.kind == "주행") H.Accent else H.Text, FontWeight.Bold, Modifier.width(80.dp))
                        T(h.detail, 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(modifier: Modifier, icon: Int, title: String, sub: String, big: String, caption: String, bg: Color, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(30.dp)).background(bg)
            .clickable(role = Role.Button, onClick = onClick).padding(28.dp),
    ) {
        T(title, 30.sp, weight = FontWeight.ExtraBold)
        T(sub, 19.sp, H.Sub)
        // 기능을 한눈에 알아보게 가운데 큰 아이콘
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(120.dp))
            }
        }
        T(big, 56.sp, weight = FontWeight.ExtraBold, lineHeight = 60.sp)
        T(caption, 19.sp, H.Sub, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(H.Accent), contentAlignment = Alignment.Center) {
            T("열기", 22.sp, H.OnAccent, FontWeight.ExtraBold)
        }
    }
}

/** 이름 첫 글자 동그라미 */
@Composable
private fun Avatar(name: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(H.Accent), contentAlignment = Alignment.Center) {
        T(name.take(1), (size * 0.45).sp, H.OnAccent, FontWeight.ExtraBold)
    }
}

/**
 * 계정 변경: 이 차에서 로그인한 적 있는 이름·번호 목록. 누르면 그 계정으로 다시 로그인한다 (바로 바뀌지 않음).
 * 다른 사람의 번호는 가려서 보여준다
 */
@Composable
private fun AccountDialog(user: KakaoUser, onPick: (KakaoUser?) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts = remember { LoginHistory.accounts(context).filter { it.phone != user.phone || it.name != user.name } }
    val density = LocalDensity.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.width(640.dp).clip(RoundedCornerShape(32.dp)).background(H.Panel2).padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("계정 변경", 32.sp, weight = FontWeight.ExtraBold)
                T("누르면 그 계정으로 다시 로그인해요", 19.sp, H.Sub)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(H.Accent.copy(alpha = 0.18f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.name, 48)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T(user.name, 22.sp, weight = FontWeight.ExtraBold)
                        T(maskPhone(user.phone), 17.sp, H.Sub)
                    }
                    T("지금 로그인", 17.sp, H.Accent, FontWeight.Bold)
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(H.Panel)
                                .clickable(role = Role.Button) { onPick(a) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(a.name, 48)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                T(a.name, 22.sp, weight = FontWeight.Bold, maxLines = 1)
                                T(if (a.phone.isNotEmpty()) maskPhone(a.phone) else a.via, 17.sp, H.Sub)
                            }
                            T("로그인 ›", 18.sp, H.Accent, FontWeight.Bold)
                        }
                    }
                    if (accounts.isEmpty()) T("이 차에서 로그인한 다른 계정이 없어요", 18.sp, H.Sub)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Btn("+ 새 계정으로 로그인", H.Panel, H.Text) { onPick(null) } }
                    Box(Modifier.weight(1f)) { Btn("닫기", H.Accent, H.OnAccent) { onClose() } }
                }
            }
        }
    }
}

// ───────────────────────── 충전 ─────────────────────────

/** 팀 충전 파트 화면을 그대로 띄운다. 충전 감지 자체는 DrivingScoreService 가 백그라운드에서 한다 */
class ChargeActivity : Activity() {
    private lateinit var charge: ChargeApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LoginSession.user == null) { finish(); return }
        charge = ChargeHost.app(applicationContext)
        setContentView(withBackBar(this, "충전 기록", ChargeDashboardView(this, charge)) { finish() })
        // 감지는 백그라운드 서비스가 계속 돌린다. 이미 켜져 있으면 아무 일도 안 함
        charge.start()
    }
    // 화면을 닫아도 감지를 끄지 않는다 (release 하면 백그라운드 충전 기록도 멈춤)
}

// ───────────────────────── 공통 ─────────────────────────

/**
 * 팀 파트의 일반 View 화면 위에 뒤로 가기 줄을 붙인다 (운행일지의 "‹" 버튼과 같은 모양).
 * 차 화면 배율에 맞춰 dp 를 키운다 (가로 1280dp 기준, 최대 1.8배)
 */
private fun withBackBar(activity: Activity, title: String, content: android.view.View, onBack: () -> Unit): android.view.View {
    val metrics = activity.resources.displayMetrics
    val scale = (metrics.widthPixels / metrics.density / 1280f).coerceIn(1f, 1.8f)
    fun dp(v: Int) = (v * metrics.density * scale).toInt()
    val bg = AColor.parseColor("#11151B")
    val back = android.widget.TextView(activity).apply {
        text = "‹"
        textSize = 26f * scale
        setTextColor(AColor.WHITE)
        gravity = android.view.Gravity.CENTER
        contentDescription = "뒤로 가기"
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(AColor.parseColor("#1F2733"))
        }
        setOnClickListener { onBack() }
    }
    val label = android.widget.TextView(activity).apply {
        text = title
        textSize = 22f * scale
        setTextColor(AColor.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(16), 0, 0, 0)
    }
    val bar = android.widget.LinearLayout(activity).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(4))
        setBackgroundColor(bg)
        addView(back, android.widget.LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(label)
    }
    return android.widget.LinearLayout(activity).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(bg)
        addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
        addView(content, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

private const val QR_LIFETIME_MS = 3 * 60_000L

/** 운행일지에서 쓰는 사용자: 기록 주인은 로그인 ID로 구분 */
internal fun tripUser(u: KakaoUser) = TripLogUser("kakao:${u.id}", u.driverName, isAdmin = u.isAdmin)

/** 010-****-5678 */
internal fun maskPhone(p: String) = when {
    p.length >= 10 -> "${p.take(3)}-****-${p.takeLast(4)}"
    else -> p
}

internal fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** 차량 화면은 해상도가 높고 밀도가 낮아 글씨가 작게 보인다 → 가로 1280dp 기준으로 키운다 (운전 점수 화면과 같은 방식) */
@Composable
private fun Scaled(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val factor = (maxWidth.value / 1280f).coerceIn(1f, 1.8f)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * factor, base.fontScale), content = content)
    }
}

@Composable
private fun T(
    text: String,
    size: TextUnit = 22.sp,
    color: Color = H.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) = Text(text, modifier, color = color, fontSize = size, fontWeight = weight, lineHeight = lineHeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
private fun Btn(text: String, bg: Color, fg: Color, width: Int? = null, onClick: () -> Unit) {
    Box(
        (if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
            .heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 21.sp, fg, FontWeight.ExtraBold) }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { T(hint, 18.sp, H.Sub) },
        textStyle = TextStyle(fontSize = 20.sp, color = H.Text),
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = H.Accent, unfocusedBorderColor = H.Line, cursorColor = H.Accent),
        modifier = Modifier.fillMaxWidth(),
    )
}
