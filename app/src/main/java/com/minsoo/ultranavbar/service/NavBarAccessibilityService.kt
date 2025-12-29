package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
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
    
    // Status/Navigation bars height to verify actual fullscreen
    private var systemBarsHeight = 0

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.minsoo.ultranavbar.SETTINGS_CHANGED" -> {
                    Log.d(TAG, "Settings changed, refreshing overlay")
                    overlay?.refreshSettings()
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
                    // 약간의 딜레이를 주어 화면이 완전히 켜진 후 표시
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
        
        // Calculate approximate system bars height for basic validation
        calculateSystemBarsHeight()

        Log.i(TAG, "Overlay created and service fully initialized")
    }
    
    private fun calculateSystemBarsHeight() {
        val resources = resources
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        // Approx navigation bar
        val navResourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navBarHeight = if (navResourceId > 0) resources.getDimensionPixelSize(navResourceId) else 0
        systemBarsHeight = statusBarHeight + navBarHeight
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
            // resolveActivity는 주어진 인텐트를 처리할 최적의 액티비티 하나를 찾아줌 (기본 런처)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName != null) {
                launcherPackages = setOf(resolveInfo.activityInfo.packageName)
            } else {
                // resolveActivity가 실패할 경우, 이전 방식으로 모든 런처를 찾되, 알려진 오류(설정 앱)는 제외
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // WallpaperPreviewActivity가 전면에 나타나면, 다른 로직을 모두 무시하고 네비바를 즉시 숨김
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className == "com.minsoo.ultranavbar.ui.WallpaperPreviewActivity"
        ) {
            overlay?.hide(animate = false)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                checkFullscreenState()
                checkNotificationPanelState()
            }
        }
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
            // handleWindowStateChanged에서는 isFullscreen 상태를 직접 바꾸지 않으므로,
            // 여기서는 updateOverlayVisibility()를 호출할 필요가 없음. checkFullscreenState()에서 처리.
        }
    }

    /**
     * 접근성 창 목록을 분석하여 실제 전체화면 상태를 유추
     */
    private fun checkFullscreenState() {
        // 시스템 네비게이션 바 창('탐색 메뉴')이 보이는지 확인
        val systemNavBarWindow = windows.find {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.title?.contains("탐색") == true
        }
        
        // 네비게이션 바 창이 보이지 않으면 전체화면으로 간주
        val newFullscreenState = systemNavBarWindow == null

        if (isFullscreen != newFullscreenState) {
            isFullscreen = newFullscreenState
            Log.d(TAG, "Fullscreen state updated by window detection: $isFullscreen")
            updateOverlayVisibility()
        }
    }

    private fun checkNotificationPanelState() {
        // TYPE_SYSTEM 윈도우 중, 'com.android.systemui' 패키지에 속하고, 포커스를 받을 수 있는 창을 알림 패널로 간주
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
        // 1. 전체화면 모드면 숨김 (유튜브 영상, 게임, 사진 등)
        // 단, 런처(홈화면)는 전체화면으로 인식되더라도(일부 런처 특성) 숨기지 않음
        // ** 추가: currentPackage가 파악되기 전(앱 시작 직후)에는 성급하게 숨기지 않도록 함
        if (settings.autoHideOnVideo && isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) return true

        // 2. 앱별 설정에 따라 숨김
        if (currentPackage.isNotEmpty() && settings.shouldHideForPackage(currentPackage)) return true
        
        return false
    }

    private fun createOverlay() {
        if (overlay == null) {
            overlay = NavBarOverlay(this)
            overlay?.create()
            // 버튼 초기 상태를 '패널 내리기' (0도)로 강제 설정
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
                    // 기본 동작: Google Assistant 실행
                    val intent = Intent(Intent.ACTION_ASSIST).apply {
                        setPackage("com.google.android.googlequicksearchbox")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    Log.d(TAG, "Executing default long-press action: Google Assistant")
                } else {
                    // 커스텀 앱 실행
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