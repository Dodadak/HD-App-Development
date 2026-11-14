package com.hdapp.drivingscore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.hdapp.R
import com.hdapp.home.LoginActivity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * 운전 점수 — UI 파일
 *
 * 2030: 점수가 차오르는 게이지, 숫자 카운트업, 감점이 부드럽게 올라오는 알림 → "예쁘다"
 * 4050: 숫자마다 붙는 말(좋아요/조금 잦아요), 기준 막대, 고칠 점 딱 한 가지, 주행별 감점 이유 → "편하다, 이렇게 하면 되겠네"
 * 주행 중(기어 D/R)에는 큰 점수와 방금 감점만 보여주고 조작을 막는다.
 */

// ───────────────────────── 색 (D안: 현대 컬러) ─────────────────────────

private object K {
    val Bg = Color(0xFF060B14)
    val Panel = Color(0xFF0F1A2A)
    val Panel2 = Color(0xFF16243A)
    val Line = Color(0xFF2A3C58)
    val Text = Color(0xFFF2F4F6)
    val Sub = Color(0xFFB8C2CF)
    val SubOnBlue = Color(0xFFD6E4F5)
    val Accent = Color(0xFF00AAD2)
    val AccentSoft = Color(0xFF7FDDF2)
    val OnAccent = Color(0xFF031421)
    val Blue = Color(0xFF002C5F)
    val BlueDeep = Color(0xFF0B3D78)
    val Good = Color(0xFF3DD68C)
    val GoodBg = Color(0xFF10291F)
    val Warn = Color(0xFFFFC56B)
    val WarnBg = Color(0xFF2A2113)
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0A1220)
}

private fun itemColor(item: ScoreItem) = when (item) {
    ScoreItem.HARD_ACCEL -> Color(0xFF7FDDF2)
    ScoreItem.HARD_BRAKE -> Color(0xFFFFC56B)
    ScoreItem.SPEEDING -> Color(0xFFFF8A7A)
    ScoreItem.LANE_CHANGE -> Color(0xFF8FE3A8)
    ScoreItem.NIGHT -> Color(0xFFB9A6FF)
}

/** 항목 기호(↗ ↘ ≫ ☾)는 화면 읽기(TalkBack)가 이상하게 읽지 않도록 읽지 않게 표시해서 쓴다 */
private val NoRead = Modifier.clearAndSetSemantics { }

private fun itemIcon(item: ScoreItem) = when (item) {
    ScoreItem.HARD_ACCEL -> "↗"
    ScoreItem.HARD_BRAKE -> "↘"
    ScoreItem.SPEEDING -> "≫"
    ScoreItem.LANE_CHANGE -> "⇄"
    ScoreItem.NIGHT -> "☾"
}

// ───────────────────────── 화면 진입점 ─────────────────────────

class DrivingScoreActivity : ComponentActivity() {
    private lateinit var score: DrivingScore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 기록은 백그라운드 서비스가 맡는다 → 화면을 닫아도, 내비로 넘어가도 계속 기록
        score = DrivingScore.get(this)
        DrivingScoreService.start(this)
        val needed = buildList {
            if (checkSelfPermission(CAR_SPEED) != PackageManager.PERMISSION_GRANTED) add(CAR_SPEED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
        setContent { DrivingScoreApp(score, onFixSpeed = ::fixSpeedPermission, onBack = ::finish, onLoginAs = ::loginAs) }
    }

    /** 다른 운전자로 바꾸려면 카카오 로그인부터 다시 (로그인 화면으로 돌아가면 지금 사람은 로그아웃) */
    private fun loginAs(name: String?) {
        startActivity(LoginActivity.switchUser(this, name))
        finish()
    }

    /** "차속 신호 없음"을 눌렀을 때: 다시 물어보고, 예전에 "다시 묻지 않기"를 골랐으면 앱 설정 화면을 연다 */
    private fun fixSpeedPermission() {
        if (checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
            score.startCollecting()
            return
        }
        askedFromButton = true
        requestPermissions(arrayOf(CAR_SPEED), 2)
    }

    private var askedFromButton = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 차속 권한을 방금 받았으면 차량 신호를 다시 연결해서 차속까지 받는다
        // (허용하지 않아도 기어·브레이크는 동작한다)
        val i = permissions.indexOf(CAR_SPEED)
        if (i >= 0 && grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) {
            score.startCollecting()
        } else if (i >= 0 && askedFromButton && !shouldShowRequestPermissionRationale(CAR_SPEED)) {
            // 시스템이 더 이상 묻지 않는 상태 → 설정 화면에서 직접 허용
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        askedFromButton = false
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}

@Composable
fun DrivingScoreApp(
    score: DrivingScore,
    onFixSpeed: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** 운전자 바꾸기 = 그 사람으로 다시 로그인. null 이면 예전처럼 바로 바꾼다 (로그인 없는 단독 실행) */
    onLoginAs: ((String?) -> Unit)? = null,
) {
    val state by score.state.collectAsState()
    var period by remember { mutableStateOf(Period.MONTH) }
    val summary = remember(period, state.tripsVersion, state.driver) { score.summary(period) }
    var detail by remember { mutableStateOf<TripRecord?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<ShareResult?>(null) }
    var popup by remember { mutableStateOf(Popup.NONE) }

    val driving = state.live != null && (state.gear == Gear.D || state.gear == Gear.R || state.gear == Gear.N)
    // 주행이 시작되면 열려 있던 창을 모두 닫는다 (운전 중 화면을 가리지 않게)
    LaunchedEffect(driving) {
        if (driving) {
            detail = null
            sharing = false
            popup = Popup.NONE
        }
    }
    // 운전자가 바뀌면 이전 사람의 "보냈어요" 표시를 지운다
    LaunchedEffect(state.driver) { shareResult = null }

    // 차량 화면은 해상도가 높고 밀도가 낮아서 글씨가 작아 보인다 → 가로 1280dp 기준으로 전체를 키운다
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val factor = (maxWidth.value / 1280f).coerceIn(1f, 1.8f)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * factor, base.fontScale)) {
            ScreenContent(score, state, summary, period, { period = it }, driving, detail, { detail = it },
                sharing, { sharing = it }, shareResult, { shareResult = it }, popup, { popup = it }, onFixSpeed, onBack, onLoginAs)
        }
    }
}

@Composable
private fun ScreenContent(
    score: DrivingScore,
    state: DrivingScoreState,
    summary: ScoreSummary,
    period: Period,
    setPeriod: (Period) -> Unit,
    driving: Boolean,
    detail: TripRecord?,
    setDetail: (TripRecord?) -> Unit,
    sharing: Boolean,
    setSharing: (Boolean) -> Unit,
    shareResult: ShareResult?,
    setShareResult: (ShareResult?) -> Unit,
    popup: Popup,
    setPopup: (Popup) -> Unit,
    onFixSpeed: () -> Unit,
    onBack: (() -> Unit)?,
    onLoginAs: ((String?) -> Unit)?,
) {
    val ui = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(K.Bg)) {
        AnimatedContent(
            targetState = driving,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) },
            label = "mode",
        ) { isDriving ->
            if (isDriving) {
                // 로그인으로 운전자를 정하는 앱에서는 주행 화면에서 운전자를 바꾸지 않는다
                DrivingView(state, onPickDriver = if (onLoginAs == null) score::selectDriver else null)
            } else {
                Dashboard(
                    state = state,
                    summary = summary,
                    period = period,
                    onPeriod = setPeriod,
                    onTrip = { setDetail(it) },
                    scoreOf = score::scoreOf,
                    shared = shareResult,
                    onDriver = { setPopup(Popup.DRIVER) },
                    onFixSpeed = onFixSpeed,
                    onBack = onBack,
                    onBadges = { setPopup(Popup.BADGES) },
                    onAutoShareOff = { score.setAutoShare(false) },
                    onShare = {
                        if (state.autoShare) {
                            ui.launch { setShareResult(score.shareWithInsurer(period)) }
                        } else {
                            setSharing(true)
                        }
                    },
                )
            }
        }
        // 창은 정차 중에만 띄운다
        if (!driving) {
            detail?.let { trip ->
                TripDetailDialog(
                    trip = trip,
                    score = score.scoreOf(trip),
                    drivers = state.drivers,
                    onMove = { to -> score.moveTrip(trip, to); setDetail(null) },
                    onDelete = { score.deleteTrip(trip); setDetail(null) },
                    onClose = { setDetail(null) },
                )
            }
            when (popup) {
                Popup.DRIVER -> DriverDialog(
                    state = state,
                    onPick = { d ->
                        setPopup(Popup.NONE)
                        when {
                            d == state.driver -> Unit
                            onLoginAs != null -> onLoginAs(d)
                            else -> score.selectDriver(d)
                        }
                    },
                    onLoginOther = onLoginAs?.let { f -> { setPopup(Popup.NONE); f(null) } },
                    onAdd = { name -> score.addDriver(name).also { if (it) setPopup(Popup.NONE) } },
                    onRename = score::renameDriver,
                    onRemove = score::removeDriver,
                    onClose = { setPopup(Popup.NONE) },
                )
                Popup.BADGES -> BadgeDialog(summary) { setPopup(Popup.NONE) }
                Popup.NONE -> Unit
            }
            // 방금 오른 배지: 다른 창이 없을 때 주차 중에 한 번 보여준다
            if (detail == null && popup == Popup.NONE && !sharing && state.newBadges.isNotEmpty()) {
                BadgeUpDialog(state.newBadges, onDone = score::ackBadges)
            }
            if (sharing) {
                ShareDialog(
                    summary = summary,
                    driver = state.driver,
                    onCancel = { setSharing(false) },
                    onSend = { remembered ->
                        setSharing(false)
                        if (remembered) score.setAutoShare(true)
                        ui.launch { setShareResult(score.shareWithInsurer(period)) }
                    },
                )
            }
        }
    }
}

/** 창도 대시보드처럼 크게. Dialog는 새 창이라 키워 둔 글씨 배율이 빠져서 다시 넣어 준다 */
@Composable
private fun ScaledDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/** 정차 중에 띄우는 창 */
private enum class Popup { NONE, DRIVER, BADGES }

// ───────────────────────── 공통 조각 ─────────────────────────

@Composable
private fun T(
    text: String,
    size: TextUnit = 22.sp,
    color: Color = K.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text, modifier, color = color, fontSize = size, fontWeight = weight, textAlign = align,
    lineHeight = lineHeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    color: Color = K.Panel,
    padding: PaddingValues = PaddingValues(26.dp),
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier.clip(RoundedCornerShape(30.dp)).background(color).padding(padding), content = content)

@Composable
private fun Pill(
    text: String,
    bg: Color = K.Panel,
    fg: Color = K.Text,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) = Row(
    Modifier
        .clip(RoundedCornerShape(30.dp))
        .background(bg)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 56.dp)
        .padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    dot?.let {
        Box(Modifier.size(12.dp).clip(CircleShape).background(it))
        Spacer(Modifier.width(10.dp))
    }
    T(text, 20.sp, fg, FontWeight.Bold)
}

@Composable
private fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = K.Accent,
    fg: Color = K.OnAccent,
    height: Dp = 76.dp,
    onClick: () -> Unit,
) = Box(
    modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp)).background(bg).clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) { T(text, 25.sp, fg, FontWeight.ExtraBold) }

/** 0 → target 으로 숫자가 차오르는 애니메이션 */
@Composable
private fun countUp(target: Int, durationMs: Int = 900): Int {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(durationMs, easing = FastOutSlowInEasing)) }
    return anim.value.toInt()
}

// ───────────────────────── 대시보드 (정차 중) ─────────────────────────

/** internal: 화면 테스트(DashboardUiTest)에서 부른다 */
@Composable
internal fun Dashboard(
    state: DrivingScoreState,
    summary: ScoreSummary,
    period: Period,
    onPeriod: (Period) -> Unit,
    onTrip: (TripRecord) -> Unit,
    scoreOf: (TripRecord) -> Int,
    shared: ShareResult?,
    onShare: () -> Unit,
    onDriver: () -> Unit,
    onFixSpeed: () -> Unit,
    onBadges: () -> Unit,
    onAutoShareOff: () -> Unit,
    /** 메인 페이지로 돌아가기. null 이면 버튼을 그리지 않는다 */
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp)) {
        // 윗줄: 제목 · 기간 · 연결 상태
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            onBack?.let { back ->
                // 운행일지 화면과 같은 모양의 뒤로 가기
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(K.Panel2)
                        .clickable(role = Role.Button, onClick = back)
                        .semantics { contentDescription = "뒤로 가기" },
                    contentAlignment = Alignment.Center,
                ) { T("‹", 30.sp, weight = FontWeight.Bold, modifier = NoRead) }
            }
            T("운전 점수", 30.sp, weight = FontWeight.ExtraBold)
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(K.Panel).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Period.entries.forEach { p ->
                    val on = p == period
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (on) K.White else Color.Transparent)
                            .semantics { selected = on }
                            .clickable(role = Role.Tab) { onPeriod(p) }
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(p.label, 21.sp, if (on) K.Ink else K.Sub, FontWeight.ExtraBold) }
                }
            }
            Spacer(Modifier.weight(1f))
            // 누구 점수인지 늘 보이게. 눌러서 운전자 바꾸기
            Pill("운전자 · ${state.driver}  ▾", K.Panel2, K.Text, onClick = onDriver)
            Pill(
                ScoreReport.nextBadge(summary.badges)?.let { "★ 배지까지 ${it.remainingText}" } ?: "★ 배지 완성",
                K.Panel2, K.AccentSoft, onClick = onBadges,
            )
            // 연결 상태는 문제가 있을 때만 보여준다 (위쪽 줄 자리가 좁음). 권한이 없으면 바로 고칠 수 있게
            if (!state.speedAvailable) {
                Pill("차속 신호 없음 · 눌러서 허용", K.WarnBg, K.Warn, dot = K.Warn, onClick = onFixSpeed)
            }
        }
        Spacer(Modifier.height(18.dp))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeroCard(summary, shared, state.autoShare, onShare, onAutoShareOff, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiveTodayCard(state.today, Modifier.weight(1f).fillMaxHeight())
                    TipCard(if (summary.hasData) summary.tip else null, Modifier.weight(1.15f).fillMaxHeight())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    summary.items.forEach { ItemTile(it, summary.hasData, Modifier.weight(1f)) }
                }
                RecentTrips(summary.trips.take(3), scoreOf, onTrip, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroCard(
    summary: ScoreSummary,
    shared: ShareResult?,
    autoShare: Boolean,
    onShare: () -> Unit,
    onAutoShareOff: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier, color = K.Blue, padding = PaddingValues(28.dp)) {
        T("${summary.period.label} 운전 점수", 21.sp, K.SubOnBlue, FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (!summary.hasData) {
            EmptyHero(summary.period)
            return@Card
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ScoreGauge(summary.score, 250.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            T(summary.word, 30.sp, if (summary.score >= 80) K.Good else K.Warn, FontWeight.ExtraBold)
            summary.delta?.let { d ->
                Spacer(Modifier.width(12.dp))
                val up = d >= 0
                Pill(
                    when { d > 0 -> "▲ ${d}점"; d < 0 -> "▼ ${-d}점"; else -> "지난번과 같아요" },
                    if (up) K.GoodBg else K.WarnBg,
                    if (up) K.Good else K.Warn,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        ScaleBar(summary.score)
        Spacer(Modifier.height(20.dp))
        TrendBars(summary.trend, Modifier.weight(1f))
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = shared, label = "share") { s ->
            if (s == null) {
                BigButton("보험사에 점수 보내기", onClick = onShare)
            } else {
                Row(
                    Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(22.dp)).background(K.GoodBg)
                        .clickable(onClick = onShare),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { T("✓  ${s.sentScore}점 보냈어요 · 다시 보내기", 23.sp, K.Good, FontWeight.ExtraBold) }
            }
        }
        // "묻지 않고 바로 보내기"를 켰다면 여기서 언제든 끌 수 있다
        if (autoShare) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("확인 없이 바로 보내는 중", 18.sp, K.SubOnBlue, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(K.BlueDeep).clickable(onClick = onAutoShareOff)
                        .heightIn(min = 48.dp).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { T("끄기", 19.sp, K.White, FontWeight.ExtraBold) }
            }
        }
    }
}

/** 기간 안 점수 흐름 — 마지막(지금) 막대만 강조 */
@Composable
private fun TrendBars(trend: List<Pair<String, Int>>, modifier: Modifier) {
    if (trend.size < 2) {
        // 빈칸 대신 언제 보이는지 알려준다
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            T("기록이 쌓이면\n점수 흐름이 여기에 보여요", 19.sp, K.SubOnBlue, align = TextAlign.Center, lineHeight = 28.sp)
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        T("점수 흐름", 18.sp, K.SubOnBlue, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            trend.forEachIndexed { i, (label, v) ->
                val last = i == trend.lastIndex
                val h by animateFloatAsState(((v - 50) / 50f).coerceIn(0.08f, 1f), tween(900, delayMillis = i * 80), label = "trend$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    T("$v", 17.sp, if (last) K.White else K.SubOnBlue, FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.weight(1f, fill = false).fillMaxHeight(h).widthIn(max = 44.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)).background(if (last) K.Accent else K.BlueDeep),
                    )
                    Spacer(Modifier.height(6.dp))
                    T(label, 16.sp, K.SubOnBlue)
                }
            }
        }
    }
}

/** 점수만큼 차오르는 원형 게이지 + 가운데 큰 숫자 */
@Composable
private fun ScoreGauge(score: Int, size: Dp) {
    val sweep by animateFloatAsState(score / 100f, tween(1100, easing = FastOutSlowInEasing), label = "gauge")
    val shown = countUp(score, 1100)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(K.BlueDeep, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(K.Accent, 135f, 270f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T("$shown", 96.sp, weight = FontWeight.ExtraBold, lineHeight = 96.sp)
            T("점 / 100", 20.sp, K.SubOnBlue, FontWeight.Bold)
        }
    }
}

/** 주의·보통·좋아요·아주 좋아요 기준 막대 (내 점수가 어디쯤인지) */
@Composable
private fun ScaleBar(score: Int) {
    val pos = when {
        score < 60 -> score / 60f * 0.25f
        score < 80 -> 0.25f + (score - 60) / 20f * 0.25f
        score < 90 -> 0.5f + (score - 80) / 10f * 0.25f
        else -> 0.75f + (score - 90) / 10f * 0.25f
    }
    val animPos by animateFloatAsState(pos, tween(1100, easing = FastOutSlowInEasing), label = "scale")
    val level = when { score < 60 -> 0; score < 80 -> 1; score < 90 -> 2; else -> 3 }
    Column {
        Box(Modifier.fillMaxWidth().height(26.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val h = 12.dp.toPx()
                val y = this.size.height - h
                val w = this.size.width
                val gap = 4.dp.toPx()
                val seg = (w - gap * 3) / 4
                listOf(Color(0xFF5A4520), Color(0xFF3A4658), Color(0xFF0B5C85), Color(0xFF1C7A4E)).forEachIndexed { i, c ->
                    drawRoundRect(c, Offset(i * (seg + gap), y), Size(seg, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                }
                val x = w * animPos
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x - 9.dp.toPx(), 0f); lineTo(x + 9.dp.toPx(), 0f); lineTo(x, y - 2.dp.toPx()); close()
                }
                drawPath(path, Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("주의", "보통", "좋아요", "아주 좋아요").forEachIndexed { i, label ->
                T(
                    label, 18.sp, if (i == level) K.White else K.SubOnBlue,
                    if (i == level) FontWeight.ExtraBold else FontWeight.Normal,
                    Modifier.weight(1f), TextAlign.Center,
                )
            }
        }
    }
}

/** 기록이 없을 때의 점수 카드 */
@Composable
private fun ColumnScope.EmptyHero(period: Period) {
    Spacer(Modifier.weight(1f))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                drawArc(
                    K.BlueDeep, 135f, 270f, false, Offset(inset, inset),
                    Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            T("—", 80.sp, K.SubOnBlue, FontWeight.ExtraBold)
        }
    }
    Spacer(Modifier.height(18.dp))
    T("${period.label}엔 아직 기록이 없어요", 28.sp, weight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    T("주행을 마치면 여기에 점수가 생겨요", 21.sp, K.SubOnBlue, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    Spacer(Modifier.weight(1f))
}

/** 오늘 실시간 집계 — 달리는 동안 0.5초마다 바뀐다 */
@Composable
private fun LiveTodayCard(today: TodayStats, modifier: Modifier) {
    val driving = today.liveScore != null
    val pulse by animateFloatAsState(if (driving) 1f else 0.35f, tween(600), label = "pulse")
    Card(modifier, padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(K.Accent.copy(alpha = pulse)))
            Spacer(Modifier.width(10.dp))
            T(if (driving) "실시간 · 지금 주행 중" else "실시간 · 오늘", 19.sp, K.Sub, FontWeight.Bold, Modifier.weight(1f))
            today.score?.let { T("오늘 ${it}점", 19.sp, if (it >= 80) K.Good else K.Warn, FontWeight.ExtraBold) }
        }
        Spacer(Modifier.height(8.dp))
        if (today.tripCount == 0) {
            T("오늘은 아직 안 달렸어요", 26.sp, weight = FontWeight.ExtraBold)
            T("주행하면 바로 여기서 집계돼요", 20.sp, K.Sub)
            return@Card
        }
        Row(verticalAlignment = Alignment.Bottom) {
            T("%.1f".format(today.distanceKm), 40.sp, weight = FontWeight.ExtraBold, lineHeight = 40.sp)
            T("km", 20.sp, K.Sub, FontWeight.Bold, Modifier.padding(start = 4.dp, bottom = 5.dp))
            Spacer(Modifier.width(14.dp))
            T("${today.tripCount}번 · ${if (today.driveMinutes == 0) "1분 미만" else "${today.driveMinutes}분"}", 20.sp, K.Sub, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            today.liveScore?.let {
                T("이번 주행 ${it}점", 20.sp, K.AccentSoft, FontWeight.ExtraBold, Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ScoreItem.HARD_ACCEL to today.hardAccel, ScoreItem.HARD_BRAKE to today.hardBrake, ScoreItem.SPEEDING to today.speeding, ScoreItem.LANE_CHANGE to today.laneChange)
                .forEach { (item, n) ->
                    // 이름이 잘리지 않게 이름 위, 숫자 아래로
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(K.Panel2).padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        // 칸이 4개라 좁다: 이름 대신 짧은 이름 (급가속·급정거·과속·차선)
                        T(item.label.removeSuffix(" 변경"), 14.sp, K.Sub, maxLines = 1)
                        T("${n}회", 22.sp, if (n == 0) K.Text else itemColor(item), FontWeight.ExtraBold, maxLines = 1)
                    }
                }
        }
    }
}

@Composable
private fun TipCard(tip: Tip?, modifier: Modifier) {
    if (tip == null) {
        // 기록이 없을 때: 어떻게 시작하는지 알려준다
        Card(modifier, padding = PaddingValues(horizontal = 26.dp, vertical = 22.dp)) {
            T("시작하는 방법", 19.sp, K.Sub, FontWeight.Bold)
            T("운전만 하면 돼요", 30.sp, K.AccentSoft, FontWeight.ExtraBold)
            T("기어를 D로 바꾸면 기록이 시작되고,\nP로 바꾸면 점수가 나와요.", 22.sp, K.Text, lineHeight = 30.sp)
        }
        return
    }
    val color = tip.item?.let { itemColor(it) } ?: K.Good
    Card(modifier, padding = PaddingValues(horizontal = 26.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { T(tip.item?.let { itemIcon(it) } ?: "✓", 30.sp, color, FontWeight.ExtraBold, NoRead) }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                T(if (tip.item != null) "한 가지만 고쳐 보세요" else "잘하고 있어요", 19.sp, K.Sub, FontWeight.Bold)
                T(tip.title, 26.sp, color, FontWeight.ExtraBold, maxLines = 1)
                T(tip.body, 22.sp, K.Text, lineHeight = 30.sp)
            }
        }
    }
}

@Composable
private fun ItemTile(s: ItemScore, hasData: Boolean, modifier: Modifier) {
    val color = if (!hasData) K.Sub else if (s.isWeak) K.Warn else K.Good
    val fill by animateFloatAsState(if (hasData) s.score / 100f else 0f, tween(900), label = "bar")
    Card(
        modifier.then(if (hasData && s.isWeak) Modifier.border(BorderStroke(2.dp, K.Warn.copy(alpha = 0.5f)), RoundedCornerShape(30.dp)) else Modifier),
        padding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(itemIcon(s.item), 22.sp, itemColor(s.item), FontWeight.ExtraBold, NoRead)
            Spacer(Modifier.width(8.dp))
            T(s.item.label, 18.sp, weight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        // 항목이 5칸이라 좁아서 글자를 한 단계 줄인다
        T(if (hasData) s.word else "없음", 21.sp, color, FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(K.Panel2)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
        Spacer(Modifier.height(8.dp))
        T(if (hasData) "${s.amountText} · ${s.score}점" else "아직 0회", 16.sp, K.Sub, maxLines = 1)
    }
}

@Composable
private fun RecentTrips(trips: List<TripRecord>, scoreOf: (TripRecord) -> Int, onTrip: (TripRecord) -> Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("최근 주행", 21.sp, K.Sub, FontWeight.Bold, Modifier.weight(1f))
            T("눌러서 감점 이유 보기", 18.sp, K.Sub)
        }
        Spacer(Modifier.height(12.dp))
        if (trips.isEmpty()) {
            T("아직 주행 기록이 없어요. 기어를 D로 바꾸면 자동으로 기록돼요.", 21.sp, K.Sub)
            return@Card
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 1~2건이어도 카드 하나가 화면을 다 차지하지 않게 늘 3칸으로 나눈다
            repeat(3 - trips.size) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .border(BorderStroke(2.dp, K.Line), RoundedCornerShape(22.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { T("다음 주행이\n여기에 보여요", 18.sp, K.Sub, align = TextAlign.Center) }
            }
            trips.forEach { trip ->
                val s = scoreOf(trip)
                val worst = trip.events.groupBy { it.item }.maxByOrNull { it.value.size }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(K.Panel2)
                        .clickable { onTrip(trip) }.padding(16.dp),
                ) {
                    T(dateText(trip.startMillis), 17.sp, K.Sub, maxLines = 1)
                    T(trip.title, 20.sp, weight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("$s", 40.sp, if (s >= 80) K.Text else K.Warn, FontWeight.ExtraBold, lineHeight = 40.sp)
                        T("점", 19.sp, K.Sub, FontWeight.Bold, Modifier.padding(start = 4.dp, bottom = 5.dp))
                        Spacer(Modifier.weight(1f))
                        T(
                            worst?.let { "${it.key.label} ${it.value.size}" } ?: "감점 없음",
                            17.sp, worst?.let { itemColor(it.key) } ?: K.Good, FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun dateText(millis: Long) = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA).format(millis)

// ───────────────────────── 주행 중 (조작 잠금) ─────────────────────────

@Composable
private fun DrivingView(state: DrivingScoreState, onPickDriver: ((String) -> Unit)?) {
    val live = state.live ?: return
    val today = state.today
    var toast by remember { mutableStateOf<DrivingEvent?>(null) }
    LaunchedEffect(live.lastEvent) {
        toast = live.lastEvent
        if (live.lastEvent != null) {
            delay(4_000)
            toast = null
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // 누구 기록인지 늘 보이게
            Pill("${state.driver} 주행 중", K.Blue, K.White, dot = K.Accent)
            if (onPickDriver != null && state.drivers.size > 1 && live.speedKmh < 1f) {
                // 출발 전(멈춰 있을 때)만 운전자를 바로 바꿀 수 있다. 달리기 시작하면 사라진다
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    T("운전자가 맞나요?", 21.sp, K.Sub, maxLines = 1)
                    state.drivers.filter { it != state.driver }.forEach { d ->
                        Pill("$d 운전", K.Panel2, K.AccentSoft, onClick = { onPickDriver(d) })
                    }
                }
            } else {
                T("운전에 집중하세요. 멈추면 자세히 보여드려요", 22.sp, K.Sub, modifier = Modifier.weight(1f), maxLines = 1)
            }
            // 실시간 오늘 누적 (지금 주행 포함)
            Row(
                Modifier.clip(RoundedCornerShape(30.dp)).background(K.Panel).heightIn(min = 56.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("오늘 누적", 20.sp, K.Sub, FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                T("${"%.1f".format(today.distanceKm)}km · ${today.tripCount}번", 22.sp, weight = FontWeight.ExtraBold)
                today.score?.let {
                    Spacer(Modifier.width(12.dp))
                    T("${it}점", 22.sp, if (it >= 80) K.Good else K.Warn, FontWeight.ExtraBold)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Card(Modifier.weight(1.2f).fillMaxHeight(), color = K.Blue, padding = PaddingValues(36.dp)) {
                T("이번 주행 점수", 26.sp, K.SubOnBlue, FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                AnimatedContent(targetState = live.score, label = "liveScore") { s ->
                    T("$s", 180.sp, weight = FontWeight.ExtraBold, lineHeight = 180.sp)
                }
                T(DrivingRules.wordOf(live.score), 36.sp, if (live.score >= 80) K.Good else K.Warn, FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                T("이번 주행 ${"%.1f".format(live.distanceKm)}km", 22.sp, K.SubOnBlue, FontWeight.Bold)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Card(Modifier.fillMaxWidth().weight(1f), padding = PaddingValues(30.dp)) {
                    T("지금 속도", 24.sp, K.Sub, FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("${live.speedKmh.toInt()}", 96.sp, weight = FontWeight.ExtraBold, lineHeight = 96.sp)
                        T("km/h", 28.sp, K.Sub, FontWeight.Bold, Modifier.padding(start = 8.dp, bottom = 12.dp))
                        Spacer(Modifier.weight(1f))
                        val lim = live.speedLimitKmh
                        if (lim != null) {
                            Box(
                                Modifier.size(84.dp).clip(CircleShape).background(K.White).border(8.dp, Color(0xFFE5483B), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { T("$lim", 32.sp, K.Ink, FontWeight.ExtraBold) }
                        } else {
                            // 제한속도를 모르면 과속은 시속 ${DrivingRules.DEFAULT_SPEED_LIMIT + DrivingRules.OVERSPEED_MARGIN_KMH}km 넘을 때만 잡는다
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                                T("제한속도 정보 없음", 19.sp, K.Sub, FontWeight.Bold)
                                T("내비를 켜면 더 정확해요", 17.sp, K.Sub)
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().weight(1f), padding = PaddingValues(30.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("감점", 24.sp, K.Sub, FontWeight.Bold, Modifier.weight(1f))
                        T("이번 주행 · 오늘", 18.sp, K.Sub)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(
                            Triple(ScoreItem.HARD_ACCEL, live.events.count { it.item == ScoreItem.HARD_ACCEL }, today.hardAccel),
                            Triple(ScoreItem.HARD_BRAKE, live.events.count { it.item == ScoreItem.HARD_BRAKE }, today.hardBrake),
                            Triple(ScoreItem.SPEEDING, live.events.count { it.item == ScoreItem.SPEEDING }, today.speeding),
                            Triple(ScoreItem.LANE_CHANGE, live.events.count { it.item == ScoreItem.LANE_CHANGE }, today.laneChange),
                        ).forEach { (item, n, todayN) ->
                            Column(Modifier.weight(1f)) {
                                T(item.label, 22.sp, K.Sub)
                                T("${n}회", 44.sp, if (n == 0) K.Text else itemColor(item), FontWeight.ExtraBold)
                                T("오늘 ${todayN}회", 19.sp, K.Sub)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                toast?.let { e ->
                    Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(K.WarnBg)
                            .border(BorderStroke(2.dp, itemColor(e.item).copy(alpha = 0.6f)), RoundedCornerShape(28.dp))
                            .padding(horizontal = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(itemIcon(e.item), 38.sp, itemColor(e.item), FontWeight.ExtraBold, NoRead)
                        Spacer(Modifier.width(18.dp))
                        // 운전 중엔 한눈에: 항목 이름만 크게. 자세한 숫자는 멈춘 뒤 주행 상세에서
                        T(e.item.label, 34.sp, itemColor(e.item), FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ───────────────────────── 주행 한 건 자세히 ─────────────────────────

@Composable
private fun TripDetailDialog(
    trip: TripRecord,
    score: Int,
    drivers: List<String>,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    // 고치기: 없음 → 옮길 사람 고르기 / 정말 지울지 한 번 더 묻기
    var fixing by remember { mutableStateOf(Fix.NONE) }
    ScaledDialog(onClose) {
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(0.75f).clip(RoundedCornerShape(32.dp)).background(K.Panel2).padding(34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(dateText(trip.startMillis), 20.sp, K.Sub)
                    T(trip.title, 30.sp, weight = FontWeight.ExtraBold)
                    T("${"%.1f".format(trip.distanceKm)}km · ${if (trip.drivingSec < 60) "${trip.drivingSec}초" else "${trip.drivingSec / 60}분"} 운전", 20.sp, K.Sub)
                }
                Column(horizontalAlignment = Alignment.End) {
                    T("${score}점", 52.sp, if (score >= 80) K.Text else K.Warn, FontWeight.ExtraBold)
                    T(DrivingRules.wordOf(score), 22.sp, if (score >= 80) K.Good else K.Warn, FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(22.dp))
            T("감점 이유", 21.sp, K.Sub, FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (trip.events.isEmpty() && trip.nightSec == 0) {
                    T("감점 없이 깔끔하게 운전했어요 👍", 24.sp, K.Good, FontWeight.Bold)
                }
                trip.events.sortedBy { it.atMillis }.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Panel).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(SimpleDateFormat("a h:mm", Locale.KOREA).format(e.atMillis), 19.sp, K.Sub, modifier = Modifier.width(110.dp))
                        T(itemIcon(e.item), 24.sp, itemColor(e.item), FontWeight.ExtraBold, NoRead)
                        Spacer(Modifier.width(10.dp))
                        T(e.item.label, 22.sp, itemColor(e.item), FontWeight.ExtraBold, Modifier.width(96.dp))
                        T(e.detail, 21.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
                if (trip.nightSec > 0) {
                    T("☾ 밤 운전 ${trip.nightSec / 60}분 — 밤에는 조금 더 천천히", 21.sp, itemColor(ScoreItem.NIGHT))
                }
            }
            Spacer(Modifier.height(24.dp))
            when (fixing) {
                Fix.NONE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (drivers.size > 1) {
                        BigButton("운전자 바꾸기", Modifier.weight(1f), K.Panel, K.AccentSoft) { fixing = Fix.MOVE }
                    }
                    BigButton("이 주행 지우기", Modifier.weight(1f), K.Panel, Color(0xFFFF8A7A)) { fixing = Fix.DELETE }
                    Box(Modifier.weight(1f)) { BigButton("닫기", onClick = onClose) }
                }
                Fix.MOVE -> Column {
                    T("누가 운전했나요?", 22.sp, K.Sub, FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        drivers.filter { it != trip.driver }.forEach { d ->
                            Box(Modifier.weight(1f)) { BigButton(d) { onMove(d) } }
                        }
                        BigButton("취소", Modifier.weight(1f), K.Panel, K.Text) { fixing = Fix.NONE }
                    }
                }
                Fix.DELETE -> Column {
                    T("이 주행을 지우면 되돌릴 수 없어요. 점수에서도 빠져요.", 22.sp, K.Warn, FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("취소", Modifier.weight(1f), K.Panel, K.Text) { fixing = Fix.NONE }
                        Box(Modifier.weight(1f)) { BigButton("지우기", bg = Color(0xFFE5483B), fg = K.White, onClick = onDelete) }
                    }
                }
            }
        }
    }
}

private enum class Fix { NONE, MOVE, DELETE }

// ───────────────────────── 보험사 공유 확인 ─────────────────────────

@Composable
private fun ShareDialog(summary: ScoreSummary, driver: String, onCancel: () -> Unit, onSend: (rememberChoice: Boolean) -> Unit) {
    var sending by remember { mutableStateOf(false) }
    var rememberChoice by remember { mutableStateOf(false) }
    ScaledDialog(onCancel) {
        Column(Modifier.width(660.dp).clip(RoundedCornerShape(32.dp)).background(K.Panel2).padding(38.dp)) {
            T("보험사에 이것만 보내요", 34.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.height(20.dp))
            Line("✓", K.Good, "${driver}님의 ${summary.period.label} 운전 점수 ${summary.score}점")
            Line("✓", K.Good, "항목별 점수 (급가속 · 급정거 · 과속 · 차선 변경 · 밤 운전)")
            Line("✕", Color(0xFFFF8A7A), "위치와 이동 경로는 보내지 않아요", K.Sub)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { rememberChoice = !rememberChoice }.heightIn(min = 56.dp).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (rememberChoice) K.Accent else Color.Transparent)
                        .border(2.dp, if (rememberChoice) K.Accent else K.Sub, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (rememberChoice) T("✓", 20.sp, K.OnAccent, FontWeight.ExtraBold) }
                Spacer(Modifier.width(12.dp))
                T("다음부터는 묻지 않고 바로 보내기", 21.sp, K.Text)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("취소", Modifier.weight(1f), K.Panel, K.Text, onClick = onCancel)
                Box(Modifier.weight(1f)) {
                    if (sending) {
                        Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = K.Accent)
                        }
                    } else {
                        BigButton("보내기") { sending = true; onSend(rememberChoice) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(mark: String, markColor: Color, text: String, textColor: Color = K.Text) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(mark, 26.sp, markColor, FontWeight.ExtraBold, Modifier.width(40.dp))
        T(text, 24.sp, textColor, FontWeight.Bold)
    }
}

// ───────────────────────── 운전자 고르기 ─────────────────────────

@Composable
private fun DriverDialog(
    state: DrivingScoreState,
    onPick: (String) -> Unit,
    /** 있으면 "+ 운전자 추가" 대신 "다른 사람으로 로그인" */
    onLoginOther: (() -> Unit)?,
    onAdd: (String) -> Boolean,
    onRename: (String, String) -> Boolean,
    onRemove: (String) -> Boolean,
    onClose: () -> Unit,
) {
    /** null: 고르기 화면 / "": 새 운전자 추가 / 이름: 그 사람 고치기 */
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    ScaledDialog(onClose) {
        Column(Modifier.width(660.dp).clip(RoundedCornerShape(32.dp)).background(K.Panel2).padding(36.dp)) {
            val target = editing
            if (target == null) {
                T("누가 운전하나요?", 34.sp, weight = FontWeight.ExtraBold)
                T(if (onLoginOther != null) "이름을 누르면 그 사람의 카카오 로그인으로 넘어가요" else "고른 사람의 점수로 따로 모아요", 21.sp, K.Sub)
                Spacer(Modifier.height(20.dp))
                // 로그인한 사람이 늘어나도 창이 넘치지 않게 목록만 스크롤
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                state.drivers.forEach { d ->
                    val on = d == state.driver
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                .background(if (on) K.Accent else K.Panel).clickable(role = Role.Button) { onPick(d) }
                                .heightIn(min = 72.dp).padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(d, 26.sp, if (on) K.OnAccent else K.Text, FontWeight.ExtraBold, Modifier.weight(1f))
                            if (on) T("지금 운전자", 19.sp, K.OnAccent, FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(K.Panel)
                                .clickable(role = Role.Button) { editing = d; name = d; error = null; confirmRemove = false }
                                .heightIn(min = 72.dp).padding(horizontal = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) { T("고치기", 21.sp, K.Sub, FontWeight.Bold) }
                    }
                }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onLoginOther != null) {
                        BigButton("+ 다른 사람으로 로그인", Modifier.weight(1f), K.Panel, K.AccentSoft, onClick = onLoginOther)
                    } else if (state.drivers.size < DrivingScore.MAX_DRIVERS) {
                        BigButton("+ 운전자 추가", Modifier.weight(1f), K.Panel, K.AccentSoft) { editing = ""; name = ""; error = null }
                    }
                    Box(Modifier.weight(1f)) { BigButton("닫기", onClick = onClose) }
                }
            } else {
                val adding = target.isEmpty()
                T(if (adding) "운전자 추가" else "$target 고치기", 34.sp, weight = FontWeight.ExtraBold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(8); error = null },
                    singleLine = true,
                    placeholder = { T("이름 (예: 엄마)", 22.sp, K.Sub) },
                    textStyle = TextStyle(fontSize = 24.sp, color = K.Text),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Accent, unfocusedBorderColor = K.Line, cursorColor = K.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { T(it, 19.sp, K.Warn, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("취소", Modifier.weight(1f), K.Panel, K.Text) { editing = null }
                    Box(Modifier.weight(1f)) {
                        BigButton(if (adding) "추가" else "이름 바꾸기") {
                            val ok = if (adding) onAdd(name) else onRename(target, name)
                            if (ok) editing = null else error = "이름을 넣어 주세요 (같은 이름은 안 돼요)"
                        }
                    }
                }
                // 지우기는 한 명보다 많을 때만, 한 번 더 확인
                if (!adding && state.drivers.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    if (!confirmRemove) {
                        BigButton("$target 지우기", bg = K.Panel, fg = Color(0xFFFF8A7A)) { confirmRemove = true }
                    } else {
                        T("$target 님의 주행 기록도 모두 지워져요.", 21.sp, K.Warn, FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        BigButton("정말 지우기", bg = Color(0xFFE5483B), fg = K.White) {
                            if (onRemove(target)) editing = null
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 배지 ─────────────────────────

/** 단계 색: 1단계 샌드, 2단계 액티브 블루, 3단계 골드 */
private fun tierColor(tier: Int) = when (tier) {
    1 -> Color(0xFFE4DCD3)
    2 -> Color(0xFF00AAD2)
    else -> Color(0xFFFFC56B)
}

/** 배지 그림 (design/badges 의 SVG를 scripts/svg2vd.py 로 바꾼 벡터). locked = 회색 잠김 모양 */
private fun badgeRes(kind: BadgeKind, tier: Int, locked: Boolean): Int {
    val t = tier.coerceIn(1, kind.need.size)
    val earned = when (kind) {
        BadgeKind.CLEAN_STREAK -> listOf(R.drawable.badge_clean_streak_1, R.drawable.badge_clean_streak_2, R.drawable.badge_clean_streak_3)
        BadgeKind.ROAD_RUNNER -> listOf(R.drawable.badge_road_runner_1, R.drawable.badge_road_runner_2, R.drawable.badge_road_runner_3)
        BadgeKind.COOL_CRUISER -> listOf(R.drawable.badge_cool_cruiser_1, R.drawable.badge_cool_cruiser_2, R.drawable.badge_cool_cruiser_3)
        BadgeKind.NIGHT_OWL -> listOf(R.drawable.badge_night_owl_1, R.drawable.badge_night_owl_2, R.drawable.badge_night_owl_3)
        BadgeKind.BUTTER_DRIVER -> listOf(R.drawable.badge_butter_driver_1, R.drawable.badge_butter_driver_2, R.drawable.badge_butter_driver_3)
        BadgeKind.FIRST_IGNITION -> listOf(R.drawable.badge_first_ignition_1)
    }
    val gray = when (kind) {
        BadgeKind.CLEAN_STREAK -> listOf(R.drawable.badge_clean_streak_1_locked, R.drawable.badge_clean_streak_2_locked, R.drawable.badge_clean_streak_3_locked)
        BadgeKind.ROAD_RUNNER -> listOf(R.drawable.badge_road_runner_1_locked, R.drawable.badge_road_runner_2_locked, R.drawable.badge_road_runner_3_locked)
        BadgeKind.COOL_CRUISER -> listOf(R.drawable.badge_cool_cruiser_1_locked, R.drawable.badge_cool_cruiser_2_locked, R.drawable.badge_cool_cruiser_3_locked)
        BadgeKind.NIGHT_OWL -> listOf(R.drawable.badge_night_owl_1_locked, R.drawable.badge_night_owl_2_locked, R.drawable.badge_night_owl_3_locked)
        BadgeKind.BUTTER_DRIVER -> listOf(R.drawable.badge_butter_driver_1_locked, R.drawable.badge_butter_driver_2_locked, R.drawable.badge_butter_driver_3_locked)
        BadgeKind.FIRST_IGNITION -> listOf(R.drawable.badge_first_ignition_1_locked)
    }
    return (if (locked) gray else earned)[t - 1]
}

/**
 * 메달 하나 + 다음 단계까지의 진행 링.
 * 받은 단계가 있으면 그 단계 모양, 없으면 1단계 잠김 모양.
 * 링은 배지 바깥 여백에 따로 그린다 (3단계 장식과 겹치지 않게). 0%거나 최고 단계면 숨긴다
 */
@Composable
private fun BadgeMedal(badge: Badge, size: Dp) {
    val ring by animateFloatAsState(if (badge.isMax) 0f else badge.progress, tween(900), label = "badgeRing")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring > 0f) {
            val color = tierColor(badge.tier + 1)
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arc = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(K.Line, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(color, -90f, 360f * ring, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Image(
            painterResource(badgeRes(badge.kind, badge.tier, locked = badge.tier == 0)),
            contentDescription = null,
            modifier = Modifier.size(size - 16.dp),
        )
    }
}

/** 단계 점 (●●○). 색만으로 구분하지 않게 점 개수로도 보여준다 */
@Composable
private fun TierPips(badge: Badge) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = NoRead) {
        repeat(badge.maxTier) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < badge.tier) tierColor(i + 1) else K.Line))
        }
    }
}

@Composable
private fun BadgeDialog(summary: ScoreSummary, onClose: () -> Unit) {
    val earned = summary.badges.sumOf { it.tier }
    val total = summary.badges.sumOf { it.maxTier }
    ScaledDialog(onClose) {
        Column(Modifier.width(1080.dp).clip(RoundedCornerShape(32.dp)).background(K.Panel2).padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("내 배지", 34.sp, weight = FontWeight.ExtraBold)
                Spacer(Modifier.width(14.dp))
                T("$earned / $total 단계", 22.sp, K.AccentSoft, FontWeight.Bold, Modifier.weight(1f))
                T(
                    if (summary.streak > 0) "지금 무감점 ${summary.streak}연속 중" else "감점 없이 마치면 연속 기록이 시작돼요",
                    19.sp, K.Sub,
                )
            }
            Spacer(Modifier.height(18.dp))
            summary.badges.chunked(3).forEach { row ->
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { b ->
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(if (b.tier > 0) K.Panel else K.Bg)
                                .then(if (b.isMax) Modifier.border(BorderStroke(2.dp, tierColor(b.tier).copy(alpha = 0.6f)), RoundedCornerShape(24.dp)) else Modifier)
                                .padding(14.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${b.kind.label} ${if (b.tier > 0) "${b.tier}단계" else "아직 못 받음"}, ${b.remainingText}"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BadgeMedal(b, 124.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                T(b.kind.label, 22.sp, if (b.tier > 0) K.Text else K.Sub, FontWeight.ExtraBold, maxLines = 1)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TierPips(b)
                                    Spacer(Modifier.width(8.dp))
                                    T(if (b.tier > 0) "${b.tier}단계" else "도전 전", 17.sp, if (b.tier > 0) tierColor(b.tier) else K.Sub, FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                T(
                                    if (b.isMax) "최고 단계 달성" else "${b.remainingText} → ${b.tier + 1}단계",
                                    17.sp, if (b.isMax) K.Good else K.AccentSoft, FontWeight.Bold, maxLines = 1,
                                )
                                T(b.kind.how, 15.sp, K.Sub, maxLines = 1)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(14.dp))
            BigButton("닫기", onClick = onClose)
        }
    }
}

/**
 * 배지가 올랐을 때의 축하 화면 (주차 중에만, 소리 없음). 여러 개면 하나씩 넘겨 본다.
 * 연출 1.5초 (design/badges/README.md):
 *   0~300ms 이전 메달이 1.07배로 커짐 → 300~900ms 새 메달이 0.78→1배로 펼쳐지며 겹쳐 바뀜
 *   → 900~1230ms 그대로 → 1230~1500ms 작은 반짝임. 시스템 애니메이션을 꺼 두면 바로 끝 모습
 */
@Composable
private fun BadgeUpDialog(ups: List<BadgeUp>, onDone: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    val up = ups.getOrNull(index) ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val t = remember(index) { Animatable(0f) }
    LaunchedEffect(index) {
        if (reduceMotion) t.snapTo(1500f) else t.animateTo(1500f, tween(1500, easing = androidx.compose.animation.core.LinearEasing))
    }
    val ms = t.value
    val swap = ((ms - 300f) / 600f).coerceIn(0f, 1f)
    val oldScale = if (ms < 300f) 1f + 0.07f * (ms / 300f) else 1.07f
    val newScale = 0.78f + 0.22f * FastOutSlowInEasing.transform(swap)
    val sparkle = if (ms in 1230f..1500f) kotlin.math.sin(Math.PI * (ms - 1230f) / 270f).toFloat() else 0f

    val single = up.kind.need.size == 1
    ScaledDialog(onDone) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(32.dp)).background(K.Blue).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T(if (up.from == 0) "새 배지" else "배지 업그레이드", 21.sp, K.SubOnBlue, FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                // 이전 모습 (못 받았던 배지면 회색 잠김 모양)
                Image(
                    painterResource(badgeRes(up.kind, maxOf(up.from, 1), locked = up.from == 0)),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp).graphicsLayer {
                        scaleX = oldScale; scaleY = oldScale; alpha = 1f - swap
                    },
                )
                Image(
                    painterResource(badgeRes(up.kind, up.to, locked = false)),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp).graphicsLayer {
                        scaleX = newScale; scaleY = newScale; alpha = swap
                    },
                )
                if (sparkle > 0f) {
                    val color = tierColor(up.to)
                    Canvas(Modifier.fillMaxSize()) {
                        // 메달 둘레 네 곳에 작은 네 갈래 별
                        listOf(Offset(0.18f, 0.2f), Offset(0.84f, 0.26f), Offset(0.12f, 0.74f), Offset(0.88f, 0.8f)).forEach { p ->
                            val c = Offset(this.size.width * p.x, this.size.height * p.y)
                            val r = 12.dp.toPx() * sparkle
                            val w = 3.dp.toPx() * sparkle
                            val star = androidx.compose.ui.graphics.Path().apply {
                                moveTo(c.x, c.y - r); lineTo(c.x + w, c.y - w); lineTo(c.x + r, c.y); lineTo(c.x + w, c.y + w)
                                lineTo(c.x, c.y + r); lineTo(c.x - w, c.y + w); lineTo(c.x - r, c.y); lineTo(c.x - w, c.y - w); close()
                            }
                            drawPath(star, color.copy(alpha = sparkle))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            T(
                if (single) "${up.kind.label} 배지를 받았어요" else "${up.kind.label} ${up.to}단계를 받았어요",
                32.sp, K.White, FontWeight.ExtraBold, align = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            T(
                "${up.kind.how} ${"%,d".format(up.kind.need[up.to - 1])}${up.kind.unit} 달성" +
                    if (ups.map { it.driver }.distinct().size > 1 || up.driver != DEFAULT_DRIVER) " · ${up.driver}" else "",
                21.sp, K.SubOnBlue, align = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            BigButton(if (index < ups.lastIndex) "다음 배지 보기 (${index + 1}/${ups.size})" else "확인") {
                if (index < ups.lastIndex) index++ else onDone()
            }
        }
    }
}
