package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.overlay.NavBarOverlay
import com.minsoo.ultranavbar.settings.SettingsManager

class NavBarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavBarAccessibility"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "UltraNavBarServiceChannel"

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
    private var isNotificationPanelOpen: Boolean = false

    private var launcherPackages: Set<String> = emptySet()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // approximate navigation bar height (px)
    private var navBarHeightPx = 0

    // debounce
    private var pendingStateCheck: Runnable? = null

    // fullscreen polling
    private var fullscreenPoll: Runnable? = null

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
                    Log.d(TAG, "Screen off, hiding overlay.")
                    overlay?.hide(animate = false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present, showing with fade animation.")
                    handler.postDelayed({
                        updateOverlayVisibility(forceFade = true)
                    }, 200)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        instance = this
        settings = SettingsManager.getInstance(this)

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

        val settingsFilter = IntentFilter().apply {
            addAction("com.minsoo.ultranavbar.SETTINGS_CHANGED")
            addAction("com.minsoo.ultranavbar.RELOAD_BACKGROUND")
        }
        registerReceiver(settingsReceiver, settingsFilter, RECEIVER_NOT_EXPORTED)

        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)

        calculateNavBarHeight()
        Log.i(TAG, "Overlay created and service fully initialized")
    }

    private fun calculateNavBarHeight() {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        navBarHeightPx = if (resId > 0) resources.getDimensionPixelSize(resId) else dpToPx(48f)
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
            .setContentTitle("LG UltraTab Extension")
            .setContentText("Custom Navigation Bar Running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Service started in foreground.")
    }

    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName != null) {
                launcherPackages = setOf(resolveInfo.activityInfo.packageName)
            } else {
                val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                launcherPackages = resolveInfos.map { it.activityInfo.packageName }
                    .filter { it != "com.android.settings" }
                    .toSet()
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
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        overlay?.handleOrientationChange(newConfig.orientation)

        // 회전 시 기준값 재계산
        calculateNavBarHeight()
        scheduleStateCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // WallpaperPreviewActivity가 전면에 나타나면 네비바를 즉시 숨김 (기존 로직 유지)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className == "com.minsoo.ultranavbar.ui.WallpaperPreviewActivity"
        ) {
            overlay?.hide(animate = false)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                scheduleStateCheck()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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
        }
        handler.postDelayed(pendingStateCheck!!, 50)
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName || packageName == "com.android.systemui") return

        if (currentPackage != packageName) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app changed: $packageName")

            val wasOnHomeScreen = isOnHomeScreen
            isOnHomeScreen = launcherPackages.contains(packageName)
            if (wasOnHomeScreen != isOnHomeScreen) {
                Log.d(TAG, "Home screen state changed: $isOnHomeScreen")
                overlay?.setHomeScreenState(isOnHomeScreen)
            }
        }
    }

    /**
     * "기본 네비게이션 바가 실제로 화면 하단을 차지하고 있는지"를 bounds 기반으로 판정.
     * - 네비바 높이(대략) 이상으로 하단을 점유하면: 시스템 네비바가 보이는 상태
     * - 하단 점유가 매우 작으면(제스처 pill 수준): 사실상 숨김(=fullscreen로 취급)
     */
    private fun checkFullscreenState() {
        val screen = getScreenBounds()

        var bottomSystemUiHeight = 0

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

            val touchesBottom = (r.bottom >= screen.bottom - 2)
            val wideEnough = r.width() >= (screen.width() * 0.5f)
            if (touchesBottom && wideEnough) {
                bottomSystemUiHeight = maxOf(bottomSystemUiHeight, r.height())
            }
        }

        // "네비바가 보임" 기준: navBarHeight의 70% 이상, 또는 40dp 이상
        val navVisibleThreshold = maxOf((navBarHeightPx * 0.7f).toInt(), dpToPx(40f))
        val gestureOnlyThreshold = dpToPx(24f)

        val navBarVisible = bottomSystemUiHeight >= navVisibleThreshold
        val navBarHiddenOrGestureOnly = bottomSystemUiHeight <= gestureOnlyThreshold

        // 목적: "기본 네비바가 숨겨진 상태"면 커스텀 네비바도 숨김
        val newFullscreenState = navBarHiddenOrGestureOnly || !navBarVisible

        if (isFullscreen != newFullscreenState) {
            isFullscreen = newFullscreenState
            Log.d(
                TAG,
                "Fullscreen state updated: fullscreen=$isFullscreen, bottomSystemUiHeight=$bottomSystemUiHeight, pkg=$currentPackage"
            )

            if (isFullscreen) startFullscreenPolling() else stopFullscreenPolling()
            updateOverlayVisibility()
        } else {
            if (isFullscreen) startFullscreenPolling()
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

    private fun checkNotificationPanelState() {
        val panelWindow = windows.find {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    it.root?.packageName == "com.android.systemui" &&
                    it.root?.isFocusable == true
        }
        val panelVisible = panelWindow != null

        if (isNotificationPanelOpen != panelVisible) {
            isNotificationPanelOpen = panelVisible
            Log.d(TAG, "Notification panel state changed: $isNotificationPanelOpen")
            handler.post {
                overlay?.updatePanelButtonState(isNotificationPanelOpen)
            }
        }
    }

    private fun updateOverlayVisibility(forceFade: Boolean = false) {
        val shouldHide = shouldHideOverlay()
        Log.d(TAG, "Update visibility: shouldHide=$shouldHide, package=$currentPackage, fullscreen=$isFullscreen")
        if (shouldHide) {
            overlay?.hide()
        } else {
            overlay?.show(fade = forceFade)
        }
    }

    private fun shouldHideOverlay(): Boolean {
        // 1) 전체화면이면 숨김 (영상/게임/사진뷰어 등)
        // 단, 런처는 예외
        // + currentPackage가 아직 비어있을 때는 성급히 숨기지 않음
        if (settings.autoHideOnVideo && isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) return true

        // 2) 앱별 숨김 설정
        if (currentPackage.isNotEmpty() && settings.shouldHideForPackage(currentPackage)) return true

        return false
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
