package com.example.hyundai_sdk

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.hyundai_sdk.navigation.NavigationSnapshot
import com.example.hyundai_sdk.navigation.PleosNavigationSession
import com.example.hyundai_sdk.vehicle.PleosTripController
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject
import triplog.Category
import triplog.Role
import triplog.Source
import triplog.Trip
import triplog.TripLogApp
import triplog.TripLogUser
import triplog.RecordingMode
import triplog.ReportDetails

open class TripLogActivity : Activity() {
    protected open val demoDate: LocalDate? get() = null
    private val demoHistory get() = demoDate != null
    private fun summaryDate() = demoDate ?: LocalDate.now(SEOUL)
    private data class Dashboard(
        val account: Pair<String, Role>?,
        val trips: List<Trip> = emptyList(),
        val month: YearMonth = YearMonth.now(SEOUL),
        val plannedMeters: Map<String, Int> = emptyMap(),
        val mode: RecordingMode = RecordingMode.SEGMENT
    ) {
        val active get() = trips.firstOrNull { it.end == null }
        val pending get() = trips.count { it.end != null && !it.confirmed }
    }

    private enum class Tab(val label: String) {
        MAIN("메인"), RECORDS("운행 기록"), DRIVE("현재 운행"), REVIEW("분류")
    }

    private var tab = Tab.MAIN
    private var renderedTab = Tab.MAIN
    private val tabScroll = IntArray(Tab.entries.size)
    private var selectedRecordId: String? = null
    private var reviewId: String? = null
    private var draftPurpose = ""
    private var draftCategory: Category? = null
    private var odometerKm: Double? = null
    private var driveDistance: TextView? = null
    private var driveOdometer: TextView? = null
    private lateinit var shell: LinearLayout
    private lateinit var scroll: ScrollView
    private var historyScroll: ScrollView? = null

    private var hostUser: TripLogUser? = null
    private lateinit var root: LinearLayout
    private var screen = Dashboard(null)
    private var signal = ""
    private var busy = false
    private var visible = false
    @Volatile private var generation = 0
    private var message = ""
    @Volatile private var selectedMonth = YearMonth.now(SEOUL)
    private var filter = "전체"

    private val automotive by lazy { packageManager.hasSystemFeature("android.hardware.type.automotive") }
    // Accessed on the shared worker. Recreation queues old close before new open.
    private var controller: PleosTripController? = null
    private var workerSignal = ""
    private var navigation: PleosNavigationSession? = null
    @Volatile private var navigationSnapshot = NavigationSnapshot()
    private val routeEstimates by lazy { getSharedPreferences("triplog_route_estimates", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostUser = TripLogScreen.readUser(this, intent)?.let { user ->
            if (demoHistory) user.copy(id = TripLogHost.DEMO_DRIVER) else user
        }
        if (hostUser == null) {
            val home = packageManager.getLaunchIntentForPackage(packageName)
                ?.takeUnless { it.component?.className == javaClass.name }
            setContentView(column().apply {
                setBackgroundColor(BACKGROUND)
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(text("운행일지", 36f, TEXT, true))
                addView(text("메인 앱에서 사용자 정보를 전달해 주세요.", 16f, MUTED))
                addView(button(if (home == null) "닫기" else "앱 홈으로", primary = true) {
                    if (home != null) startActivity(home)
                    finish()
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            })
            return
        }
        demoDate?.let { selectedMonth = YearMonth.from(it) }
        selectedMonth = savedInstanceState?.getString("selected_month")?.let(YearMonth::parse) ?: selectedMonth
        filter = savedInstanceState?.getString("filter") ?: filter
        savedInstanceState?.let { saved ->
            tab = saved.getString("tab")?.let(Tab::valueOf) ?: Tab.MAIN
            renderedTab = tab
            saved.getIntArray("tab_scroll")?.copyInto(tabScroll)
            selectedRecordId = saved.getString("selected_record")
            reviewId = saved.getString("review_id")
            draftPurpose = saved.getString("draft_purpose").orEmpty()
            draftCategory = saved.getString("draft_category")?.let(Category::valueOf)
        }
        root = column()
        scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(root)
        }
        shell = column().apply {
            setBackgroundColor(BACKGROUND)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsetsCompat()
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(shell)
        render()
    }

    override fun onStart() {
        super.onStart()
        if (hostUser == null) return
        visible = true
        navigation?.setForeground(true)
        val token = generation
        if (busy) {
            WORKER.execute {
                val app = openApp()
                app.useUser(requireNotNull(hostUser))
                refreshCollector(app, currentAccount(app), token)
            }
        } else runTask { app ->
            refreshCollector(app, currentAccount(app), token)
        }
    }

    override fun onStop() {
        visible = false
        generation++
        signal = ""
        odometerKm = null
        navigation?.setForeground(false)
        WORKER.execute { closeCollector() }
        super.onStop()
    }

    override fun onDestroy() {
        closeNavigation()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_month", selectedMonth.toString())
        outState.putString("filter", filter)
        outState.putString("tab", tab.name)
        if (::scroll.isInitialized) rememberScroll()
        outState.putIntArray("tab_scroll", tabScroll)
        outState.putString("selected_record", selectedRecordId)
        outState.putString("review_id", reviewId)
        outState.putString("draft_purpose", draftPurpose)
        outState.putString("draft_category", draftCategory?.name)
        super.onSaveInstanceState(outState)
    }

    private fun openApp(): TripLogApp =
        if (demoHistory) TripLogHost.demoApp(applicationContext) else TripLogHost.app(applicationContext)

    private fun currentAccount(app: TripLogApp) =
        runCatching { app.trips.currentUser().let { it.id to it.role } }.getOrNull()

    private fun loadDashboard(app: TripLogApp): Dashboard {
        val manager = app.trips
        val account = currentAccount(app) ?: return Dashboard(null)
        val month = selectedMonth
        val trips = app.list().sortedByDescending { it.start }
        val active = trips.firstOrNull { it.end == null }
        val estimate = navigationSnapshot.estimate
        if (active != null && estimate != null && !estimate.claimed) {
            // Keep the first observed route estimate for this trip. A reroute does not rewrite it.
            estimate.claimed = routeEstimates.contains(active.id) ||
                routeEstimates.edit().putInt(active.id, estimate.meters).commit()
        }
        val plannedMeters = trips.mapNotNull { trip ->
            routeEstimates.getInt(trip.id, 0).takeIf { it > 0 }?.let { trip.id to it }
        }.toMap()
        return Dashboard(account, trips, month, plannedMeters, manager.recordingMode())
    }

    private fun runTask(
        after: () -> Unit = {},
        onFailure: ((Throwable) -> Unit)? = null,
        action: (TripLogApp) -> Unit
    ) {
        if (busy) return
        busy = true
        message = ""
        render()
        WORKER.execute {
            val result = runCatching {
                val app = openApp()
                app.useUser(requireNotNull(hostUser))
                action(app)
                loadDashboard(app)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess {
                    screen = it
                    syncNavigation()
                }.onFailure {
                    if (onFailure == null) message = errorMessage(it) else onFailure(it)
                }
                if (visible) {
                    render()
                    if (result.isSuccess) after()
                }
            }
        }
    }

    private fun syncNavigation() {
        if (screen.account == null) {
            closeNavigation()
            return
        }
        if (demoHistory || !automotive || !visible || navigation != null) return
        try {
            navigation = PleosNavigationSession(applicationContext, ::applyNavigation).also {
                it.start()
                it.setForeground(true)
            }
        } catch (error: Exception) {
            android.util.Log.w("TripLogNavigation", "Cannot start navigation session", error)
            applyNavigation(NavigationSnapshot(unavailable = true))
        }
    }

    private fun applyNavigation(snapshot: NavigationSnapshot) {
        val previous = navigationSnapshot
        navigationSnapshot = snapshot
        if (snapshot.estimate != null && snapshot.estimate !== previous.estimate) {
            // Navigation can start before or during a trip. Also capture it when the trip starts.
            val token = generation
            WORKER.execute {
                val dashboard = runCatching {
                    val app = openApp()
                    app.useUser(requireNotNull(hostUser))
                    loadDashboard(app)
                }.getOrNull()
                runOnUiThread {
                    if (!isDestroyed && visible && generation == token && !busy && dashboard != null) {
                        screen = dashboard
                        if (tab == Tab.DRIVE || tab == Tab.MAIN) render()
                    }
                }
            }
        }
        if (visible && !busy && tab == Tab.DRIVE) render()
    }

    private fun closeNavigation() {
        val current = navigation
        navigation = null
        navigationSnapshot = NavigationSnapshot()
        runCatching { current?.close() }.onFailure {
            android.util.Log.w("TripLogNavigation", "Cannot close navigation session", it)
        }
    }

    private fun refreshCollector(app: TripLogApp, account: Pair<String, Role>?, token: Int) {
        if (demoHistory || !automotive || account == null || controller != null || generation != token) return
        workerSignal = ""
        // 메인 앱이 백그라운드에서 기록 중이면 화면은 그 결과만 받아서 보여준다 (기록기는 하나만 돌려야 함)
        if (TripLogBackground.isRunning) {
            TripLogBackground.listener = { result, reading -> onSignal(app, token, result, reading) }
            return
        }
        controller = PleosTripController(applicationContext, app, Source.EMULATOR, { null }) { result ->
            WORKER.execute {
                if (generation != token) return@execute
                val changed = workerSignal != result.status
                workerSignal = result.status
                val dashboard = if (changed) runCatching { loadDashboard(app) }.getOrNull() else null
                val reading = controller?.latestOdometerKm
                runOnUiThread {
                    if (!visible || generation != token || isDestroyed) return@runOnUiThread
                    signal = result.status
                    odometerKm = reading
                    dashboard?.let { screen = it }
                    if (!busy && changed && (tab == Tab.DRIVE || tab == Tab.MAIN)) render()
                    else if (tab == Tab.DRIVE) updateDriveDistance()
                }
            }
        }.useDistanceGearPolicy().also { it.start() }
    }

    /** 백그라운드 기록기에서 온 결과를 화면에 반영 (자체 기록기 콜백과 같은 처리) */
    private fun onSignal(app: TripLogApp, token: Int, result: triplog.SignalResult, reading: Double?) {
        WORKER.execute {
            if (generation != token) return@execute
            val changed = workerSignal != result.status
            workerSignal = result.status
            val dashboard = if (changed) runCatching { loadDashboard(app) }.getOrNull() else null
            runOnUiThread {
                if (!visible || generation != token || isDestroyed) return@runOnUiThread
                signal = result.status
                odometerKm = reading
                dashboard?.let { screen = it }
                if (!busy && changed && (tab == Tab.DRIVE || tab == Tab.MAIN)) render()
                else if (tab == Tab.DRIVE) updateDriveDistance()
            }
        }
    }

    private fun closeCollector() {
        TripLogBackground.listener = null
        val current = controller ?: return
        runCatching { current.close() }.onSuccess { controller = null }.onFailure {
            android.util.Log.w("TripLogActivity", "Vehicle collector close failed", it)
        }
    }

    private fun switchTo(next: Tab) {
        tab = next
        render()
    }

    private fun rememberScroll() {
        val visibleScroll = historyScroll ?: scroll.takeIf { it.parent != null }
        if (visibleScroll != null) tabScroll[renderedTab.ordinal] = visibleScroll.scrollY
    }

    private fun render() {
        rememberScroll()
        historyScroll = null
        val targetScroll = tabScroll[tab.ordinal]
        val switched = renderedTab != tab
        renderedTab = tab
        if (switched) hideKeyboard()
        shell.removeAllViews()
        root.removeAllViews()
        driveDistance = null
        driveOdometer = null

        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(12))
            addView(button("‹") { finish() }.apply { textSize = 28f; contentDescription = "뒤로" })
            addView(column().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(if (demoHistory) "더미 운행 데이터" else "카로그", 19f, if (demoHistory) WARNING else MUTED))
                addView(text("운행일지", 36f, TEXT, true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(hostUser?.displayName.orEmpty(), 20f, TEXT, true).apply {
                background = fill(CARD, 20)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                maxLines = 1
                maxWidth = dp(if (readableWidthDp >= 600) 200 else 120)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        shell.addView(header)
        shell.addView(row().apply {
            setPadding(dp(20), 0, dp(20), 0)
            Tab.entries.forEach { item ->
                val selected = item == tab
                val label = if (item == Tab.REVIEW && screen.pending > 0) "분류 ${screen.pending}" else item.label
                val tabItem = column().apply {
                    addView(button(label) { switchTo(item) }.apply {
                        isSelected = selected
                        minWidth = 0
                        minimumWidth = 0
                        maxLines = 2
                        textSize = if (readableWidthDp >= 600) 23f else 18f
                        background = ripple(if (selected) SELECTED else BACKGROUND, 10)
                        setTextColor(if (selected) ACCENT else MUTED)
                        setPadding(dp(3), dp(6), dp(3), dp(6))
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(View(this@TripLogActivity).apply {
                        setBackgroundColor(if (selected) ACCENT else Color.TRANSPARENT)
                    }, LinearLayout.LayoutParams(-1, dp(3)))
                }
                addView(tabItem, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            }
        })
        shell.addView(line())
        val fixedHistory = tab == Tab.RECORDS && wideHistory
        val body = if (fixedHistory) column() else root
        shell.addView(if (fixedHistory) body else scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.setPadding(dp(24), dp(24), dp(24), dp(28))
        if (message.isNotEmpty()) body.addView(text(message, 20f, ACCENT).apply {
            setPadding(0, 0, 0, dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        if (busy) body.addView(text("처리 중…", 18f, MUTED))
        body.addView(when (tab) {
            Tab.MAIN -> mainTab()
            Tab.RECORDS -> recordsTab()
            Tab.DRIVE -> driveTab()
            Tab.REVIEW -> classificationTab()
        }, when {
            fixedHistory -> LinearLayout.LayoutParams(-1, 0, 1f)
            tab == Tab.MAIN && wideHistory -> LinearLayout.LayoutParams(-1, -2, 1f)
            else -> LinearLayout.LayoutParams(-1, -2)
        })
        val visibleScroll = historyScroll ?: scroll
        visibleScroll.post {
            if (visibleScroll.parent != null && visibleScroll === (historyScroll ?: scroll)) {
                visibleScroll.scrollTo(0, targetScroll)
            }
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focus ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        }
    }

    private fun completedTrips() = screen.trips.filter { it.end != null }
    private fun tripDay(trip: Trip) = trip.start.atZone(SEOUL).toLocalDate()
    private fun distance(trips: List<Trip>) = trips.sumOf { it.distanceKm ?: 0.0 }

    private val readableWidthDp get() = resources.configuration.let {
        it.screenWidthDp / it.fontScale.coerceAtLeast(1f)
    }
    private val wideHistory get() = readableWidthDp >= 760
    private val showTimeColumn get() = readableWidthDp >= 1000

    private fun mainTab(): LinearLayout {
        val day = summaryDate()
        val completed = completedTrips()
        val dayTrips = completed.filter { tripDay(it) == day }
        val month = YearMonth.from(day)
        val monthTrips = completed.filter { YearMonth.from(tripDay(it)) == month }
        val weekStart = day.minusDays(6)
        val weekTrips = completed.filter { tripDay(it) in weekStart..day }
        val tripsByDay = weekTrips.groupBy(::tripDay)
        val dailyBars = tripsByDay.toSortedMap().map { (date, trips) ->
            DistanceBar(CHART_DAY.format(date), distance(trips), ACCENT)
        }

        val overview = column().apply {
            addView(historyPanel().apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(29, 52, 77), CARD)).apply { cornerRadius = dp(14).toFloat() }
                addView(text(FULL_DAY.format(day), 21f, MUTED))
                addView(text(if (demoHistory) "기준일 주행거리" else "오늘 주행거리", 26f, TEXT, true).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                addView(distanceLabel(km(distance(dayTrips)), 70, TEXT))
                addView(text("운행 ${dayTrips.size}건", 22f, MUTED))
            })
            addView(historyPanel().apply {
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(if (demoHistory) "기준일 운행" else "오늘 운행", 28f, TEXT, true),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button("자세히 보기 ›") {
                        filter = "전체"
                        selectedRecordId = dayTrips.firstOrNull()?.id
                        tabScroll[Tab.RECORDS.ordinal] = 0
                        val refresh = screen.month != month
                        selectedMonth = month
                        switchTo(Tab.RECORDS)
                        if (refresh) runTask {}
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                })
                if (dayTrips.isEmpty()) {
                    addView(text("완료한 운행이 없어요.", 21f, MUTED).apply {
                        setPadding(0, dp(12), 0, 0)
                    })
                } else {
                    addView(column().apply {
                        background = fill(BACKGROUND, 10)
                        clipToOutline = true
                        dayTrips.take(3).forEachIndexed { index, trip ->
                            if (index > 0) addView(line())
                            addView(historyTripRow(trip, false, rowColor = BACKGROUND) { detailDialog(trip) })
                        }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        val summary = column().apply {
            addView(historyPanel().apply {
                addView(text("최근 운행", 28f, TEXT, true))
                addView(text("${SHORT_DAY.format(weekStart)} – ${SHORT_DAY.format(day)}", 20f, MUTED))
                addView(distanceLabel(km(distance(weekTrips)), 48, TEXT))
                addView(text("운행 ${weekTrips.size}건", 21f, MUTED))
                addView(distanceChart(dailyBars, expand = wideHistory))
                if (tripsByDay.isNotEmpty()) {
                    addView(text("운행한 날짜", 20f, MUTED).apply { setPadding(0, dp(12), 0, dp(4)) })
                    tripsByDay.forEach { (date, trips) ->
                        addView(line())
                        addView(historyFact(DAY.format(date), km(distance(trips))))
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2, if (wideHistory) 1f else 0f))
            addView(historyPanel().apply {
                addView(text("${month.monthValue}월 누적", 28f, TEXT, true))
                addView(distanceLabel(km(distance(monthTrips)), 44, TEXT))
                addView(text("운행 ${monthTrips.size}건", 21f, MUTED))
                addView(monthBreakdown(monthTrips, expand = wideHistory))
                if (screen.pending > 0) {
                    addView(button("분류 대기 ${screen.pending}건 · 전체 기간   ›") { switchTo(Tab.REVIEW) }.apply {
                        setTextColor(WARNING)
                        background = ripple(PENDING_BACKGROUND, 12)
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }
            }, LinearLayout.LayoutParams(-1, -2, if (wideHistory) 1f else 0f).apply { topMargin = dp(12) })
        }
        return historyColumns(overview, summary, fillHeight = true)
    }

    private fun driveTab(): LinearLayout {
        val active = screen.active
        val candidate = active != null && signal == "MOTION_ARRIVAL_CONFIRM_REQUIRED"
        val state = when {
            demoHistory -> "더미 기록 둘러보기"
            candidate -> "정차 · 종료 확인 대기"
            active != null -> "운행 기록 중"
            signal.startsWith("SIGNAL_UNAVAILABLE") || signal == "MOTION_INVALID_OR_STALE" -> "차량 신호 대기"
            automotive && signal.isEmpty() -> "차량 신호 연결 중"
            else -> "운행 대기"
        }
        val current = panel().apply {
            minimumHeight = dp(320)
            gravity = Gravity.CENTER_VERTICAL
            addView(text("● $state", 22f, if (candidate) WARNING else if (active != null) GREEN else MUTED, true))
            addView(text("현재 운행", 34f, TEXT, true).apply { setPadding(0, dp(10), 0, dp(8)) })
            addView(text("이번 운행 거리", 22f, MUTED))
            driveDistance = distanceLabel("—", 72, ACCENT).also { addView(it) }
            driveOdometer = text("", 22f, MUTED).also { addView(it) }
            // candidate 는 active != null 일 때만 true (Kotlin 1.9는 이 스마트 캐스트를 못 해서 !! 로 표시)
            if (candidate) addView(button(
                if (active!!.mode == RecordingMode.MANUAL_GROUP) "묶음 운행 종료" else "운행 종료 확인",
                primary = true
            ) { finishDialog(active!!.id) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        val nav = navigationSnapshot
        val navigationPanel = panel().apply {
            minimumHeight = dp(320)
            gravity = Gravity.CENTER_VERTICAL
            addView(text("내비게이션", 22f, MUTED, true))
            addView(text(nav.destination ?: "목적지 안내 대기", 34f, TEXT, true).apply {
                setPadding(0, dp(14), 0, dp(10))
            })
            val remaining = nav.remainingMeters
            if (remaining != null) {
                addView(text("목적지까지", 22f, MUTED))
                addView(distanceLabel(km(remaining / 1000.0), 52, ACCENT))
            } else {
                val guidance = if (demoHistory) "더미 기록을 보는 중이에요."
                    else if (nav.unavailable) "내비 정보를 받을 수 없어요."
                    else "기본 내비에서 길 안내를 시작하면 목적지와 남은 거리가 표시돼요."
                addView(text(guidance, 22f, MUTED))
            }
        }
        updateDriveDistance()
        return paired(current, navigationPanel, 1f, 1.15f)
    }

    private fun updateDriveDistance() {
        val active = screen.active
        val reading = odometerKm?.takeIf { active != null && it >= active.startKm }
        driveDistance?.text = if (reading != null && active != null) km(reading - active.startKm) else "—"
        driveOdometer?.text = when {
            demoHistory -> "운행 기록 탭에서 2025년 12월부터 확인할 수 있어요."
            active == null -> "출발하면 운행이 기록돼요."
            reading == null -> "${TIME.format(active.start)} 출발 · 누적거리 신호 대기"
            else -> "${TIME.format(active.start)} 출발 · ${km(active.startKm)} → ${km(reading)}"
        }
    }

    private fun recordsTab(): LinearLayout = column().apply {
        val month = screen.month
        val records = completedTrips().filter { YearMonth.from(tripDay(it)) == month }
        val filtered = records.filter { trip -> when (filter) {
            "분류 대기" -> !trip.confirmed
            "업무용" -> trip.confirmed && trip.category == Category.BUSINESS
            "개인용" -> trip.confirmed && trip.category == Category.PERSONAL
            else -> true
        } }
        val selected = filtered.firstOrNull { it.id == selectedRecordId } ?: filtered.firstOrNull()
        addView(row().apply {
            background = fill(CARD, 12)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(button("‹") { changeMonth(-1) }.apply { textSize = 28f; contentDescription = "이전 달" })
            addView(text("${month.year}년 ${month.monthValue}월", if (wideHistory) 27f else 22f, TEXT, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(if (wideHistory) dp(194) else 0, -2, if (wideHistory) 0f else 1f))
            addView(button("›") { changeMonth(1) }.apply { textSize = 28f; contentDescription = "다음 달" })
            if (wideHistory) {
                addView(View(this@TripLogActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(currentMonthButton())
                addView(recordModeButton(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
        })
        val list = column().apply {
            addView(HorizontalScrollView(this@TripLogActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row().apply {
                    listOf("전체", "업무용", "개인용", "분류 대기").forEach { name ->
                        addView(button(name, primary = filter == name) {
                            filter = name
                            resetHistoryScroll()
                            render()
                        }.apply { isSelected = filter == name },
                            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                    }
                })
            })
            if (filtered.isEmpty()) {
                addView(emptyState(
                    if (records.isEmpty()) "이 달의 운행 기록이 없어요" else "선택한 분류의 운행이 없어요",
                    if (records.isEmpty()) "월을 이동해 다른 기록을 볼 수 있어요." else "전체를 선택하면 이 달의 모든 운행을 볼 수 있어요."
                ))
            } else {
                addView(historyRow(
                    if (showTimeColumn) text("시간", 19f, MUTED) else null,
                    text("운행 목적", 19f, MUTED),
                    if (showTimeColumn) text("분류", 19f, MUTED) else null,
                    text(if (showTimeColumn) "거리" else "거리·분류", 19f, MUTED)
                ).apply { minimumHeight = dp(44) })
                addView(column().apply {
                    background = fill(CARD, 12)
                    clipToOutline = true
                    filtered.groupBy(::tripDay).entries.forEachIndexed { index, (day, trips) ->
                        if (index > 0) addView(line())
                        addView(text("${DAY.format(day)} · ${trips.size}건 · ${km(distance(trips))}", 20f, MUTED, true).apply {
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            setAccessibilityHeading(true)
                        }, LinearLayout.LayoutParams(-1, -2))
                        trips.forEach { trip ->
                            addView(line())
                            addView(historyTripRow(trip, wideHistory && selected?.id == trip.id) {
                                if (wideHistory) {
                                    selectedRecordId = trip.id
                                    render()
                                } else detailDialog(trip)
                            })
                        }
                    }
                })
            }
        }
        val summary = column().apply {
            if (wideHistory && selected != null) {
                addView(recordDetail(selected), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            addView(monthSummary(month, records))
            if (!wideHistory) addView(row().apply {
                addView(currentMonthButton())
                addView(recordModeButton(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val panels = if (wideHistory) row().apply {
            val listPane = ScrollView(this@TripLogActivity).apply {
                isFillViewport = true
                addView(list)
            }
            historyScroll = listPane
            addView(listPane, LinearLayout.LayoutParams(0, -1, 1.55f).apply { marginEnd = dp(12) })
            addView(ScrollView(this@TripLogActivity).apply { addView(summary) },
                LinearLayout.LayoutParams(0, -1, 1f))
        } else historyColumns(list, summary)
        addView(panels, (if (wideHistory) LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(-1, -2)).apply { topMargin = dp(10) })
    }

    private fun currentMonthButton() = button(if (demoHistory) "기준 월" else "이번 달") {
        showMonth(YearMonth.from(summaryDate()))
    }

    private fun recordModeButton() = button(
        if (screen.mode == RecordingMode.SEGMENT) "기록 단위 · 구간별" else "기록 단위 · 묶음"
    ) { modeDialog() }

    private fun historyPanel() = column().apply {
        background = fill(CARD, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun historyColumns(list: View, side: View, fillHeight: Boolean = false): LinearLayout =
        if (wideHistory) row().apply {
            val height = if (fillHeight) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT
            addView(list, LinearLayout.LayoutParams(0, height, 1.55f).apply { marginEnd = dp(12) })
            addView(side, LinearLayout.LayoutParams(0, height, 1f))
        } else column().apply {
            addView(list, LinearLayout.LayoutParams(-1, -2))
            addView(side, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

    private fun historyFact(label: String, value: String) = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(text(label, 20f, MUTED), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        addView(text(value, 22f, TEXT, true))
    }

    private fun monthSummary(month: YearMonth, records: List<Trip>) = historyPanel().apply {
        addView(text("${month.monthValue}월 요약", 27f, TEXT, true))
        addView(distanceLabel(km(distance(records)), 44, TEXT))
        addView(text("운행 ${records.size}건 · 분류 대기 ${records.count { !it.confirmed }}건", 20f, MUTED).apply {
            setPadding(0, 0, 0, dp(10))
        })
        addView(monthBreakdown(records))
    }

    private fun monthBreakdown(records: List<Trip>, expand: Boolean = false): View {
        val business = records.filter { it.confirmed && it.category == Category.BUSINESS }
        val personal = records.filter { it.confirmed && it.category == Category.PERSONAL }
        val unresolved = records.filter { !it.confirmed || it.category == Category.UNCLASSIFIED }
        val bars = buildList {
            add(DistanceBar("업무용", distance(business), ACCENT))
            add(DistanceBar("개인용", distance(personal), GREEN))
            if (unresolved.isNotEmpty()) {
                add(DistanceBar("미분류", distance(unresolved), WARNING))
            }
        }
        return distanceChart(bars, expand)
    }

    private fun distanceChart(bars: List<DistanceBar>, expand: Boolean): View {
        val chart = if (bars.isEmpty()) {
            text("최근 7일간 운행 기록이 없어요.", 17f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(20), dp(12), dp(20))
            }
        } else {
            HorizontalScrollView(this).apply {
                isFillViewport = true
                addView(DistanceBarChart(this@TripLogActivity, bars, MUTED, TEXT),
                    android.widget.FrameLayout.LayoutParams(-2, -1))
            }
        }
        return chart.apply {
            background = fill(BACKGROUND, 12)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, -2, if (expand) 1f else 0f).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun recordDetail(trip: Trip) = historyPanel().apply {
        val ended = requireNotNull(trip.end)
        val minutes = Duration.between(trip.start, ended).toMinutes()
        val duration = when {
            minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
            minutes > 0 -> "${minutes}분"
            else -> "1분 미만"
        }
        addView(text(trip.purpose.ifBlank { "목적 미입력" }, 31f, TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(text(DAY.format(tripDay(trip)), 21f, MUTED))
        addView(distanceLabel(km(trip.distanceKm ?: 0.0), 54, TEXT))
        addView(line())
        addView(historyFact("출발", TIME.format(trip.start)))
        addView(historyFact("종료", TIME.format(ended)))
        addView(historyFact("운행 시간", duration))
        addView(historyFact("분류", historyCategory(trip)))
        addView(line())
        addView(text("차량 누적거리", 20f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        addView(text("${String.format(Locale.KOREA, "%,.1f", trip.startKm)} → ${km(trip.endKm ?: trip.startKm)}", 22f, TEXT, true))
        screen.plannedMeters[trip.id]?.let { meters ->
            addView(historyFact("내비 예상거리", km(meters / 1000.0)))
        }
        addView(row().apply {
            addView(button("분류 수정", primary = true) { chooseReview(trip) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(button("기록 관리") { detailDialog(trip) }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun emptyState(title: String, detail: String) = panel().apply {
        minimumHeight = dp(162)
        gravity = Gravity.CENTER
        addView(text(title, 27f, TEXT, true).apply { gravity = Gravity.CENTER })
        addView(text(detail, 21f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } }

    private fun distanceLabel(label: String, size: Int, color: Int) = text(label, size.toFloat(), color, true).apply {
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(24, size, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun panel() = column().apply {
        background = fill(CARD, 18)
        setPadding(dp(22), dp(20), dp(22), dp(20))
    }

    private fun paired(first: View, second: View, left: Float, right: Float): LinearLayout {
        return if (readableWidthDp >= 680) row().apply {
            addView(first, LinearLayout.LayoutParams(0, -1, left).apply { marginEnd = dp(16) })
            addView(second, LinearLayout.LayoutParams(0, -1, right))
        } else column().apply {
            addView(first, LinearLayout.LayoutParams(-1, -2))
            addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private val reviewDraftCache by lazy {
        if (demoHistory) null else getSharedPreferences("triplog_review_drafts", MODE_PRIVATE)
    }

    private fun reviewDraftKey(tripId: String): String? =
        hostUser?.let { user -> "${user.id.length}:${user.id}:$tripId" }

    private fun cacheReviewDraft(tripId: String) {
        val key = reviewDraftKey(tripId) ?: return
        val draft = JSONObject()
            .put("purpose", draftPurpose)
            .put("category", draftCategory?.name ?: JSONObject.NULL)
        reviewDraftCache?.edit()?.putString(key, draft.toString())?.apply()
    }

    private fun clearReviewDraft(tripId: String) {
        val key = reviewDraftKey(tripId) ?: return
        reviewDraftCache?.edit()?.remove(key)?.apply()
    }

    private fun restoreReviewDraft(trip: Trip) {
        val key = reviewDraftKey(trip.id) ?: return
        val raw = reviewDraftCache?.getString(key, null)
        val draft = raw?.let {
            try {
                JSONObject(it)
            } catch (_: org.json.JSONException) {
                null
            }
        }
        draftPurpose = draft?.optString("purpose", trip.purpose) ?: trip.purpose
        draftCategory = if (draft == null) {
            trip.category.takeIf { trip.confirmed && it != Category.UNCLASSIFIED }
        } else {
            Category.entries.firstOrNull { it != Category.UNCLASSIFIED && it.name == draft.optString("category") }
        }
    }

    private fun chooseReview(trip: Trip) {
        if (reviewId != trip.id) {
            reviewId = trip.id
            draftPurpose = trip.purpose
            draftCategory = trip.category.takeIf { trip.confirmed && it != Category.UNCLASSIFIED }
        }
        switchTo(Tab.REVIEW)
    }

    private fun classificationTab(): LinearLayout {
        val completed = completedTrips()
        val queue = completed.filter { !it.confirmed }
        val selected = completed.firstOrNull { it.id == reviewId } ?: queue.firstOrNull()
        if (screen.account != null && selected?.id != reviewId) {
            reviewId = selected?.id
            draftPurpose = selected?.purpose.orEmpty()
            draftCategory = selected?.category?.takeIf { selected.confirmed && it != Category.UNCLASSIFIED }
        }
        if (!demoHistory && selected != null) restoreReviewDraft(selected)
        val list = panel().apply {
            minimumHeight = dp(if (readableWidthDp >= 680) 310 else 124)
            addView(text("분류 대기 ${queue.size}건", 29f, TEXT, true))
            if (queue.isEmpty()) addView(text("운행을 종료하면 여기에 추가돼요.", 21f, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            queue.forEach { trip ->
                val title = trip.purpose.ifBlank { "목적 미입력" }
                val detail = "${DATE.format(trip.start)} · ${km(trip.distanceKm ?: 0.0)}"
                addView(button("$title\n$detail", primary = selected?.id == trip.id) {
                    hideKeyboard()
                    chooseReview(trip)
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = dp(78)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }
        val editor = panel().apply {
            minimumHeight = dp(310)
            addView(text("운행 분류", 32f, TEXT, true))
            if (selected == null) {
                addView(emptyState("정리할 운행을 선택해 주세요", "운행 목적을 입력하고 업무용 또는 개인용으로 저장할 수 있어요."),
                    LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                addView(text("${DATE.format(selected.start)} · ${km(selected.distanceKm ?: 0.0)}", 22f, MUTED))
                addView(text("운행 목적", 23f, TEXT, true).apply { setPadding(0, dp(18), 0, dp(8)) })
                val purpose = input("예. 거래처 장비 점검").apply {
                    setText(draftPurpose)
                    textSize = 23f
                    minHeight = dp(64)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = fill(BACKGROUND, 10).apply { setStroke(dp(1), DIVIDER) }
                    isEnabled = !busy
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            draftPurpose = s?.toString().orEmpty()
                            if (!demoHistory) cacheReviewDraft(selected.id)
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                addView(purpose, LinearLayout.LayoutParams(-1, -2))
                addView(text("분류", 23f, TEXT, true).apply { setPadding(0, dp(16), 0, dp(8)) })
                val choices = row()
                val categories = listOf(Category.BUSINESS to "업무용", Category.PERSONAL to "개인용")
                val categoryButtons = categories.associate { (category, label) ->
                    category to button(label) {}
                }
                fun paintChoices() {
                    categoryButtons.forEach { (category, view) ->
                        val chosen = draftCategory == category
                        view.isSelected = chosen
                        view.background = ripple(if (chosen) SELECTED else BUTTON, 12)
                        view.setTextColor(if (chosen) ACCENT else TEXT)
                    }
                }
                categoryButtons.forEach { (category, view) ->
                    view.setOnClickListener {
                        draftCategory = category
                        if (!demoHistory) cacheReviewDraft(selected.id)
                        paintChoices()
                    }
                    choices.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
                }
                paintChoices()
                addView(choices)
                val validation = text("", 19f, WARNING).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                addView(validation)
                val actions = row().apply {
                    addView(button("저장", primary = true) {
                        val category = draftCategory
                        val value = draftPurpose.trim()
                        if (value.isEmpty() || value.length > 2000 || category == null) {
                            validation.text = "목적(1~2,000자)과 업무용 또는 개인용을 입력해 주세요."
                        } else {
                            hideKeyboard()
                            runTask(after = {
                                reviewId = null
                                draftPurpose = ""
                                draftCategory = null
                                message = "분류를 저장했어요."
                                render()
                            }) {
                                it.review(selected.id, value, category)
                                clearReviewDraft(selected.id)
                            }
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                    addView(button("보류") {
                        val value = draftPurpose.trim()
                        if (value.length > 2000) validation.text = "목적은 2,000자 이하로 입력해 주세요."
                        else if (value.isEmpty()) {
                            message = "운행은 기존 분류 상태로 남겨뒀어요."
                            hideKeyboard()
                            render()
                        } else {
                            hideKeyboard()
                            runTask(after = {
                                draftCategory = null
                                message = "분류 대기로 남겨뒀어요."
                                render()
                            }) {
                                it.defer(selected.id, value)
                                clearReviewDraft(selected.id)
                            }
                        }
                    })
                }
                addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        }
        return paired(list, editor, .8f, 1.5f)
    }

    private fun resetHistoryScroll() {
        (historyScroll ?: scroll).scrollTo(0, 0)
    }

    private fun changeMonth(delta: Long) = showMonth(selectedMonth.plusMonths(delta))

    private fun showMonth(month: YearMonth) {
        if (selectedMonth == month) return
        selectedMonth = month
        selectedRecordId = null
        resetHistoryScroll()
        runTask {}
    }

    private fun modeDialog() {
        AlertDialog.Builder(this).setTitle("운행 기록 단위")
            .setSingleChoiceItems(arrayOf("구간별 기록", "여러 구간을 하나로 묶기"), if (screen.mode == RecordingMode.SEGMENT) 0 else 1) { dialog, which ->
                runTask { it.trips.setMode(if (which == 0) RecordingMode.SEGMENT else RecordingMode.MANUAL_GROUP) }
                dialog.dismiss()
            }.setNegativeButton("닫기", null).show()
    }

    private fun detailDialog(trip: Trip) {
        val actions = mutableListOf("목적·분류 편집", "보고서 정보 입력")
        if (screen.account?.second == Role.ADMIN) actions.add("기록 삭제")
        val content = column().apply {
            setPadding(dp(24), dp(8), dp(24), dp(16))
            addView(text("${DATE.format(trip.start)} → ${trip.end?.let(TIME::format).orEmpty()}", 21f, TEXT))
            addView(text("${km(trip.startKm)} → ${km(trip.endKm ?: trip.startKm)} · ${km(trip.distanceKm ?: 0.0)}", 21f, ACCENT))
            addView(text("운전자 ${trip.driverId} · ${sourceLabel(trip.source)} · ${historyCategory(trip)}", 19f, MUTED))
            screen.plannedMeters[trip.id]?.let { meters ->
                addView(text("내비 예상거리 ${km(meters / 1000.0)}", 19f, MUTED))
            }
            addView(text(trip.purpose.ifBlank { "목적 미입력" }, 22f, TEXT).apply { setPadding(0, dp(16), 0, dp(16)) })
        }
        val dialog = AlertDialog.Builder(this).setTitle("운행 상세").setView(content).setNegativeButton("닫기", null).create()
        actions.forEach { label -> content.addView(button(label) {
            dialog.dismiss()
            when (label) {
                "목적·분류 편집" -> chooseReview(trip)
                "보고서 정보 입력" -> {
                    var details: ReportDetails? = null
                    runTask(after = { reportDetailsDialog(trip, details) }) { details = it.reportDetails(trip.id) }
                }
                else -> AlertDialog.Builder(this).setTitle("운행 기록을 삭제할까요?")
                    .setMessage("${DATE.format(trip.start)} · ${km(trip.distanceKm ?: 0.0)}")
                    .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                        runTask {
                            it.trips.deleteTrip(trip.id)
                            clearReviewDraft(trip.id)
                        }
                    }.show()
            }
        }) }
        dialog.show()
    }

    private fun sourceLabel(source: Source) = when (source) {
        Source.SIMULATED -> "모의 입력"
        Source.EMULATOR -> "에뮬레이터"
        Source.VEHICLE -> "차량 기록"
    }

    private fun reportDetailsDialog(trip: Trip, existing: ReportDetails?) {
        val labels = listOf("회사명", "사업자번호", "차종", "차량번호", "부서", "운전자 이름")
        val values = existing?.let { listOf(it.company, it.businessNumber, it.vehicleType, it.plate, it.department, it.name) }
        val fields = labels.mapIndexed { index, label -> input(label).apply { setText(values?.get(index).orEmpty()) } }
        val commute = android.widget.CheckBox(this).apply { text = "출퇴근 운행"; isChecked = existing?.commute ?: false }
        val form = form(*fields.toTypedArray()).apply { addView(commute) }
        val dialog = AlertDialog.Builder(this).setTitle("보고서 정보")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy) return@setOnClickListener
            val values = fields.map { it.text.toString().trim() }
            val invalid = values.indexOfFirst { it.isBlank() || it.length > 200 }
            if (invalid >= 0) { fields[invalid].error = "1~200자로 입력하세요."; return@setOnClickListener }
            if (commute.isChecked && trip.category != Category.BUSINESS) { commute.error = "업무용으로 분류한 운행만 선택하세요."; return@setOnClickListener }
            val details = ReportDetails(values[0],values[1],values[2],values[3],values[4],values[5],commute.isChecked)
            runTask(after = { dialog.dismiss() }, onFailure = { fields.first().error = errorMessage(it) }) {
                it.reportDetails(trip.id, details)
            }
        }
    }

    private fun finishDialog(tripId: String) {
        AlertDialog.Builder(this).setTitle("운행을 종료할까요?")
            .setMessage("정차가 확인된 운행을 저장한 뒤 목적을 분류할 수 있어요.")
            .setNegativeButton("계속 운행", null)
            .setPositiveButton("종료") { _, _ ->
                var ended: Trip? = null
                runTask(after = { ended?.let(::chooseReview) }) { app ->
                    val active = checkNotNull(app.list().singleOrNull { it.id == tripId && it.end == null }) {
                        "현재 운행의 종료 상태를 다시 확인하세요."
                    }
                    val result = app.distanceMotion.confirmArrival(finishGroup = active.mode == RecordingMode.MANUAL_GROUP)
                    ended = checkNotNull(result.trip?.takeIf { it.end != null }) { "운행 종료를 다시 확인하세요." }
                }
            }.show()
    }

    private fun historyCategory(trip: Trip) = when {
        !trip.confirmed -> "분류 대기"
        trip.category == Category.BUSINESS -> "업무용"
        trip.category == Category.PERSONAL -> "개인용"
        else -> "미분류"
    }

    private fun historyRow(time: View?, purpose: View, category: View?, distance: TextView) = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minimumHeight = dp(68)
        distance.gravity = Gravity.END
        if (time != null) {
            addView(time, LinearLayout.LayoutParams(dp(144), -2))
            addView(purpose, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(requireNotNull(category), LinearLayout.LayoutParams(dp(110), -2))
            addView(distance, LinearLayout.LayoutParams(dp(126), -2).apply { marginStart = dp(6) })
        } else {
            addView(purpose, LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginEnd = dp(8) })
            addView(column().apply {
                gravity = Gravity.END
                addView(distance, LinearLayout.LayoutParams(-1, -2))
                category?.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) }) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun historyTripRow(trip: Trip, selected: Boolean, rowColor: Int = CARD, click: () -> Unit): LinearLayout {
        val purpose = trip.purpose.ifBlank { "목적 미입력" }
        val times = "${TIME.format(trip.start)}–${trip.end?.let(TIME::format).orEmpty()}"
        val category = historyCategory(trip)
        val distance = km(trip.distanceKm ?: 0.0)
        val purposeLabel = text(purpose, 22f, TEXT, true).apply {
            maxLines = if (showTimeColumn) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val purposeCell = if (showTimeColumn) purposeLabel else column().apply {
            addView(purposeLabel)
            addView(text(times, 19f, MUTED))
        }
        val categoryColor = when {
            !trip.confirmed -> WARNING
            trip.category == Category.BUSINESS -> ACCENT
            else -> TEXT
        }
        val categoryBackground = when {
            !trip.confirmed -> PENDING_BACKGROUND
            trip.category == Category.BUSINESS -> SELECTED
            else -> BUTTON
        }
        val badge = row().apply {
            addView(text(category, 18f, categoryColor, true).apply {
                background = fill(categoryBackground, 7)
                setPadding(dp(6), dp(4), dp(6), dp(4))
            })
        }
        return historyRow(
            if (showTimeColumn) text(times, 19f, MUTED) else null,
            purposeCell, badge, text(distance, 22f, TEXT, true)
        ).apply {
            background = ripple(if (selected) SELECTED else rowColor, 6)
            isSelected = selected
            isEnabled = !busy
            isFocusable = !busy
            contentDescription = "${DAY.format(tripDay(trip))}, $times, $purpose, $category, $distance"
            setOnClickListener { if (!busy) click() }
        }
    }

    private fun button(label: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) =
        Button(this).apply {
            val active = enabled && !busy
            text = label
            textSize = 20f
            isAllCaps = false
            minHeight = dp(62)
            minimumHeight = dp(62)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = ripple(if (primary && active) ACCENT else BUTTON, 12)
            setTextColor(if (!active) MUTED else if (primary) BACKGROUND else TEXT)
            isEnabled = active
            setOnClickListener { click() }
        }

    private fun input(placeholder: String) = EditText(this).apply {
        hint = placeholder
        textSize = 20f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
    }
    private fun form(vararg fields: EditText) = column().apply {
        setPadding(dp(24), dp(8), dp(24), dp(8))
        fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun line() = View(this).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun km(value: Double) = String.format(Locale.KOREA, "%,.1f km", value)
    private fun fill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun ripple(color: Int, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(0x335fa6ff), fill(color, radius), null)

    private fun WindowInsets.getInsetsCompat(): android.graphics.Rect {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return android.graphics.Rect(bars.left, bars.top, bars.right, bars.bottom)
        }
        @Suppress("DEPRECATION")
        return android.graphics.Rect(systemWindowInsetLeft, systemWindowInsetTop,
            systemWindowInsetRight, systemWindowInsetBottom)
    }

    private fun errorMessage(error: Throwable): String = when (error.message) {
        "Another driver owns active trip" -> "진행 중인 운행의 운전자 또는 관리자 정보가 필요해요."
        "Read a valid stationary arrival candidate first",
        "Arrival observation expired; read signals again" -> "정차를 다시 확인한 뒤 종료를 눌러주세요."
        else -> if (error.message?.startsWith("Report details required") == true) "운행 상세에서 보고서 정보를 입력해 주세요."
            else if (error.message?.startsWith("Review required") == true) "이 달의 모든 운행 목적과 분류를 확정해 주세요."
            else error.message ?: "작업을 완료하지 못했어요. 다시 시도하세요."
    }

    private companion object {
        val WORKER = Executors.newSingleThreadExecutor()
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA).withZone(SEOUL)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREA)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA).withZone(SEOUL)
        val FULL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREA)
        val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA)
        val CHART_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d", Locale.KOREA)
        val BACKGROUND = Color.rgb(16, 21, 29)
        val CARD = Color.rgb(24, 31, 42)
        val SELECTED = Color.rgb(29, 54, 83)
        val BUTTON = Color.rgb(45, 52, 63)
        val DIVIDER = Color.rgb(43, 53, 67)
        val TEXT = Color.rgb(244, 247, 251)
        val MUTED = Color.rgb(193, 205, 219)
        val ACCENT = Color.rgb(128, 189, 255)
        val GREEN = Color.rgb(58, 212, 150)
        val WARNING = Color.rgb(255, 198, 113)
        val PENDING_BACKGROUND = Color.rgb(73, 53, 28)
    }
}
