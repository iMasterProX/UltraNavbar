package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.overlay.NavBarOverlay
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.DeviceProfile

class NavBarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavBarAccessibility"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "UltraNavBarServiceChannel"
        private const val IME_DEBUG_LOG = true
        private const val IME_DEBUG_WINDOW_MS = 1500L
        private const val HOME_WINDOW_GRACE_MS = 400L  // 900ms -> 400ms 로 단축

        @Volatile
        var instance: NavBarAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private var overlay: NavBarOverlay? = null
    private lateinit var settings: SettingsManager

    private var currentPackage: String = ""
    private var isFullscreen: Boolean = false
    private var isOnHomeScreen: Boolean = false
    private var isRecentsVisible: Boolean = false
    private var isNotificationPanelOpen: Boolean = false
    private var isWallpaperPreviewVisible: Boolean = false
    private var isImeVisible: Boolean = false
    private var imePendingUntil: Long = 0
    private var homeExitGraceUntil: Long = 0
    private var homeEnterGraceUntil: Long = 0
    private var lastHomeActionAt: Long = 0
    private var lastHomeActionPackage: String = ""
    private var lastUiMode: Int = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var isSystemNavVisible: Boolean = false
    private var isLightNavStyle: Boolean = false
    private var lastImeEventAt: Long = 0
    private var lastImeWindowSeenAt: Long = 0
    private var lastImeWindowHeight: Int = 0
    private var lastImeWindowBottom: Int = 0
    private var imeFocusActive: Boolean = false
    private var wasAcceptingText: Boolean = false
    private var imeDebugUntil: Long = 0
    private var imeDebugLastHeight: Int = 0
    private var imeDebugLastBottom: Int = 0
    private var imeDebugLastWindowOpen: Boolean = false
    private var imeDebugLastVisible: Boolean = false
    private var imeDebugLastWindowPresent: Boolean = false
    private var lastLauncherWindowSeenAt: Long = 0

    // 잠금해제 대기 플래그 - 화면 꺼짐~잠금해제 사이에 다른 코드에서 overlay를 show하지 않도록 방지
    @Volatile
    private var unlockPending: Boolean = false

    private var launcherPackages: Set<String> = emptySet()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // approximate navigation bar height (px)
    private var navBarHeightPx = 0

    // debounce
    private var pendingStateCheck: Runnable? = null

    // fullscreen polling
    private var fullscreenPoll: Runnable? = null

    // IME polling
    private var imePoll: Runnable? = null
    private var homePoll: Runnable? = null
    private var homePollUntil: Long = 0

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.minsoo.ultranavbar.SETTINGS_CHANGED" -> {
                    Log.d(TAG, "Settings changed, refreshing overlay")
                    overlay?.refreshSettings()
                    updateOverlayVisibility(forceFade = true)
                }
                "com.minsoo.ultranavbar.RELOAD_BACKGROUND" -> {
                    Log.d(TAG, "Reloading background images")
                    overlay?.reloadBackgroundImages()
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, hiding overlay and setting unlockPending=true")
                    unlockPending = true  // 잠금해제 대기 상태 진입
                    overlay?.hide(animate = false, showHotspot = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 화면 켜짐 - 아직 잠금화면이므로 오버레이 숨김 유지
                    Log.d(TAG, "Screen on, keeping overlay hidden until user present (unlockPending=$unlockPending)")
                    // 잠금화면에서는 숨김 상태 유지, unlockPending도 유지
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present, showing with fade animation only")
                    handler.postDelayed({
                        unlockPending = false  // 잠금해제 완료, 플래그 해제
                        homeExitGraceUntil = 0
                        startHomePolling(1500)
                        // 페이드 효과로만 오버레이 표시 - forceUnlock=true로 호출
                        updateOverlayVisibility(forceFade = true, forceAnimation = true, forceUnlock = true)
                        scheduleStateCheck()
                    }, 150)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!DeviceProfile.isTablet(this)) {
            Log.w(TAG, "Device is not a tablet. Disabling service.")
            disableSelf()
            return
        }
        Log.i(TAG, "Service connected")
        instance = this
        settings = SettingsManager.getInstance(this)
        lastUiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        // (선택이지만 권장) 여러 윈도우 정보를 안정적으로 받기 위한 플래그
        try {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Could not update serviceInfo flags", e)
        }

        startForegroundService()
        loadLauncherPackages()
        createOverlay()
        updateDefaultNavStyleFromSystem(force = true)
        updateOverlayVisibility(forceFade = false)

        val settingsFilter = IntentFilter().apply {
            addAction("com.minsoo.ultranavbar.SETTINGS_CHANGED")
            addAction("com.minsoo.ultranavbar.RELOAD_BACKGROUND")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(settingsReceiver, settingsFilter)
        }

        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)

        calculateNavBarHeight()
        checkImeVisibility()
        Log.i(TAG, "Overlay created and service fully initialized")
    }

    private fun calculateNavBarHeight() {
        // 현재 orientation에 맞는 네비게이션 바 높이 계산
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val resName = if (isPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
        var resId = resources.getIdentifier(resName, "dimen", "android")

        // fallback to default navigation_bar_height
        if (resId == 0) {
            resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        }

        navBarHeightPx = if (resId > 0) resources.getDimensionPixelSize(resId) else dpToPx(48f)
        Log.d(TAG, "Navigation bar height calculated: ${navBarHeightPx}px (portrait=$isPortrait)")
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "UltraNavBar Service",
            NotificationManager.IMPORTANCE_MIN
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running_notification))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Service started in foreground.")
    }

    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            val packages = resolveInfos.map { it.activityInfo.packageName }
                .filter { it != "com.android.settings" }
                .toSet()

            launcherPackages = if (packages.isNotEmpty()) {
                packages
            } else {
                val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                resolveInfo?.activityInfo?.packageName?.let { setOf(it) } ?: emptySet()
            }

            if (launcherPackages.isEmpty()) {
                launcherPackages = setOf(
                    "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
                    "com.sec.android.app.launcher", "com.lge.launcher3", "com.huawei.android.launcher"
                )
            }

            Log.d(TAG, "Detected launcher packages: $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages, using hardcoded fallback", e)
            launcherPackages = setOf(
                "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
                "com.sec.android.app.launcher", "com.lge.launcher3", "com.huawei.android.launcher"
            )
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        instance = null

        pendingStateCheck?.let { handler.removeCallbacks(it) }
        pendingStateCheck = null
        stopFullscreenPolling()

        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }

        destroyOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientationName = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "Configuration changed: orientation=$orientationName")

        // 회전 시 기준값 먼저 재계산
        calculateNavBarHeight()

        // 오버레이에 orientation 변경 알림
        overlay?.handleOrientationChange(newConfig.orientation)

        val uiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (uiMode != lastUiMode) {
            lastUiMode = uiMode
            overlay?.refreshSettings()
            updateDefaultNavStyleFromSystem(force = true)
            updateOverlayVisibility(forceFade = true)
        }

        updateDefaultNavStyleFromSystem()

        // orientation 변경 후 fullscreen 상태 재확인 (약간 딜레이로 안정화)
        handler.postDelayed({
            checkFullscreenState()
            scheduleStateCheck()
        }, 100)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val focusedIsTextInput = isTextInputClass(event.className)
            if (focusedIsTextInput) {
                imeFocusActive = true
                lastImeEventAt = now
                imePendingUntil = now + 700
                startImePolling()
                updateImeVisible(true)
            } else if (imeFocusActive) {
                imeFocusActive = false
                imePendingUntil = 0
                scheduleStateCheck()
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedIsTextInput = isTextInputClass(event.className)
            if (clickedIsTextInput) {
                imeFocusActive = true
                lastImeEventAt = now
                imePendingUntil = now + 700
                startImePolling()
                updateImeVisible(true)
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            if (imeFocusActive) {
                lastImeEventAt = now
            }
        }


        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            val isPreview = className == "com.minsoo.ultranavbar.ui.WallpaperPreviewActivity"
            if (isPreview) {
                isWallpaperPreviewVisible = true
                overlay?.hide(animate = false)
                return
            } else if (isWallpaperPreviewVisible) {
                isWallpaperPreviewVisible = false
                updateOverlayVisibility(forceFade = true)
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                startHomePolling(1500)
                scheduleStateCheck()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // 사진뷰어/일부 앱은 이 이벤트로만 시스템바 변화가 잡히는 경우가 있음
                scheduleStateCheck()
            }
        }
    }

    private fun scheduleStateCheck() {
        pendingStateCheck?.let { handler.removeCallbacks(it) }
        pendingStateCheck = Runnable {
            checkFullscreenState()
            checkNotificationPanelState()
            checkImeVisibility()
            updateHomeAndRecentsFromWindows()
            updateNavBarStyle()
        }
        handler.postDelayed(pendingStateCheck!!, 50)
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val isSystemUi = packageName == "com.android.systemui"
        val isRecents = isRecentsClassName(className)
        val isLauncher = isLauncherPackage(packageName)
        val isHomeOverlay = isHomeOverlayPackage(packageName)

        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            Log.d(TAG, "Recents state changed: $isRecents (class=$className)")
            overlay?.setRecentsState(isRecents)
        }

        if (packageName == this.packageName) return

        if (isSystemUi) {
            if (isRecents && isOnHomeScreen) {
                isOnHomeScreen = false
                homeExitGraceUntil = now + 400  // 900ms -> 400ms
                overlay?.setHomeScreenState(false, immediate = true)
            }
            return
        }

        if (isLauncher) {
            lastLauncherWindowSeenAt = now
        }

        val launcherRecentlyVisible = isLauncher || now - lastLauncherWindowSeenAt < HOME_WINDOW_GRACE_MS
        val homeEntryGraceActive = now < homeEnterGraceUntil
        if (isHomeOverlay && (isOnHomeScreen || launcherRecentlyVisible || homeEntryGraceActive)) {
            if (!isOnHomeScreen) {
                isOnHomeScreen = true
                homeEnterGraceUntil = maxOf(homeEnterGraceUntil, now + 600)  // 1200ms -> 600ms
                overlay?.setHomeScreenState(true, immediate = true)
            }
            return
        }

        val packageChanged = currentPackage != packageName
        if (packageChanged) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app changed: $packageName")
        }

        val newOnHomeScreen = isLauncher && !isRecents

        if (isOnHomeScreen != newOnHomeScreen) {
            if (!newOnHomeScreen && isOnHomeScreen && now < homeEnterGraceUntil) {
                val keepHome =
                    (lastHomeActionPackage.isNotEmpty() && packageName == lastHomeActionPackage) ||
                        isHomeOverlay
                if (keepHome) return
            }
            isOnHomeScreen = newOnHomeScreen
            if (newOnHomeScreen) {
                stopHomePolling()
            }
            if (!newOnHomeScreen) {
                homeExitGraceUntil = now + 400  // 900ms -> 400ms
            } else if (now > homeEnterGraceUntil) {
                homeEnterGraceUntil = 0
            }
            Log.d(TAG, "Home screen state changed: $isOnHomeScreen (pkg=$packageName)")
            // 앱 전환 시 항상 immediate로 처리하여 빠른 배경 전환
            overlay?.setHomeScreenState(isOnHomeScreen, immediate = true)
        }

    }

    private fun isRecentsClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Recents", ignoreCase = true) ||
            simpleName.contains("Overview", ignoreCase = true) ||
            simpleName.contains("TaskSwitcher", ignoreCase = true)
    }

    private fun updateDefaultNavStyleFromSystem(force: Boolean = false) {
        val useLightDefault = !isSystemInDarkMode()
        if (!force && isLightNavStyle == useLightDefault) return
        isLightNavStyle = useLightDefault
        overlay?.setLightNavStyle(useLightDefault)
    }

    private fun isSystemInDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        if (launcherPackages.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower.contains("launcher") || lower.contains("quickstep")
    }

    private fun isHomeOverlayPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return packageName.lowercase().contains("quicksearchbox")
    }

    private fun shouldExitHomeImmediately(
        packageName: String,
        isRecents: Boolean,
        isSystemUi: Boolean
    ): Boolean {
        if (isSystemUi || isRecents) return false
        if (packageName.isBlank()) return false
        if (isLauncherPackage(packageName)) return false
        if (isHomeOverlayPackage(packageName)) return false
        return true
    }

    private fun updateHomeAndRecentsFromWindows() {
        val now = SystemClock.elapsedRealtime()
        val activeRoot = rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString().orEmpty()
        val activeClassName = activeRoot?.className?.toString().orEmpty()
        val isSystemUiActive = activePackage == "com.android.systemui"
        val activeIsLauncher = isLauncherPackage(activePackage)

        var launcherWindowVisible = false
        var launcherWindowPackage: String? = null
        var recentsWindowVisible = false
        var recentsWindowClass = ""

        for (w in windows) {
            val root = w.root ?: continue
            val rootPackage = root.packageName?.toString() ?: continue
            val rootClass = root.className?.toString() ?: ""
            if (rootPackage == this.packageName) continue

            if (isLauncherPackage(rootPackage)) {
                launcherWindowVisible = true
                if (launcherWindowPackage == null) {
                    launcherWindowPackage = rootPackage
                }
            }

            if (isRecentsClassName(rootClass)) {
                recentsWindowVisible = true
                if (recentsWindowClass.isEmpty()) {
                    recentsWindowClass = rootClass
                }
            }
        }

        if (launcherWindowVisible || activeIsLauncher) {
            lastLauncherWindowSeenAt = now
        }

        val isRecents = recentsWindowVisible || isRecentsClassName(activeClassName)

        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            val recentsClass = if (recentsWindowClass.isNotEmpty()) recentsWindowClass else activeClassName
            Log.d(TAG, "Recents state (windows) changed: $isRecents (class=$recentsClass)")
            overlay?.setRecentsState(isRecents)
        }

        if (activePackage == this.packageName) return

        val launcherRecentlyVisible =
            launcherWindowVisible || activeIsLauncher || now - lastLauncherWindowSeenAt < HOME_WINDOW_GRACE_MS
        val isHomeOverlayActive = activePackage.isBlank() ||
            isSystemUiActive ||
            activeIsLauncher ||
            isHomeOverlayPackage(activePackage)
        val homeEntryGraceActive = now < homeEnterGraceUntil
        val keepHomeForOverlay = isOnHomeScreen && isHomeOverlayActive && !isRecents
        val newOnHomeScreen =
            ((launcherRecentlyVisible || homeEntryGraceActive) && isHomeOverlayActive && !isRecents) ||
                keepHomeForOverlay
        if (newOnHomeScreen && now < homeExitGraceUntil && !isLauncherPackage(currentPackage)) {
            return
        }

        val previousPackage = currentPackage
        val trackingPackage = if (isHomeOverlayActive) {
            launcherWindowPackage ?: activePackage
        } else {
            activePackage
        }
        val trackingPackageValid =
            trackingPackage.isNotBlank() && !isSystemUiActive && trackingPackage != this.packageName
        if (trackingPackageValid && currentPackage != trackingPackage) {
            currentPackage = trackingPackage
            Log.d(TAG, "Foreground app (windows) changed: $currentPackage")
        } else if (!trackingPackageValid) {
            launcherWindowPackage?.let { pkg ->
                if (currentPackage != pkg) {
                    currentPackage = pkg
                    Log.d(TAG, "Foreground app (windows) changed: $currentPackage")
                }
            }
        }

        if (!newOnHomeScreen && isOnHomeScreen && previousPackage != currentPackage) {
            // 앱이 변경된 경우 즉시 홈화면 상태 업데이트
            isOnHomeScreen = false
            homeExitGraceUntil = now + 400
            Log.d(TAG, "Home screen state (windows) changed: false (app changed to $currentPackage)")
            overlay?.setHomeScreenState(false, immediate = true)
            return
        }

        if (isOnHomeScreen != newOnHomeScreen) {
            if (!newOnHomeScreen && isOnHomeScreen && now < homeEnterGraceUntil) {
                val keepHome =
                    (lastHomeActionPackage.isNotEmpty() && activePackage == lastHomeActionPackage) ||
                        isHomeOverlayActive
                if (keepHome) return
            }
            isOnHomeScreen = newOnHomeScreen
            if (newOnHomeScreen) {
                stopHomePolling()
            }
            if (!newOnHomeScreen) {
                homeExitGraceUntil = now + 400  // 900ms -> 400ms
            } else if (now > homeEnterGraceUntil) {
                homeEnterGraceUntil = 0
            }
            Log.d(TAG, "Home screen state (windows) changed: $isOnHomeScreen (pkg=$activePackage)")
            // 모든 상태 변경에서 immediate 사용
            overlay?.setHomeScreenState(isOnHomeScreen, immediate = true)
        }

    }

    /**
     * "기본 네비게이션 바가 실제로 화면 하단을 차지하고 있는지"를 bounds 기반으로 판정.
     * - 네비바 높이(대략) 이상으로 하단을 점유하면: 시스템 네비바가 보이는 상태
     * - 하단 점유가 매우 작으면(제스처 pill 수준): 사실상 숨김(=fullscreen로 취급)
     */
    private fun checkFullscreenState() {
        val screen = getScreenBounds()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // orientation 변경 시 nav bar 높이 재계산
        calculateNavBarHeight()

        val navVisibleThreshold = maxOf((navBarHeightPx * 0.7f).toInt(), dpToPx(40f))
        val gestureOnlyThreshold = dpToPx(24f)
        var bottomSystemUiHeight = 0
        var systemUiWindowSeen = false

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val rootPkg = w.root?.packageName?.toString()
            if (rootPkg != "com.android.systemui") continue
            systemUiWindowSeen = true

            val r = Rect()
            try {
                w.getBoundsInScreen(r)
            } catch (e: Exception) {
                continue
            }

            val touchesBottom = (r.bottom >= screen.bottom - 2)
            // 세로/가로 화면에 따라 네비바 너비 기준 조정
            val minWidthRatio = if (isPortrait) 0.3f else 0.5f
            val wideEnough = r.width() >= (screen.width() * minWidthRatio)
            if (touchesBottom && wideEnough) {
                bottomSystemUiHeight = maxOf(bottomSystemUiHeight, r.height())
            }
        }

        // API 30+ 에서 WindowInsets 기반 감지도 병행
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val navInsetInfo = getSystemNavInsetInfo()
            if (navInsetInfo != null) {
                val (navInsetSize, navInsetVisible) = navInsetInfo
                // WindowInsets 결과를 우선 사용
                val navBarVisible = navInsetVisible && navInsetSize >= navVisibleThreshold
                val navBarHiddenOrGestureOnly = !navInsetVisible || navInsetSize <= gestureOnlyThreshold
                val newFullscreenState = navBarHiddenOrGestureOnly || !navBarVisible
                isSystemNavVisible = navInsetVisible && navInsetSize > gestureOnlyThreshold

                if (isFullscreen != newFullscreenState) {
                    isFullscreen = newFullscreenState
                    Log.d(
                        TAG,
                        "Fullscreen state updated (insets): fullscreen=$isFullscreen, navInsetSize=$navInsetSize, portrait=$isPortrait, pkg=$currentPackage"
                    )
                    if (isFullscreen) startFullscreenPolling() else stopFullscreenPolling()
                    updateOverlayVisibility()
                } else if (isFullscreen) {
                    startFullscreenPolling()
                }
                return
            }
        }

        // fallback: window bounds 기반 감지
        if (systemUiWindowSeen || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val navBarVisible = bottomSystemUiHeight >= navVisibleThreshold
            val navBarHiddenOrGestureOnly = bottomSystemUiHeight <= gestureOnlyThreshold
            val newFullscreenState = navBarHiddenOrGestureOnly || !navBarVisible

            isSystemNavVisible = bottomSystemUiHeight > gestureOnlyThreshold

            if (isFullscreen != newFullscreenState) {
                isFullscreen = newFullscreenState
                Log.d(
                    TAG,
                    "Fullscreen state updated (bounds): fullscreen=$isFullscreen, bottomSystemUiHeight=$bottomSystemUiHeight, portrait=$isPortrait, pkg=$currentPackage"
                )

                if (isFullscreen) startFullscreenPolling() else stopFullscreenPolling()
                updateOverlayVisibility()
            } else {
                if (isFullscreen) startFullscreenPolling()
            }
        }
    }

    private fun getScreenBounds(): Rect {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun getSystemNavInsetInfo(): Pair<Int, Boolean>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val insets = wm.currentWindowMetrics.windowInsets
        val navInsets = insets.getInsets(WindowInsets.Type.navigationBars())
        val size = maxOf(navInsets.bottom, navInsets.left, navInsets.right, navInsets.top)
        val visible = insets.isVisible(WindowInsets.Type.navigationBars())
        return size to visible
    }

    private fun startFullscreenPolling() {
        if (fullscreenPoll != null) return

        fullscreenPoll = object : Runnable {
            override fun run() {
                val before = isFullscreen
                checkFullscreenState()
                if (isFullscreen) {
                    handler.postDelayed(this, 300)
                } else {
                    stopFullscreenPolling()
                    // fullscreen 해제 직후 overlay show가 씹히는 경우 대비
                    if (before) handler.postDelayed({ updateOverlayVisibility() }, 100)
                }
            }
        }
        handler.postDelayed(fullscreenPoll!!, 300)
    }

    private fun stopFullscreenPolling() {
        fullscreenPoll?.let { handler.removeCallbacks(it) }
        fullscreenPoll = null
    }

    private fun startImePolling() {
        if (imePoll != null) return
        imePoll = object : Runnable {
            override fun run() {
                checkImeVisibility()
                val now = SystemClock.elapsedRealtime()
                if (isImeVisible || imeFocusActive || now < imePendingUntil) {
                    handler.postDelayed(this, 100)
                } else {
                    stopImePolling()
                }
            }
        }
        handler.postDelayed(imePoll!!, 100)
    }

    private fun stopImePolling() {
        imePoll?.let { handler.removeCallbacks(it) }
        imePoll = null
    }

    private fun startHomePolling(durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        homePollUntil = maxOf(homePollUntil, now + durationMs)
        if (homePoll != null) return
        homePoll = object : Runnable {
            override fun run() {
                updateHomeAndRecentsFromWindows()
                val now = SystemClock.elapsedRealtime()
                if (now < homePollUntil && !isOnHomeScreen) {
                    handler.postDelayed(this, 200)
                } else {
                    stopHomePolling()
                }
            }
        }
        handler.postDelayed(homePoll!!, 200)
    }

    private fun stopHomePolling() {
        homePoll?.let { handler.removeCallbacks(it) }
        homePoll = null
        homePollUntil = 0
    }

    private fun checkNotificationPanelState() {
        val screen = getScreenBounds()
        val minPanelHeight = dpToPx(100f)
        var panelVisible = false

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val rootPkg = w.root?.packageName?.toString()
            if (rootPkg != "com.android.systemui") continue

            val r = Rect()
            try {
                w.getBoundsInScreen(r)
            } catch (e: Exception) {
                continue
            }

            val wideEnough = r.width() >= (screen.width() * 0.5f)
            val touchesTop = r.top <= screen.top + 2
            val tallEnough = r.height() >= minPanelHeight

            if (wideEnough && touchesTop && tallEnough) {
                panelVisible = true
                break
            }
        }

        if (isNotificationPanelOpen != panelVisible) {
            isNotificationPanelOpen = panelVisible
            Log.d(TAG, "Notification panel state changed: $isNotificationPanelOpen")
            handler.post {
                overlay?.updatePanelButtonState(isNotificationPanelOpen)
            }
        }
    }

    private fun updateOverlayVisibility(
        forceFade: Boolean = false,
        forceAnimation: Boolean = false,
        forceUnlock: Boolean = false
    ) {
        // unlockPending이 true이고 forceUnlock이 아니면 show를 건너뜀 (잠금해제 시 페이드만 적용하기 위해)
        if (unlockPending && !forceUnlock) {
            Log.d(TAG, "Update visibility skipped: unlockPending=true, waiting for USER_PRESENT")
            return
        }

        val lockScreenActive = isLockScreenActive()
        val shouldHide = shouldHideOverlay(lockScreenActive)
        Log.d(
            TAG,
            "Update visibility: shouldHide=$shouldHide, lockscreen=$lockScreenActive, package=$currentPackage, fullscreen=$isFullscreen, forceFade=$forceFade, forceUnlock=$forceUnlock"
        )
        if (shouldHide) {
            overlay?.hide(animate = !lockScreenActive, showHotspot = !lockScreenActive)
        } else {
            overlay?.show(fade = forceFade, forceAnimation = forceAnimation)
            updateNavBarStyle()
        }
    }

    private fun updateNavBarStyle() {
        if (overlay == null) return
        if (isWallpaperPreviewVisible) return
        if (isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible) return
        if (shouldHideOverlay(isLockScreenActive())) return
        updateDefaultNavStyleFromSystem()
    }

    private fun logImeDebug(
        now: Long,
        imeWindowPresent: Boolean,
        imeWindowOpen: Boolean,
        maxImeHeight: Int,
        maxImeBottom: Int,
        nextImeVisible: Boolean,
        acceptingText: Boolean,
        pendingIme: Boolean,
        debugTrigger: Boolean
    ) {
        if (!IME_DEBUG_LOG) return
        if (debugTrigger) {
            imeDebugUntil = now + IME_DEBUG_WINDOW_MS
            imeDebugLastHeight = -1
            imeDebugLastBottom = -1
            imeDebugLastWindowOpen = !imeWindowOpen
            imeDebugLastVisible = !nextImeVisible
            imeDebugLastWindowPresent = !imeWindowPresent
        }
        if (imeDebugUntil == 0L || now > imeDebugUntil) return
        val changed = imeWindowPresent != imeDebugLastWindowPresent ||
            imeWindowOpen != imeDebugLastWindowOpen ||
            maxImeHeight != imeDebugLastHeight ||
            maxImeBottom != imeDebugLastBottom ||
            nextImeVisible != imeDebugLastVisible ||
            nextImeVisible != isImeVisible
        if (!changed) return
        Log.d(
            TAG,
            "IME debug: present=$imeWindowPresent open=$imeWindowOpen height=$maxImeHeight bottom=$maxImeBottom prevVisible=$isImeVisible nextVisible=$nextImeVisible focus=$imeFocusActive accepting=$acceptingText pending=$pendingIme"
        )
        imeDebugLastWindowPresent = imeWindowPresent
        imeDebugLastWindowOpen = imeWindowOpen
        imeDebugLastHeight = maxImeHeight
        imeDebugLastBottom = maxImeBottom
        imeDebugLastVisible = nextImeVisible
    }

    private fun checkImeVisibility() {
        val screen = getScreenBounds()
        val minImeHeight = dpToPx(24f)
        val openingThreshold = dpToPx(6f)
        val closingThreshold = dpToPx(6f)
        val bottomSlack = maxOf(navBarHeightPx, dpToPx(8f))
        val bottomThreshold = screen.bottom - bottomSlack
        val now = SystemClock.elapsedRealtime()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val acceptingText = imm.isAcceptingText
        val acceptingTextStarted = acceptingText && !wasAcceptingText
        val acceptingTextEnded = !acceptingText && wasAcceptingText
        var pendingIme = now < imePendingUntil
        var imeVisible = false
        var imeWindowPresent = false
        var imeWindowOpen = false
        var maxImeHeight = 0
        var maxImeBottom = 0
        val imePackage = getCurrentImePackageName()

        if (acceptingTextStarted) {
            lastImeEventAt = now
            imePendingUntil = now + 700
        } else if (acceptingTextEnded) {
            imePendingUntil = 0
        }

        for (w in windows) {
            val rootPackage = w.root?.packageName?.toString()
            val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                w.title?.toString()
            } else {
                null
            }
            val isImePackageHint =
                rootPackage?.contains("inputmethod", ignoreCase = true) == true ||
                    rootPackage?.contains("keyboard", ignoreCase = true) == true
            val isImeTitleHint =
                title?.contains("gboard", ignoreCase = true) == true ||
                    title?.contains("keyboard", ignoreCase = true) == true ||
                    title?.contains("input", ignoreCase = true) == true
            val isImeWindow =
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (imePackage != null && rootPackage == imePackage) ||
                    isImePackageHint ||
                    isImeTitleHint
            if (!isImeWindow) continue
            val r = Rect()
            try {
                w.getBoundsInScreen(r)
            } catch (e: Exception) {
                continue
            }

            val height = r.height()
            if (height <= 0) continue
            imeWindowPresent = true
            if (height > maxImeHeight) {
                maxImeHeight = height
                maxImeBottom = r.bottom
            }

        }

        if (imeWindowPresent) {
            val tallIme = maxImeHeight >= dpToPx(96f)
            imeWindowOpen = maxImeHeight >= minImeHeight &&
                (maxImeBottom >= bottomThreshold || tallIme)
        }

        var openingStart = false
        var closingStart = false
        if (imeWindowPresent) {
            val recentImeEvent = now - lastImeEventAt < 350
            val heightDelta = lastImeWindowHeight - maxImeHeight
            val openingDelta = maxImeHeight - lastImeWindowHeight
            closingStart = isImeVisible && heightDelta > closingThreshold
            openingStart = !isImeVisible && openingDelta > openingThreshold

            if (closingStart) {
                imeVisible = false
                lastImeWindowSeenAt = 0
                imePendingUntil = 0
                pendingIme = false
            } else if (imeWindowOpen || openingStart || pendingIme) {
                imeVisible = true
                lastImeEventAt = now
                lastImeWindowSeenAt = now
            } else if (imeFocusActive && acceptingText && recentImeEvent) {
                imeVisible = true
            } else if (isImeVisible && !pendingIme) {
                imeVisible = false
                lastImeWindowSeenAt = 0
            }
        } else {
            val recentWindow = now - lastImeWindowSeenAt < 150
            val recentImeEvent = now - lastImeEventAt < 350
            if (pendingIme || recentWindow) {
                imeVisible = true
            } else if (imeFocusActive && acceptingText && recentImeEvent) {
                imeVisible = true
            }
        }

        if (acceptingTextEnded) {
            imeVisible = false
            lastImeWindowSeenAt = 0
        }

        if (!imeVisible && !acceptingText) {
            imePendingUntil = 0
        }

        pendingIme = now < imePendingUntil
        val debugTrigger = acceptingTextStarted || acceptingTextEnded || openingStart || closingStart
        logImeDebug(
            now,
            imeWindowPresent,
            imeWindowOpen,
            maxImeHeight,
            maxImeBottom,
            imeVisible,
            acceptingText,
            pendingIme,
            debugTrigger
        )

        updateImeVisible(imeVisible)
        if (imeWindowPresent) {
            lastImeWindowHeight = maxImeHeight
            lastImeWindowBottom = maxImeBottom
        } else {
            lastImeWindowHeight = 0
            lastImeWindowBottom = 0
        }
        wasAcceptingText = acceptingText
    }

    private fun updateImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        Log.d(TAG, "IME visibility changed: $isImeVisible")
        startImePolling()
        handler.post {
            overlay?.setImeVisible(isImeVisible)
        }
    }

    private fun isTextInputClass(className: CharSequence?): Boolean {
        val name = className?.toString() ?: return false
        return name.endsWith("EditText") || name.contains("TextInput", ignoreCase = true)
    }

    private fun getCurrentImePackageName(): String? {
        val imeId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return imeId?.substringBefore('/')
    }

    private fun shouldHideOverlay(lockScreenActive: Boolean): Boolean {
        if (lockScreenActive) return true
        if (isWallpaperPreviewVisible) return true

        // 1) 전체화면이면 숨김 (영상/게임/사진뷰어 등)
        // 단, 런처는 예외
        // + currentPackage가 아직 비어있을 때는 성급히 숨기지 않음
        if (settings.autoHideOnVideo && isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) {
            if (!isSystemNavVisible) return true
        }

        // 2) 앱별 숨김 설정
        if (currentPackage.isNotEmpty() && settings.shouldHideForPackage(currentPackage)) return true

        return false
    }

    private fun isLockScreenActive(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
        } else {
            keyguardManager.isKeyguardLocked
        }
    }

    private fun createOverlay() {
        if (overlay == null) {
            overlay = NavBarOverlay(this)
            overlay?.create()
            overlay?.updatePanelButtonState(isOpen = false)
        }
    }

    private fun destroyOverlay() {
        overlay?.destroy()
        overlay = null
    }

    fun executeAction(action: NavAction): Boolean {
        if (action == NavAction.NOTIFICATIONS) {
            return if (isNotificationPanelOpen) {
                Log.d(TAG, "Closing notification panel")
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                Log.d(TAG, "Opening notification panel")
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
        }

        if (action == NavAction.HOME) {
            val now = SystemClock.elapsedRealtime()
            homeExitGraceUntil = 0
            startHomePolling(1500)  // 2000ms -> 1500ms
            homeEnterGraceUntil = now + 600  // 1200ms -> 600ms
            lastHomeActionAt = now
            lastHomeActionPackage = currentPackage
            if (!isOnHomeScreen || isRecentsVisible) {
                isOnHomeScreen = true
                isRecentsVisible = false
                // 즉시 배경 전환
                overlay?.setRecentsState(false)
                overlay?.setHomeScreenState(true, immediate = true)
            } else {
                // 이미 홈화면인 경우도 배경 강제 갱신
                overlay?.setHomeScreenState(true, immediate = true)
            }
        }

        if (action == NavAction.ASSIST) {
            try {
                val customActionPackage = settings.longPressAction
                if (customActionPackage == null) {
                    val intent = Intent(Intent.ACTION_ASSIST).apply {
                        setPackage("com.google.android.googlequicksearchbox")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    Log.d(TAG, "Executing default long-press action: Google Assistant")
                } else {
                    val intent = packageManager.getLaunchIntentForPackage(customActionPackage)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        Log.d(TAG, "Executing custom long-press action: $customActionPackage")
                    } else {
                        Log.w(TAG, "Could not launch custom app: $customActionPackage. Intent is null.")
                        return false
                    }
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute ASSIST action", e)
                return false
            }
        }

        val actionId = action.globalActionId ?: return false
        Log.d(TAG, "Executing action: ${action.name} (id=$actionId)")
        return performGlobalAction(actionId)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
}
