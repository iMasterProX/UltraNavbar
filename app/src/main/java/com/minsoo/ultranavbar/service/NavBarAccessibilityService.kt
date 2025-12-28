package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.overlay.NavBarOverlay
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 네비게이션 바 접근성 서비스
 *
 * 주요 역할:
 * 1. 오버레이 바 표시/숨김 관리
 * 2. 포그라운드 앱 감지
 * 3. 전체화면/영상 재생 감지
 * 4. performGlobalAction을 통한 네비게이션 동작 수행
 */
class NavBarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavBarAccessibility"

        // 영상 앱 패키지 목록 (전체화면 감지용)
        private val VIDEO_APP_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "tv.twitch.android.app",
            "com.naver.vapp",           // V LIVE
            "com.wavve.android",
            "com.coupang.play",
            "com.google.android.videos", // Google Play 영화
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "org.videolan.vlc",
            "com.brouken.player",        // Just Player
            "com.kiwibrowser.browser",   // Kiwi Browser
            "com.android.chrome"         // Chrome (전체화면 영상)
        )

        @Volatile
        var instance: NavBarAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private var overlay: NavBarOverlay? = null
    private lateinit var settings: SettingsManager
    private var currentPackage: String = ""
    private var isFullscreen: Boolean = false
    private var isVideoPlaying: Boolean = false
    private var isOnHomeScreen: Boolean = false
    private var launcherPackages: Set<String> = emptySet()

    // 설정 변경 수신
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Settings changed, refreshing overlay")
            overlay?.refreshSettings()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        instance = this
        settings = SettingsManager.getInstance(this)

        // 런처 패키지 목록 로드
        loadLauncherPackages()

        // 오버레이 생성
        createOverlay()

        // 설정 변경 리시버 등록
        registerReceiver(
            settingsReceiver,
            IntentFilter("com.minsoo.ultranavbar.SETTINGS_CHANGED"),
            RECEIVER_NOT_EXPORTED
        )

        Log.i(TAG, "Overlay created and service fully initialized")
    }

    /**
     * 시스템에 설치된 런처(홈 앱) 패키지 목록 로드
     */
    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            launcherPackages = resolveInfos.map { it.activityInfo.packageName }.toSet()
            Log.d(TAG, "Detected launcher packages: $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages", e)
            // 기본 런처 패키지들
            launcherPackages = setOf(
                "com.android.launcher",
                "com.android.launcher3",
                "com.google.android.apps.nexuslauncher",
                "com.sec.android.app.launcher",  // Samsung
                "com.lge.launcher3",             // LG
                "com.huawei.android.launcher"    // Huawei
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        instance = null
        try {
            unregisterReceiver(settingsReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered", e)
        }
        destroyOverlay()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        // 화면 회전 시 오버레이 재생성
        overlay?.handleOrientationChange(newConfig.orientation)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 자기 자신은 무시
        if (packageName == this.packageName) return

        // 시스템 UI 패키지는 무시 (알림 패널 등)
        if (packageName == "com.android.systemui") return

        if (currentPackage != packageName) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app changed: $packageName")

            // 홈 화면 감지
            val wasOnHomeScreen = isOnHomeScreen
            isOnHomeScreen = launcherPackages.contains(packageName)
            if (wasOnHomeScreen != isOnHomeScreen) {
                Log.d(TAG, "Home screen state changed: $isOnHomeScreen")
                overlay?.setHomeScreenState(isOnHomeScreen)
            }

            updateOverlayVisibility()
        }

        // 전체화면 상태 감지
        checkFullscreenState()
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        // 영상 재생 감지 (휴리스틱)
        if (settings.autoHideOnVideo && isVideoApp(currentPackage)) {
            checkVideoPlayingState()
        }
    }

    /**
     * 전체화면 상태 확인
     */
    private fun checkFullscreenState() {
        try {
            val windows = windows
            var foundFullscreen = false

            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    // 전체화면 판단: 시스템 바가 안 보이는 상태
                    // (정확한 판단은 어려움, 휴리스틱 사용)
                    val isFullscreenWindow = window.isFullScreen
                    if (isFullscreenWindow) {
                        foundFullscreen = true
                        break
                    }
                }
            }

            if (isFullscreen != foundFullscreen) {
                isFullscreen = foundFullscreen
                Log.d(TAG, "Fullscreen state changed: $isFullscreen")
                updateOverlayVisibility()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking fullscreen state", e)
        }
    }

    /**
     * 영상 재생 상태 확인 (휴리스틱)
     */
    private fun checkVideoPlayingState() {
        // 간단한 휴리스틱: 영상 앱이 전체화면이면 영상 재생 중으로 간주
        val playing = isVideoApp(currentPackage) && isFullscreen

        if (isVideoPlaying != playing) {
            isVideoPlaying = playing
            Log.d(TAG, "Video playing state changed: $isVideoPlaying (app: $currentPackage)")
            updateOverlayVisibility()
        }
    }

    private fun isVideoApp(packageName: String): Boolean {
        return VIDEO_APP_PACKAGES.contains(packageName)
    }

    /**
     * 오버레이 표시 여부 결정
     */
    private fun updateOverlayVisibility() {
        val shouldHide = shouldHideOverlay()
        Log.d(TAG, "Update visibility: shouldHide=$shouldHide, package=$currentPackage, fullscreen=$isFullscreen, video=$isVideoPlaying")

        if (shouldHide) {
            overlay?.hide()
        } else {
            overlay?.show()
        }
    }

    private fun shouldHideOverlay(): Boolean {
        // 1. 영상 재생 중이면 숨김
        if (settings.autoHideOnVideo && isVideoPlaying) {
            return true
        }

        // 2. 앱별 설정에 따라 숨김
        if (currentPackage.isNotEmpty() && settings.shouldHideForPackage(currentPackage)) {
            return true
        }

        return false
    }

    /**
     * 오버레이 생성
     */
    private fun createOverlay() {
        if (overlay == null) {
            overlay = NavBarOverlay(this)
            overlay?.create()
        }
    }

    /**
     * 오버레이 제거
     */
    private fun destroyOverlay() {
        overlay?.destroy()
        overlay = null
    }

    /**
     * 네비게이션 액션 실행
     */
    fun executeAction(action: NavAction): Boolean {
        val actionId = action.globalActionId ?: return false
        Log.d(TAG, "Executing action: ${action.name} (id=$actionId)")
        return performGlobalAction(actionId)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
}
