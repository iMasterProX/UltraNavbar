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
        private const val GOOGLE_QUICKSEARCHBOX_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val LAUNCHER3_PACKAGE = "com.android.launcher3"
        private const val QUICKSTEP_PLUS_PACKAGE = Constants.Launcher.QUICKSTEPPLUS_PACKAGE
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

    private val launcherOverlayTitleKeywords = listOf(
        "widget",
        "widgets",
        "위젯",
        "wallpaper",
        "배경",
        "menu",
        "drawer",
        "settings",
        "설정"
    )

    private val novaHomePreviewTextGroups = listOf(
        listOf("Wallpaper", "wallpaper", "배경화면", "배경"),
        listOf("Widgets", "widgets", "위젯"),
        listOf("Settings", "settings", "설정")
    )

    private val launcherDragHintTexts = listOf(
        "Remove",
        "Uninstall",
        "App info",
        "삭제",
        "제거",
        "앱 정보"
    )

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
        val detected = mutableSetOf<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }

            // 1. 현재 기본 런처
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName?.let {
                detected.add(it)
                Log.d(TAG, "Detected default launcher: $it")
            }

            // 2. HOME 카테고리에 응답하는 모든 앱 (서드파티 런처 포함)
            val allHomeApps = context.packageManager.queryIntentActivities(intent, 0)
            for (info in allHomeApps) {
                val pkg = info.activityInfo.packageName
                if (pkg != "com.android.settings") {
                    detected.add(pkg)
                }
            }
            Log.d(TAG, "Detected launcher packages: $detected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load launcher packages, using fallback", e)
            detected.add(LAUNCHER3_PACKAGE)
            detected.add(QUICKSTEP_PLUS_PACKAGE)
            detected.add("com.teslacoilsw.launcher")
        }

        val extraLaunchers = setOf(
            LAUNCHER3_PACKAGE,
            "com.android.quickstep",
            QUICKSTEP_PLUS_PACKAGE,
            "com.google.android.apps.nexuslauncher",
            "com.lge.launcher3",
            "com.lge.launcher",
            "com.samsung.android.launcher",
            "com.miui.home",
            "com.oneplus.launcher"
        )
        launcherPackages = (detected + extraLaunchers).toSet()
        Log.d(TAG, "Launcher packages (extended): $launcherPackages")
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

    /**
     * 디스플레이 회전 기반 실제 방향 감지
     * configuration.orientation이 신뢰할 수 없는 기기에서 사용
     */
    fun getOrientationFromDisplay(): Int = getActualOrientation()

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

    fun getTopNonLauncherApplicationWindow(
        windows: List<AccessibilityWindowInfo>,
        selfPackage: String
    ): TopAppWindow? {
        var best: TopAppWindow? = null

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue

            val root = try { window.root } catch (e: Exception) { null } ?: continue
            try {
                val packageName = root.packageName?.toString() ?: continue
                if (packageName == selfPackage || packageName == "com.android.systemui") continue

                val className = root.className?.toString() ?: ""
                if (isLauncherPackage(packageName) || isRecentsClassName(className)) continue
                if (isLauncherCompanionSurface(packageName, className)) continue

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

// ===== 화면 정보 =====

    /**
     * 현재 화면 경계 가져오기
     */
    fun getScreenBounds(): Rect {
        return windowManager.currentWindowMetrics.bounds
    }

    // ===== 런처 오버레이(앱 서랍/위젯 시트/Discover) 감지 =====

    /**
     * 런처의 상단 오버레이 상태(앱 서랍/위젯 시트/Discover 패널) 감지.
     * rootInActiveWindow가 아닌 다른 윈도우에서 열리는 경우도 있어
     * 현재 루트 + 전체 윈도우를 함께 확인한다.
     */
    fun analyzeLauncherOverlayState(
        windows: List<AccessibilityWindowInfo>,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        val launcherVisible = hasLauncherLikeWindow(windows, rootNode)
        if (!launcherVisible) return false
        val screenBounds = getScreenBounds()

        if (isLauncherOverlayOpen(rootNode)) return true
        if (isDiscoverOverlayOpen(rootNode, launcherVisible)) return true
        if (isNovaHomePreviewSurface(rootNode)) return true

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val windowTitle = try { window.title?.toString().orEmpty() } catch (e: Exception) { "" }
            val root = try { window.root } catch (e: Exception) { null } ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                val className = root.className?.toString() ?: ""
                if (isLauncherPackage(pkg)) {
                    if (isLauncherOverlayOpen(root)) return true
                    if (isLauncherNonHomeClass(pkg, className)) return true
                    if (isNovaHomePreviewSurface(root)) return true
                    if (isLauncherOverlayTitle(windowTitle)) {
                        val bounds = Rect()
                        val isOverlayLike = try {
                            window.getBoundsInScreen(bounds)
                            bounds.height() >= (screenBounds.height() * 0.35f).toInt() &&
                                bounds.width() >= (screenBounds.width() * 0.4f).toInt()
                        } catch (e: Exception) {
                            false
                        }
                        if (isOverlayLike) return true
                    }
                    continue
                }
                if (isDiscoverOverlaySurface(pkg, className, launcherVisible)) return true
            } finally {
                root.recycle()
            }
        }

        return false
    }

    private fun isLauncher3BasedPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return packageName == LAUNCHER3_PACKAGE ||
            packageName == QUICKSTEP_PLUS_PACKAGE ||
            packageName == "com.android.quickstep" ||
            packageName == "com.google.android.apps.nexuslauncher"
    }

    private fun buildLauncherViewIds(packageName: String, idNames: List<String>): List<String> {
        if (idNames.isEmpty()) return emptyList()

        val packages = linkedSetOf<String>()
        if (packageName.isNotBlank()) {
            packages.add(packageName)
        }
        if (isLauncher3BasedPackage(packageName)) {
            packages.add(LAUNCHER3_PACKAGE)
        }

        return packages.flatMap { pkg ->
            idNames.map { idName -> "$pkg:id/$idName" }
        }
    }

    fun analyzeLauncherIconDragState(
        windows: List<AccessibilityWindowInfo>,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (!hasLauncherLikeWindow(windows, rootNode)) return false

        val launcherDragIdNames = listOf(
            "drop_target_bar",
            "action_drop_target",
            "delete_target_text",
            "uninstall_target_text",
            "info_target_text"
        )
        val novaDragTargetIds = buildLauncherViewIds("com.teslacoilsw.launcher", launcherDragIdNames)

        val rootPkg = rootNode?.packageName?.toString()
        val rootClass = rootNode?.className?.toString().orEmpty()
        if (!rootPkg.isNullOrEmpty() && isLauncherPackage(rootPkg) && !isRecentsClassName(rootClass)) {
            val dragTargetIds = if (rootPkg == "com.teslacoilsw.launcher") {
                novaDragTargetIds
            } else {
                buildLauncherViewIds(rootPkg, launcherDragIdNames)
            }
            if (hasVisibleNodeByIds(rootNode, dragTargetIds)) {
                return true
            }
            if (rootPkg == "com.teslacoilsw.launcher" &&
                hasVisibleTextHints(rootNode, launcherDragHintTexts, minMatches = 1)
            ) {
                return true
            }
        }

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { window.root } catch (e: Exception) { null } ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                val className = root.className?.toString() ?: ""
                if (!isLauncherPackage(pkg) || isRecentsClassName(className)) continue

                val dragTargetIds = if (pkg == "com.teslacoilsw.launcher") {
                    novaDragTargetIds
                } else {
                    buildLauncherViewIds(pkg, launcherDragIdNames)
                }
                if (hasVisibleNodeByIds(root, dragTargetIds)) {
                    return true
                }
                if (pkg == "com.teslacoilsw.launcher" &&
                    hasVisibleTextHints(root, launcherDragHintTexts, minMatches = 1)
                ) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }

        return false
    }

    fun isLauncherNonHomeSurface(packageName: String, className: String): Boolean {
        return isLauncherNonHomeClass(packageName, className)
    }

    fun isLauncherDragSurface(packageName: String, className: String): Boolean {
        if (!isLauncherPackage(packageName)) return false
        if (className.isBlank()) return false
        if (isRecentsClassName(className)) return false

        val simpleName = className.substringAfterLast('.')
        return simpleName.contains("Drag", ignoreCase = true) ||
            simpleName.contains("DropTarget", ignoreCase = true)
    }

    private fun hasVisibleNodeByIds(
        rootNode: AccessibilityNodeInfo?,
        ids: List<String>
    ): Boolean {
        rootNode ?: return false

        for (id in ids) {
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

    private fun isLauncherOverlayTitle(title: String): Boolean {
        if (title.isBlank()) return false
        return launcherOverlayTitleKeywords.any { keyword ->
            title.contains(keyword, ignoreCase = true)
        }
    }

    private fun isLauncherNonHomeClass(packageName: String, className: String): Boolean {
        if (!isLauncherPackage(packageName)) return false
        if (className.isBlank()) return false
        if (isRecentsClassName(className)) return false

        val normalizedClass = className.trim()
        val simpleName = normalizedClass.substringAfterLast('.')

        if (packageName == "com.teslacoilsw.launcher") {
            if (
                normalizedClass.equals("com.teslacoilsw.launcher.NovaLauncher", ignoreCase = true) ||
                simpleName.equals("NovaLauncher", ignoreCase = true)
            ) {
                return false
            }

            val novaNonHomeKeywords = listOf(
                "widget",
                "drawer",
                "allapps",
                "settings",
                "preference",
                "preview",
                "menu"
            )
            if (novaNonHomeKeywords.any { simpleName.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        if (isLauncher3BasedPackage(packageName)) {
            if (simpleName.equals("Launcher", ignoreCase = true) ||
                simpleName.equals("SearchLauncher", ignoreCase = true)
            ) {
                return false
            }

            val launcher3NonHomeKeywords = listOf(
                "allapps",
                "widget",
                "widgets",
                "sheet",
                "picker",
                "settings",
                "preference",
                "popup"
            )
            if (launcher3NonHomeKeywords.any { simpleName.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        return simpleName.contains("AllApps", ignoreCase = true) ||
            simpleName.contains("Widget", ignoreCase = true) ||
            simpleName.contains("WidgetSheet", ignoreCase = true) ||
            simpleName.contains("WidgetPicker", ignoreCase = true)
    }

    private fun isNovaHomePreviewSurface(rootNode: AccessibilityNodeInfo?): Boolean {
        rootNode ?: return false
        val pkg = rootNode.packageName?.toString() ?: return false
        if (pkg != "com.teslacoilsw.launcher") return false

        val className = rootNode.className?.toString() ?: ""
        if (isRecentsClassName(className)) return false

        val previewIds = listOf(
            "com.teslacoilsw.launcher:id/wallpaper_button",
            "com.teslacoilsw.launcher:id/widgets_button",
            "com.teslacoilsw.launcher:id/settings_button",
            "com.teslacoilsw.launcher:id/overview_panel"
        )
        if (hasVisibleNodeByIds(rootNode, previewIds)) {
            return true
        }

        val matchedGroups = novaHomePreviewTextGroups.count { group ->
            hasVisibleTextHints(rootNode, group, minMatches = 1)
        }
        return matchedGroups >= 2
    }

    private fun hasVisibleTextHints(
        rootNode: AccessibilityNodeInfo?,
        keywords: List<String>,
        minMatches: Int
    ): Boolean {
        rootNode ?: return false
        var matches = 0
        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            if (hasVisibleText(rootNode, keyword)) {
                matches += 1
                if (matches >= minMatches) return true
            }
        }
        return false
    }

    private fun hasVisibleText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = try { rootNode.findAccessibilityNodeInfosByText(text) } catch (e: Exception) { null }
        if (nodes.isNullOrEmpty()) return false

        var found = false
        for (node in nodes) {
            if (!found && node.isVisibleToUser) {
                found = true
            }
            node.recycle()
        }
        return found
    }

    private fun hasLauncherLikeWindow(
        windows: List<AccessibilityWindowInfo>,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        val rootPkg = rootNode?.packageName?.toString()
        val rootClass = rootNode?.className?.toString() ?: ""
        if (!rootPkg.isNullOrEmpty() && (isLauncherPackage(rootPkg) || isRecentsClassName(rootClass))) {
            return true
        }

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { window.root } catch (e: Exception) { null } ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                val className = root.className?.toString() ?: ""
                if (isLauncherPackage(pkg) || isRecentsClassName(className)) {
                    return true
                }
            } finally {
                root.recycle()
            }
        }

        return false
    }

    private fun isDiscoverOverlayOpen(
        rootNode: AccessibilityNodeInfo?,
        launcherVisible: Boolean
    ): Boolean {
        rootNode ?: return false
        val pkg = rootNode.packageName?.toString() ?: return false
        val className = rootNode.className?.toString() ?: ""
        return isDiscoverOverlaySurface(pkg, className, launcherVisible)
    }

    /**
     * 하위 호환용: 기존 호출부는 런처 오버레이 감지로 대체
     */
    fun isAppDrawerOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        return isLauncherOverlayOpen(rootNode)
    }

    private fun isLauncherOverlayOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        rootNode ?: return false
        val packageName = rootNode.packageName?.toString() ?: return false
        if (!isLauncherPackage(packageName)) return false

        val launcherOverlayIdNames = listOf(
            "apps_view",
            "apps_list_view",
            "all_apps_container_view",
            "all_apps_header",
            "search_container_all_apps",
            "widgets_full_sheet",
            "widgets_list_view",
            "widgets_search_bar",
            "widgets_recycler_view",
            "widget_list_view",
            "widget_picker_container",
            "widgets_bottom_sheet",
            "widget_cell"
        )
        val targetIds = if (packageName == "com.teslacoilsw.launcher") {
            listOf(
                "com.teslacoilsw.launcher:id/apps_view",
                "com.teslacoilsw.launcher:id/apps_list_view",
                "com.teslacoilsw.launcher:id/widgets_panel",
                "com.teslacoilsw.launcher:id/widgets_list_view",
                "com.teslacoilsw.launcher:id/widgets_recycler_view",
                "com.teslacoilsw.launcher:id/widget_picker_container",
                "com.teslacoilsw.launcher:id/search_container_all_apps"
            )
        } else {
            buildLauncherViewIds(packageName, launcherOverlayIdNames)
        }

        if (hasVisibleNodeByIds(rootNode, targetIds)) return true
        if (packageName == "com.teslacoilsw.launcher" && isNovaHomePreviewSurface(rootNode)) return true

        val className = rootNode.className?.toString() ?: ""
        return isLauncherNonHomeClass(packageName, className)
    }

    private fun isDiscoverOverlaySurface(
        packageName: String,
        className: String,
        launcherVisible: Boolean
    ): Boolean {
        if (packageName != GOOGLE_QUICKSEARCHBOX_PACKAGE) return false
        if (!launcherVisible) return false
        if (isLauncherCompanionSurface(packageName, className)) return true
        return isGoogleAppDiscoverActivity(className)
    }

    private fun isGoogleAppDiscoverActivity(className: String): Boolean {
        if (className.isBlank()) return false
        if (className.equals("com.google.android.apps.search.googleapp.activity.GoogleAppActivity", ignoreCase = true)) {
            return true
        }
        val simpleName = className.substringAfterLast('.')
        return simpleName.equals("GoogleAppActivity", ignoreCase = true) ||
            simpleName.contains("Discover", ignoreCase = true)
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

        val topWindow = getTopApplicationWindow(windows)
        if (topWindow != null) {
            if (isRecentsClassName(topWindow.className)) return true
            if (isLauncherPackage(topWindow.packageName)) {
                val hasNonLauncher = windows.any { window ->
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
                    val bounds = Rect()
                    try {
                        window.getBoundsInScreen(bounds)
                    } catch (e: Exception) {
                        return@any false
                    }
                    if (bounds.width() <= 0 || bounds.height() <= 0) return@any false

                    val root = try { window.root } catch (e: Exception) { null }
                    val pkg = root?.packageName?.toString()
                    val className = root?.className?.toString()
                    root?.recycle()

                    if (pkg.isNullOrEmpty()) return@any false
                    if (pkg == context.packageName || pkg == "com.android.systemui") return@any false
                    if (isLauncherPackage(pkg) || isRecentsClassName(className ?: "")) return@any false
                    true
                }
                if (hasNonLauncher) return true
            }
        }

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

    /**
     * 분할 화면 선택(런처 + 도킹 앱) 상태인지 판단.
     * 런처가 보이면서, 런처가 아닌 앱 창이 화면의 상당 부분을 차지하고 있으면 true.
     */
    fun analyzeSplitSelectionState(
        windows: List<AccessibilityWindowInfo>,
        selfPackage: String
    ): Boolean {
        val screen = getScreenBounds()
        var launcherVisible = false
        var dockedAppVisible = false

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
            val className = root?.className?.toString()
            root?.recycle()
            if (pkg.isNullOrEmpty()) continue

            val isLauncherLike = isLauncherPackage(pkg) || isRecentsClassName(className ?: "")
            if (isLauncherLike) {
                launcherVisible = true
                continue
            }
            if (pkg == selfPackage || pkg == "com.android.systemui") continue

            val widthRatio = bounds.width().toFloat() / screen.width()
            val heightRatio = bounds.height().toFloat() / screen.height()
            if (widthRatio < 0.9f || heightRatio < 0.9f) {
                dockedAppVisible = true
            }
        }

        return launcherVisible && dockedAppVisible
    }

    fun isRecentsOpen(rootNode: AccessibilityNodeInfo?): Boolean {
        rootNode ?: return false

        val packageName = rootNode.packageName?.toString().orEmpty()
        val targetIds = buildLauncherViewIds(
            packageName,
            listOf("overview_panel", "overview_actions_view")
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
        var launcherVisible = false
        var launcherTopLayer = Int.MIN_VALUE
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            val className = root?.className?.toString()
            root?.recycle()
            if (pkg.isNullOrEmpty()) continue
            if (isLauncherPackage(pkg) || isRecentsClassName(className ?: "")) {
                launcherVisible = true
                if (window.layer > launcherTopLayer) {
                    launcherTopLayer = window.layer
                }
            }
        }

        val screenBounds = getScreenBounds()
        val minUnknownWindowArea =
            (screenBounds.width().toLong() * screenBounds.height().toLong() * 0.2f).toLong()

        var hasUnknownForegroundWindow = false
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
            val className = root?.className?.toString()
            root?.recycle()

            if (pkg == null) {
                val area = bounds.width().toLong() * bounds.height().toLong()
                val unknownCouldBeForeground = !launcherVisible || window.layer > launcherTopLayer
                if (unknownCouldBeForeground && area >= minUnknownWindowArea) {
                    hasUnknownForegroundWindow = true
                }
                continue
            }
            if (pkg == selfPackage) continue
            if (isDiscoverOverlaySurface(pkg, className ?: "", launcherVisible)) continue
            if (isLauncherPackage(pkg) || isRecentsClassName(className ?: "")) continue
            if (launcherVisible && window.layer <= launcherTopLayer) continue
            return true
        }
        return hasUnknownForegroundWindow
    }

    private fun isLauncherCompanionSurface(packageName: String, className: String): Boolean {
        if (packageName != GOOGLE_QUICKSEARCHBOX_PACKAGE) return false
        val simpleName = className.substringAfterLast('.')
        return className.isBlank() ||
            className == "android.widget.FrameLayout" ||
            simpleName.contains("SearchLauncher", ignoreCase = true) ||
            simpleName.contains("LauncherClient", ignoreCase = true)
    }

    /**
     * QuickStep/Launcher3에서 Discover 패널 전환 중 발생하는
     * googlequicksearchbox 표면을 실제 앱 실행으로 간주하지 않도록 런처 패키지로 보정.
     */
    fun remapLauncherCompanionPackage(
        packageName: String,
        className: String,
        windows: List<AccessibilityWindowInfo>,
        selfPackage: String
    ): String? {
        val launcherVisible = hasLauncherLikeWindow(windows, rootNode = null)
        if (!isDiscoverOverlaySurface(packageName, className, launcherVisible)) {
            if (packageName == GOOGLE_QUICKSEARCHBOX_PACKAGE) {
                Log.d(TAG, "Skip companion remap: class=$className, launcherVisible=$launcherVisible")
            }
            return null
        }

        var hasForeignNonLauncherWindow = false
        val topWindow = getTopApplicationWindow(windows)
        var launcherPackageCandidate: String? =
            topWindow?.packageName?.takeIf { isLauncherPackage(it) }

        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { window.root } catch (e: Exception) { null }
            val pkg = root?.packageName?.toString()
            val cls = root?.className?.toString()
            root?.recycle()

            if (pkg.isNullOrEmpty()) continue
            if (pkg == selfPackage || pkg == "com.android.systemui") continue

            if (isDiscoverOverlaySurface(pkg, cls ?: "", launcherVisible)) continue

            val launcherLike = isLauncherPackage(pkg) || isRecentsClassName(cls ?: "")
            if (launcherLike) {
                if (launcherPackageCandidate == null && isLauncherPackage(pkg)) {
                    launcherPackageCandidate = pkg
                }
                continue
            }

            hasForeignNonLauncherWindow = true
            break
        }

        if (hasForeignNonLauncherWindow) {
            Log.d(
                TAG,
                "Skip companion remap: foreignWindow=$hasForeignNonLauncherWindow"
            )
            return null
        }

        return launcherPackageCandidate ?: launcherPackages.firstOrNull()
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
