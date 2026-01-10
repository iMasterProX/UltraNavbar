package com.minsoo.ultranavbar.core

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager


class WindowAnalyzer(
    private val context: Context
) {
    companion object {
        private const val TAG = "WindowAnalyzer"
    }

    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    
    private var launcherPackages: Set<String> = emptySet()

    
    private var navBarHeightPx: Int = 0

    
    private var lastImeEventAt: Long = 0
    private var imeFocusActive: Boolean = false

    

    
    fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName != null) {
                launcherPackages = setOf(resolveInfo.activityInfo.packageName)
            } else {
                val resolveInfos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                launcherPackages = resolveInfos.map { it.activityInfo.packageName }
                    .filter { it != "com.android.settings" }
                    .toSet()
            }
            Log.d(TAG, "Detected launcher packages: $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages, using fallback", e)
            launcherPackages = setOf(
                "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
                "com.sec.android.app.launcher", "com.lge.launcher3", "com.huawei.android.launcher"
            )
        }
    }

    
    fun calculateNavBarHeight() {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        navBarHeightPx = if (resId > 0) {
            context.resources.getDimensionPixelSize(resId)
        } else {
            context.dpToPx(Constants.Dimension.NAV_BUTTON_SIZE_DP)
        }
    }

    

    
    fun getScreenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    

    
    fun analyzeFullscreenState(windows: List<AccessibilityWindowInfo>): Boolean {
        val screen = getScreenBounds()
        var bottomSystemUiHeight = 0

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val rootPkg = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
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

        
        val navVisibleThreshold = maxOf(
            (navBarHeightPx * Constants.Threshold.NAV_BAR_VISIBLE_RATIO).toInt(),
            context.dpToPx(Constants.Dimension.MIN_NAV_BAR_HEIGHT_DP)
        )
        val gestureOnlyThreshold = context.dpToPx(Constants.Threshold.GESTURE_ONLY_HEIGHT_DP)

        val insetResult = runCatching {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val navInsets = insets.getInsets(WindowInsets.Type.navigationBars())
            val navInsetSize = maxOf(navInsets.bottom, navInsets.top, navInsets.left, navInsets.right)
            val navVisible = insets.isVisible(WindowInsets.Type.navigationBars())
            Pair(navVisible, navInsetSize)
        }.getOrNull()

        if (insetResult != null) {
            val (navVisible, navInsetSize) = insetResult
            if (!navVisible) {
                return true
            }
            if (navInsetSize in 1..gestureOnlyThreshold) {
                return true
            }
            if (navInsetSize >= navVisibleThreshold) {
                return false
            }
        }

        val navBarVisible = bottomSystemUiHeight >= navVisibleThreshold
        val navBarHiddenOrGestureOnly = bottomSystemUiHeight <= gestureOnlyThreshold

        return navBarHiddenOrGestureOnly || !navBarVisible
    }

    

    
    fun analyzeImeVisibility(windows: List<AccessibilityWindowInfo>): Boolean {
        val screen = getScreenBounds()
        val minImeHeight = context.dpToPx(Constants.Dimension.MIN_IME_HEIGHT_DP)
        val now = SystemClock.elapsedRealtime()
        val imePackage = getCurrentImePackageName()
        var imeVisible = false

        for (w in windows) {
            val rootPackage = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { w.title?.toString() } catch (e: Exception) { null }
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
            val bottomThreshold = screen.bottom - maxOf(navBarHeightPx, context.dpToPx(24))
            val touchesBottom = r.bottom >= bottomThreshold

            if (tallEnough && touchesBottom) {
                imeVisible = true
                lastImeEventAt = now
                break
            }
        }

        
        if (!imeVisible && inputMethodManager.isAcceptingText &&
            (imeFocusActive || now - lastImeEventAt < Constants.Timing.IME_VISIBILITY_DELAY_MS)) {
            imeVisible = true
        }

        return imeVisible
    }

    
    private fun getCurrentImePackageName(): String? {
        val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return imeId?.substringBefore('/')
    }

    
    fun setImeFocusActive(active: Boolean) {
        imeFocusActive = active
        if (active) {
            lastImeEventAt = SystemClock.elapsedRealtime()
        }
    }

    
    fun isTextInputClass(className: CharSequence?): Boolean {
        val name = className?.toString() ?: return false
        return name.endsWith("EditText") || name.contains("TextInput", ignoreCase = true)
    }

    

    
    fun analyzeNotificationPanelState(windows: List<AccessibilityWindowInfo>): Boolean {
        val screen = getScreenBounds()
        val minPanelHeight = context.dpToPx(Constants.Dimension.MIN_PANEL_HEIGHT_DP)

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val rootPkg = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
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
                return true
            }
        }

        return false
    }

    

    
    fun isLauncherPackage(packageName: String): Boolean {
        return launcherPackages.contains(packageName)
    }

    
    fun isRecentsClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Recents", ignoreCase = true) ||
            simpleName.contains("Overview", ignoreCase = true) ||
            simpleName.contains("TaskSwitcher", ignoreCase = true)
    }

    

    
    fun isLockScreenActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
        } else {
            keyguardManager.isKeyguardLocked
        }
    }

    

    
    fun shouldAutoHideOverlay(
        currentPackage: String,
        isFullscreen: Boolean,
        isOnHomeScreen: Boolean,
        isWallpaperPreviewVisible: Boolean
    ): Boolean {
        if (isLockScreenActive()) return true
        if (isWallpaperPreviewVisible) return true

        
        if (isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) return true

        return false
    }
}
