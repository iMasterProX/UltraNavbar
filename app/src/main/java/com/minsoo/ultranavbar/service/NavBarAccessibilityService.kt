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
    private var lastImeEventAt: Long = 0
    private var imeFocusActive: Boolean = false

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
                    overlay?.hide(animate = false, showHotspot = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on, updating overlay visibility.")
                    updateOverlayVisibility(forceFade = false)
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
        if (!DeviceProfile.isTablet(this)) {
            Log.w(TAG, "Device is not a tablet. Disabling service.")
            disableSelf()
            return
        }
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
        overlay?.updateDarkMode()  // 다크 모드 변경 감지

        // 회전 시 기준값 재계산
        calculateNavBarHeight()
        scheduleStateCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val focusedIsTextInput = isTextInputClass(event.className)
            if (focusedIsTextInput) {
                imeFocusActive = true
                lastImeEventAt = now
                updateImeVisible(true)
            } else if (imeFocusActive) {
                imeFocusActive = false
                scheduleStateCheck()
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
                scheduleStateCheck()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
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
        }
        handler.postDelayed(pendingStateCheck!!, 50)
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val isSystemUi = packageName == "com.android.systemui"
        val isRecents = isRecentsClassName(className)

        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            Log.d(TAG, "Recents state changed: $isRecents (class=$className)")
            overlay?.setRecentsState(isRecents)
        }

        if (packageName == this.packageName) return

        if (isSystemUi) {
            if (isRecents && isOnHomeScreen) {
                isOnHomeScreen = false
                overlay?.setHomeScreenState(false)
            }
            return
        }

        if (currentPackage != packageName) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app changed: $packageName")
        }

        val newOnHomeScreen = launcherPackages.contains(packageName) && !isRecents
        if (isOnHomeScreen != newOnHomeScreen) {
            isOnHomeScreen = newOnHomeScreen
            Log.d(TAG, "Home screen state changed: $isOnHomeScreen")
            overlay?.setHomeScreenState(isOnHomeScreen)
        }
    }

    private fun isRecentsClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Recents", ignoreCase = true) ||
            simpleName.contains("Overview", ignoreCase = true) ||
            simpleName.contains("TaskSwitcher", ignoreCase = true)
    }

    private fun updateHomeAndRecentsFromWindows() {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        val className = root.className?.toString() ?: ""
        val isRecents = isRecentsClassName(className)

        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            Log.d(TAG, "Recents state (windows) changed: $isRecents (class=$className)")
            overlay?.setRecentsState(isRecents)
        }

        if (packageName == this.packageName) return

        if (packageName == "com.android.systemui") {
            if (isRecents && isOnHomeScreen) {
                isOnHomeScreen = false
                overlay?.setHomeScreenState(false)
            }
            return
        }

        if (currentPackage != packageName) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app (windows) changed: $packageName")
        }

        val newOnHomeScreen = launcherPackages.contains(packageName) && !isRecents
        if (isOnHomeScreen != newOnHomeScreen) {
            isOnHomeScreen = newOnHomeScreen
            Log.d(TAG, "Home screen state (windows) changed: $isOnHomeScreen")
            overlay?.setHomeScreenState(isOnHomeScreen)
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

    private fun updateOverlayVisibility(forceFade: Boolean = false) {
        val lockScreenActive = isLockScreenActive()
        val shouldHide = shouldHideOverlay(lockScreenActive)
        Log.d(
            TAG,
            "Update visibility: shouldHide=$shouldHide, lockscreen=$lockScreenActive, package=$currentPackage, fullscreen=$isFullscreen"
        )
        if (shouldHide) {
            // 제스처로 보여준 직후에는 자동 숨김 방지 (잠금화면은 예외)
            if (!lockScreenActive && overlay?.canAutoHide() == false) {
                Log.d(TAG, "Auto-hide blocked: recently shown by gesture")
                return
            }
            overlay?.hide(animate = !lockScreenActive, showHotspot = !lockScreenActive)
        } else {
            overlay?.show(fade = forceFade)
        }
    }

    private fun checkImeVisibility() {
        val screen = getScreenBounds()
        val minImeHeight = dpToPx(80f)
        val now = SystemClock.elapsedRealtime()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var imeVisible = false
        val imePackage = getCurrentImePackageName()

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

            val tallEnough = r.height() >= minImeHeight
            val bottomThreshold = screen.bottom - maxOf(navBarHeightPx, dpToPx(24f))
            val touchesBottom = r.bottom >= bottomThreshold
            if (tallEnough && touchesBottom) {
                imeVisible = true
                lastImeEventAt = now
                break
            }
        }

        if (!imeVisible && imm.isAcceptingText && (imeFocusActive || now - lastImeEventAt < 1000)) {
            imeVisible = true
        }

        updateImeVisible(imeVisible)
    }

    private fun updateImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        Log.d(TAG, "IME visibility changed: $isImeVisible")
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
        if (settings.autoHideOnVideo && isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) return true

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
