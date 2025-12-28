package com.minsoo.ultranavbar.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
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

        private val VIDEO_APP_PACKAGES = setOf(
            "com.google.android.youtube", "com.google.android.apps.youtube.music", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "tv.twitch.android.app", "com.naver.vapp",
            "com.wavve.android", "com.coupang.play", "com.google.android.videos", "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro", "org.videolan.vlc", "com.brouken.player", "com.kiwibrowser.browser",
            "com.android.chrome"
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

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Settings changed, refreshing overlay")
            overlay?.refreshSettings()
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off, hiding overlay.")
                    overlay?.hide()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present, updating overlay visibility.")
                    // A small delay may be necessary for the keyguard to fully dismiss
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateOverlayVisibility()
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

        registerReceiver(
            settingsReceiver,
            IntentFilter("com.minsoo.ultranavbar.SETTINGS_CHANGED"),
            RECEIVER_NOT_EXPORTED
        )

        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)

        Log.i(TAG, "Overlay created and service fully initialized")
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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a real icon
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Service started in foreground.")
    }

    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            launcherPackages = resolveInfos.map { it.activityInfo.packageName }.toSet()
            Log.d(TAG, "Detected launcher packages: $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages", e)
            launcherPackages = setOf(
                "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
                "com.sec.android.app.launcher", "com.lge.launcher3", "com.huawei.android.launcher"
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

        checkRecentsScreen(event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowContentChanged(event)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName || packageName == "com.android.systemui") return

        if (currentPackage != packageName) {
            currentPackage = packageName
            Log.d(TAG, "Foreground app changed: $packageName")

            // Reset video state when app changes
            isVideoPlaying = false

            // 홈 화면 감지
            val wasOnHomeScreen = isOnHomeScreen
            isOnHomeScreen = launcherPackages.contains(packageName)
            if (wasOnHomeScreen != isOnHomeScreen) {
                Log.d(TAG, "Home screen state changed: $isOnHomeScreen")
                overlay?.setHomeScreenState(isOnHomeScreen)
            }
            updateOverlayVisibility()
        }
        checkFullscreenState()
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        if (settings.autoHideOnVideo && isVideoApp(currentPackage)) {
            checkVideoPlayingState()
        }
    }

    /**
     * 최근 앱 화면인지 확인 (휴리스틱)
     */
    private fun checkRecentsScreen(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val recentsViews = source.findAccessibilityNodeInfosByViewId("com.android.systemui:id/recents_view")
            overlay?.setRecentsState(recentsViews.isNotEmpty())
            recentsViews?.forEach { it.recycle() }
        } finally {
            source.recycle()
        }
    }

    private fun checkFullscreenState() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val screenBounds = windowManager.currentWindowMetrics.bounds
            var foundFullscreen = false
            val windowBounds = Rect()

            for (window in windows) {
                if (window.isActive && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    window.getBoundsInScreen(windowBounds)
                    // If the app window's bounds are nearly the same as the screen's, it's fullscreen.
                    // A direct equality check can fail due to screen cutouts or small system bars.
                    val widthMatches = windowBounds.width() >= screenBounds.width() * 0.99
                    val heightMatches = windowBounds.height() >= screenBounds.height() * 0.99
                    if (widthMatches && heightMatches) {
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

    private fun checkVideoPlayingState() {
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
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        Log.d(TAG, "Keyguard check: isKeyguardLocked = $isLocked")
        if (isLocked) {
            return true
        }

        // 1. 영상 재생 중이면 숨김
        if (settings.autoHideOnVideo && isVideoPlaying) return true

        // 2. 앱별 설정에 따라 숨김
        if (currentPackage.isNotEmpty() && settings.shouldHideForPackage(currentPackage)) return true
        
        return false
    }

    private fun createOverlay() {
        if (overlay == null) {
            overlay = NavBarOverlay(this)
            overlay?.create()
        }
    }

    private fun destroyOverlay() {
        overlay?.destroy()
        overlay = null
    }

    fun executeAction(action: NavAction): Boolean {
        if (action == NavAction.ASSIST) {
            try {
                val intent = Intent(Intent.ACTION_ASSIST)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Executing action: ASSIST")
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