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
    private var launcherPackages: Set<String> = emptySet()
    
    // Status/Navigation bars height to verify actual fullscreen
    private var systemBarsHeight = 0

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
                    overlay?.hide(animate = false)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present, showing with fade animation.")
                    // 약간의 딜레이를 주어 화면이 완전히 켜진 후 표시
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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
        // Orientation change affects screen bounds, recheck fullscreen
        checkFullscreenState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkFullscreenState()
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
            // 앱이 변경되면 일단 표시 (fullscreen 체크 전) - 유튜브 등 진입 시 숨겨짐 방지
            // 단, 이미 풀스크린이 확실하면 숨김 유지
            updateOverlayVisibility()
        }
        checkFullscreenState()
    }

    private fun checkFullscreenState() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = windowManager.currentWindowMetrics
            val screenBounds = metrics.bounds
            
            var foundFullscreen = false
            val windowBounds = Rect()

            // 윈도우 목록 순회 (Z-order 상위부터)
            for (window in windows) {
                // Application 타입 윈도우만 체크
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    window.getBoundsInScreen(windowBounds)
                    
                    // 화면 전체를 덮는지 확인
                    // 오차 범위(tolerance)를 아주 작게 설정 (10px 미만) - 상태바가 조금이라도 보이면 풀스크린 아님
                    // 이전 98%는 너무 관대해서 유튜브 일반 모드(상태바 있음)도 풀스크린으로 오인 가능성 있음
                    val widthFull = windowBounds.width() >= screenBounds.width()
                    val heightFull = windowBounds.height() >= screenBounds.height()
                    
                    if (widthFull && heightFull) {
                        foundFullscreen = true
                        break
                    }
                }
            }

            if (isFullscreen != foundFullscreen) {
                isFullscreen = foundFullscreen
                Log.d(TAG, "Fullscreen state changed: $isFullscreen (app: $currentPackage)")
                updateOverlayVisibility()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking fullscreen state", e)
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
        if (settings.autoHideOnVideo && isFullscreen && !isOnHomeScreen) return true

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
                // SettingsManager에서 설정된 롱프레스 액션 확인
                // 0: Assistant (Default), 1: Google App
                if (settings.longPressAction == 1) {
                    // 구글 앱 실행
                    val intent = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
                    if (intent != null) {
                         intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                         startActivity(intent)
                         Log.d(TAG, "Executing action: Google App")
                         return true
                    }
                }
                
                // 기본 어시스턴트
                val intent = Intent(Intent.ACTION_VOICE_COMMAND)
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