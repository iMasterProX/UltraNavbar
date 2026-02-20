package com.minsoo.ultranavbar.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.BackgroundManager
import com.minsoo.ultranavbar.core.ButtonManager
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.core.GestureHandler
import com.minsoo.ultranavbar.core.NavbarAppsPanel
import com.minsoo.ultranavbar.core.RecentAppsManager
import com.minsoo.ultranavbar.core.RecentAppsTaskbar
import com.minsoo.ultranavbar.core.SplitScreenHelper
import com.minsoo.ultranavbar.core.dpToPx
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 네비게이션 바 오버레이
 * 화면 하단에 고정되는 커스텀 네비게이션 바 (TYPE_ACCESSIBILITY_OVERLAY)
 *
 * 리팩토링된 구조:
 * - BackgroundManager: 배경 이미지/색상 관리
 * - ButtonManager: 버튼 생성/스타일 관리
 * - GestureHandler: 제스처 감지 관리
 */
class NavBarOverlay(private val service: NavBarAccessibilityService) {

    companion object {
        private const val TAG = "NavBarOverlay"
    }

    private val context: Context = service
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())

    // === 컴포넌트 매니저 ===
    private lateinit var backgroundManager: BackgroundManager
    private lateinit var buttonManager: ButtonManager
    private lateinit var gestureHandler: GestureHandler
    private var recentAppsManager: RecentAppsManager? = null
    private var recentAppsTaskbar: RecentAppsTaskbar? = null
    private var navbarAppsPanel: NavbarAppsPanel? = null
    private var cachedLauncherPackages: Set<String> = emptySet()

    // === 뷰 참조 ===
    private var rootView: FrameLayout? = null
    private var backgroundView: View? = null
    private var navBarView: ViewGroup? = null
    private var gestureOverlayView: View? = null
    private var hotspotView: View? = null
    private var splitScreenOverlayView: View? = null  // 드래그 시 분할화면 피드백 오버레이
    private var dragOverlayView: FrameLayout? = null  // 드래그 중 아이콘 표시용 별도 오버레이 윈도우
    private var dragIconView: ImageView? = null  // 드래그 오버레이 안의 아이콘 복사본
    private var dragIconCenterOffsetX: Float = 0f  // 아이콘 중심 오프셋
    private var dragIconCenterOffsetY: Float = 0f
    private var dragFreeMove: Boolean = false  // true: 손가락 추적 자유이동, false: X 고정 수직이동
    private var centerGroupView: View? = null  // 최근 앱 작업 표시줄 뷰 (홈화면에서 숨기기용)

    // === 상태 ===
    private var isShowing = true
    private var isCreated = false
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    // 시스템 상태
    private var currentPackage: String = ""
    private var isOnHomeScreen = false
    private var isRecentsVisible = false
    private var isAppDrawerOpen = false
    private var isImeVisible = false
    private var isNotificationPanelOpen = false
    private var isQuickSettingsOpen = false
    private var isPanelButtonOpen = false
    private var isHomeExitPending = false
    private var homeExitSuppressUntil: Long = 0L
    private var homeExitSuppressTask: Runnable? = null

    // 다크 모드 전환 추적
    private var darkModeTransitionTime: Long = 0

    // 디바운스용 Runnable
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null
    private var pendingRecentsClose: Runnable? = null
    private var pendingPanelClose: Runnable? = null

    // 숨김 애니메이션 진행 중 추적 (홈 화면 복귀 시 복원 여부 결정)
    private var hideAnimationInProgress: Boolean = false
    
    // 현재 실행 중인 애니메이션 (취소용)
    private var currentAnimator: Animator? = null
    private var isCancellingAnimator: Boolean = false

    // 잠금화면 해제 시 페이드 애니메이션 대기 플래그
    private var isUnlockPending: Boolean = false
    private var isUnlockFadeRunning: Boolean = false
    private var isUnlockFadeSuppressed: Boolean = false
    private var unlockUseCustomOverride: Boolean? = null
    private var lastNavbarAppsToggleAt: Long = 0L

    private var currentWindowHeight: Int = -1

    // ===== 컴포넌트 콜백 구현 =====

    private val backgroundListener = object : BackgroundManager.BackgroundChangeListener {
        override fun onButtonColorChanged(color: Int) {
            // 명시적 색상 변경 요청이므로 강제 업데이트
            buttonManager.updateAllButtonColors(color, force = true)
        }

        override fun onBackgroundApplied(drawable: Drawable) {
            // 배경이 적용되면 backgroundView도 업데이트
            if (drawable is ColorDrawable) {
                backgroundView?.setBackgroundColor(drawable.color)
            }
        }
    }

    private val buttonListener = object : ButtonManager.ButtonActionListener {
        override fun onButtonClick(action: NavAction) {
            handleButtonClick(action)
        }

        override fun onButtonLongClick(action: NavAction): Boolean {
            return handleButtonLongClick(action)
        }

        override fun shouldIgnoreTouch(toolType: Int): Boolean {
            return settings.ignoreStylus && toolType == MotionEvent.TOOL_TYPE_STYLUS
        }
    }

    private val gestureListener = object : GestureHandler.GestureListener {
        override fun onSwipeUpDetected() {
            show(fade = false, fromGesture = true)
        }

        override fun onSwipeDownDetected() {
            hideGestureOverlay()
            hide(animate = true, showHotspot = true)
        }

        override fun onGestureAutoHide() {
            if (isShowing) {
                hide(animate = true, showHotspot = true)
            }
        }

        override fun onGestureOverlayTapped() {
            // 탭 시 제스처 오버레이 숨겨서 버튼 클릭 가능하게 함
            hideGestureOverlay()
        }
    }

    private val taskbarListener = object : RecentAppsTaskbar.TaskbarActionListener {
        override fun onAppTapped(packageName: String) {
            Log.d(TAG, "App tapped: $packageName")
            launchApp(packageName)
        }

        /**
         * 앱 실행
         * 분할화면 상태면 종료 후 실행, 아니면 바로 실행
         */
        private fun launchApp(packageName: String) {
            val isSplitScreen = SplitScreenHelper.isSplitScreenActive()

            if (isSplitScreen) {
                // 분할화면 종료 후 앱 실행
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "Exiting split screen before launch: $packageName")

                handler.postDelayed({
                    launchAppDirect(packageName)
                }, 150L)
            } else {
                // 바로 앱 실행
                launchAppDirect(packageName)
            }
        }

        /**
         * 앱 직접 실행
         */
        private fun launchAppDirect(packageName: String) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                   android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    context.startActivity(intent)
                    Log.d(TAG, "App launched: $packageName")
                } else {
                    Log.w(TAG, "Cannot find launch intent for: $packageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: $packageName", e)
            }
        }

        override fun onAppDraggedToSplit(packageName: String) {
            if (!SplitScreenHelper.isResizeableActivity(context, packageName)) {
                showSplitNotSupportedToast(packageName)
                return
            }
            val launchContext = service.getSplitLaunchContext()
            if (launchContext.isOnHomeScreen && !launchContext.isRecentsVisible) {
                Log.d(TAG, "Split request ignored on home screen")
                return
            }
            // UltraNavbar 자체가 전체화면이면 분할화면 미지원 토스트 표시
            if (launchContext.currentPackage.isEmpty() &&
                !launchContext.hasVisibleNonLauncherApp &&
                service.isSelfAppVisibleForSplit()
            ) {
                Log.d(TAG, "Self app in foreground, split screen not supported")
                showSplitNotSupportedToast(context.packageName)
                return
            }
            val fallbackPrimary = getFallbackPrimaryPackage(packageName)
            val launched = SplitScreenHelper.launchSplitScreen(context, packageName, launchContext, fallbackPrimary)
            if (!launched) {
                val failure = SplitScreenHelper.getLastLaunchFailure()
                when (failure) {
                    SplitScreenHelper.SplitLaunchFailure.TARGET_UNSUPPORTED -> {
                        showSplitNotSupportedToast(packageName)
                    }
                    SplitScreenHelper.SplitLaunchFailure.CURRENT_UNSUPPORTED -> {
                        val current = launchContext.currentPackage.takeIf { it.isNotEmpty() }
                        showSplitNotSupportedToast(current)
                    }
                    SplitScreenHelper.SplitLaunchFailure.NO_PRIMARY -> {
                        // 현재 앱이 분할화면을 지원하지 않아 primary가 없는 경우
                        // (예: UltraNavbar 앱 자체에서 분할화면 시도)
                        showSplitNotSupportedToast(null)
                    }
                    SplitScreenHelper.SplitLaunchFailure.IO_SYSTEM_ERROR -> {
                        showSplitIoErrorToast()
                    }
                    else -> {
                        Log.w(TAG, "Split launch failed: $failure (target=$packageName)")
                    }
                }
            }
        }

        fun getFallbackPrimaryPackage(targetPackage: String): String? =
            this@NavBarOverlay.getFallbackPrimaryPackage(targetPackage)

        override fun onDragStateChanged(isDragging: Boolean, progress: Float) {
            updateSplitScreenOverlay(isDragging, progress)
        }

        override fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float) {
            dragFreeMove = true  // 태스크바: Android 12L 스타일 자유 드래그
            showDragOverlay(iconView, screenX, screenY)
        }

        override fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float) {
            updateDragOverlayPosition(screenX, screenY, scale)
        }

        override fun onDragEnd() {
            hideDragOverlay()
        }

        override fun shouldIgnoreTouch(toolType: Int): Boolean {
            return settings.ignoreStylus && toolType == MotionEvent.TOOL_TYPE_STYLUS
        }
    }

    private val navbarAppsPanelListener = object : NavbarAppsPanel.PanelActionListener {
        override fun onAppTapped(packageName: String) {
            Log.d(TAG, "NavbarApps: app tapped: $packageName")
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                   android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "NavbarApps: launch failed: $packageName", e)
            }
        }

        override fun onAppDraggedToSplit(packageName: String) {
            if (!SplitScreenHelper.isResizeableActivity(context, packageName)) {
                showSplitNotSupportedToast(packageName)
                return
            }
            val launchContext = service.getSplitLaunchContext()
            if (launchContext.isOnHomeScreen && !launchContext.isRecentsVisible) {
                Log.d(TAG, "NavbarApps: split request ignored on home screen")
                return
            }
            if (launchContext.currentPackage.isEmpty() &&
                !launchContext.hasVisibleNonLauncherApp &&
                service.isSelfAppVisibleForSplit()
            ) {
                showSplitNotSupportedToast(context.packageName)
                return
            }
            val fallbackPrimary = getFallbackPrimaryPackage(packageName)
            val launched = SplitScreenHelper.launchSplitScreen(context, packageName, launchContext, fallbackPrimary)
            if (!launched) {
                val failure = SplitScreenHelper.getLastLaunchFailure()
                when (failure) {
                    SplitScreenHelper.SplitLaunchFailure.TARGET_UNSUPPORTED -> {
                        showSplitNotSupportedToast(packageName)
                    }
                    SplitScreenHelper.SplitLaunchFailure.CURRENT_UNSUPPORTED -> {
                        val current = launchContext.currentPackage.takeIf { it.isNotEmpty() }
                        showSplitNotSupportedToast(current)
                    }
                    SplitScreenHelper.SplitLaunchFailure.NO_PRIMARY -> {
                        showSplitNotSupportedToast(null)
                    }
                    SplitScreenHelper.SplitLaunchFailure.IO_SYSTEM_ERROR -> {
                        showSplitIoErrorToast()
                    }
                    else -> {
                        Log.w(TAG, "NavbarApps split launch failed: $failure (target=$packageName)")
                    }
                }
            }
        }

        override fun onDragStateChanged(isDragging: Boolean, progress: Float) {
            updateSplitScreenOverlay(isDragging, progress)
        }

        override fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float) {
            dragFreeMove = true  // 네비바앱스: 손가락 추적 자유이동
            showDragOverlay(iconView, screenX, screenY)
        }

        override fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float) {
            updateDragOverlayPosition(screenX, screenY, scale)
        }

        override fun onDragEnd() {
            hideDragOverlay()
        }

        override fun onAddAppRequested() {
            val intent = android.content.Intent(
                context,
                com.minsoo.ultranavbar.ui.NavbarAppsAddActivity::class.java
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        override fun isOnHomeScreen(): Boolean {
            val launchContext = service.getSplitLaunchContext()
            return launchContext.isOnHomeScreen && !launchContext.isRecentsVisible
        }

        override fun isSplitScreenDragEnabled(): Boolean {
            return settings.splitScreenTaskbarEnabled
        }
    }

    private fun toggleNavbarAppsPanel() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavbarAppsToggleAt < 180L) {
            Log.d(TAG, "NavbarApps toggle debounced")
            return
        }
        lastNavbarAppsToggleAt = now

        val panel = navbarAppsPanel ?: return
        if (panel.isShowing()) {
            panel.hide(reason = "toggle_button")
        } else {
            // 분할화면 오버레이는 드래그 시작 시(onDragStateChanged)에서 생성되므로
            // 패널 열 때는 생성하지 않음 (불필요한 윈도우 추가로 배경 깜빡임 방지)
            val barHeight = getSystemNavigationBarHeightPx()
            val isSwapped = settings.navButtonsSwapped
            panel.show(0, barHeight, isSwapped)
        }
    }

    /**
     * 최근 앱 목록에서 분할화면 가능한 대체 primary 앱을 찾는다.
     * taskbarListener와 navbarAppsPanelListener 모두에서 사용.
     */
    private fun getFallbackPrimaryPackage(targetPackage: String): String? {
        val apps = recentAppsManager?.getRecentApps() ?: return null
        for (app in apps) {
            val pkg = app.packageName
            if (pkg == targetPackage) continue
            if (!SplitScreenHelper.isResizeableActivity(context, pkg)) continue
            return pkg
        }
        return null
    }

    private fun showSplitNotSupportedToast(packageName: String?) {
        val label = packageName?.let { getAppLabel(it) }
        val message = if (!label.isNullOrEmpty()) {
            context.getString(R.string.split_screen_not_supported, label)
        } else {
            context.getString(R.string.split_screen_not_supported_generic)
        }
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showSplitIoErrorToast() {
        try {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.split_screen_io_system_error),
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show split I/O error toast", e)
        }
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info)?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
    }

    private val recentAppsListener = object : RecentAppsManager.RecentAppsChangeListener {
        override fun onRecentAppsChanged(apps: List<RecentAppsManager.RecentAppInfo>) {
            handler.post {
                recentAppsTaskbar?.updateApps(apps)

                // 아이콘 목록 갱신 시점에도 가시성을 동기화해
                // 아이콘이 "뿅" 나타나는 대신 진입 애니메이션이 보이도록 보장
                if (apps.isNotEmpty()) {
                    syncTaskbarVisibility(animate = true)
                } else {
                    hideTaskbarImmediate()
                }
            }
        }
    }

    // ===== 생성/파괴 =====

    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isCreated) return

        try {
            initializeManagers()
            currentOrientation = context.resources.configuration.orientation

            rootView = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                clipChildren = false
                clipToPadding = false
            }

            createNavBar()
            createHotspot()

            // 분할화면 반투명 오버레이를 네비바보다 먼저 추가 (z-order: 뒤)
            createSplitScreenOverlay()

            val params = createLayoutParams()
            windowManager.addView(rootView, params)
            currentWindowHeight = params.height

            isCreated = true
            isShowing = true

            backgroundManager.loadBackgroundBitmaps()
            updateNavBarBackground()
            buttonManager.updatePanelButtonState(isOpen = false, animate = false)

            Log.i(TAG, "Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay", e)
        }
    }

    private fun initializeManagers() {
        backgroundManager = BackgroundManager(context, backgroundListener).apply {
            initializeDarkMode()
            initializeOrientation(context.resources.configuration.orientation)
        }

        buttonManager = ButtonManager(context, buttonListener)
        gestureHandler = GestureHandler(context, gestureListener)

        if (settings.recentAppsTaskbarEnabled) {
            recentAppsManager = RecentAppsManager(context, recentAppsListener)
            recentAppsTaskbar = RecentAppsTaskbar(context, taskbarListener).apply {
                splitScreenEnabled = settings.splitScreenTaskbarEnabled
            }
        }

        navbarAppsPanel = NavbarAppsPanel(context, windowManager, navbarAppsPanelListener)
    }

    fun destroy() {
        if (!isCreated) return

        try {
            // isCreated를 먼저 false로 설정하여 진행 중인 콜백이 즉시 중단되도록 함
            isCreated = false

            cancelPendingTasks()
            cancelCurrentAnimator()
            handler.removeCallbacksAndMessages(null)  // 이 handler의 모든 대기 콜백 제거
            gestureHandler.cleanup()
            backgroundManager.cleanup()
            buttonManager.clear()
            recentAppsTaskbar?.clear()
            recentAppsManager?.clear()
            destroySplitScreenOverlay()
            hideDragOverlay()
            navbarAppsPanel?.hide(immediate = true, reason = "overlay_destroy")
            navbarAppsPanel = null

            windowManager.removeView(rootView)
            rootView = null
            backgroundView = null
            navBarView = null
            gestureOverlayView = null
            hotspotView = null
            centerGroupView = null
            currentWindowHeight = -1

            Log.i(TAG, "Overlay destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy overlay", e)
        }
    }

    private fun cancelPendingTasks() {
        pendingHomeState?.let { handler.removeCallbacks(it) }
        pendingHomeState = null
        clearHomeExitSuppression()
        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
        pendingRecentsClose?.let { handler.removeCallbacks(it) }
        pendingRecentsClose = null
        pendingPanelClose?.let { handler.removeCallbacks(it) }
        pendingPanelClose = null
        taskbarEntryAnimator?.cancel()
        taskbarEntryAnimator = null
        taskbarExitAnimator?.cancel()
        taskbarExitAnimator = null
    }

    // ===== 네비바 생성 =====

    @SuppressLint("ClickableViewAccessibility")
    private fun createNavBar() {
        val barHeightPx = getSystemNavigationBarHeightPx()
        val buttonSizePx = context.dpToPx(Constants.Dimension.NAV_BUTTON_SIZE_DP)
        val buttonSpacingPx = context.dpToPx(Constants.Dimension.BUTTON_SPACING_DP)
        val paddingPx = context.dpToPx(Constants.Dimension.NAV_BAR_PADDING_DP)

        val defaultBgColor = backgroundManager.getDefaultBackgroundColor()
        val initialButtonColor = backgroundManager.currentButtonColor

        // 배경 레이어 (시스템 네비바 가리기용)
        backgroundView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(defaultBgColor)
        }
        rootView?.addView(backgroundView)

        // 네비바 레이아웃
        val bar = RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(defaultBgColor)
            setPadding(paddingPx, 0, paddingPx, 0)
            // 클리핑 비활성화 - 아이콘이 네비바 밖으로 나갈 수 있게
            clipChildren = false
            clipToPadding = false
        }

        // 버튼 배치 반전 설정 확인
        val isSwapped = settings.navButtonsSwapped

        // Navigation buttons group: Back / Home / Recents
        val navGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        navGroup.addView(
            buttonManager.createNavButton(NavAction.BACK, R.drawable.ic_sysbar_back, buttonSizePx, initialButtonColor)
        )
        buttonManager.addSpacerToGroup(navGroup, buttonSpacingPx)
        navGroup.addView(
            buttonManager.createNavButton(NavAction.HOME, R.drawable.ic_sysbar_home, buttonSizePx, initialButtonColor)
        )
        buttonManager.addSpacerToGroup(navGroup, buttonSpacingPx)
        navGroup.addView(
            buttonManager.createNavButton(NavAction.RECENTS, R.drawable.ic_sysbar_recent, buttonSizePx, initialButtonColor)
        )

        // Extra buttons group: Screenshot / NavbarApps / Notifications
        val extraGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (isSwapped) {
            // When swapped to left: Notifications, NavbarApps, Screenshot (innermost = right)
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx, initialButtonColor)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.NAVBAR_APPS, R.drawable.ic_sysbar_apps, buttonSizePx, initialButtonColor)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture, buttonSizePx, initialButtonColor)
            )
        } else {
            // Default order: Screenshot (innermost = left), NavbarApps, Notifications
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture, buttonSizePx, initialButtonColor)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.NAVBAR_APPS, R.drawable.ic_sysbar_apps, buttonSizePx, initialButtonColor)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                buttonManager.createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx, initialButtonColor)
            )
        }

        // 반전 설정에 따라 배치 결정
        val leftGroup = if (isSwapped) extraGroup else navGroup
        val rightGroup = if (isSwapped) navGroup else extraGroup

        // ID 부여 (centerGroup이 참조할 수 있도록)
        leftGroup.id = View.generateViewId()
        rightGroup.id = View.generateViewId()

        val leftParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        bar.addView(leftGroup, leftParams)

        val rightParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        bar.addView(rightGroup, rightParams)

        // Center group: Recent Apps Taskbar
        if (settings.recentAppsTaskbarEnabled) {
            val centerView = recentAppsTaskbar?.createCenterGroup(barHeightPx, initialButtonColor)
            if (centerView != null) {
                val centerParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                }
                bar.addView(centerView, centerParams)
                centerGroupView = centerView

                // 홈화면이면 숨김
                if (isOnHomeScreen || isRecentsVisible) {
                    centerView.visibility = View.GONE
                }
            }
        }

        navBarView = bar
        rootView?.addView(navBarView)

        // 제스처 오버레이 (스와이프 다운 감지)
        createGestureOverlay(barHeightPx)

        updateNavBarBackground()
        buttonManager.updateBackButtonRotation(isImeVisible, animate = false)
    }

    private fun createGestureOverlay(heightPx: Int) {
        gestureOverlayView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                heightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        gestureHandler.setupGestureOverlayTouchListener(gestureOverlayView!!)
        rootView?.addView(gestureOverlayView)
    }

    // ===== 핫스팟 생성 =====

    @SuppressLint("ClickableViewAccessibility")
    private fun createHotspot() {
        val hotspotHeightPx = context.dpToPx(settings.hotspotHeight)

        hotspotView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                hotspotHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        gestureHandler.setupHotspotTouchListener(hotspotView!!)
        rootView?.addView(hotspotView)
    }

    // ===== 레이아웃 파라미터 =====

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val barHeightPx = getSystemNavigationBarHeightPx()

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            width = WindowManager.LayoutParams.MATCH_PARENT
            height = barHeightPx
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }
    }

    private fun getSystemNavigationBarHeightPx(): Int {
        val res = context.resources
        val orientation = if (currentOrientation == Configuration.ORIENTATION_UNDEFINED) {
            resolveActualOrientation()
        } else {
            currentOrientation
        }
        val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val resName = if (isPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
        val id = res.getIdentifier(resName, "dimen", "android")
        val h = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (h > 0) h else context.dpToPx(Constants.Dimension.NAV_BUTTON_SIZE_DP)
    }

    private fun resolveActualOrientation(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
            ?: return context.resources.configuration.orientation

        val rotation = display.rotation
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()

        // 기기의 자연 방향을 감지하여 회전에 따른 실제 방향을 결정
        val naturalPortrait = if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) {
            w < h
        } else {
            w > h
        }

        val isPortrait = if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) {
            naturalPortrait
        } else {
            !naturalPortrait
        }

        return if (isPortrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }

    private fun syncOrientationIfNeeded(reason: String): Boolean {
        val actualOrientation = resolveActualOrientation()
        if (currentOrientation == actualOrientation) return false

        Log.d(TAG, "Orientation mismatch ($reason): $currentOrientation -> $actualOrientation")
        currentOrientation = actualOrientation
        backgroundManager.forceOrientationSync(actualOrientation)

        rootView?.let { root ->
            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
            currentWindowHeight = params.height
        }

        // 분할화면 오버레이 크기 재생성
        recreateSplitScreenOverlay()

        backgroundManager.loadBackgroundBitmaps(forceReload = false)
        return true
    }

    private fun cancelCurrentAnimator() {
        val animator = currentAnimator ?: return
        if (isCancellingAnimator) return
        isCancellingAnimator = true
        try {
            animator.cancel()
        } finally {
            if (currentAnimator == animator) currentAnimator = null
            isCancellingAnimator = false
        }
    }

    private fun startAnimator(
        animator: Animator,
        onStart: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        cancelCurrentAnimator()
        currentAnimator = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                onStart?.invoke()
            }

            override fun onAnimationEnd(animation: Animator) {
                if (currentAnimator == animation) currentAnimator = null
                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                if (currentAnimator == animation) currentAnimator = null
            }
        })
        animator.start()
    }

    private fun createAlphaAnimator(view: View, from: Float, to: Float): ObjectAnimator {
        return ObjectAnimator.ofFloat(view, View.ALPHA, from, to).apply {
            duration = Constants.Timing.ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        }
    }

    private fun createTranslationAnimator(view: View, from: Float, to: Float): ObjectAnimator {
        view.translationY = from
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, from, to).apply {
            duration = Constants.Timing.ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        }
    }

    private fun getBarHeightForAnimation(): Float {
        val barHeight = navBarView?.height ?: 0
        return if (barHeight > 0) barHeight.toFloat() else getSystemNavigationBarHeightPx().toFloat()
    }

    // ===== 표시/숨김 =====

    fun show(fade: Boolean = false, fromGesture: Boolean = false, isUnlockFade: Boolean = false) {
        if (isUnlockPending && !isUnlockFade) {
            Log.d(TAG, "Show ignored: waiting for unlock fade")
            return
        }
        if (isUnlockFadeRunning && !isUnlockFade) {
            Log.d(TAG, "Show ignored: unlock fade running")
            return
        }

        updateTouchableState(true)

        if (fromGesture) {
            gestureHandler.markGestureShow()
            showGestureOverlay()
        }

        val shouldFade = fade || isUnlockFade
        val suppressBackgroundLayer = isUnlockFade || isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed

        val wasHidden = !isShowing
        if (wasHidden && !isUnlockFade) {
            syncOrientationAndBackground()
        }

        val bar = navBarView ?: return
        fun runFadeAnimator(set: AnimatorSet) {
            var unlockFadeCancelled = false
            if (isUnlockFade) {
                set.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        unlockFadeCancelled = true
                    }
                })
                isUnlockFadeRunning = true
            }
            startAnimator(
                set,
                onStart = {
                    if (suppressBackgroundLayer) {
                        backgroundView?.visibility = View.GONE
                    }
                },
                onEnd = {
                    if (isUnlockFade) {
                        isUnlockFadeRunning = false
                        isUnlockFadeSuppressed = isUnlockPending
                        if (!isUnlockPending) {
                            unlockUseCustomOverride = null
                        }
                        if (!isUnlockPending) {
                            restoreBackgroundLayerDefault()
                        }
                        backgroundView?.alpha = 1f
                        backgroundView?.visibility = if (!unlockFadeCancelled && !isUnlockPending) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                        // 언락 페이드 완료 후 상태 동기화 (버튼 색상 등)
                        if (!unlockFadeCancelled && !isUnlockPending) {
                            handler.postDelayed({
                                if (!isCreated) return@postDelayed
                                ensureVisualStateSync()
                            }, 50L)
                        }
                    }
                }
            )
        }

        if (isShowing) {
            updateWindowHeight(getSystemNavigationBarHeightPx())
            if (!shouldFade) return
            if (bar.alpha >= 1f && !isUnlockFade) return

            bar.translationY = 0f
            val animators = mutableListOf<Animator>()
            animators.add(createAlphaAnimator(bar, bar.alpha, 1f))
            if (!suppressBackgroundLayer) {
                backgroundView?.let { bg ->
                    bg.visibility = View.VISIBLE
                    animators.add(createAlphaAnimator(bg, bg.alpha, 1f))
                }
            } else {
                backgroundView?.visibility = View.GONE
            }

            val set = AnimatorSet().apply { playTogether(animators) }
            runFadeAnimator(set)
            return
        }

        if (shouldFade) {
            bar.translationY = 0f
            if (!suppressBackgroundLayer) {
                bar.alpha = 0f
                bar.visibility = View.VISIBLE
                backgroundView?.alpha = 0f
                backgroundView?.visibility = View.VISIBLE
            } else {
                bar.alpha = 0f
                bar.visibility = View.VISIBLE
                backgroundView?.visibility = View.GONE
            }
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())

            val animators = mutableListOf<Animator>()
            animators.add(createAlphaAnimator(bar, bar.alpha, 1f))
            if (!suppressBackgroundLayer) {
                backgroundView?.let { bg ->
                    animators.add(createAlphaAnimator(bg, bg.alpha, 1f))
                }
            }

            val set = AnimatorSet().apply { playTogether(animators) }
            runFadeAnimator(set)
        } else {
            updateWindowHeight(getSystemNavigationBarHeightPx())
            backgroundView?.alpha = 1f
            backgroundView?.visibility = if (suppressBackgroundLayer) View.GONE else View.VISIBLE
            bar.alpha = 1f
            bar.visibility = View.VISIBLE
            hotspotView?.visibility = View.GONE

            val fromY = getBarHeightForAnimation()
            val animator = createTranslationAnimator(bar, fromY, 0f)
            startAnimator(animator)
        }

        isShowing = true
        Log.d(TAG, "Overlay shown (fade=$shouldFade, fromGesture=$fromGesture, unlock=$isUnlockFade)")
    }

    fun startUnlockFade() {
        if (!isUnlockPending) return // 이미 취소됨(화면 꺼짐 등)
        isUnlockPending = false
        
        // 언락 페이드 시작 (배경 없음, 페이드 강제)
        applyUnlockBackgroundToBackLayer()
        show(fade = true, fromGesture = false, isUnlockFade = true)
    }

    fun resetUnlockFadeState(clearOverride: Boolean = true) {
        isUnlockPending = false
        isUnlockFadeRunning = false
        isUnlockFadeSuppressed = false
        if (clearOverride) {
            unlockUseCustomOverride = null
        }
        restoreBackgroundLayerDefault()
    }

    fun captureUnlockBackgroundForLock() {
        // 홈 화면이고 방해 요소가 없을 때만 커스텀 배경으로 캡처
        val capturedCustom = if (isOnHomeScreen &&
            !isRecentsVisible &&
            !isAppDrawerOpen &&
            !isPanelOpen() &&
            !isImeVisible) {
            true
        } else {
            // 방해 요소가 있으면 현재 적용된 상태 유지 (기존 override 값 유지)
            unlockUseCustomOverride ?: false
        }
        unlockUseCustomOverride = capturedCustom
        Log.d(TAG, "Captured unlock background: custom=$capturedCustom (home=$isOnHomeScreen, panel=${isPanelOpen()})")
    }

    fun hide(animate: Boolean = true, showHotspot: Boolean = true) {
        val inUnlockFlow = isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed
        if (showHotspot && !inUnlockFlow) {
            isUnlockPending = false
            isUnlockFadeRunning = false
            isUnlockFadeSuppressed = false
        } else {
            isUnlockFadeRunning = false
        }

        updateTouchableState(true)

        val shouldShowHotspot = showHotspot && settings.hotspotEnabled

        if (!showHotspot && !isShowing) {
            navBarView?.visibility = View.GONE
            backgroundView?.visibility = View.GONE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(0)
            Log.d(TAG, "Force hiding for disabled app (window height = 0)")
            return
        }

        if (!isShowing) return

        val bar = navBarView ?: return
        bar.clearAnimation()
        bar.animate().cancel()

        cancelCurrentAnimator()

        if (!animate) {
            bar.visibility = View.GONE
            bar.translationY = 0f
            backgroundView?.visibility = View.GONE
            if (shouldShowHotspot) {
                updateWindowHeight(context.dpToPx(settings.hotspotHeight))
            } else {
                updateWindowHeight(0)
            }
            hideAnimationInProgress = false
        } else {
            hideAnimationInProgress = true
            val toY = getBarHeightForAnimation()
            val animator = createTranslationAnimator(bar, 0f, toY)
            startAnimator(
                animator,
                onEnd = {
                    hideAnimationInProgress = false
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                    backgroundView?.visibility = View.GONE
                    if (shouldShowHotspot) {
                        updateWindowHeight(context.dpToPx(settings.hotspotHeight))
                    } else {
                        updateWindowHeight(0)
                    }
                }
            )
        }

        hotspotView?.visibility = if (shouldShowHotspot) View.VISIBLE else View.GONE
        isShowing = false
        hideGestureOverlay()
        navbarAppsPanel?.hide(immediate = true, reason = "overlay_hide")
        Log.d(TAG, "Overlay hidden (animate=$animate, showHotspot=$showHotspot)")
    }

    private fun updateWindowHeight(heightPx: Int) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            if (currentWindowHeight == heightPx) return
            params.height = heightPx
            windowManager.updateViewLayout(rootView, params)
            currentWindowHeight = heightPx
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window height", e)
        }
    }

    private fun updateTouchableState(touchable: Boolean) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            val currentFlags = params.flags
            
            val newFlags = if (touchable) {
                currentFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                currentFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            if (currentFlags != newFlags) {
                params.flags = newFlags
                windowManager.updateViewLayout(rootView, params)
                Log.d(TAG, "Window touchable state updated: $touchable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window touchable state", e)
        }
    }

    // ===== 제스처 오버레이 =====

    private fun showGestureOverlay() {
        gestureOverlayView?.visibility = View.VISIBLE
    }

    private fun hideGestureOverlay() {
        gestureOverlayView?.visibility = View.GONE
        gestureHandler.hideGestureOverlay()
    }

    // ===== 자동 숨김 =====

    /**
     * 자동 숨김 가능 여부
     * 다크 모드 전환 중이거나 제스처로 표시된 직후에는 차단
     */
    fun canAutoHide(): Boolean {
        // 다크 모드 상태 변경 여부를 먼저 체크 (onConfigurationChanged보다 먼저 호출될 수 있음)
        checkDarkModeChange()

        // 다크 모드 전환 중에는 차단
        val darkModeElapsed = SystemClock.elapsedRealtime() - darkModeTransitionTime
        if (darkModeElapsed < Constants.Timing.DARK_MODE_DEBOUNCE_MS) {
            Log.d(TAG, "Auto-hide blocked: dark mode transition")
            return false
        }

        // 제스처로 표시된 경우 일정 시간 동안 차단
        if (!gestureHandler.canAutoHide()) {
            return false
        }

        return true
    }

    /**
     * 다크 모드 변경 여부 체크 (조기 감지)
     */
    private fun checkDarkModeChange() {
        if (backgroundManager.updateDarkMode()) {
            darkModeTransitionTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Dark mode change detected early: ${backgroundManager.isDarkMode}")
        }
    }

    // ===== 상태 업데이트 =====

    fun setHomeScreenState(onHome: Boolean) {
        if (onHome) {
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            val wasHomeExitPending = isHomeExitPending
            isHomeExitPending = false
            if (!isOnHomeScreen) {
                isOnHomeScreen = true
                clearHomeExitSuppression()
                Log.d(TAG, "Home screen state: true")

                // 홈화면에서는 최근 앱 작업 표시줄 숨김 (애니메이션 적용)
                animateTaskbarExit()

                // 언락 페이드 중에는 배경 전환하지 않음 (언락 완료 후 자동 업데이트)
                if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
                    Log.d(TAG, "Home entry during unlock fade - skipping background transition")
                    return
                }

                // 숨김 애니메이션만 취소하고, 배경은 페이드로 전환
                cancelHideAnimationOnly()
                applyHomeEntryBackground()
                return
            }

            if (wasHomeExitPending) {
                // 홈 이탈이 취소됨 (패널/앱 서랍이 빠르게 열렸다 닫힌 경우)
                // 억제를 해제하고 올바른 배경 상태를 즉시 적용
                clearHomeExitSuppression()
                val shouldUseCustom = shouldUseCustomBackground()
                if (shouldUseCustom != backgroundManager.wasLastAppliedCustom()) {
                    applyBackgroundImmediate(shouldUseCustom, animate = true)
                }
                return
            }

            val shouldUseCustom = shouldUseCustomBackground()
            if (backgroundManager.isTransitioningFromCustomToDefault() ||
                (shouldUseCustom && !backgroundManager.wasLastAppliedCustom())
            ) {
                // 홈 화면 상태 복원 시 애니메이션 사용 (부드러운 전환)
                applyBackgroundImmediate(shouldUseCustom, animate = true)
            }
            return
        }

        if (!isOnHomeScreen) return
        pendingHomeState?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingHomeState = null
            isHomeExitPending = false
            if (!isOnHomeScreen) return@Runnable
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state: false (debounced)")

            // 홈 -> 앱 전환 시 진입 애니메이션이 항상 보이도록
            // 한 프레임 리셋 후 재생
            playTaskbarEntryFromHomeIfNeeded()

            updateNavBarBackground()
        }
        isHomeExitPending = true
        startHomeExitSuppression()
        updateNavBarBackground()
        pendingHomeState = task
        handler.postDelayed(task, Constants.Timing.HOME_STATE_DEBOUNCE_MS)
    }

    private fun playTaskbarEntryFromHomeIfNeeded() {
        if (!shouldShowTaskbar()) {
            hideTaskbarImmediate()
            return
        }
        hideTaskbarImmediate()
        handler.post {
            if (shouldShowTaskbar()) {
                animateTaskbarEntry()
            }
        }
    }

    // ===== 최근 앱 작업 표시줄 애니메이션 =====

    private var taskbarEntryAnimator: AnimatorSet? = null
    private var taskbarExitAnimator: AnimatorSet? = null

    /**
     * 최근 앱 작업 표시줄 등장 애니메이션 (홈 → 앱)
     * Android 12 스타일: 아래에서 슬라이드 + 페이드 + 스케일
     */
    private fun animateTaskbarEntry() {
        val view = centerGroupView ?: return

        if (!shouldShowTaskbar()) {
            hideTaskbarImmediate()
            return
        }

        if (taskbarEntryAnimator?.isRunning == true) {
            return
        }

        if (view.visibility == View.VISIBLE &&
            taskbarEntryAnimator == null &&
            taskbarExitAnimator == null &&
            view.alpha >= 0.99f
        ) {
            return
        }

        // 퇴장 애니메이션 취소
        taskbarExitAnimator?.cancel()
        taskbarExitAnimator = null

        val startAlpha = if (view.visibility == View.VISIBLE) view.alpha else 0f
        val startTranslationY = if (view.visibility == View.VISIBLE) {
            view.translationY
        } else {
            context.dpToPx(30).toFloat()
        }
        val startScaleX = if (view.visibility == View.VISIBLE) view.scaleX else 0.85f
        val startScaleY = if (view.visibility == View.VISIBLE) view.scaleY else 0.85f

        // 초기 상태 보정
        view.alpha = startAlpha
        view.translationY = startTranslationY
        view.scaleX = startScaleX
        view.scaleY = startScaleY
        view.visibility = View.VISIBLE

        // 애니메이션 생성
        val alphaAnim = ObjectAnimator.ofFloat(view, "alpha", startAlpha, 1f)
        val translateAnim = ObjectAnimator.ofFloat(view, "translationY", startTranslationY, 0f)
        val scaleXAnim = ObjectAnimator.ofFloat(view, "scaleX", startScaleX, 1f)
        val scaleYAnim = ObjectAnimator.ofFloat(view, "scaleY", startScaleY, 1f)

        taskbarEntryAnimator = AnimatorSet().apply {
            playTogether(alphaAnim, translateAnim, scaleXAnim, scaleYAnim)
            duration = 250L
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    taskbarEntryAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    taskbarEntryAnimator = null
                }
            })
            start()
        }
    }

    /**
     * 최근 앱 작업 표시줄 퇴장 애니메이션 (앱 → 홈)
     * Android 12 스타일: 아래로 슬라이드 + 페이드 + 스케일
     */
    private fun animateTaskbarExit() {
        val view = centerGroupView ?: return

        // 등장 애니메이션 취소
        taskbarEntryAnimator?.cancel()
        taskbarEntryAnimator = null

        // 이미 숨겨져 있거나 퇴장 애니메이션 진행 중이면 스킵
        if (view.visibility != View.VISIBLE || taskbarExitAnimator?.isRunning == true) {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.GONE
            }
            return
        }

        // 이전 퇴장 애니메이션 취소
        taskbarExitAnimator?.cancel()

        // 현재 상태에서 시작 (진행 중인 등장 애니메이션이 있었을 수 있음)
        val currentAlpha = view.alpha
        val currentTranslationY = view.translationY
        val currentScaleX = view.scaleX
        val currentScaleY = view.scaleY

        // 애니메이션 생성 - 현재 상태에서 목표 상태로
        val targetTranslationY = context.dpToPx(25).toFloat()
        val alphaAnim = ObjectAnimator.ofFloat(view, "alpha", currentAlpha, 0f)
        val translateAnim = ObjectAnimator.ofFloat(view, "translationY", currentTranslationY, targetTranslationY)
        val scaleXAnim = ObjectAnimator.ofFloat(view, "scaleX", currentScaleX, 0.85f)
        val scaleYAnim = ObjectAnimator.ofFloat(view, "scaleY", currentScaleY, 0.85f)

        taskbarExitAnimator = AnimatorSet().apply {
            playTogether(alphaAnim, translateAnim, scaleXAnim, scaleYAnim)
            duration = 180L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    // 상태 리셋
                    view.alpha = 1f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    taskbarExitAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    taskbarExitAnimator = null
                }
            })
            start()
        }
    }

    /**
     * 최근 앱 작업 표시줄 즉시 숨김 (애니메이션 없이)
     */
    private fun hideTaskbarImmediate() {
        taskbarEntryAnimator?.cancel()
        taskbarExitAnimator?.cancel()
        taskbarEntryAnimator = null
        taskbarExitAnimator = null

        centerGroupView?.apply {
            visibility = View.GONE
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }

    /**
     * 최근 앱 작업 표시줄 즉시 표시 (애니메이션 없이)
     */
    private fun showTaskbarImmediate() {
        taskbarEntryAnimator?.cancel()
        taskbarExitAnimator?.cancel()
        taskbarEntryAnimator = null
        taskbarExitAnimator = null

        centerGroupView?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }

    /**
     * 현재 상태에 맞춰 최근 앱 작업 표시줄 가시성 동기화
     */
    private fun syncTaskbarVisibility(animate: Boolean) {
        if (shouldShowTaskbar()) {
            val view = centerGroupView
            val isCurrentlyHidden =
                view == null ||
                view.visibility != View.VISIBLE ||
                view.alpha < 0.99f ||
                taskbarEntryAnimator != null ||
                taskbarExitAnimator != null

            if (animate || isCurrentlyHidden) {
                animateTaskbarEntry()
            } else {
                showTaskbarImmediate()
            }
        } else {
            hideTaskbarImmediate()
        }
    }

    private fun startHomeExitSuppression() {
        val now = SystemClock.elapsedRealtime()
        val nextUntil = now + Constants.Timing.HOME_STATE_DEBOUNCE_MS
        if (nextUntil > homeExitSuppressUntil) {
            homeExitSuppressUntil = nextUntil
        }
        homeExitSuppressTask?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                homeExitSuppressTask = null
                val remaining = homeExitSuppressUntil - SystemClock.elapsedRealtime()
                if (remaining > 0L) {
                    homeExitSuppressTask = this
                    handler.postDelayed(this, remaining)
                    return
                }
                if (isOnHomeScreen) {
                    // 억제 만료 후 올바른 배경 상태 적용
                    // 현재 상태와 목표 상태가 다르면 전환
                    val shouldUseCustom = shouldUseCustomBackground()
                    if (shouldUseCustom != backgroundManager.wasLastAppliedCustom()) {
                        Log.d(TAG, "Restoring background after suppression: custom=$shouldUseCustom")
                        applyBackgroundImmediate(shouldUseCustom, animate = true)
                    }
                }
            }
        }
        homeExitSuppressTask = task
        handler.postDelayed(task, maxOf(0L, homeExitSuppressUntil - now))
    }

    private fun clearHomeExitSuppression() {
        homeExitSuppressUntil = 0L
        homeExitSuppressTask?.let { handler.removeCallbacks(it) }
        homeExitSuppressTask = null
    }

    /**
     * 홈 진입 시 숨김 애니메이션만 취소 (배경 전환은 유지)
     */
    private fun cancelHideAnimationOnly() {
        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
            return
        }

        // 숨김 애니메이션 취소
        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()
        }
        cancelCurrentAnimator()

        val shouldRestoreToShowing = hideAnimationInProgress || isShowing
        hideAnimationInProgress = false

        if (shouldRestoreToShowing) {
            navBarView?.let { bar ->
                bar.alpha = 1f
                bar.visibility = View.VISIBLE
                bar.translationY = 0f
            }
            backgroundView?.alpha = 1f
            backgroundView?.visibility = View.VISIBLE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())
            isShowing = true
        }
    }

    /**
     * 홈 진입 시 배경을 페이드 효과로 전환
     */
    private fun applyHomeEntryBackground() {
        val bar = navBarView ?: return
        syncOrientationIfNeeded("home_entry")

        val shouldUseCustom = shouldUseCustomBackground()
        backgroundManager.applyBackground(bar, shouldUseCustom, forceUpdate = true, animate = true)

        if (!shouldUseCustom) {
            backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
        }
        Log.d(TAG, "Home entry background applied with fade (custom=$shouldUseCustom)")

        // 홈 진입 완료 후 상태 동기화 예약 (애니메이션 완료 후)
        handler.postDelayed({
            if (!isCreated) return@postDelayed
            ensureVisualStateSync()
        }, Constants.Timing.BG_TRANSITION_DURATION_MS + 50L)
    }

    /**
     * 진행 중인 애니메이션 취소 및 뷰 상태 복원
     * 페이드 애니메이션 중간에 홈 화면 복귀 시 어중간한 상태 방지
     *
     * 숨김 애니메이션 진행 중이면: 취소 후 표시 상태로 복원
     * 의도적으로 숨겨진 상태면 (잠금화면 등): 취소만 하고 숨김 유지
     */
    private fun cancelAnimationsAndRestoreState() {
        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
            backgroundView?.alpha = 1f
            backgroundView?.visibility = View.GONE
            Log.d(TAG, "Animations cancel skipped for unlock fade")
            return
        }
        // 애니메이션 취소
        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()
        }
        
        // 실행 중인 애니메이터 취소
        cancelCurrentAnimator()

        // 진행 중인 배경 페이드 전환 중단
        backgroundManager.cancelBackgroundTransition()

        // 배경 드로어블 알파 복원 (페이드 중단 시 부분 투명 방지)
        (navBarView?.background as? BitmapDrawable)?.alpha = 255

        // 숨김 애니메이션 진행 중이었다면 표시 상태로 복원
        // 그렇지 않으면 (의도적으로 숨겨진 상태) 현재 상태 유지
        val shouldRestoreToShowing = hideAnimationInProgress || isShowing
        hideAnimationInProgress = false

        if (shouldRestoreToShowing) {
            navBarView?.let { bar ->
                bar.alpha = 1f
                bar.visibility = View.VISIBLE
                // 슬라이드 애니메이션에서 남은 translation 초기화
                bar.translationY = 0f
            }
            backgroundView?.alpha = 1f
            backgroundView?.visibility = View.VISIBLE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())
            isShowing = true
            applyBackgroundImmediate(shouldUseCustomBackground())
            Log.d(TAG, "Animations cancelled and state restored to showing (including button color)")
        } else {
            Log.d(TAG, "Animations cancelled, keeping hidden state (for lock screen unlock fade)")
        }
    }

    fun setRecentsState(isRecents: Boolean) {
        if (isRecents) {
            if (isRecentsVisible) return
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null
            pendingRecentsClose?.let { handler.removeCallbacks(it) }
            pendingRecentsClose = null

            if (!isOnHomeScreen) {
                isRecentsVisible = true
                updateNavBarBackground()
                animateTaskbarExit()
                return
            }

            val task = Runnable {
                pendingRecentsState = null
                if (isRecentsVisible) return@Runnable
                isRecentsVisible = true
                updateNavBarBackground()
                hideTaskbarImmediate()
            }
            pendingRecentsState = task
            handler.postDelayed(task, Constants.Timing.RECENTS_STATE_DEBOUNCE_MS)
            return
        }

        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
        pendingRecentsClose?.let { handler.removeCallbacks(it) }
        pendingRecentsClose = null
        if (!isRecentsVisible) return
        isRecentsVisible = false
        updateNavBarBackground()
        if (isOnHomeScreen) {
            hideTaskbarImmediate()
        } else {
            val task = Runnable {
                pendingRecentsClose = null
                syncTaskbarVisibility(animate = true)
            }
            pendingRecentsClose = task
            handler.postDelayed(task, Constants.Timing.RECENTS_STATE_DEBOUNCE_MS)
        }
    }

    fun setImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        buttonManager.updateBackButtonRotation(visible, animate = true)
        updateNavBarBackground()
    }

    fun setAppDrawerState(isOpen: Boolean) {
        if (isAppDrawerOpen == isOpen) return
        isAppDrawerOpen = isOpen
        Log.d(TAG, "App Drawer state changed: $isOpen")
        updateNavBarBackground()
    }

    fun setForegroundPackage(packageName: String) {
        if (currentPackage == packageName) return
        currentPackage = packageName
        Log.d(TAG, "Foreground package changed: $packageName")
        updateNavBarBackground()
        recentAppsManager?.onForegroundAppChanged(packageName)

        // 포그라운드 패키지 변동(IME/런처/앱 전환) 후 진입 애니메이션 우선
        syncTaskbarVisibility(animate = true)
    }

    private fun shouldShowTaskbar(): Boolean {
        if (!settings.recentAppsTaskbarEnabled) return false
        if (isOnHomeScreen || isRecentsVisible || isHomeExitPending) return false
        if (currentPackage.isEmpty()) return false
        if (cachedLauncherPackages.contains(currentPackage)) return false
        return true
    }

    fun setPanelStates(isNotificationOpen: Boolean, isQuickSettingsOpen: Boolean) {
        if (this.isNotificationPanelOpen == isNotificationOpen &&
            this.isQuickSettingsOpen == isQuickSettingsOpen) {
            return
        }

        this.isNotificationPanelOpen = isNotificationOpen
        this.isQuickSettingsOpen = isQuickSettingsOpen

        if (isPanelOpen()) {
            pendingPanelClose?.let { handler.removeCallbacks(it) }
            pendingPanelClose = null
            if (!isPanelButtonOpen) {
                isPanelButtonOpen = true
                updatePanelButtonState(true)
            }
            // 알림 패널 열면 깜빡임 중지
            buttonManager.stopNotificationBlink()
        } else {
            if (pendingPanelClose == null) {
                val task = Runnable {
                    pendingPanelClose = null
                    if (!isPanelOpen() && isPanelButtonOpen) {
                        isPanelButtonOpen = false
                        updatePanelButtonState(false)
                    }
                }
                pendingPanelClose = task
                handler.postDelayed(task, Constants.Timing.PANEL_CLOSE_DEBOUNCE_MS)
            }
        }
        updateNavBarBackground()
    }

    fun setNotificationPresent(hasNotifications: Boolean) {
        if (hasNotifications && !isPanelOpen()) {
            buttonManager.startNotificationBlink()
        } else {
            buttonManager.stopNotificationBlink()
        }
    }

    fun updatePanelButtonState(isOpen: Boolean) {
        buttonManager.updatePanelButtonState(isOpen)
        buttonManager.updatePanelButtonDescription(
            isOpen,
            context.getString(R.string.notification_panel_open),
            context.getString(R.string.notification_panel_close)
        )
    }

    private fun isPanelOpen(): Boolean {
        return isNotificationPanelOpen || isQuickSettingsOpen
    }

    fun setFullscreenState(fullscreen: Boolean) {
        // no-op: canAutoHide()로 처리
    }

    // ===== 방향 변경 =====

    fun handleOrientationChange(newOrientation: Int) {
        if (currentOrientation == newOrientation) return
        currentOrientation = newOrientation
        backgroundManager.handleOrientationChange(newOrientation)

        val orientationName = if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "Orientation changed: $orientationName")

        rootView?.let { root ->
            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
            currentWindowHeight = params.height
        }

        // 진행 중인 배경 전환 취소 (stale bitmap reference 방지)
        backgroundManager.cancelBackgroundTransition()

        // 방향 변경 시 반드시 새 배경 로드 (가로/세로 이미지가 다름)
        backgroundManager.loadBackgroundBitmaps(forceReload = true)
        updateNavBarBackground()
    }

    // ===== 다크 모드 =====

    fun updateDarkMode() {
        val darkModeChanged = backgroundManager.updateDarkMode()
        if (darkModeChanged) {
            darkModeTransitionTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Dark mode changed: ${backgroundManager.isDarkMode}")

            // 진행 중인 배경 전환 취소 (색상 전환이 필요하므로)
            backgroundManager.cancelBackgroundTransition()

            // 다크 모드 전환 시 비트맵 리로드 (다크 변형 사용을 위해)
            backgroundManager.loadBackgroundBitmaps(forceReload = true)

            // forceUpdate=true로 호출하여 dedup 로직을 우회
            // (useCustom 값이 같아도 색상이 WHITE↔BLACK으로 바뀌므로 반드시 업데이트 필요)
            val bar = navBarView ?: return
            val shouldUseCustom = shouldUseCustomBackground()
            backgroundManager.applyBackground(bar, shouldUseCustom, forceUpdate = true, animate = true)
            if (!shouldUseCustom) {
                backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
            }

            // 분할화면 오버레이 색상 업데이트
            updateSplitScreenOverlayColor()
        }
    }

    private fun updateSplitScreenOverlayColor() {
        splitScreenOverlayView?.setBackgroundColor(
            if (backgroundManager.isDarkMode) Color.WHITE else Color.BLACK
        )
    }

    /**
     * 방향 변경 + 다크 모드 변경을 한 번에 처리
     * onConfigurationChanged에서 호출하여 비트맵 리로드를 한 번만 수행
     */
    fun handleConfigurationChange(newOrientation: Int) {
        val orientationChanged = currentOrientation != newOrientation
        val darkModeChanged = backgroundManager.updateDarkMode()

        if (darkModeChanged) {
            darkModeTransitionTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Dark mode changed during config change: ${backgroundManager.isDarkMode}")
        }

        if (orientationChanged) {
            currentOrientation = newOrientation
            backgroundManager.handleOrientationChange(newOrientation)

            val orientationName = if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
            Log.d(TAG, "Orientation changed during config change: $orientationName")

            rootView?.let { root ->
                val params = createLayoutParams()
                windowManager.updateViewLayout(root, params)
                currentWindowHeight = params.height
            }

            // 분할화면 오버레이 크기 재생성 (방향에 맞게)
            recreateSplitScreenOverlay()
        }

        if (orientationChanged || darkModeChanged) {
            // 진행 중인 배경 전환 취소 후 비트맵 리로드 (1회만)
            backgroundManager.cancelBackgroundTransition()
            backgroundManager.loadBackgroundBitmaps(forceReload = true)

            // forceUpdate로 배경 강제 적용 (dedup 우회)
            val bar = navBarView ?: return
            val shouldUseCustom = shouldUseCustomBackground()
            backgroundManager.applyBackground(
                bar, shouldUseCustom,
                forceUpdate = true,
                animate = !orientationChanged  // 방향 전환 시는 즉시, 다크 모드만 변경 시는 페이드
            )
            if (!shouldUseCustom) {
                backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
            }

            // 분할화면 오버레이 색상 업데이트
            if (darkModeChanged) {
                updateSplitScreenOverlayColor()
            }
        }
    }

    // ===== 배경 업데이트 =====

    /**
     * 방향 및 배경 강제 동기화
     * 전체화면 모드 복귀 시 또는 홈 화면 복귀 시 호출
     */
    private fun syncOrientationAndBackground() {
        syncOrientationIfNeeded("sync")
        updateNavBarBackground()
    }

    private fun shouldUseCustomBackground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isHomeExitPending || now < homeExitSuppressUntil) {
            return false
        }

        // 홈 상태 플래그가 지연 갱신되는 동안(non-home 앱으로 전환 직후)
        // 커스텀 홈 배경이 재적용되지 않도록 포그라운드 패키지로 한 번 더 가드한다.
        val appForegroundWhileHome =
            isOnHomeScreen &&
            currentPackage.isNotEmpty() &&
            currentPackage != context.packageName &&
            currentPackage != "com.android.systemui" &&
            !service.isLauncherPackageForOverlay(currentPackage)
        if (appForegroundWhileHome) {
            return false
        }

        return backgroundManager.shouldUseCustomBackground(
            isOnHomeScreen = isOnHomeScreen,
            isRecentsVisible = isRecentsVisible,
            isAppDrawerOpen = isAppDrawerOpen,
            isPanelOpen = isPanelOpen(),
            isImeVisible = isImeVisible,
            currentPackage = currentPackage
        )
    }

    private fun resolveUnlockUseCustom(): Boolean {
        val override = unlockUseCustomOverride
        return override ?: backgroundManager.resolveUseCustomForUnlock(shouldUseCustomBackground())
    }

    /**
     * 배경 즉시 적용 (트랜지션 중단 시 사용)
     * @param useCustom 커스텀 배경 사용 여부
     * @param animate 애니메이션 사용 여부 (기본값: false)
     */
    private fun applyBackgroundImmediate(useCustom: Boolean, animate: Boolean = false) {
        val bar = navBarView ?: return
        syncOrientationIfNeeded("background")
        backgroundManager.applyBackground(
            bar,
            useCustom,
            forceUpdate = true,
            animate = animate
        )
        if (!useCustom) {
            backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
        }
    }

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return

        syncOrientationIfNeeded("background")

        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
            val shouldUseCustom = resolveUnlockUseCustom()
            backgroundManager.applyBackground(
                bar,
                shouldUseCustom,
                forceUpdate = true,
                animate = false
            )
            applyUnlockBackgroundToBackLayer()
            return
        }

        val shouldUseCustom = shouldUseCustomBackground()
        if (backgroundManager.isTransitionInProgress() &&
            backgroundManager.wasLastAppliedCustom() != shouldUseCustom
        ) {
            // 진행 중인 트랜지션을 중단하고 올바른 상태로 전환
            // 홈 화면에서는 부드러운 전환을 위해 애니메이션 사용
            applyBackgroundImmediate(shouldUseCustom, animate = isOnHomeScreen)
            return
        }
        backgroundManager.applyBackground(bar, shouldUseCustom)

        if (!shouldUseCustom) {
            backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
        }
    }

    private fun applyUnlockBackgroundToBackLayer() {
        val barBg = navBarView?.background ?: return
        val drawable = when (barBg) {
            is ColorDrawable -> ColorDrawable(barBg.color)
            is BitmapDrawable -> BitmapDrawable(context.resources, barBg.bitmap)
            else -> barBg.constantState?.newDrawable()?.mutate() ?: barBg
        }
        backgroundView?.background = drawable
    }

    private fun restoreBackgroundLayerDefault() {
        backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
    }

    fun ensureOrientationSync() {
        if (syncOrientationIfNeeded("stateCheck")) {
            updateNavBarBackground()
        }
    }

    /**
     * 상태 동기화 확인 및 강제 적용
     * 시각적 상태가 내부 상태와 일치하지 않을 수 있는 상황 후 호출
     * (예: 빠른 앱 전환, 언락 페이드 완료 후 등)
     */
    fun ensureVisualStateSync() {
        if (!isCreated || !isShowing) return

        // 버튼 색상 강제 동기화
        backgroundManager.forceApplyCurrentButtonColor()

        // 배경 레이어 상태 확인
        val shouldUseCustom = shouldUseCustomBackground()
        if (!shouldUseCustom) {
            val expectedBgColor = backgroundManager.getDefaultBackgroundColor()
            backgroundView?.setBackgroundColor(expectedBgColor)
        }

        Log.d(TAG, "Visual state sync completed (home=$isOnHomeScreen, custom=$shouldUseCustom)")
    }

// ===== 버튼 액션 처리 =====

    private fun handleButtonClick(action: NavAction) {
        if (action == NavAction.NAVBAR_APPS) {
            toggleNavbarAppsPanel()
            return
        }
        if (action == NavAction.NOTIFICATIONS) {
            when {
                isQuickSettingsOpen -> {
                    service.executeAction(NavAction.NOTIFICATIONS)
                    setPanelStates(isNotificationOpen = true, isQuickSettingsOpen = false)
                }
                isNotificationPanelOpen -> {
                    service.executeAction(NavAction.DISMISS_NOTIFICATION_SHADE)
                    setPanelStates(isNotificationOpen = false, isQuickSettingsOpen = false)
                }
                else -> {
                    service.executeAction(NavAction.NOTIFICATIONS)
                    setPanelStates(isNotificationOpen = true, isQuickSettingsOpen = false)
                }
            }
        } else {
            service.executeAction(action)
        }
    }

    private fun handleButtonLongClick(action: NavAction): Boolean {
        return when (action) {
            NavAction.HOME -> {
                service.executeAction(NavAction.ASSIST)
                true
            }
            NavAction.NOTIFICATIONS -> {
                if (isQuickSettingsOpen) {
                    service.executeAction(NavAction.DISMISS_NOTIFICATION_SHADE)
                    setPanelStates(isNotificationOpen = false, isQuickSettingsOpen = false)
                } else {
                    service.executeAction(NavAction.QUICK_SETTINGS)
                    setPanelStates(isNotificationOpen = true, isQuickSettingsOpen = true)
                }
                true
            }
            else -> false
        }
    }

    // ===== 분할화면 드래그 오버레이 =====

    /**
     * 드래그 상태에 따라 분할화면 반투명 오버레이 업데이트
     * progress 0.0~1.0: 드래그 진행률
     */
    private fun updateSplitScreenOverlay(isDragging: Boolean, progress: Float) {
        if (isDragging) {
            showSplitScreenOverlay(progress)
        } else {
            hideSplitScreenOverlay()
        }
    }

    /**
     * 분할화면 반투명 오버레이 표시 (화면 오른쪽/아래쪽 절반)
     * 네비바 뒤에 표시되도록 rootView의 첫 번째 자식으로 추가
     */
    private fun showSplitScreenOverlay(progress: Float) {
        if (splitScreenOverlayView == null) {
            createSplitScreenOverlay()
        }

        splitScreenOverlayView?.let { overlay ->
            val alpha = (progress.coerceIn(0f, 1f) * 0.3f)
            overlay.alpha = alpha
            if (overlay.visibility != View.VISIBLE) {
                overlay.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 분할화면 반투명 오버레이 숨기기 (window는 유지, 숨기기만)
     */
    private fun hideSplitScreenOverlay() {
        val overlay = splitScreenOverlayView ?: return

        overlay.animate().cancel()

        if (overlay.visibility == View.VISIBLE && overlay.alpha > 0f) {
            overlay.animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction {
                    overlay.visibility = View.INVISIBLE
                }
                .start()
        } else {
            overlay.alpha = 0f
            overlay.visibility = View.INVISIBLE
        }
    }

    /**
     * 분할화면 반투명 오버레이 윈도우 완전 제거 (오버레이 destroy 시)
     */
    private fun destroySplitScreenOverlay() {
        val overlay = splitScreenOverlayView ?: return
        splitScreenOverlayView = null
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            // 이미 제거됨
        }
    }

    /**
     * 분할화면 반투명 오버레이의 LayoutParams 생성
     * 가로: 오른쪽 절반 / 세로: 아래쪽 절반
     */
    private fun createSplitScreenOverlayParams(): WindowManager.LayoutParams {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        // 실제 화면 비율로 가로/세로 판단 (configuration.orientation이 부정확한 기기 대응)
        val isLandscape = screenWidth > screenHeight

        val overlayWidth: Int
        val overlayHeight: Int

        if (isLandscape) {
            overlayWidth = screenWidth / 2
            overlayHeight = screenHeight
        } else {
            overlayWidth = screenWidth
            overlayHeight = screenHeight / 2
        }

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = overlayWidth
            height = overlayHeight
            // 가로: 오른쪽, 세로: 아래쪽
            gravity = if (isLandscape) Gravity.TOP or Gravity.END else Gravity.BOTTOM
            x = 0
            y = 0
        }
    }

    /**
     * 분할화면 반투명 오버레이 윈도우 생성 (별도 window, 네비바 뒤에 표시)
     */
    private fun createSplitScreenOverlay() {
        if (splitScreenOverlayView != null) return

        val overlayColor = if (backgroundManager.isDarkMode) Color.WHITE else Color.BLACK
        splitScreenOverlayView = View(context).apply {
            setBackgroundColor(overlayColor)
            alpha = 0f
            visibility = View.INVISIBLE
        }

        try {
            windowManager.addView(splitScreenOverlayView, createSplitScreenOverlayParams())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add split screen overlay", e)
            splitScreenOverlayView = null
        }
    }

    /**
     * 분할화면 오버레이 크기 재생성 (방향 변경 시, z-order 유지)
     */
    private fun recreateSplitScreenOverlay() {
        val overlay = splitScreenOverlayView ?: return
        try {
            windowManager.updateViewLayout(overlay, createSplitScreenOverlayParams())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update split screen overlay layout", e)
        }
    }

    // ===== 드래그 아이콘 오버레이 =====

    /**
     * 드래그 시작: 별도 오버레이 윈도우에 아이콘 복사본 표시
     * 메인 네비바 윈도우를 건드리지 않으므로 깜빡임 없음
     */
    private fun showDragOverlay(iconView: ImageView, screenX: Float, screenY: Float) {
        hideDragOverlay()  // 이전 것 정리

        val iconSize = iconView.width
        if (iconSize <= 0) return

        // 아이콘 drawable 복사 + 원형 클리핑 적용
        val drawable = iconView.drawable?.constantState?.newDrawable()?.mutate() ?: return

        val icon = ImageView(context).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 원형 클리핑 (원본 아이콘과 동일)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        dragIconView = icon

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(icon, FrameLayout.LayoutParams(iconSize, iconSize))
        }
        dragOverlayView = container

        // X 기준: 자유이동이면 손가락 위치, 아니면 원본 아이콘 위치 고정
        val iconLoc = IntArray(2)
        iconView.getLocationOnScreen(iconLoc)
        dragIconCenterOffsetX = (iconLoc[0] + iconSize / 2f)  // 태스크바용 고정 X 좌표
        dragIconCenterOffsetY = iconSize / 2f

        val initialX = if (dragFreeMove) {
            (screenX - iconSize / 2f).toInt()
        } else {
            (dragIconCenterOffsetX - iconSize / 2f).toInt()
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = iconSize
            height = iconSize
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = (screenY - dragIconCenterOffsetY).toInt()
        }

        try {
            windowManager.addView(container, params)
            Log.d(TAG, "Drag overlay created at fixedX=${dragIconCenterOffsetX}, y=$screenY, iconSize=$iconSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create drag overlay", e)
            dragOverlayView = null
            dragIconView = null
        }
    }

    /**
     * 드래그 중 아이콘 위치/스케일 업데이트
     * dragFreeMove=true: X/Y 모두 손가락 추적 (앱 즐겨찾기)
     * dragFreeMove=false: X 고정 + 가로모드 중앙 끌림, Y만 이동 (태스크바)
     */
    private fun updateDragOverlayPosition(screenX: Float, screenY: Float, scale: Float) {
        val container = dragOverlayView ?: return

        try {
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return
            val baseSize = dragIconCenterOffsetY.toInt() * 2  // 원본 아이콘 크기
            val scaledSize = (baseSize * scale).toInt()
            // window 크기를 스케일에 맞춰 변경
            params.width = scaledSize
            params.height = scaledSize
            // X: 자유이동이면 손가락 추적, 아니면 원본 위치 고정 (+ 가로모드 중앙 끌림)
            params.x = if (dragFreeMove) {
                (screenX - scaledSize / 2f).toInt()
            } else {
                val targetX = calcTaskbarDragX(screenY)
                (targetX - scaledSize / 2f).toInt()
            }
            params.y = (screenY - scaledSize / 2f).toInt()
            windowManager.updateViewLayout(container, params)

            // 아이콘 뷰 크기도 맞춤
            dragIconView?.layoutParams = FrameLayout.LayoutParams(scaledSize, scaledSize)
        } catch (e: Exception) {
            // 레이아웃 업데이트 실패 무시
        }
    }

    /**
     * 태스크바 드래그 시 X 좌표 계산
     * 가로모드: 위로 올라갈수록 화면 중앙 쪽으로 X 이동 (하단 1/3 지점 도달 시 완전 중앙)
     * 세로모드: 원본 X 고정
     */
    private fun calcTaskbarDragX(screenY: Float): Float {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val sw = bounds.width()
        val sh = bounds.height()
        val isLandscape = sw > sh
        if (!isLandscape) return dragIconCenterOffsetX

        val screenCenterX = sw / 2f
        // 화면 하단(네비바)에서 하단 1/3 지점까지의 progress 계산
        val thresholdY = sh * 2f / 3f  // 하단에서 1/3 지점 (= 상단에서 2/3)
        val startY = sh.toFloat()  // 하단 (네비바 근처)
        val progress = ((startY - screenY) / (startY - thresholdY)).coerceIn(0f, 1f)

        // 원본 X → 화면 중앙 X로 lerp
        return dragIconCenterOffsetX + (screenCenterX - dragIconCenterOffsetX) * progress
    }

    /**
     * 드래그 종료: 오버레이 제거
     */
    private fun hideDragOverlay() {
        val container = dragOverlayView ?: return
        dragOverlayView = null
        dragIconView = null

        try {
            windowManager.removeView(container)
        } catch (e: Exception) {
            // 이미 제거됨
        }
    }

    // ===== 설정 새로고침 =====

    fun refreshSettings() {
        if (!isCreated) return

        // 진행 중인 전환 취소 후 강제 리로드
        backgroundManager.cancelBackgroundTransition()
        backgroundManager.loadBackgroundBitmaps(forceReload = true)

        // Recent apps 컴포넌트 초기화 (createNavBar 전에 해야 centerGroup 생성됨)
        if (settings.recentAppsTaskbarEnabled) {
            if (recentAppsManager == null) {
                recentAppsManager = RecentAppsManager(context, recentAppsListener)
                recentAppsTaskbar = RecentAppsTaskbar(context, taskbarListener).apply {
                    splitScreenEnabled = settings.splitScreenTaskbarEnabled
                }
                recentAppsManager?.setLauncherPackages(cachedLauncherPackages)
                recentAppsManager?.loadInitialRecentApps()
            } else {
                // 설정 변경 시 분할화면 플래그 업데이트
                recentAppsTaskbar?.splitScreenEnabled = settings.splitScreenTaskbarEnabled
            }
        } else {
            recentAppsManager?.clear()
            recentAppsTaskbar?.clear()
            recentAppsManager = null
            recentAppsTaskbar = null
        }

        navbarAppsPanel?.hide(immediate = true, reason = "refresh_settings")
        navbarAppsPanel = NavbarAppsPanel(context, windowManager, navbarAppsPanelListener)

        rootView?.let { root ->
            root.removeAllViews()
            buttonManager.clear()
            centerGroupView = null
            createNavBar()
            createHotspot()

            // 최근 앱 목록 복원 (createNavBar가 새 centerGroup을 만들었으므로)
            if (settings.recentAppsTaskbarEnabled) {
                recentAppsManager?.getRecentApps()?.let { apps ->
                    if (apps.isNotEmpty()) {
                        recentAppsTaskbar?.updateApps(apps)
                    }
                }
            }

            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
            currentWindowHeight = params.height
        }

        updateNavBarBackground()
        buttonManager.updatePanelButtonState(isPanelOpen())
    }

    fun refreshBackgroundStyle() {
        if (!isCreated) return
        updateNavBarBackground()
    }

    fun initializeRecentApps(launcherPackages: Set<String>) {
        cachedLauncherPackages = launcherPackages
        recentAppsManager?.setLauncherPackages(launcherPackages)
        recentAppsManager?.loadInitialRecentApps()
    }

    fun reloadBackgroundImages() {
        // 진행 중인 전환 취소 후 배경 이미지 강제 리로드
        backgroundManager.cancelBackgroundTransition()
        backgroundManager.loadBackgroundBitmaps(forceReload = true)
        updateNavBarBackground()
    }

    // ===== 유틸리티 =====

    /**
     * 잠금화면 해제 시 페이드 애니메이션 준비
     * 화면 켜질 때 잠금화면이 활성화된 경우 호출
     *
     * 핵심: 윈도우 높이를 미리 설정하여 show() 시 윈도우 리사이즈가 없도록 함
     * 윈도우 리사이즈가 슬라이드처럼 보이는 현상을 방지
     */
    fun prepareForUnlockFade() {
        isUnlockPending = true
        isUnlockFadeRunning = false
        isUnlockFadeSuppressed = true

        // 언락 시점의 실시간 상태로 판단 (override 무시)
        // 홈 화면이면 커스텀 배경 사용 (알림 패널은 언락 시 이미 닫혀있음)
        val useCustomForUnlock = if (isOnHomeScreen) {
            true
        } else {
            unlockUseCustomOverride ?: false
        }
        unlockUseCustomOverride = useCustomForUnlock

        cancelCurrentAnimator()
        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()
        }
        hideAnimationInProgress = false

        // 대기 중일 때는 터치 입력을 통과시켜 잠금 해제 제스처 등을 방해하지 않도록 함
        updateTouchableState(false)

        // 먼저 배경을 적용 (커스텀 배경이 준비되도록)
        navBarView?.let { bar ->
            backgroundManager.applyBackground(
                bar,
                useCustomForUnlock,
                forceUpdate = true,
                animate = false
            )
        }
        applyUnlockBackgroundToBackLayer()

        // 윈도우 높이를 미리 네비바 높이로 설정 (show 시 리사이즈 방지)
        // navBarView만 투명하게 설정하고 VISIBLE (커스텀 배경이 페이드로 나타남)
        // backgroundView는 GONE으로 유지 - 페이드 중에 기본 배경색이 보이지 않도록
        navBarView?.alpha = 0f
        navBarView?.visibility = View.VISIBLE
        backgroundView?.visibility = View.GONE  // 기본 배경색 숨김
        hotspotView?.visibility = View.GONE
        updateWindowHeight(getSystemNavigationBarHeightPx())

        Log.d(TAG, "Prepared for unlock fade (home=$isOnHomeScreen, useCustom=$useCustomForUnlock)")
    }
    fun markNextShowInstant() { /* no-op */ }
}
