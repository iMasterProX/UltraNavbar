package com.minsoo.ultranavbar.core

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * 윈도우 상태 분석기
 * - 전체화면 상태 감지
 * - IME(키보드) 가시성 감지
 * - 알림 패널 상태 감지
 * - 최근 앱/홈 화면 감지
 * - 잠금화면 감지
 */
class WindowAnalyzer(
    private val context: Context
) {
    companion object {
        private const val TAG = "WindowAnalyzer"
    }

    private data class NavBarInsets(
        val visible: Boolean,
        val sizePx: Int
    )

    data class TopAppWindow(
        val packageName: String,
        val className: String,
        val layer: Int
    )


    // 시스템 서비스 참조
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // 런처 패키지 목록
    private var launcherPackages: Set<String> = emptySet()

    // 네비바 높이 (px)
    private var navBarHeightPx: Int = 0
    private var navBarHeightOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    // IME 추적
    private var lastImeEventAt: Long = 0
    private var imeFocusActive: Boolean = false

    // ===== 초기화 =====

    /**
     * 런처 패키지 목록 로드
     */
    fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName != null) {
                launcherPackages = setOf(resolveInfo.activityInfo.packageName)
                Log.d(TAG, "Detected default launcher: ${resolveInfo.activityInfo.packageName}")
            } else {
                val resolveInfos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                launcherPackages = resolveInfos.map { it.activityInfo.packageName }
                    .filter { it != "com.android.settings" }
                    .toSet()
                Log.d(TAG, "Detected launcher packages: $launcherPackages")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages, using fallback", e)
            // QuickStep 및 Nova Launcher 폴백
            launcherPackages = setOf("com.android.launcher3", "com.teslacoilsw.launcher")
        }
    }

    /**
     * 네비바 높이 계산
     */
    fun calculateNavBarHeight() {
        val actualOrientation = getActualOrientation()
        val resName = if (actualOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            "navigation_bar_height_landscape"
        } else {
            "navigation_bar_height"
        }

        var resId = context.resources.getIdentifier(resName, "dimen", "android")
        if (resId == 0 && actualOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        }

        navBarHeightPx = if (resId > 0) {
            context.resources.getDimensionPixelSize(resId)
        } else {
            context.dpToPx(Constants.Dimension.NAV_BUTTON_SIZE_DP)
        }
        navBarHeightOrientation = actualOrientation
    }

    private fun ensureNavBarHeight() {
        val actualOrientation = getActualOrientation()
        if (navBarHeightPx == 0 || navBarHeightOrientation != actualOrientation) {
            calculateNavBarHeight()
        }
    }

    private fun getActualOrientation(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
            ?: return context.resources.configuration.orientation

        val rotation = display.rotation
        val size = Point()
        val bounds = windowManager.currentWindowMetrics.bounds
        size.x = bounds.width()
        size.y = bounds.height()

        val naturalPortrait = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            size.x < size.y
        } else {
            size.x > size.y
        }

        val isPortrait = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            naturalPortrait
        } else {
            !naturalPortrait
        }

        return if (isPortrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }

    private fun getNavigationBarInsets(): NavBarInsets? {
        return try {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets
            val navInsets = insets.getInsets(WindowInsets.Type.navigationBars())
            val sizePx = maxOf(navInsets.bottom, navInsets.left, navInsets.right)
            NavBarInsets(
                visible = insets.isVisible(WindowInsets.Type.navigationBars()),
                sizePx = sizePx
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun findTopApplicationWindow(windows: List<AccessibilityWindowInfo>): TopAppWindow? {
        var best: TopAppWindow? = null

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue

            val root = try { window.root } catch (e: Exception) { null } ?: continue
            try {
                val packageName = root.packageName?.toString()
                if (packageName == null) {
                    root.recycle()
                    continue
                }
                val className = root.className?.toString() ?: ""
                val layer = window.layer

                if (best == null || layer > best.layer) {
                    best = TopAppWindow(packageName, className, layer)
                }
            } finally {
                root.recycle()
            }
        }

        return best
    }

    fun getTopApplicationWindow(windows: List<AccessibilityWindowInfo>): TopAppWindow? {
        return findTopApplicationWindow(windows)
    }

// ===== 화면 정보 =====

    /**
     * 현재 화면 경계 가져오기
     */
    fun getScreenBounds(): Rect {
        return windowManager.currentWindowMetrics.bounds
    }

    // ===== 앱 서랍 감지 =====

    /**
     * 앱 서랍(App Drawer)이 열려있는지 확인
     * QuickStep 런처의 뷰 ID를 통해 감지
     * @param rootNode 활성 윈도우의 루트 노드
     * @return 앱 서랍 열림 여부
     */
    fun isAppDrawerOpen(rootNode: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        rootNode ?: return false

        // QuickStep 런처 및 Nova Launcher 앱 서랍 뷰 ID
        val targetIds = listOf(
            // QuickStep
            "com.android.launcher3:id/apps_view",
            "com.android.launcher3:id/apps_list_view",
            // Nova Launcher
            "com.teslacoilsw.launcher:id/apps_view",
            "com.teslacoilsw.launcher:id/apps_list_view"
        )

        for (id in targetIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                var found = false
                for (node in nodes) {
                    if (!found && node.isVisibleToUser) {
                        found = true
                    }
                    node.recycle()
                }
                if (found) return true
            }
        }
        return false
    }


    // ===== 전체화면 감지 =====

    /**
     * 전체화면 상태 분석
     * @param windows 현재 윈도우 목록
     * @return 전체화면 여부
     */
    fun analyzeFullscreenState(windows: List<AccessibilityWindowInfo>): Boolean {
        ensureNavBarHeight()
        val minNavBarHeight = maxOf(
            (navBarHeightPx * Constants.Threshold.NAV_BAR_VISIBLE_RATIO).toInt(),
            context.dpToPx(Constants.Dimension.MIN_NAV_BAR_HEIGHT_DP)
        )
        val gestureOnlyThreshold = context.dpToPx(Constants.Threshold.GESTURE_ONLY_HEIGHT_DP)

        val insets = getNavigationBarInsets()
        if (insets != null) {
            val navBarVisible = insets.visible && insets.sizePx >= minNavBarHeight
            val navBarHiddenOrGestureOnly = !insets.visible || insets.sizePx <= gestureOnlyThreshold
            return navBarHiddenOrGestureOnly || !navBarVisible
        }

        val screen = getScreenBounds()
        val maxNavBarHeight = run {
            val scaled = maxOf(navBarHeightPx * 3, context.dpToPx(120))
            minOf(scaled, (screen.height() * 0.4f).toInt())
        }
        var navBarThicknessPx = 0

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = try { w.root } catch (e: Exception) { null }
            val rootPkg = root?.packageName?.toString()
            root?.recycle()
            if (rootPkg != "com.android.systemui") continue

            val r = Rect()
            try {
                w.getBoundsInScreen(r)
            } catch (e: Exception) {
                continue
            }

            val touchesBottom = (r.bottom >= screen.bottom - 2)
            val wideEnough = r.width() >= (screen.width() * 0.5f)
            val reasonableHeight = r.height() <= maxNavBarHeight
            if (touchesBottom && wideEnough && reasonableHeight) {
                navBarThicknessPx = maxOf(navBarThicknessPx, r.height())
            }

            val touchesLeft = (r.left <= screen.left + 2)
            val touchesRight = (r.right >= screen.right - 2)
            val tallEnough = r.height() >= (screen.height() * 0.5f)
            val reasonableWidth = r.width() <= maxNavBarHeight
            if ((touchesLeft || touchesRight) && tallEnough && reasonableWidth) {
                navBarThicknessPx = maxOf(navBarThicknessPx, r.width())
            }
        }

        val navBarVisible = navBarThicknessPx >= minNavBarHeight
        val navBarHiddenOrGestureOnly = navBarThicknessPx <= gestureOnlyThreshold

        return navBarHiddenOrGestureOnly || !navBarVisible
    }

    // ===== IME 감지 =====

    /**
     * IME(키보드) 가시성 분석
     * @param windows 현재 윈도우 목록
     * @return IME 가시 여부
     */
    fun analyzeImeVisibility(windows: List<AccessibilityWindowInfo>): Boolean {
        ensureNavBarHeight()
        val screen = getScreenBounds()
        val minImeHeight = context.dpToPx(Constants.Dimension.MIN_IME_HEIGHT_DP)
        val now = SystemClock.elapsedRealtime()
        val imePackage = getCurrentImePackageName()
        var imeVisible = false

        for (w in windows) {
            val rootPackage = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
            val title = try { w.title?.toString() } catch (e: Exception) { null }

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

        // 윈도우에서 감지 안 되어도 포커스 활성 상태면 IME 가시로 간주
        if (!imeVisible && inputMethodManager.isAcceptingText &&
            (imeFocusActive || now - lastImeEventAt < Constants.Timing.IME_VISIBILITY_DELAY_MS)) {
            imeVisible = true
        }

        return imeVisible
    }

    /**
     * 현재 IME 패키지명 가져오기
     */
    private fun getCurrentImePackageName(): String? {
        val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return imeId?.substringBefore('/')
    }

    /**
     * IME 포커스 상태 업데이트
     */
    fun setImeFocusActive(active: Boolean) {
        imeFocusActive = active
        if (active) {
            lastImeEventAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 텍스트 입력 클래스인지 확인
     */
    fun isTextInputClass(className: CharSequence?): Boolean {
        val name = className?.toString() ?: return false
        return name.endsWith("EditText") || name.contains("TextInput", ignoreCase = true)
    }

    // ===== 알림 패널 감지 =====

    /**
     * 알림 패널 상태 분석
     * @param windows 현재 윈도우 목록
     * @return 알림 패널 열림 여부
     */
    fun analyzeNotificationPanelState(windows: List<AccessibilityWindowInfo>): Boolean {
        val screen = getScreenBounds()
        // Heads-up 알림 윈도우(~100-150dp)와 실제 패널(화면 30%+)을 구분하기 위해
        // 고정 dp 대신 화면 높이 비율 사용
        val minPanelHeight = (screen.height() * Constants.Threshold.NOTIFICATION_PANEL_MIN_HEIGHT_RATIO).toInt()

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = try { w.root } catch (e: Exception) { null }
            val rootPkg = root?.packageName?.toString()
            root?.recycle()
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

    /**
     * 빠른 설정 패널(Quick Settings) 확장 여부 확인
     */
    fun analyzeQuickSettingsState(
        windows: List<AccessibilityWindowInfo>,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        val screen = getScreenBounds()

        if (isQuickSettingsExpanded(rootNode, screen)) return true

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            try {
                val rootPkg = root.packageName?.toString()
                if (rootPkg != "com.android.systemui") continue
                if (isQuickSettingsExpanded(root, screen)) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }

        return false
    }

    private fun isQuickSettingsExpanded(rootNode: AccessibilityNodeInfo?, screen: Rect): Boolean {
        rootNode ?: return false
        val rootPkg = rootNode.packageName?.toString() ?: return false
        if (rootPkg != "com.android.systemui") return false

        val minHeight = (screen.height() * 0.45f).toInt()
        val minWidth = (screen.width() * 0.5f).toInt()

        val targetIds = listOf(
            "com.android.systemui:id/qs_frame",
            "com.android.systemui:id/qs_panel",
            "com.android.systemui:id/quick_settings_panel",
            "com.android.systemui:id/qs_container",
            "com.android.systemui:id/quick_settings_container",
            "com.android.systemui:id/qs_detail",
            "com.android.systemui:id/qs_pager"
        )

        for (id in targetIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                var found = false
                for (node in nodes) {
                    if (!found && node.isVisibleToUser) {
                        val r = Rect()
                        try {
                            node.getBoundsInScreen(r)
                            if (r.height() >= minHeight && r.width() >= minWidth) {
                                found = true
                            }
                        } catch (e: Exception) { /* ignore */ }
                    }
                    node.recycle()
                }
                if (found) return true
            }
        }

        return false
    }

    // ===== 최근 앱 감지 =====

    /**
     * 최근 앱(오버뷰) 화면이 열려있는지 확인
     */
    fun analyzeRecentsState(
        windows: List<AccessibilityWindowInfo>,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (isRecentsOpen(rootNode)) return true

        for (window in windows) {
            val root = try { window.root } catch (e: Exception) { null }
            val className = root?.className?.toString()
            root?.recycle()
            if (isRecentsClassName(className ?: "")) {
                return true
            }

            val title = try { window.title?.toString() } catch (e: Exception) { null }
            if (title != null && isRecentsClassName(title)) {
                return true
            }
        }

        return false
    }

    fun isRecentsOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        rootNode ?: return false

        // QuickStep 런처 최근 앱 뷰 ID
        val targetIds = listOf(
            "com.android.launcher3:id/overview_panel",
            "com.android.launcher3:id/overview_actions_view"
        )

        for (id in targetIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                var found = false
                for (node in nodes) {
                    if (!found && node.isVisibleToUser) {
                        found = true
                    }
                    node.recycle()
                }
                if (found) return true
            }
        }

        return false
    }

    /**
     * 런처가 최상위로 보이더라도 다른 앱 창이 실제로 보이는지 확인
     * QuickStep 앱 실행 전환 화면에서 커스텀 배경을 억제하기 위한 보조 신호.
     */
    fun hasVisibleNonLauncherAppWindow(
        windows: List<AccessibilityWindowInfo>,
        selfPackage: String
    ): Boolean {
        var hasUnknownAppWindow = false
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (e: Exception) {
                continue
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            root?.recycle()

            if (pkg == null) {
                hasUnknownAppWindow = true
                continue
            }
            if (pkg == selfPackage) continue
            if (isLauncherPackage(pkg)) continue
            return true
        }
        return hasUnknownAppWindow
    }

    // ===== 홈/최근 앱 감지 =====

    /**
     * 패키지가 런처인지 확인
     */
    fun isLauncherPackage(packageName: String): Boolean {
        return launcherPackages.contains(packageName)
    }

    /**
     * 런처 패키지 목록 반환
     */
    fun getLauncherPackages(): Set<String> = launcherPackages

    /**
     * 최근 앱 화면 클래스명인지 확인
     */
    fun isRecentsClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Recents", ignoreCase = true) ||
            simpleName.contains("Overview", ignoreCase = true) ||
            simpleName.contains("TaskSwitcher", ignoreCase = true)
    }

    // ===== 잠금화면 감지 =====

    /**
     * 잠금화면 활성 상태 확인
     */
    fun isLockScreenActive(): Boolean {
        return keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
    }

    // ===== 숨김 여부 판단 =====

    /**
     * 오버레이를 자동 숨김해야 하는지 판단
     * (자동 숨김은 항상 활성화, 제스처로 재호출 가능)
     * @param currentPackage 현재 포그라운드 패키지
     * @param isFullscreen 전체화면 상태
     * @param isOnHomeScreen 홈 화면 여부
     * @param isWallpaperPreviewVisible 배경화면 미리보기 여부
     * @return 숨겨야 하면 true
     */
    fun shouldAutoHideOverlay(
        currentPackage: String,
        isFullscreen: Boolean,
        isOnHomeScreen: Boolean,
        isWallpaperPreviewVisible: Boolean
    ): Boolean {
        if (isLockScreenActive()) return true
        if (isWallpaperPreviewVisible) return true

        // 전체화면이면 자동 숨김 (런처는 예외)
        if (isFullscreen && !isOnHomeScreen && currentPackage.isNotEmpty()) return true

        return false
    }
}
