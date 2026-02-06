package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
import android.view.accessibility.AccessibilityNodeInfo
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.core.SplitScreenHelper
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
        private const val ORIENTATION_CHANGE_STABILIZE_MS = 500L  // 화면 회전 후 안정화 시간

        // 방향 고정 브로드캐스트 액션
        const val ACTION_APPLY_ORIENTATION_LOCK = "com.minsoo.ultranavbar.ACTION_APPLY_ORIENTATION_LOCK"
        const val ACTION_REMOVE_ORIENTATION_LOCK = "com.minsoo.ultranavbar.ACTION_REMOVE_ORIENTATION_LOCK"

        @Volatile
        var instance: NavBarAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    // === 컴포넌트 ===
    private var overlay: NavBarOverlay? = null
    private var orientationLockView: android.view.View? = null  // 화면 방향 강제용 오버레이
    private lateinit var settings: SettingsManager
    private lateinit var windowAnalyzer: WindowAnalyzer
    private lateinit var keyEventHandler: KeyEventHandler
    private val notificationTracker = NotificationTracker()

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
    private var isSplitScreenMode: Boolean = false  // 분할화면 모드 상태
    private var wasSplitScreenUsed: Boolean = false  // 분할화면 사용 이력 (최근 앱 종료 후 복구용)
    private var lastImeEventAt: Long = 0
    private var lastNonLauncherEventAt: Long = 0
    private var lastHomeEntryAt: Long = 0  // 홈 진입 안정화용
    private var lastHomeExitAt: Long = 0   // 홈 이탈 안정화용
    private var lastOrientationChangeAt: Long = 0  // 화면 회전 안정화용

    // === 디바운스/폴링 ===
    private var pendingStateCheck: Runnable? = null
    private var fullscreenPoll: Runnable? = null
    private var unlockFadeRunnable: Runnable? = null
    private var batteryMonitorRunnable: Runnable? = null
    private var windowRecoveryRunnable: Runnable? = null  // 윈도우 복구용

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
                ACTION_APPLY_ORIENTATION_LOCK -> {
                    Log.d(TAG, "Applying orientation lock from broadcast")
                    applyOrientationLock()
                }
                ACTION_REMOVE_ORIENTATION_LOCK -> {
                    Log.d(TAG, "Removing orientation lock from broadcast")
                    removeOrientationLock()
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
                        if (instance == null) return@Runnable
                        overlay?.startUnlockFade()
                    }
                    handler.postDelayed(unlockFadeRunnable!!, Constants.Timing.UNLOCK_FADE_DELAY_MS)
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    Log.d(TAG, "Bluetooth device connected: ${device?.name}")
                    if (device != null && isBluetoothKeyboard(device)) {
                        Log.d(TAG, "Keyboard connected, applying orientation lock")
                        applyOrientationLock()
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    Log.d(TAG, "Bluetooth device disconnected: ${device?.name}")
                    // 방향 고정이 활성 상태일 때만 처리
                    if (orientationLockView != null) {
                        // 물리 키보드가 아직 연결되어 있는지 확인
                        // (isBluetoothKeyboard는 권한/디바이스 상태에 따라 실패할 수 있으므로
                        //  InputDevice API로 실제 연결 상태를 직접 확인)
                        handler.postDelayed({
                            if (instance == null) return@postDelayed
                            if (!hasConnectedPhysicalKeyboard()) {
                                Log.d(TAG, "No physical keyboard connected, removing orientation lock")
                                removeOrientationLock()
                            } else {
                                Log.d(TAG, "Physical keyboard still connected, keeping orientation lock")
                            }
                        }, 500L) // BT 연결 해제 반영 대기
                    }
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

        // SplitScreenHelper에 접근성 서비스 참조 설정
        SplitScreenHelper.setAccessibilityService(this)

        initializeComponents()
        setupServiceInfo()
        registerReceivers()

        windowAnalyzer.loadLauncherPackages()
        windowAnalyzer.calculateNavBarHeight()
        currentOrientation = resources.configuration.orientation

        createOverlay()
        updateOverlayVisibility(forceFade = false)

        // Initialize recent apps if enabled
        if (settings.recentAppsTaskbarEnabled) {
            overlay?.initializeRecentApps(windowAnalyzer.getLauncherPackages())
        }
        checkImeVisibility()

        // 배터리 모니터링 시작
        startBatteryMonitoring()

        // 현재 연결된 키보드 확인 및 방향 고정 적용
        checkAndApplyOrientationLock()

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

            // Ensure key event filtering is explicitly enabled
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

            // Verify key event filtering is enabled
            val hasFlag = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
            val hasCapability = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0

            if (hasFlag && hasCapability) {
                Log.i(TAG, "Key event filtering is ENABLED (flag=true, capability=true)")
            } else {
                Log.w(TAG, "Key event filtering issue - flag=$hasFlag, capability=$hasCapability")
                if (!hasCapability) {
                    Log.w(TAG, "Missing CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS - ensure canRequestFilterKeyEvents=true in XML")
                }
            }

            Log.d(TAG, "Service flags: ${info.flags}")
            Log.d(TAG, "Service capabilities: ${info.capabilities}")
            Log.d(TAG, "Event types: ${info.eventTypes}")
            Log.i(TAG, "Keyboard shortcut handling initialized")

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
            addAction(ACTION_APPLY_ORIENTATION_LOCK)
            addAction(ACTION_REMOVE_ORIENTATION_LOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_EXPORTED)
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

        val bluetoothFilter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, bluetoothFilter)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        instance = null

        // SplitScreenHelper 참조 해제
        SplitScreenHelper.setAccessibilityService(null)

        cancelPendingTasks()
        handler.removeCallbacksAndMessages(null)  // 모든 대기 콜백 제거
        unregisterReceivers()
        destroyOverlay()
        removeOrientationLockOverlay()
        stopBatteryMonitoring()

        super.onDestroy()
    }

    private fun cancelPendingTasks() {
        pendingStateCheck?.let { handler.removeCallbacks(it) }
        pendingStateCheck = null

        unlockFadeRunnable?.let { handler.removeCallbacks(it) }
        unlockFadeRunnable = null

        windowRecoveryRunnable?.let { handler.removeCallbacks(it) }
        windowRecoveryRunnable = null

        stopFullscreenPolling()
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(settingsReceiver)
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(bluetoothReceiver)
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

        // 방향 + 다크 모드를 통합 처리 (비트맵 리로드 1회, 올바른 순서 보장)
        overlay?.handleConfigurationChange(newConfig.orientation)

        if (orientationChanged) {
            // 화면 회전 시간 기록 (안정화 기간 동안 auto-hide 차단)
            lastOrientationChangeAt = SystemClock.elapsedRealtime()

            windowAnalyzer.calculateNavBarHeight()

            // 화면 회전 후 오버레이를 강제로 표시 (숨김 방지)
            overlay?.show(fade = false)

            // 화면 회전 후 일정 시간 후에 상태 재확인
            handler.postDelayed({
                if (instance == null) return@postDelayed
                Log.d(TAG, "Re-checking overlay visibility after orientation change")
                updateOverlayVisibility(forceFade = false)
            }, 200)

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
        val parcelableData = event.parcelableData
        val packageName = event.packageName?.toString()

        // Notification 객체에서 정보 추출
        var notificationKey: String? = null
        var category: String? = null
        var isOngoing = false

        // 알림 제거 이벤트 감지
        val isRemoval = parcelableData == null

        if (parcelableData is android.app.Notification) {
            notificationKey = "${packageName}:${parcelableData.hashCode()}"
            category = parcelableData.category
            isOngoing = (parcelableData.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        }

        Log.d(TAG, "Notification state: pkg=$packageName, key=$notificationKey, category=$category, ongoing=$isOngoing, removal=$isRemoval")

        // NotificationTracker로 새 알림인지 판단
        val isNewNotification = notificationTracker.processNotificationEvent(
            key = notificationKey,
            packageName = packageName,
            category = category,
            isOngoing = isOngoing,
            isRemoval = isRemoval
        )

        // 새 알림이고 확인하지 않은 알림이 있으면 깜빡임 시작
        if (isNewNotification && notificationTracker.hasUnseenNotifications()) {
            overlay?.setNotificationPresent(true)
        }

        // 알림이 제거되었거나 더 이상 미확인 알림이 없으면 깜빡임 중지
        if (isRemoval || !notificationTracker.hasUnseenNotifications()) {
            overlay?.setNotificationPresent(false)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val isRecents = windowAnalyzer.isRecentsClassName(className)

        // 디버깅: 모든 윈도우 상태 변경 로그
        Log.d(TAG, "WindowStateChanged: pkg=$packageName, class=$className")

        // 시스템 펜 설정 화면 차단 및 리다이렉트
        if (shouldBlockSystemPenSettings(packageName, className)) {
            redirectToAppPenSettings()
            return
        }

        updateRecentsState(isRecents, "event")

        // 이 앱이 열리면 홈 화면 상태를 false로 설정 후 리턴
        if (packageName == this.packageName) {
            updateHomeScreenState(false, "self_app")
            return
        }

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
        val wasRecentsVisible = isRecentsVisible
        if (isRecentsVisible != isRecents) {
            isRecentsVisible = isRecents
            Log.d(TAG, "Recents state ($source): $isRecentsVisible")
            overlay?.setRecentsState(isRecents)

            // 최근 앱 화면이 닫힐 때 - 분할화면 플래그 리셋
            if (wasRecentsVisible && !isRecents) {
                val helperFlag = SplitScreenHelper.wasSplitScreenUsedAndReset()
                if (wasSplitScreenUsed || helperFlag) {
                    Log.d(TAG, "Recents closed after split screen, resetting flags")
                    // 분할화면 상태 플래그만 리셋 (스택 정리는 시스템에 맡김)
                    cleanSplitScreenStacks()
                    wasSplitScreenUsed = false
                }
            }
        }
    }

    /**
     * 분할화면 스택 정리
     *
     * 참고: Android 12+에서 'am stack remove-all-secondary-split-stacks' 명령어가
     * 제거되어 더 이상 사용할 수 없음. 대신 빈 split-screen 태스크를 개별적으로 정리하거나,
     * 시스템이 자동으로 정리하도록 두는 방식으로 변경.
     *
     * 실험 결과, 분할화면 종료 후 명시적인 스택 정리를 하지 않아도
     * 시스템이 자동으로 처리하는 것이 더 안정적임.
     */
    private fun cleanSplitScreenStacks() {
        Log.d(TAG, "Split screen cleanup: resetting all state flags")

        // 모든 분할화면 관련 상태 플래그 강제 초기화
        SplitScreenHelper.forceResetSplitScreenState()
    }

    // 복구 체크는 더 이상 사용하지 않음 - 분할화면 종료 시 즉시 복구
    private fun scheduleWindowRecoveryCheck() {
        // 비활성화됨
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

        // 앱 전환 시 키보드 수정자 키 상태 초기화 (stuck modifier 방지)
        keyEventHandler.resetModifiers()

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
            if (instance == null) return@Runnable
            checkFullscreenState()
            checkNotificationPanelState()
            checkQuickSettingsState()
            checkImeVisibility()
            checkSplitScreenState()  // 분할화면 상태 체크 추가
            updateHomeAndRecentsFromWindows()
            overlay?.ensureOrientationSync()
        }
        handler.postDelayed(pendingStateCheck!!, Constants.Timing.STATE_CHECK_DELAY_MS)
    }

    /**
     * 분할화면 상태 감지 및 복구 처리
     * 분할화면 종료 후 유효한 윈도우가 없으면 자동으로 홈 화면으로 이동
     */
    private fun checkSplitScreenState() {
        val windowList = windows.toList()
        val wasInSplitScreen = isSplitScreenMode

        // 분할화면 상태 감지 (멀티윈도우 윈도우가 있는지 확인)
        isSplitScreenMode = windowList.any { window ->
            try {
                // 앱 윈도우이면서 분할화면 영역에 있는지 확인
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    // 화면의 절반 이하 크기의 앱 윈도우가 있으면 분할화면으로 판단
                    val displayMetrics = resources.displayMetrics
                    val screenHeight = displayMetrics.heightPixels
                    val screenWidth = displayMetrics.widthPixels
                    val isHalfScreen = bounds.height() < screenHeight * 0.7 || bounds.width() < screenWidth * 0.7
                    isHalfScreen && window.isActive
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        // SplitScreenHelper에 상태 동기화
        SplitScreenHelper.setSplitScreenActive(isSplitScreenMode)

        // 분할화면 진입 시 플래그 설정 (최근 앱 종료 후 복구용)
        if (isSplitScreenMode && !wasInSplitScreen) {
            wasSplitScreenUsed = true
            Log.d(TAG, "Split screen entered, setting recovery flag")
        }

        // 분할화면이 종료되었는지 확인 (로그만 남김, 복구는 최근 앱 종료 시 수행)
        if (wasInSplitScreen && !isSplitScreenMode) {
            Log.d(TAG, "Split screen ended")
        }

        if (wasInSplitScreen != isSplitScreenMode) {
            Log.d(TAG, "Split screen state changed: $wasInSplitScreen -> $isSplitScreenMode")
        }
    }

    /**
     * 분할화면 종료 후 처리
     * 유효한 터치 가능 윈도우가 없으면 홈 화면으로 자동 이동
     */
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

            // 패널이 열리면 모든 알림을 본 것으로 처리하고 깜빡임 중지
            if (panelVisible) {
                notificationTracker.markAllAsSeen()
                overlay?.setNotificationPresent(false)
            }

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
            keyEventHandler.setImeVisible(isImeVisible)
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

        // 이 앱이 열리면 홈 화면 상태를 false로 설정 후 리턴
        if (packageName.isNullOrEmpty() || packageName == this.packageName) {
            if (packageName == this.packageName) {
                updateHomeScreenState(false, "self_app_windows")
            }
            return
        }

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

    // ===== 배터리 모니터링 =====

    private fun startBatteryMonitoring() {
        // 30분마다 배터리 체크 (BLE GATT 캐시는 10분 유효)
        batteryMonitorRunnable = object : Runnable {
            override fun run() {
                KeyboardBatteryMonitor.checkBatteryLevels(this@NavBarAccessibilityService)
                handler.postDelayed(this, 1800000L) // 30 minutes
            }
        }
        handler.post(batteryMonitorRunnable!!)
    }

    private fun stopBatteryMonitoring() {
        batteryMonitorRunnable?.let { handler.removeCallbacks(it) }
        batteryMonitorRunnable = null
    }

    // ===== 오버레이 가시성 =====

    private fun updateOverlayVisibility(forceFade: Boolean = false) {
        val lockScreenActive = windowAnalyzer.isLockScreenActive()

        // 0. 네비게이션 바 전체 비활성화 체크
        if (!settings.navbarEnabled) {
            Log.d(TAG, "Navbar disabled globally - hiding overlay completely")
            overlay?.hide(animate = false, showHotspot = false)
            return
        }

        // 1. 비활성화된 앱 체크 - 오버레이를 완전히 숨김 (핫스팟 없음, 재호출 불가)
        if (currentPackage.isNotEmpty() && settings.isAppDisabled(currentPackage)) {
            Log.d(TAG, "App disabled: $currentPackage - hiding overlay completely")
            overlay?.hide(animate = false, showHotspot = false)
            return
        }

        // 1.5. 화면 회전 안정화 기간 동안 숨김 방지
        val orientationChangeElapsed = SystemClock.elapsedRealtime() - lastOrientationChangeAt
        if (orientationChangeElapsed < ORIENTATION_CHANGE_STABILIZE_MS) {
            Log.d(TAG, "Orientation change stabilizing (${orientationChangeElapsed}ms) - keeping overlay visible")
            overlay?.show(fade = false)
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
        // 방법 1: Google Assistant 직접 실행 (하단 팝업 형태)
        try {
            val assistantIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (assistantIntent.resolveActivity(packageManager) != null) {
                startActivity(assistantIntent)
                Log.d(TAG, "Executing Google Assistant via ACTION_VOICE_COMMAND")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Google Assistant ACTION_VOICE_COMMAND failed: ${e.message}")
        }

        // 방법 2: Google Assistant 앱 직접 실행
        try {
            val assistantIntent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voiceinteraction.GsaVoiceInteractionService"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(assistantIntent)
            Log.d(TAG, "Executing Google Assistant via GsaVoiceInteractionService")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "GsaVoiceInteractionService failed: ${e.message}")
        }

        // 방법 3: com.google.android.apps.googleassistant 패키지 실행
        try {
            val assistantIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.googleassistant")
            if (assistantIntent != null) {
                assistantIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(assistantIntent)
                Log.d(TAG, "Executing Google Assistant app directly")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Google Assistant app launch failed: ${e.message}")
        }

        // 방법 4: ACTION_VOICE_ASSIST (음성 대기 모드로 실행)
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

        // 방법 5: ACTION_ASSIST (최후의 폴백)
        try {
            val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (assistIntent.resolveActivity(packageManager) != null) {
                startActivity(assistIntent)
                Log.d(TAG, "Executing assistant via ACTION_ASSIST (fallback)")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "All assistant methods failed", e)
            return false
        }

        return false
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

    /**
     * 시스템 펜 설정 화면을 차단해야 하는지 확인
     */
    private fun shouldBlockSystemPenSettings(packageName: String, className: String): Boolean {
        // 펜 커스텀 기능이 활성화되어 있지 않으면 차단하지 않음
        if (!settings.isPenCustomFunctionEnabled()) {
            return false
        }

        // 설정 앱인지 먼저 확인 (다른 앱은 절대 차단하지 않음)
        val isSettingsApp = packageName == "com.android.settings" ||
                packageName == "com.lge.settings" ||
                packageName.contains("settings", ignoreCase = true)

        if (!isSettingsApp) {
            return false
        }

        // 펜 관련 Activity 감지 (LG, Samsung, 일반 Android)
        val classNameLower = className.lowercase()
        val isPenSettingsActivity = classNameLower.contains("extension") ||
                classNameLower.contains("pen") ||
                classNameLower.contains("stylus") ||
                classNameLower.contains("wacom") ||
                classNameLower.contains("spen") ||
                classNameLower.contains("buttonconfig")

        if (isPenSettingsActivity) {
            Log.d(TAG, "Blocking system pen settings: pkg=$packageName, class=$className")
            return true
        }

        return false
    }

    /**
     * 이 앱의 펜 설정 화면으로 리다이렉트
     */
    private fun redirectToAppPenSettings() {
        try {
            // 먼저 시스템 설정 화면을 닫음
            performGlobalAction(GLOBAL_ACTION_BACK)

            // 이 앱의 메인 화면 열기 (펜 설정 탭)
            handler.postDelayed({
                val intent = Intent(this, com.minsoo.ultranavbar.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("navigate_to", "pen_settings")
                }
                startActivity(intent)
                Log.d(TAG, "Redirected to app pen settings")
            }, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect to app pen settings", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) {
            Log.d(TAG, "onKeyEvent: event is null")
            return false
        }

        // 모든 키 이벤트 로그 (펜 버튼 디버깅용)
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val deviceName = event.device?.name ?: "unknown"
        Log.d(TAG, "onKeyEvent: keyCode=${event.keyCode}, action=$action, device=$deviceName, source=${event.source}")

        // 펜 버튼 이벤트 처리 (최우선)
        if (com.minsoo.ultranavbar.util.PenButtonHandler.isPenButtonEvent(event)) {
            Log.d(TAG, "onKeyEvent: Detected pen button event, handling...")
            val handled = com.minsoo.ultranavbar.util.PenButtonHandler.handlePenButtonEvent(this, event)
            if (handled) {
                Log.d(TAG, "onKeyEvent: pen button event consumed")
                return true
            }
            Log.d(TAG, "onKeyEvent: pen button event not consumed, passing through")
        }

        // 현재 앱에서 키보드 단축키가 비활성화되어 있으면 이벤트 전파
        if (currentPackage.isNotEmpty() && settings.isShortcutDisabledForApp(currentPackage)) {
            Log.d(TAG, "onKeyEvent: shortcuts disabled for $currentPackage, passing through")
            return false
        }

        val result = keyEventHandler.handleKeyEvent(event)
        Log.d(TAG, "onKeyEvent: result=$result (consumed=$result)")
        return result
    }

    // ===== 화면 방향 고정 기능 =====

    /**
     * 블루투스 장치가 키보드인지 확인
     */
    private fun isBluetoothKeyboard(device: android.bluetooth.BluetoothDevice): Boolean {
        return try {
            val deviceClass = device.bluetoothClass ?: return false
            val majorClass = deviceClass.majorDeviceClass
            majorClass == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ||
            majorClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if device is keyboard", e)
            false
        }
    }

    /**
     * 화면 방향 고정 적용 (오버레이 윈도우의 screenOrientation 사용)
     * 이 방식은 앱 자체의 orientation 설정도 무시하고 강제로 방향 고정
     */
    private fun applyOrientationLock() {
        val lockMode = settings.keyboardOrientationLock
        if (lockMode == 0) {
            Log.d(TAG, "Orientation lock is disabled in settings")
            return
        }

        // 기존 오버레이 제거
        removeOrientationLockOverlay()

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

            // 투명 오버레이 생성
            val view = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // 화면 방향 설정
            val screenOrientation = when (lockMode) {
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> return
            }

            val params = android.view.WindowManager.LayoutParams().apply {
                type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = android.graphics.PixelFormat.TRANSLUCENT
                flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = 1  // 최소 크기 (보이지 않음)
                height = 1
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
                this.screenOrientation = screenOrientation
            }

            wm.addView(view, params)
            orientationLockView = view

            Log.d(TAG, "Applied orientation lock overlay: ${if (lockMode == 1) "landscape" else "portrait"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply orientation lock overlay", e)
        }
    }

    /**
     * 화면 방향 고정 오버레이 제거
     */
    private fun removeOrientationLockOverlay() {
        orientationLockView?.let { view ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                wm.removeView(view)
                Log.d(TAG, "Removed orientation lock overlay")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove orientation lock overlay", e)
            }
            orientationLockView = null
        }
    }

    /**
     * 화면 방향 고정 해제
     */
    private fun removeOrientationLock() {
        removeOrientationLockOverlay()
        Log.d(TAG, "Orientation lock removed")
    }

    /**
     * 현재 연결된 물리 키보드 확인 및 방향 고정 적용
     */
    private fun checkAndApplyOrientationLock() {
        val lockMode = settings.keyboardOrientationLock
        if (lockMode == 0) {
            Log.d(TAG, "Orientation lock is disabled")
            return
        }

        // InputDevice API로 실제 연결된 물리 키보드 확인
        // (bondedDevices는 페어링만 된 기기도 포함하므로 부정확)
        if (hasConnectedPhysicalKeyboard()) {
            Log.d(TAG, "Physical keyboard connected on service start, applying orientation lock")
            applyOrientationLock()
        }
    }

    /**
     * 실제 연결된 물리 키보드가 있는지 확인
     * BT API 대신 InputDevice API 사용 (권한 불필요, 실제 연결 상태 반영)
     */
    private fun hasConnectedPhysicalKeyboard(): Boolean {
        val deviceIds = android.view.InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = android.view.InputDevice.getDevice(id) ?: continue
            if (device.sources and android.view.InputDevice.SOURCE_KEYBOARD == android.view.InputDevice.SOURCE_KEYBOARD &&
                device.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                !device.isVirtual) {
                Log.d(TAG, "Physical keyboard found: ${device.name}")
                return true
            }
        }
        return false
    }

    // ===== 자동 터치 기능 =====

    /**
     * 지정된 좌표에 터치(탭) 제스처 수행
     *
     * 개선된 제스처 방식:
     * 1. startTime에 딜레이를 주어 펜 버튼 이벤트 완료 후 실행
     * 2. 약간의 움직임을 추가하여 정지 탭 무시 방지
     * 3. 적절한 터치 지속 시간 사용
     *
     * @param x X 좌표
     * @param y Y 좌표
     * @param callback 결과 콜백 (선택)
     * @param startDelay 제스처 시작 전 딜레이 (ms)
     * @return 제스처 디스패치 성공 여부
     */
    fun performTap(
        x: Float,
        y: Float,
        callback: GestureResultCallback? = null,
        startDelay: Long = 0L
    ): Boolean {
        if (x < 0 || y < 0) {
            Log.w(TAG, "performTap: Invalid coordinates ($x, $y)")
            return false
        }

        return try {
            // 약간의 움직임이 있는 탭 (일부 앱에서 정지 탭을 무시할 수 있음)
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
                lineTo(x, y)
            }

            // 터치 지속 시간: 100ms
            val strokeDescription = GestureDescription.StrokeDescription(
                path,
                startDelay,
                100L
            )

            val gestureBuilder = GestureDescription.Builder()
                .addStroke(strokeDescription)
                .build()

            val result = dispatchGesture(
                gestureBuilder,
                callback ?: object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "performTap: Gesture completed at ($x, $y)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "performTap: Gesture cancelled at ($x, $y)")
                    }
                },
                handler
            )

            Log.d(TAG, "performTap: dispatchGesture result=$result at ($x, $y)")
            result
        } catch (e: Exception) {
            Log.e(TAG, "performTap: Failed to dispatch gesture", e)
            false
        }
    }

    /**
     * 저장된 노드 정보를 기반으로 UI 요소 클릭
     * 화면 방향에 상관없이 동작
     *
     * @param nodeId 리소스 ID (예: com.example:id/button)
     * @param nodeText 텍스트 내용
     * @param nodeDesc contentDescription
     * @param nodePackage 패키지 이름 (특정 앱에서만 클릭)
     * @return 클릭 성공 여부
     */
    fun performNodeClick(
        nodeId: String?,
        nodeText: String?,
        nodeDesc: String?,
        nodePackage: String?
    ): Boolean {
        // 매 시도마다 새로운 rootInActiveWindow 가져오기
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "performNodeClick: No active window")
            return false
        }

        try {
            // 패키지 확인 (선택사항) - 패키지 불일치 시에도 노드 찾기 시도
            val currentPackage = rootNode.packageName?.toString()
            if (!nodePackage.isNullOrEmpty() && currentPackage != nodePackage) {
                Log.d(TAG, "performNodeClick: Package hint mismatch ($currentPackage vs $nodePackage), trying anyway")
            }

            // 노드 찾기 (여러 방법 시도)
            var targetNode: AccessibilityNodeInfo? = null

            // 1. Resource ID로 찾기 (가장 신뢰할 수 있음)
            if (!nodeId.isNullOrEmpty()) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(nodeId)
                if (nodes.isNotEmpty()) {
                    // 클릭 가능한 노드 우선 선택
                    for (node in nodes) {
                        if (node.isClickable || node.isCheckable) {
                            targetNode = node
                            break
                        }
                    }
                    // 클릭 가능한 노드가 없으면 첫 번째 노드 사용
                    if (targetNode == null) {
                        targetNode = nodes[0]
                    }
                    // 나머지 노드 recycle
                    for (node in nodes) {
                        if (node != targetNode) {
                            node.recycle()
                        }
                    }
                    Log.d(TAG, "performNodeClick: Found node by ID: $nodeId")
                }
            }

            // 2. contentDescription으로 찾기
            if (!nodeDesc.isNullOrEmpty() && targetNode == null) {
                targetNode = findNodeByContentDescription(rootNode, nodeDesc)
                if (targetNode != null) {
                    Log.d(TAG, "performNodeClick: Found node by description: $nodeDesc")
                }
            }

            // 3. 텍스트로 찾기 (부분 일치 포함)
            if (!nodeText.isNullOrEmpty() && targetNode == null) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(nodeText)
                // 정확한 일치 우선
                for (node in nodes) {
                    val text = node.text?.toString()
                    if (text == nodeText) {
                        targetNode = node
                        Log.d(TAG, "performNodeClick: Found node by exact text: $nodeText")
                        break
                    }
                }
                // 정확한 일치가 없으면 부분 일치 중 클릭 가능한 것
                if (targetNode == null) {
                    for (node in nodes) {
                        if (node.isClickable || node.isCheckable) {
                            targetNode = node
                            Log.d(TAG, "performNodeClick: Found clickable node containing text: $nodeText")
                            break
                        }
                    }
                }
                // 나머지 노드 recycle
                for (node in nodes) {
                    if (node != targetNode) {
                        node.recycle()
                    }
                }
            }

            if (targetNode == null) {
                Log.w(TAG, "performNodeClick: Node not found (id=$nodeId, text=$nodeText, desc=$nodeDesc)")
                rootNode.recycle()
                return false
            }

            // 클릭 수행 - 노드가 클릭 불가능하면 클릭 가능한 부모 찾기
            var result = false
            if (targetNode.isClickable || targetNode.isCheckable) {
                result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "performNodeClick: Direct click result=$result")
            } else {
                // 클릭 가능한 부모 노드 찾기
                var parent = targetNode.parent
                var depth = 0
                while (parent != null && depth < 10) {
                    if (parent.isClickable) {
                        result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "performNodeClick: Parent click result=$result (depth=$depth)")
                        parent.recycle()
                        break
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                    depth++
                }
                parent?.recycle()
            }

            targetNode.recycle()
            rootNode.recycle()
            return result
        } catch (e: Exception) {
            Log.e(TAG, "performNodeClick: Failed", e)
            try { rootNode.recycle() } catch (_: Exception) {}
            return false
        }
    }

    /**
     * contentDescription으로 노드 찾기 (재귀)
     * 클릭 가능 여부와 관계없이 찾음 (나중에 부모에서 클릭 시도)
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString()
        if (nodeDesc == desc) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, desc)
            child.recycle()
            if (result != null) {
                return result
            }
        }

        return null
    }
}
