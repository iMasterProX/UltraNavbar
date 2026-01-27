package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.core.WindowAnalyzer
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.overlay.NavBarOverlay
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.DeviceProfile

/**
 * 네비게이션 바 접근성 서비스
 *
 * 리팩토링된 구조:
 * - WindowAnalyzer: 윈도우 상태 분석 (전체화면, IME, 알림 패널 등)
 * - NavBarOverlay: 오버레이 표시/숨김
 */
class NavBarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavBarAccessibility"
        private const val HOME_ENTRY_STABILIZE_MS = 400L  // 홈 진입 후 안정화 시간
        private const val HOME_EXIT_STABILIZE_MS = 500L   // 홈 이탈 후 안정화 시간 (windows 소스의 잘못된 재진입 방지)

        @Volatile
        var instance: NavBarAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    // === 컴포넌트 ===
    private var overlay: NavBarOverlay? = null
    private lateinit var settings: SettingsManager
    private lateinit var windowAnalyzer: WindowAnalyzer
    private lateinit var keyEventHandler: KeyEventHandler

    private val handler = Handler(Looper.getMainLooper())

    // === 상태 ===
    private var currentPackage: String = ""
    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var isFullscreen: Boolean = false
    private var isOnHomeScreen: Boolean = false
    private var isRecentsVisible: Boolean = false
    private var isAppDrawerOpen: Boolean = false
    private var isNotificationPanelOpen: Boolean = false
    private var isQuickSettingsOpen: Boolean = false
    private var isWallpaperPreviewVisible: Boolean = false
    private var isImeVisible: Boolean = false
    private var lastImeEventAt: Long = 0
    private var lastNonLauncherEventAt: Long = 0
    private var lastHomeEntryAt: Long = 0  // 홈 진입 안정화용
    private var lastHomeExitAt: Long = 0   // 홈 이탈 안정화용

    // === 디바운스/폴링 ===
    private var pendingStateCheck: Runnable? = null
    private var fullscreenPoll: Runnable? = null
    private var unlockFadeRunnable: Runnable? = null

    // === 브로드캐스트 리시버 ===
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.Action.SETTINGS_CHANGED -> {
                    Log.d(TAG, "Settings changed, refreshing overlay")
                    overlay?.refreshSettings()
                    updateOverlayVisibility(forceFade = true)
                }
                Constants.Action.RELOAD_BACKGROUND -> {
                    Log.d(TAG, "Reloading background images")
                    overlay?.reloadBackgroundImages()
                }
                Constants.Action.UPDATE_BUTTON_COLORS -> {
                    Log.d(TAG, "Updating background/button colors")
                    overlay?.refreshBackgroundStyle()
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, hiding overlay")
                    overlay?.captureUnlockBackgroundForLock()
                    overlay?.resetUnlockFadeState(clearOverride = false)
                    overlay?.hide(animate = false, showHotspot = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on, updating visibility")
                    // 잠금화면이 활성화된 상태에서 화면이 켜지면 해제 시 페이드 애니메이션 준비
                    if (windowAnalyzer.isLockScreenActive()) {
                        overlay?.prepareForUnlockFade()
                    }
                    updateOverlayVisibility(forceFade = false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present, showing with fade")
                    // 안전을 위해 여기서도 플래그 설정
                    overlay?.prepareForUnlockFade()
                    
                    // 기존 예약 취소
                    unlockFadeRunnable?.let { handler.removeCallbacks(it) }
                    
                    // 새 작업 예약
                    unlockFadeRunnable = Runnable {
                        unlockFadeRunnable = null
                        overlay?.startUnlockFade()
                    }
                    handler.postDelayed(unlockFadeRunnable!!, Constants.Timing.UNLOCK_FADE_DELAY_MS)
                }
            }
        }
    }

    // ===== 서비스 생명주기 =====

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!DeviceProfile.isTablet(this)) {
            Log.w(TAG, "Device is not a tablet. Disabling service.")
            disableSelf()
            return
        }

        Log.i(TAG, "Service connected")
        instance = this

        initializeComponents()
        setupServiceInfo()
        registerReceivers()

        windowAnalyzer.loadLauncherPackages()
        windowAnalyzer.calculateNavBarHeight()
        currentOrientation = resources.configuration.orientation

        createOverlay()
        updateOverlayVisibility(forceFade = false)
        checkImeVisibility()

        Log.i(TAG, "Service fully initialized")
    }

    private fun initializeComponents() {
        settings = SettingsManager.getInstance(this)
        windowAnalyzer = WindowAnalyzer(this)
        keyEventHandler = KeyEventHandler(this)
    }

    private fun setupServiceInfo() {
        try {
            val info = serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Could not update serviceInfo flags", e)
        }
    }

    private fun registerReceivers() {
        val settingsFilter = IntentFilter().apply {
            addAction(Constants.Action.SETTINGS_CHANGED)
            addAction(Constants.Action.RELOAD_BACKGROUND)
            addAction(Constants.Action.UPDATE_BUTTON_COLORS)
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
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        instance = null

        cancelPendingTasks()
        unregisterReceivers()
        destroyOverlay()

        super.onDestroy()
    }

    private fun cancelPendingTasks() {
        pendingStateCheck?.let { handler.removeCallbacks(it) }
        pendingStateCheck = null
        
        unlockFadeRunnable?.let { handler.removeCallbacks(it) }
        unlockFadeRunnable = null
        
        stopFullscreenPolling()
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    // ===== 설정 변경 =====

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientationChanged = currentOrientation != newConfig.orientation
        currentOrientation = newConfig.orientation

        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}, changed=$orientationChanged")

        overlay?.handleOrientationChange(newConfig.orientation)
        overlay?.updateDarkMode()

        if (orientationChanged) {
            windowAnalyzer.calculateNavBarHeight()
            scheduleStateCheck()
        }
    }

    // ===== 접근성 이벤트 =====

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.elapsedRealtime()

        // IME 포커스 추적
        handleImeFocusEvent(event, now)

        // 배경화면 미리보기 감지
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (className == "com.minsoo.ultranavbar.ui.WallpaperPreviewActivity") {
                isWallpaperPreviewVisible = true
                overlay?.hide(animate = false)
                return
            } else if (isWallpaperPreviewVisible) {
                isWallpaperPreviewVisible = false
                updateOverlayVisibility(forceFade = true)
            }
        }

        // 이벤트 유형별 처리
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                scheduleStateCheck()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotificationStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                scheduleStateCheck()
            }
        }
    }

    private fun handleImeFocusEvent(event: AccessibilityEvent, now: Long) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val focusedIsTextInput = windowAnalyzer.isTextInputClass(event.className)
            if (focusedIsTextInput) {
                windowAnalyzer.setImeFocusActive(true)
                lastImeEventAt = now
                updateImeVisible(true)
            } else {
                windowAnalyzer.setImeFocusActive(false)
                scheduleStateCheck()
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            lastImeEventAt = now
        }
    }

    private fun handleNotificationStateChanged(event: AccessibilityEvent) {
        // 알림 추가 이벤트인지 확인
        val parcelableData = event.parcelableData
        val text = event.text?.toString() ?: ""
        val hasNotifications = parcelableData != null || text.isNotEmpty()

        Log.d(TAG, "Notification state: parcelable=${parcelableData != null}, text=$text")
        overlay?.setNotificationPresent(hasNotifications)
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val isRecents = windowAnalyzer.isRecentsClassName(className)

        updateRecentsState(isRecents, "event")

        if (packageName == this.packageName) return

        val isSystemUi = packageName == "com.android.systemui"
        if (handleSystemUiState(isSystemUi, isRecents, "event")) {
            return
        }

        markNonLauncherEvent(packageName)

        val packageChanged = updateForegroundPackage(packageName, "event")

        val hasVisibleNonLauncherApp = windowAnalyzer.hasVisibleNonLauncherAppWindow(
            windows.toList(),
            this.packageName
        )
        if (hasVisibleNonLauncherApp) {
            lastNonLauncherEventAt = SystemClock.elapsedRealtime()
        }
        val hasNonLauncherForeground =
            currentPackage.isNotEmpty() && !windowAnalyzer.isLauncherPackage(currentPackage)
        val suppressHomeForRecentApp = shouldSuppressHomeForRecentApp()
        val isLauncherPackage = windowAnalyzer.isLauncherPackage(packageName)
        // 앱→홈 전환: 현재 홈이 아닌 상태에서 런처가 오면 즉시 홈 상태 설정
        // 홈→앱 전환: suppressHomeForRecentApp 적용 (앱 로딩 화면에서 커스텀 배경 억제)
        val shouldSuppressHome = suppressHomeForRecentApp && isOnHomeScreen
        val newOnHomeScreen = isLauncherPackage &&
            !isRecents &&
            !hasVisibleNonLauncherApp &&
            !hasNonLauncherForeground &&
            !shouldSuppressHome

        // 이미 홈 화면이고 런처 이벤트인 경우, 잔류 앱 윈도우로 인한 잘못된 홈 이탈 방지
        if (!newOnHomeScreen && isOnHomeScreen && isLauncherPackage && !isRecents) {
            Log.d(TAG, "Home exit from event suppressed (launcher event, " +
                    "hasNonLauncherApp=$hasVisibleNonLauncherApp, suppressHome=$shouldSuppressHome)")
        } else {
            updateHomeScreenState(newOnHomeScreen, "event")
        }

        if (packageChanged) {
            handler.post {
                checkFullscreenState()
                updateOverlayVisibility()
            }
        }
    }

    private fun updateRecentsState(isRecents: Boolean, source: String) {
        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            Log.d(TAG, "Recents state ($source): $isRecentsVisible")
            overlay?.setRecentsState(isRecents)
        }
    }

    private fun updateHomeScreenState(isHome: Boolean, source: String) {
        if (isOnHomeScreen == isHome) return

        val now = SystemClock.elapsedRealtime()

        // 홈 진입 안정화: 홈에 진입한 직후 false 이벤트 무시
        if (!isHome && isOnHomeScreen) {
            val elapsed = now - lastHomeEntryAt
            if (elapsed < HOME_ENTRY_STABILIZE_MS) {
                Log.d(TAG, "Home exit ignored (stabilizing, ${elapsed}ms < ${HOME_ENTRY_STABILIZE_MS}ms)")
                return
            }
        }

        // 홈 이탈 안정화: 홈을 떠난 직후 windows 소스의 잘못된 홈 재진입 방지
        // 앱 실행 시 event 소스가 먼저 홈 이탈을 감지하지만, windows 소스는
        // 앱 윈도우가 아직 나타나지 않아 런처가 최상위로 보여 잘못된 홈 재진입을 시도함
        if (isHome && !isOnHomeScreen && source == "windows") {
            val elapsed = now - lastHomeExitAt
            if (elapsed < HOME_EXIT_STABILIZE_MS) {
                Log.d(TAG, "Home re-entry from windows ignored (stabilizing, ${elapsed}ms < ${HOME_EXIT_STABILIZE_MS}ms)")
                return
            }
        }

        // 홈 진입 시 타임스탬프 기록
        if (isHome && !isOnHomeScreen) {
            lastHomeEntryAt = now
        }

        // 홈 이탈 시 타임스탬프 기록
        if (!isHome && isOnHomeScreen) {
            lastHomeExitAt = now
        }

        isOnHomeScreen = isHome
        Log.d(TAG, "Home screen state ($source): $isOnHomeScreen")
        overlay?.setHomeScreenState(isOnHomeScreen)
    }

    private fun markNonLauncherEvent(packageName: String) {
        if (packageName.isEmpty()) return
        if (packageName == this.packageName) return
        if (packageName == "com.android.systemui") return
        if (windowAnalyzer.isLauncherPackage(packageName)) return
        lastNonLauncherEventAt = SystemClock.elapsedRealtime()
    }

    private fun shouldSuppressHomeForRecentApp(): Boolean {
        val elapsed = SystemClock.elapsedRealtime() - lastNonLauncherEventAt
        return elapsed < Constants.Timing.HOME_STATE_DEBOUNCE_MS
    }

    private fun updateForegroundPackage(
        packageName: String,
        source: String
    ): Boolean {
        if (currentPackage == packageName) return false

        val previousPackage = currentPackage
        currentPackage = packageName
        Log.d(TAG, "Foreground app ($source): $packageName")

        val wasDisabled = previousPackage.isNotEmpty() && settings.isAppDisabled(previousPackage)
        val isDisabled = settings.isAppDisabled(packageName)
        if (wasDisabled || isDisabled) {
            Log.d(TAG, "Disabled app transition: wasDisabled=$wasDisabled, isDisabled=$isDisabled")
        }

        // NavBarOverlay에 현재 패키지 전달
        overlay?.setForegroundPackage(packageName)

        return true
    }

    private fun handleSystemUiState(isSystemUi: Boolean, isRecents: Boolean, source: String): Boolean {
        if (!isSystemUi) return false
        if (isRecents && isOnHomeScreen) {
            updateHomeScreenState(false, source)
        }
        return true
    }

    private fun scheduleStateCheck() {
        pendingStateCheck?.let { handler.removeCallbacks(it) }
        pendingStateCheck = Runnable {
            checkFullscreenState()
            checkNotificationPanelState()
            checkQuickSettingsState()
            checkImeVisibility()
            updateHomeAndRecentsFromWindows()
            overlay?.ensureOrientationSync()
        }
        handler.postDelayed(pendingStateCheck!!, Constants.Timing.STATE_CHECK_DELAY_MS)
    }

    private fun checkFullscreenState() {
        val newFullscreenState = windowAnalyzer.analyzeFullscreenState(windows.toList())

        if (isFullscreen != newFullscreenState) {
            isFullscreen = newFullscreenState
            Log.d(TAG, "Fullscreen state: $isFullscreen, pkg=$currentPackage")

            if (isFullscreen) startFullscreenPolling() else stopFullscreenPolling()
            updateOverlayVisibility()
        } else if (isFullscreen) {
            startFullscreenPolling()
        }
    }

    private fun checkNotificationPanelState() {
        val panelVisible = windowAnalyzer.analyzeNotificationPanelState(windows.toList())

        if (isNotificationPanelOpen != panelVisible) {
            isNotificationPanelOpen = panelVisible
            Log.d(TAG, "Notification panel: $isNotificationPanelOpen")
            handler.post { updatePanelUiState() }
        }
    }

    private fun checkQuickSettingsState() {
        val qsVisible = windowAnalyzer.analyzeQuickSettingsState(windows.toList(), rootInActiveWindow)

        if (isQuickSettingsOpen != qsVisible) {
            isQuickSettingsOpen = qsVisible
            Log.d(TAG, "Quick settings: $isQuickSettingsOpen")
            handler.post { updatePanelUiState() }
        }
    }

    private fun updatePanelUiState() {
        overlay?.setPanelStates(isNotificationPanelOpen, isQuickSettingsOpen)
    }

    private fun checkImeVisibility() {
        val imeVisible = windowAnalyzer.analyzeImeVisibility(windows.toList())
        updateImeVisible(imeVisible)
    }

    private fun updateImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        Log.d(TAG, "IME visibility: $isImeVisible")
        handler.post {
            overlay?.setImeVisible(isImeVisible)
        }
    }

    private fun updateHomeAndRecentsFromWindows() {
        val windowList = windows.toList()
        val root = rootInActiveWindow
        val recentsVisible = windowAnalyzer.analyzeRecentsState(windowList, root)
        updateRecentsState(recentsVisible, "windows")

        val topWindow = windowAnalyzer.getTopApplicationWindow(windowList)
        var packageName = topWindow?.packageName

        if (packageName.isNullOrEmpty() || packageName == this.packageName) {
            packageName = root?.packageName?.toString()
        }

        if (packageName.isNullOrEmpty() || packageName == this.packageName) return

        val isSystemUi = packageName == "com.android.systemui"
        if (handleSystemUiState(isSystemUi, recentsVisible, "windows")) {
            return
        }

        markNonLauncherEvent(packageName)

        val packageChanged = updateForegroundPackage(packageName, "windows")

        val hasVisibleNonLauncherApp = windowAnalyzer.hasVisibleNonLauncherAppWindow(
            windowList,
            this.packageName
        )
        if (hasVisibleNonLauncherApp) {
            lastNonLauncherEventAt = SystemClock.elapsedRealtime()
        }
        val hasNonLauncherForeground =
            currentPackage.isNotEmpty() && !windowAnalyzer.isLauncherPackage(currentPackage)
        val suppressHomeForRecentApp = shouldSuppressHomeForRecentApp()
        val isLauncherPackage = windowAnalyzer.isLauncherPackage(packageName)
        // 앱→홈 전환: 현재 홈이 아닌 상태에서 런처가 오면 즉시 홈 상태 설정
        val shouldSuppressHome = suppressHomeForRecentApp && isOnHomeScreen
        val newOnHomeScreen = isLauncherPackage &&
            !recentsVisible &&
            !hasVisibleNonLauncherApp &&
            !hasNonLauncherForeground &&
            !shouldSuppressHome

        // 이미 홈 화면이고 런처가 최상위 윈도우인 경우,
        // 잔류 앱 윈도우(hasVisibleNonLauncherApp)나 최근 앱 이벤트(shouldSuppressHome)로 인한
        // 잘못된 홈 이탈 방지 (플레이스토어 등 일부 앱은 홈 전환 후에도 윈도우가 잔류할 수 있음)
        if (!newOnHomeScreen && isOnHomeScreen && isLauncherPackage && !recentsVisible) {
            Log.d(TAG, "Home exit from windows suppressed (launcher is top, " +
                    "hasNonLauncherApp=$hasVisibleNonLauncherApp, suppressHome=$shouldSuppressHome)")
        } else {
            updateHomeScreenState(newOnHomeScreen, "windows")
        }

        if (isOnHomeScreen) {
            val appDrawerOpen = windowAnalyzer.isAppDrawerOpen(root)
            if (isAppDrawerOpen != appDrawerOpen) {
                isAppDrawerOpen = appDrawerOpen
                Log.d(TAG, "App Drawer state: $isAppDrawerOpen")
                overlay?.setAppDrawerState(isAppDrawerOpen)
            }
        } else if (isAppDrawerOpen) {
            isAppDrawerOpen = false
            overlay?.setAppDrawerState(false)
        }

        if (packageChanged) {
            updateOverlayVisibility()
        }
    }

    private fun startFullscreenPolling() {
        if (fullscreenPoll != null) return

        fullscreenPoll = object : Runnable {
            override fun run() {
                val before = isFullscreen
                checkFullscreenState()
                if (isFullscreen) {
                    handler.postDelayed(this, Constants.Timing.FULLSCREEN_POLLING_MS)
                } else {
                    stopFullscreenPolling()
                    if (before) {
                        handler.postDelayed({ updateOverlayVisibility() }, Constants.Timing.RECENTS_STATE_DEBOUNCE_MS)
                    }
                }
            }
        }
        handler.postDelayed(fullscreenPoll!!, Constants.Timing.FULLSCREEN_POLLING_MS)
    }

    private fun stopFullscreenPolling() {
        fullscreenPoll?.let { handler.removeCallbacks(it) }
        fullscreenPoll = null
    }

    // ===== 오버레이 가시성 =====

    private fun updateOverlayVisibility(forceFade: Boolean = false) {
        val lockScreenActive = windowAnalyzer.isLockScreenActive()

        // 1. 비활성화된 앱 체크 - 오버레이를 완전히 숨김 (핫스팟 없음, 재호출 불가)
        if (currentPackage.isNotEmpty() && settings.isAppDisabled(currentPackage)) {
            Log.d(TAG, "App disabled: $currentPackage - hiding overlay completely")
            overlay?.hide(animate = false, showHotspot = false)
            return
        }

        // 2. 자동 숨김 체크 - 전체화면 등에서 숨김 (핫스팟으로 재호출 가능)
        val shouldAutoHide = windowAnalyzer.shouldAutoHideOverlay(
            currentPackage = currentPackage,
            isFullscreen = isFullscreen,
            isOnHomeScreen = isOnHomeScreen,
            isWallpaperPreviewVisible = isWallpaperPreviewVisible
        )

        Log.d(TAG, "Update visibility: shouldAutoHide=$shouldAutoHide, lock=$lockScreenActive, pkg=$currentPackage, fullscreen=$isFullscreen")

        if (shouldAutoHide) {
            if (!lockScreenActive && overlay?.canAutoHide() == false) {
                Log.d(TAG, "Auto-hide blocked: recently shown by gesture")
                return
            }
            overlay?.hide(animate = !lockScreenActive, showHotspot = !lockScreenActive)
        } else {
            overlay?.show(fade = forceFade)
        }
    }

    // ===== 오버레이 생성/파괴 =====

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

    // ===== 액션 실행 =====

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
            return executeAssistAction()
        }

        val actionId = action.globalActionId ?: return false
        Log.d(TAG, "Executing action: ${action.name} (id=$actionId)")
        return performGlobalAction(actionId)
    }

    private fun executeAssistAction(): Boolean {
        try {
            val customAction = settings.longPressAction
            if (customAction == null) {
                // 음성 어시스턴트 모드로 실행 (마이크 활성화 상태)
                return executeVoiceAssistant()
            } else if (customAction.startsWith("shortcut:")) {
                // 바로가기 실행
                return executeShortcut(customAction.removePrefix("shortcut:"))
            } else {
                // 앱 실행
                val intent = packageManager.getLaunchIntentForPackage(customAction)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    Log.d(TAG, "Executing custom long-press: $customAction")
                    return true
                } else {
                    Log.w(TAG, "Could not launch: $customAction")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute ASSIST action", e)
            return false
        }
    }

    private fun executeVoiceAssistant(): Boolean {
        // 방법 1: ACTION_VOICE_ASSIST (음성 대기 모드로 실행)
        try {
            val voiceIntent = Intent("android.intent.action.VOICE_ASSIST").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (voiceIntent.resolveActivity(packageManager) != null) {
                startActivity(voiceIntent)
                Log.d(TAG, "Executing voice assistant via VOICE_ASSIST")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "VOICE_ASSIST failed: ${e.message}")
        }

        // 방법 2: ACTION_VOICE_COMMAND (음성 명령 모드)
        try {
            val voiceCommandIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (voiceCommandIntent.resolveActivity(packageManager) != null) {
                startActivity(voiceCommandIntent)
                Log.d(TAG, "Executing voice assistant via ACTION_VOICE_COMMAND")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "ACTION_VOICE_COMMAND failed: ${e.message}")
        }

        // 방법 3: Google Assistant 직접 호출 (폴백)
        try {
            val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(assistIntent)
            Log.d(TAG, "Executing Google Assistant via ACTION_ASSIST (fallback)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All assistant methods failed", e)
            return false
        }
    }

    private fun executeShortcut(shortcutUri: String): Boolean {
        try {
            val intent = Intent.parseUri(shortcutUri, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Executing shortcut: $shortcutUri")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute shortcut: $shortcutUri", e)
            return false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        return keyEventHandler.handleKeyEvent(event)
    }
}
