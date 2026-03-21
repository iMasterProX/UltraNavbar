package com.minsoo.ultranavbar.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.PathInterpolator

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.AnimationPerformanceHelper
import com.minsoo.ultranavbar.core.BackgroundManager
import com.minsoo.ultranavbar.core.ButtonManager
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.core.GestureHandler
import com.minsoo.ultranavbar.core.IconShapeMaskHelper
import com.minsoo.ultranavbar.core.NavbarAppsPanel
import com.minsoo.ultranavbar.core.RecentAppsManager
import com.minsoo.ultranavbar.core.RecentAppsTaskbar
import com.minsoo.ultranavbar.core.SplitScreenHelper
import com.minsoo.ultranavbar.core.dpToPx
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.CustomAppIconStore
import com.minsoo.ultranavbar.util.IconPackManager

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
        private const val RECENTS_TRANSITION_GUARD_MS = 900L
        private const val HOME_BUTTON_PREVIEW_GUARD_MS = 1200L
        private const val CUSTOM_NAV_TRANSITION_COVER_MS = HOME_BUTTON_PREVIEW_GUARD_MS
        private const val TASKBAR_UI_REQUEST_DEDUP_MS = 250L
        private const val HOME_APP_FOREGROUND_GRACE_MS = 1200L
        private const val GOOGLE_QUICKSEARCHBOX_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val QUICKSTEP_PLUS_PACKAGE = Constants.Launcher.QUICKSTEPPLUS_PACKAGE
    }

    private val context: Context = service
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val homeUiTransitionInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        AnimationPerformanceHelper.applyGlobalSettings()
    }

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
    private var homeShadowView: ImageView? = null
    private var gestureOverlayView: View? = null
    private var hotspotView: View? = null
    private var cornerHotspotView: View? = null
    private var gestureDismissStripView: View? = null
    private var splitScreenOverlayView: View? = null  // 드래그 시 분할화면 피드백 오버레이
    private var dragOverlayView: FrameLayout? = null  // 드래그 중 아이콘 표시용 별도 오버레이 윈도우
    private var dragIconView: ImageView? = null  // 드래그 오버레이 안의 아이콘 복사본
    private var dragIconCenterOffsetX: Float = 0f  // 아이콘 중심 오프셋
    private var dragIconCenterOffsetY: Float = 0f
    private var dragFreeMove: Boolean = false  // true: 손가락 추적 자유이동, false: X 고정 수직이동
    private var centerGroupView: View? = null  // 최근 앱 작업 표시줄 뷰 (홈화면에서 숨기기용)
    private var inlineTaskbarDragView: ImageView? = null
    private var inlineTaskbarDragStartX: Float = 0f
    private var inlineTaskbarDragStartY: Float = 0f
    private var taskbarWindowView: FrameLayout? = null
    private var leftButtonGroup: LinearLayout? = null
    private var rightButtonGroup: LinearLayout? = null
    private var homeButtonsAnimator: android.animation.AnimatorSet? = null
    private var areButtonsMovedToCenter: Boolean = false

    // === 상태 ===
    private var isShowing = true
    private var isCreated = false
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    // 시스템 상태
    private var currentPackage: String = ""
    private var currentForegroundClassName: String = ""
    private var isOnHomeScreen = false
    private var isRecentsVisible = false
    private var isAppDrawerOpen = false
    private var isLauncherIconDragActive = false
    private var isImeVisible = false
    private var isNotificationPanelOpen = false
    private var isQuickSettingsOpen = false
    private var isPanelButtonOpen = false
    private var isHomeExitPending = false
    private var homeExitSuppressUntil: Long = 0L
    private var recentsTransitionGuardUntil: Long = 0L
    private var homeButtonPreviewUntil: Long = 0L
    private var homeButtonGrowHintUntil: Long = 0L
    private var transientBackgroundCoverUntil: Long = 0L
    private var lastNonLauncherForegroundAt: Long = 0L
    private var homeExitSuppressTask: Runnable? = null
    private var lastTaskbarUiRequestSignature: String = ""
    private var lastTaskbarUiRequestAt: Long = 0L

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
    private var pendingHomeButtonGrowTask: Runnable? = null

    private var currentWindowHeight: Int = -1

    // QQPlus 런처 홈화면 활성 상태 추적
    // true = QQPlus 런처 홈화면 진입 → 배경 120px 크롭 버전 사용
    private var isQQPlusHomeActive: Boolean = false
    private var currentTaskbarWindowWidth: Int = -1
    private var currentTaskbarWindowHeight: Int = -1
    private var isOverlayTouchable: Boolean = true

    // ===== 컴포넌트 콜백 구현 =====

    private val backgroundListener = object : BackgroundManager.BackgroundChangeListener {
        override fun onButtonColorChanged(color: Int) {
            // 명시적 색상 변경 요청이므로 강제 업데이트
            buttonManager.updateAllButtonColors(color, force = true)
        }

        override fun onBackgroundApplied(drawable: Drawable) {
            // backgroundView는 항상 일반 배경색을 유지한다.
            syncDefaultBackgroundLayer()
            // 배경 페이드가 끝난 시점에는 최종 버튼 색상도 즉시 맞춘다.
            backgroundManager.forceApplyCurrentButtonColor()
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
            show(
                fade = false,
                fromGesture = true,
                slide = isGestureNavbarMode() && !isOnHomeScreen
            )
        }

        override fun onSwipeDownDetected() {
            hideGestureOverlay()
            hide(
                animate = true,
                showHotspot = if (isGestureNavbarMode()) !isOnHomeScreen else true,
                slide = isGestureNavbarMode() && !isOnHomeScreen
            )
        }

        override fun onGestureAutoHide() {
            if (isShowing) {
                hide(
                    animate = true,
                    showHotspot = if (isGestureNavbarMode()) !isOnHomeScreen else true,
                    slide = isGestureNavbarMode() && !isOnHomeScreen
                )
            }
        }

        override fun onGestureOverlayTapped() {
            // 탭 시 제스처 오버레이 숨겨서 버튼 클릭 가능하게 함
            hideGestureOverlay()
        }
    }

    private val taskbarListener = object : RecentAppsTaskbar.TaskbarActionListener {
        override fun onAppTapped(packageName: String, iconView: View?) {
            Log.d(TAG, "App tapped: $packageName")
            launchApp(packageName, iconView)
        }

        /**
         * 앱 실행
         * 분할화면 상태면 종료 후 실행, 아니면 바로 실행
         */
        private fun launchApp(packageName: String, iconView: View? = null) {
            val splitCached = SplitScreenHelper.isSplitScreenActive()
            val isSplitScreen = SplitScreenHelper.isSplitScreenActuallyActive()
            if (splitCached && !isSplitScreen) {
                Log.d(TAG, "Split cache was stale; skipping split toggle for normal launch: $packageName")
            }

            if (isSplitScreen) {
                // 분할화면 종료 후 앱 실행
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Log.d(TAG, "Exiting split screen before launch: $packageName")

                handler.postDelayed({
                    launchAppWithReveal(packageName, null)
                }, 150L)
            } else {
                // 바로 앱 실행
                launchAppWithReveal(packageName, iconView)
            }
        }

        override fun onAppDraggedToSplit(packageName: String) {
            handleSplitLaunchRequest(packageName, sourceTag = "Taskbar")
        }

        override fun onDragStateChanged(isDragging: Boolean, progress: Float) {
            service.setOverlayDragActive(isDragging)
            if (isGestureNavbarMode() && isRecentsVisible) {
                return
            }
            updateSplitScreenOverlay(isDragging, progress)
        }

        override fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float) {
            dragFreeMove = true  // 태스크바: Android 12L 스타일 자유 드래그
            service.setOverlayDragActive(true)
            if (isGestureNavbarMode() && isRecentsVisible) {
                startInlineTaskbarDrag(iconView, screenX, screenY)
                return
            }
            showDragOverlay(iconView, screenX, screenY)
        }

        override fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float) {
            if (isGestureNavbarMode() && isRecentsVisible) {
                updateInlineTaskbarDrag(screenX, screenY, scale)
                return
            }
            updateDragOverlayPosition(screenX, screenY, scale)
        }

        override fun onDragEnd() {
            resetInlineTaskbarDrag()
            hideDragOverlay()
            service.setOverlayDragActive(false)
        }

        override fun shouldIgnoreTouch(toolType: Int): Boolean {
            return settings.ignoreStylus && toolType == MotionEvent.TOOL_TYPE_STYLUS
        }

        override fun isSplitDragAllowed(): Boolean {
            return settings.splitScreenTaskbarEnabled
        }

        override fun onIconSizeAnimationEnd() {
            // 아이콘 축소 애니메이션 완료 후 taskbar 윈도우 크기 갱신
            // (홈→앱 전환 시 overflow 영역을 제거하여 터치 패스스루 복원)
            updateTaskbarWindowBounds()
        }
    }

    private val navbarAppsPanelListener = object : NavbarAppsPanel.PanelActionListener {
        override fun onAppTapped(packageName: String, iconView: View?) {
            Log.d(TAG, "NavbarApps: app tapped: $packageName")
            launchAppWithReveal(packageName, iconView)
        }

        override fun onAppDraggedToSplit(packageName: String) {
            handleSplitLaunchRequest(packageName, sourceTag = "NavbarApps")
        }

        override fun onDragStateChanged(isDragging: Boolean, progress: Float) {
            service.setOverlayDragActive(isDragging)
            updateSplitScreenOverlay(isDragging, progress)
        }

        override fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float) {
            dragFreeMove = true  // 네비바앱스: 손가락 추적 자유이동
            service.setOverlayDragActive(true)
            showDragOverlay(iconView, screenX, screenY)
        }

        override fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float) {
            updateDragOverlayPosition(screenX, screenY, scale)
        }

        override fun onDragEnd() {
            hideDragOverlay()
            service.setOverlayDragActive(false)
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

    /**
     * ClipReveal 애니메이션으로 앱 실행
     * iconView가 있으면 아이콘 위치에서 펼쳐지는 효과, 없으면 기본 실행
     */
    private fun launchAppWithReveal(packageName: String, iconView: View?) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                               android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                if (iconView != null) {
                    val options = ActivityOptions.makeClipRevealAnimation(
                        iconView, 0, 0, iconView.width, iconView.height
                    )
                    context.startActivity(intent, options.toBundle())
                } else {
                    context.startActivity(intent)
                }
                Log.d(TAG, "App launched with reveal: $packageName (hasIcon=${iconView != null})")
            } else {
                Log.w(TAG, "Cannot find launch intent for: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed: $packageName", e)
            // fallback: reveal 없이 실행
            try {
                val fallbackIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (fallbackIntent != null) {
                    fallbackIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                           android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    context.startActivity(fallbackIntent)
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleSplitLaunchRequest(packageName: String, sourceTag: String) {
        if (!SplitScreenHelper.isResizeableActivity(context, packageName)) {
            showSplitNotSupportedToast(packageName)
            return
        }

        val launchContext = service.getSplitLaunchContext()
        if (launchContext.isOnHomeScreen && !launchContext.isRecentsVisible) {
            Log.d(TAG, "$sourceTag: split request ignored on home screen")
            return
        }

        // UltraNavbar 자체가 전체화면이면 분할화면 미지원 토스트 표시
        if (launchContext.currentPackage.isEmpty() &&
            !launchContext.hasVisibleNonLauncherApp &&
            service.isSelfAppVisibleForSplit()
        ) {
            Log.d(TAG, "$sourceTag: self app in foreground, split screen not supported")
            showSplitNotSupportedToast(context.packageName)
            return
        }

        val fallbackPrimary = getFallbackPrimaryPackage(packageName)
        val preferSelectionFromLauncher = true
        val launched = SplitScreenHelper.launchSplitScreen(
            context,
            packageName,
            launchContext,
            fallbackPrimary,
            preferSelectionFromLauncher = preferSelectionFromLauncher
        )
        if (launched) return

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
                showSplitNotSupportedToast(null)
            }
            SplitScreenHelper.SplitLaunchFailure.IO_SYSTEM_ERROR -> {
                showSplitIoErrorToast()
            }
            else -> {
                Log.w(TAG, "$sourceTag split launch failed: $failure (target=$packageName)")
            }
        }
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
            if (settings.taskbarMode != SettingsManager.TaskbarMode.RECENT_APPS) {
                return
            }
            handler.post {
                val displayedApps = apps.take(settings.recentAppsTaskbarIconCount.coerceIn(3, 7))
                recentAppsTaskbar?.updateApps(displayedApps)

                // 아이콘 목록 갱신 시점에도 가시성을 동기화해
                // 아이콘이 "뿅" 나타나는 대신 진입 애니메이션이 보이도록 보장
                if (displayedApps.isNotEmpty()) {
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
            setupNavBarTouchableRegionListener(rootView!!)
            bringTaskbarWindowToFront()

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
            recentAppsTaskbar = RecentAppsTaskbar(context, taskbarListener).apply {
                splitScreenEnabled = settings.splitScreenTaskbarEnabled
                iconShape = settings.recentAppsTaskbarIconShape
            }
            if (!isCustomTaskbarMode()) {
                recentAppsManager = RecentAppsManager(context, recentAppsListener)
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
            destroyTaskbarWindow()
            hideDragOverlay()
            navbarAppsPanel?.hide(immediate = true, reason = "overlay_destroy")
            navbarAppsPanel = null

            windowManager.removeView(rootView)
            rootView = null
            backgroundView = null
            navBarView = null
            homeShadowView = null
            gestureOverlayView = null
            hotspotView = null
            cornerHotspotView = null
            gestureDismissStripView = null
            centerGroupView = null
            resetButtonPositionsImmediate()
            leftButtonGroup = null
            rightButtonGroup = null
            currentWindowHeight = -1
            currentTaskbarWindowWidth = -1
            currentTaskbarWindowHeight = -1

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
        val useAndroid12lLayoutTuning = settings.android12lNavbarLayoutTuningEnabled
        val buttonTouchWidthPx = if (useAndroid12lLayoutTuning) {
            (buttonSizePx * Constants.Dimension.NAV_BUTTON_TOUCH_WIDTH_12L_RATIO)
                .toInt()
                .coerceAtLeast(context.dpToPx(Constants.Dimension.NAV_BUTTON_MIN_TOUCH_WIDTH_12L_DP))
        } else {
            buttonSizePx
        }
        val buttonRippleCornerRadiusPx = if (useAndroid12lLayoutTuning) {
            context.dpToPx(Constants.Dimension.NAV_BUTTON_RIPPLE_CORNER_12L_DP).toFloat()
        } else {
            buttonSizePx / 2f
        }
        val buttonSpacingPx = if (useAndroid12lLayoutTuning) {
            val compactSpacingDp =
                Constants.Dimension.BUTTON_SPACING_DP * Constants.Dimension.NAV_BUTTON_SPACING_12L_RATIO
            context.dpToPx(compactSpacingDp).coerceAtLeast(1)
        } else {
            context.dpToPx(Constants.Dimension.BUTTON_SPACING_DP)
        }
        val paddingPx = context.dpToPx(Constants.Dimension.NAV_BAR_PADDING_DP)
        val edgeInsetPx = if (useAndroid12lLayoutTuning) {
            context.dpToPx(Constants.Dimension.NAV_GROUP_EDGE_INSET_12L_DP)
        } else {
            0
        }
        val edgeInsetCompensationPx = if (useAndroid12lLayoutTuning) {
            ((buttonSizePx - buttonTouchWidthPx) / 2).coerceAtLeast(0)
        } else {
            0
        }
        val effectiveEdgeInsetPx = edgeInsetPx + edgeInsetCompensationPx

        if (useAndroid12lLayoutTuning) {
            Log.d(
                TAG,
                "12L layout metrics: touchWidth=$buttonTouchWidthPx, spacing=$buttonSpacingPx, edgeInset=$effectiveEdgeInsetPx"
            )
        }

        val initialButtonColor = backgroundManager.currentButtonColor

        // 배경 레이어 (시스템 네비바 가리기용)
        backgroundView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            background = backgroundManager.createDefaultBackgroundDrawable()
        }
        rootView?.addView(backgroundView)

        // 네비바 레이아웃
        val bar = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            background = backgroundManager.createDefaultBackgroundDrawable()
            // 클리핑 비활성화 - 아이콘이 네비바 밖으로 나갈 수 있게
            clipChildren = false
            clipToPadding = false
        }

        val contentBar = RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setPadding(paddingPx, 0, paddingPx, 0)
            clipChildren = false
            clipToPadding = false
        }

        homeShadowView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                getQuickstepHomeShadowHeightPx(barHeightPx)
            ).apply {
                gravity = Gravity.BOTTOM
            }
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.quickstep_home_shadow)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            visibility = if (shouldShowQuickstepHomeShadow()) View.VISIBLE else View.GONE
        }
        bar.addView(homeShadowView)
        bar.addView(contentBar)

        // 버튼 배치 반전 설정 확인
        val isSwapped = settings.navButtonsSwapped

        // Navigation buttons group: Back / Home / Recents
        val navGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createButton(action: NavAction, iconResId: Int): ImageButton {
            return buttonManager.createNavButton(
                action = action,
                iconResId = iconResId,
                sizePx = buttonSizePx,
                initialColor = initialButtonColor,
                touchWidthPx = buttonTouchWidthPx,
                rippleCornerRadiusPx = buttonRippleCornerRadiusPx
            )
        }

        navGroup.addView(
            createButton(NavAction.BACK, R.drawable.ic_sysbar_back)
        )
        buttonManager.addSpacerToGroup(navGroup, buttonSpacingPx)
        navGroup.addView(
            createButton(NavAction.HOME, R.drawable.ic_sysbar_home)
        )
        buttonManager.addSpacerToGroup(navGroup, buttonSpacingPx)
        navGroup.addView(
            createButton(NavAction.RECENTS, R.drawable.ic_sysbar_recent)
        )

        // Extra buttons group: Screenshot / NavbarApps / Notifications
        val extraGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (isSwapped) {
            // When swapped to left: Notifications, NavbarApps, Screenshot (innermost = right)
            extraGroup.addView(
                createButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                createButton(NavAction.NAVBAR_APPS, R.drawable.ic_sysbar_apps)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                createButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture)
            )
        } else {
            // Default order: Screenshot (innermost = left), NavbarApps, Notifications
            extraGroup.addView(
                createButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                createButton(NavAction.NAVBAR_APPS, R.drawable.ic_sysbar_apps)
            )
            buttonManager.addSpacerToGroup(extraGroup, buttonSpacingPx)
            extraGroup.addView(
                createButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel)
            )
        }

        val gesturePlaceholderGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        // 반전 설정에 따라 배치 결정
        val leftGroup = when {
            isGestureNavbarMode() && isSwapped -> extraGroup
            isGestureNavbarMode() -> gesturePlaceholderGroup
            isSwapped -> extraGroup
            else -> navGroup
        }
        val rightGroup = when {
            isGestureNavbarMode() && isSwapped -> gesturePlaceholderGroup
            isGestureNavbarMode() -> extraGroup
            isSwapped -> navGroup
            else -> extraGroup
        }

        // ID 부여 (centerGroup이 참조할 수 있도록)
        leftGroup.id = View.generateViewId()
        rightGroup.id = View.generateViewId()

        val leftParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            barHeightPx
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            marginStart = effectiveEdgeInsetPx
        }
        contentBar.addView(leftGroup, leftParams)

        val rightParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            barHeightPx
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            marginEnd = effectiveEdgeInsetPx
        }
        contentBar.addView(rightGroup, rightParams)

        // 버튼 그룹 참조 저장 (홈화면 버튼 이동 애니메이션용)
        leftButtonGroup = leftGroup
        rightButtonGroup = rightGroup

        navBarView = bar
        rootView?.addView(navBarView)

        if (!settings.recentAppsTaskbarEnabled) {
            destroyTaskbarWindow()
        }

        // Center group: Recent Apps Taskbar
        if (settings.recentAppsTaskbarEnabled) {
            val centerView = recentAppsTaskbar?.createCenterGroup(barHeightPx, initialButtonColor)
            if (centerView != null) {
                attachCenterGroupToTaskbarWindow(centerView)
                centerGroupView = centerView

                if (!shouldShowTaskbar()) {
                    centerView.visibility = View.GONE
                }

                updateTaskbarWindowTouchableState()

                updateTaskbarContentForCurrentMode(animate = false)
            }
        }

        // 제스처 오버레이 (스와이프 다운 감지)
        createGestureOverlay(barHeightPx)

        updateNavBarBackground()
        buttonManager.updateBackButtonRotation(isImeVisible, animate = false)
    }

    private fun isGestureNavbarMode(): Boolean = settings.gestureNavbarEnabled

    private fun getGestureActionEdge(): GestureHandler.Edge {
        return if (settings.navButtonsSwapped) GestureHandler.Edge.LEFT else GestureHandler.Edge.RIGHT
    }

    private fun shouldRenderGestureNavbarBackground(): Boolean {
        if (!isGestureNavbarMode()) return true
        return isOnHomeScreen ||
            isRecentsVisible ||
            shouldKeepBackgroundLayerForHomePreview() ||
            isUnlockPending ||
            isUnlockFadeRunning ||
            isUnlockFadeSuppressed
    }

    private fun shouldShowCornerHotspot(): Boolean {
        return false
    }

    private fun shouldShowGestureDismissStrip(): Boolean {
        return false
    }

    private fun getGestureHotspotHeightPx(): Int {
        return maxOf(
            context.dpToPx(settings.hotspotHeight),
            context.dpToPx(Constants.Threshold.GESTURE_ONLY_HEIGHT_DP)
        )
    }

    private fun updateGestureAuxiliaryViews(allowCornerHotspot: Boolean = true) {
        cornerHotspotView?.visibility = if (allowCornerHotspot && shouldShowCornerHotspot()) View.VISIBLE else View.GONE
        gestureDismissStripView?.visibility = if (shouldShowGestureDismissStrip()) View.VISIBLE else View.GONE
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

    private fun createGestureCornerHotspot() {
        val hotspotHeightPx = getGestureHotspotHeightPx()
        val hotspotWidthPx = context.dpToPx(92)
        val edge = getGestureActionEdge()
        cornerHotspotView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                hotspotWidthPx,
                hotspotHeightPx
            ).apply {
                gravity = Gravity.BOTTOM or if (edge == GestureHandler.Edge.LEFT) Gravity.START else Gravity.END
            }
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        gestureHandler.setupCornerHotspotTouchListener(cornerHotspotView!!, edge)
        rootView?.addView(cornerHotspotView)
    }

    private fun createGestureDismissStrip() {
        val edge = getGestureActionEdge()
        gestureDismissStripView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                context.dpToPx(28),
                context.dpToPx(72)
            ).apply {
                gravity = Gravity.BOTTOM or if (edge == GestureHandler.Edge.LEFT) Gravity.START else Gravity.END
            }
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        gestureHandler.setupCornerDismissTouchListener(gestureDismissStripView!!)
        rootView?.addView(gestureDismissStripView)
    }

    // ===== 핫스팟 생성 =====

    @SuppressLint("ClickableViewAccessibility")
    private fun createHotspot() {
        if (isGestureNavbarMode()) {
            hotspotView = null
            cornerHotspotView = null
            gestureDismissStripView = null
            return
        }

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
        val windowHeightPx = getDesiredVisibleWindowHeight()

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            width = WindowManager.LayoutParams.MATCH_PARENT
            height = windowHeightPx
            gravity = Gravity.BOTTOM
            x = 0
            y = 0

            // PRIVATE_FLAG_NO_MOVE_ANIMATION - 윈도우 높이 변경 시 시스템 애니메이션 방지
            try {
                val privateFlagsField = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                privateFlagsField.isAccessible = true
                val current = privateFlagsField.getInt(this)
                privateFlagsField.setInt(this, current or 0x00000040)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set PRIVATE_FLAG_NO_MOVE_ANIMATION on navbar", e)
            }
        }
    }

    private fun createTaskbarLayoutParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            width = widthPx
            height = heightPx
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            windowAnimations = 0 // 시스템 윈도우 전환 애니메이션 비활성화

            // PRIVATE_FLAG_NO_MOVE_ANIMATION (0x40) - 윈도우 리사이즈/이동 시 시스템 애니메이션 차단
            try {
                val privateFlagsField = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                privateFlagsField.isAccessible = true
                val currentPrivateFlags = privateFlagsField.getInt(this)
                privateFlagsField.setInt(this, currentPrivateFlags or 0x00000040)
                Log.d("NavBarOverlay", "PRIVATE_FLAG_NO_MOVE_ANIMATION set successfully")
            } catch (e: Exception) {
                Log.w("NavBarOverlay", "Failed to set PRIVATE_FLAG_NO_MOVE_ANIMATION", e)
            }
        }
    }

    private fun attachCenterGroupToTaskbarWindow(centerView: View) {
        createTaskbarWindowIfNeeded()
        val parent = centerView.parent as? ViewGroup
        parent?.removeView(centerView)
        taskbarWindowView?.removeAllViews()

        val centerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        taskbarWindowView?.addView(centerView, centerParams)
        updateTaskbarWindowBounds()
    }

    private fun createTaskbarWindowIfNeeded() {
        if (taskbarWindowView != null) return

        val window = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutTransition = null // layout 변경 시 시스템 애니메이션 방지
        }

        val widthPx = getDesiredTaskbarWindowWidth()
        val heightPx = getDesiredTaskbarWindowHeight()
        try {
            windowManager.addView(window, createTaskbarLayoutParams(widthPx, heightPx))
            taskbarWindowView = window
            currentTaskbarWindowWidth = widthPx
            currentTaskbarWindowHeight = heightPx
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create taskbar window", e)
            taskbarWindowView = null
            currentTaskbarWindowWidth = -1
            currentTaskbarWindowHeight = -1
        }
    }

    private fun destroyTaskbarWindow() {
        val window = taskbarWindowView ?: return
        taskbarWindowView = null
        currentTaskbarWindowWidth = -1
        currentTaskbarWindowHeight = -1
        try {
            windowManager.removeView(window)
        } catch (_: Exception) {
            // already removed
        }
    }

    private fun updateTaskbarWindowBounds() {
        val window = taskbarWindowView ?: return
        val params = window.layoutParams as? WindowManager.LayoutParams ?: return
        val desiredWidth = getDesiredTaskbarWindowWidth()
        val desiredHeight = getDesiredTaskbarWindowHeight()
        recentAppsTaskbar?.updateBarHeightPx(desiredHeight)
        val sizeChanged = currentTaskbarWindowWidth != desiredWidth || currentTaskbarWindowHeight != desiredHeight
        if (sizeChanged) {
            Log.d(TAG, "updateTaskbarWindowBounds: ${currentTaskbarWindowWidth}x${currentTaskbarWindowHeight} → ${desiredWidth}x${desiredHeight}")
            try {
                params.width = desiredWidth
                params.height = desiredHeight
                windowManager.updateViewLayout(window, params)
                currentTaskbarWindowWidth = desiredWidth
                currentTaskbarWindowHeight = desiredHeight
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update taskbar window bounds", e)
            }
        }
    }

    private fun shouldTaskbarWindowBeTouchable(): Boolean {
        val center = centerGroupView
        if (!isOverlayTouchable) return false
        return center != null && center.visibility == View.VISIBLE
    }

    private fun updateTaskbarWindowTouchableState() {
        val window = taskbarWindowView ?: return
        val params = window.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = params.flags
        val newFlags = if (shouldTaskbarWindowBeTouchable()) {
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == newFlags) return
        try {
            params.flags = newFlags
            windowManager.updateViewLayout(window, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update taskbar window touchable state", e)
        }
    }

    private fun bringTaskbarWindowToFront() {
        val window = taskbarWindowView ?: return
        val params = window.layoutParams as? WindowManager.LayoutParams ?: return

        try {
            windowManager.removeView(window)
        } catch (_: Exception) {
            // ignore if already detached
        }

        try {
            windowManager.addView(window, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move taskbar window to front", e)
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
            bringTaskbarWindowToFront()
        }
        updateTaskbarWindowBounds()

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
            duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.ANIMATION_DURATION_MS)
            if (AnimationPerformanceHelper.shouldUseHardwareLayers()) {
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
    }

    private fun createTranslationAnimator(view: View, from: Float, to: Float): ObjectAnimator {
        view.translationY = from
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, from, to).apply {
            duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.ANIMATION_DURATION_MS)
            if (AnimationPerformanceHelper.shouldUseHardwareLayers()) {
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
    }

    private fun getBarHeightForAnimation(): Float {
        val barHeight = navBarView?.height ?: 0
        return if (barHeight > 0) barHeight.toFloat() else getSystemNavigationBarHeightPx().toFloat()
    }

    // ===== 표시/숨김 =====

    fun show(
        fade: Boolean = false,
        fromGesture: Boolean = false,
        isUnlockFade: Boolean = false,
        slide: Boolean = false
    ) {
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
            val autoHideMs = if (isGestureNavbarMode()) {
                Constants.Timing.GESTURE_NAVBAR_AUTO_HIDE_MS
            } else {
                Constants.Timing.GESTURE_AUTO_HIDE_MS
            }
            gestureHandler.markGestureShow(autoHideMs)
            if (!isGestureNavbarMode()) {
                showGestureOverlay()
            }
            syncTaskbarVisibility(animate = true)
        }

        val wasHidden = !isShowing
        val gestureHomeReveal =
            isGestureNavbarMode() &&
                !fromGesture &&
                !isRecentsVisible &&
                !isAppDrawerOpen &&
                !isPanelOpen() &&
                !isImeVisible &&
                isOnHomeScreen
        val gestureRecentsReveal =
            isGestureNavbarMode() &&
                !fromGesture &&
                isRecentsVisible &&
                !isAppDrawerOpen &&
                !isPanelOpen() &&
                !isImeVisible
        var shouldFade = (fade || isUnlockFade || shouldUseQuickstepPlusOverlayFade()) &&
            !AnimationPerformanceHelper.shouldSkipNonEssentialAnimations()
        if ((gestureHomeReveal || gestureRecentsReveal) && !isUnlockFade) {
            shouldFade = false
        }
        val shouldSlide = (slide || ((gestureHomeReveal || gestureRecentsReveal) && wasHidden)) && !isUnlockFade && !shouldFade &&
            !AnimationPerformanceHelper.shouldSkipNonEssentialAnimations()
        val suppressBackgroundLayer = !isUnlockFade && (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed)
        val animateDefaultBackgroundLayer = isUnlockFade

        if (wasHidden && !isUnlockFade) {
            syncOrientationAndBackground()
        }

        val bar = navBarView ?: return
        if (isGestureNavbarMode()) {
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
        }
        val shouldPrimeTaskbarContent =
            settings.recentAppsTaskbarEnabled &&
                shouldShowTaskbar() &&
                (wasHidden || isGestureNavbarMode())
        if (shouldPrimeTaskbarContent) {
            updateTaskbarContentForCurrentMode(animate = false, syncVisibility = false)
        }
        val hasTaskbarContent = recentAppsTaskbar?.hasApps() == true
        if (shouldShowTaskbar() && hasTaskbarContent) {
            centerGroupView?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                translationY = getVisibleTaskbarTranslationY()
                scaleX = 1f
                scaleY = 1f
            }
            updateTaskbarWindowBounds()
            updateTaskbarWindowTouchableState()
        } else if (wasHidden || !hasTaskbarContent) {
            hideTaskbarImmediate()
        }
        hotspotView?.visibility = View.GONE
        updateGestureAuxiliaryViews()
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
                    if (animateDefaultBackgroundLayer) {
                        backgroundView?.apply {
                            alpha = 0f
                            visibility = View.VISIBLE
                        }
                    } else if (!suppressBackgroundLayer) {
                        syncDefaultBackgroundLayer(makeVisible = true)
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
                        if (!unlockFadeCancelled && !isUnlockPending) {
                            syncDefaultBackgroundLayer(makeVisible = true)
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
            updateWindowHeight(getDesiredVisibleWindowHeight())
            if (!shouldFade) {
                updateGestureAuxiliaryViews()
                return
            }
            if (bar.alpha >= 1f && !isUnlockFade) {
                updateGestureAuxiliaryViews()
                return
            }

            bar.translationY = 0f
            val animators = mutableListOf<Animator>()
            animators.add(createAlphaAnimator(bar, bar.alpha, 1f))
            centerGroupView?.takeIf { it.visibility == View.VISIBLE }?.let { center ->
                animators.add(createAlphaAnimator(center, center.alpha, 1f))
            }
            if (animateDefaultBackgroundLayer) {
                backgroundView?.let { bg ->
                    bg.visibility = View.VISIBLE
                    animators.add(createAlphaAnimator(bg, bg.alpha, 1f))
                }
            } else {
                syncDefaultBackgroundLayer(makeVisible = true)
            }

            val set = AnimatorSet().apply { playTogether(animators) }
            runFadeAnimator(set)
            return
        }

        if (shouldFade) {
            updateWindowHeight(getDesiredVisibleWindowHeight())
            bar.translationY = 0f
            if (!suppressBackgroundLayer) {
                bar.alpha = 0f
                bar.visibility = View.VISIBLE
            } else {
                bar.alpha = 0f
                bar.visibility = View.VISIBLE
            }
            if (animateDefaultBackgroundLayer) {
                backgroundView?.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                }
            } else if (!suppressBackgroundLayer) {
                syncDefaultBackgroundLayer(makeVisible = true)
            }
            hotspotView?.visibility = View.GONE
            centerGroupView?.takeIf { it.visibility == View.VISIBLE }?.alpha = 0f

            val animators = mutableListOf<Animator>()
            animators.add(createAlphaAnimator(bar, bar.alpha, 1f))
            centerGroupView?.takeIf { it.visibility == View.VISIBLE }?.let { center ->
                animators.add(createAlphaAnimator(center, center.alpha, 1f))
            }
            if (animateDefaultBackgroundLayer) {
                backgroundView?.let { bg ->
                    animators.add(createAlphaAnimator(bg, bg.alpha, 1f))
                }
            }

            val set = AnimatorSet().apply { playTogether(animators) }
            runFadeAnimator(set)
        } else if (shouldSlide) {
            updateWindowHeight(getDesiredVisibleWindowHeight())
            val slideOffset = getBarHeightForAnimation()

            if (!suppressBackgroundLayer) {
                syncDefaultBackgroundLayer(makeVisible = true)
                backgroundView?.translationY = slideOffset
            }

            bar.alpha = 1f
            bar.visibility = View.VISIBLE
            bar.translationY = slideOffset
            hotspotView?.visibility = View.GONE

            val animators = mutableListOf<Animator>()
            animators.add(createTranslationAnimator(bar, slideOffset, 0f))
            if (!suppressBackgroundLayer) {
                backgroundView?.let { bg ->
                    animators.add(createTranslationAnimator(bg, slideOffset, 0f))
                }
            }
            centerGroupView?.takeIf { shouldShowTaskbar() }?.let { center ->
                val targetTranslationY = getVisibleTaskbarTranslationY()
                center.visibility = View.VISIBLE
                center.alpha = 1f
                center.scaleX = 1f
                center.scaleY = 1f
                center.translationY = targetTranslationY + slideOffset
                animators.add(ObjectAnimator.ofFloat(center, View.TRANSLATION_Y, targetTranslationY + slideOffset, targetTranslationY))
            }

            val set = AnimatorSet().apply {
                playTogether(animators)
                duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.ANIMATION_DURATION_MS)
                interpolator = homeUiTransitionInterpolator
            }
            startAnimator(set, onEnd = {
                bar.translationY = 0f
                backgroundView?.translationY = 0f
                if (!shouldShowTaskbar()) {
                    centerGroupView?.visibility = View.GONE
                }
                updateTaskbarWindowTouchableState()
            })
        } else {
            updateWindowHeight(getDesiredVisibleWindowHeight())
            if (!suppressBackgroundLayer) {
                syncDefaultBackgroundLayer(makeVisible = true)
            }
            bar.alpha = 1f
            bar.visibility = View.VISIBLE
            bar.translationY = 0f
            hotspotView?.visibility = View.GONE
        }

        isShowing = true
        updateGestureAuxiliaryViews()
        Log.d(TAG, "Overlay shown (fade=$shouldFade, slide=$shouldSlide, fromGesture=$fromGesture, unlock=$isUnlockFade)")
    }

    fun startUnlockFade() {
        if (!isUnlockPending) return // 이미 취소됨(화면 꺼짐 등)
        isUnlockPending = false
        
        // 언락 페이드 시작 (배경 없음, 페이드 강제)
        applyUnlockBackgroundToBackLayer()
        show(fade = true, fromGesture = false, isUnlockFade = true)
    }

    fun hasPendingUnlockFade(): Boolean = isUnlockPending

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

    fun hide(animate: Boolean = true, showHotspot: Boolean = true, slide: Boolean = false) {
        val inUnlockFlow = isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed
        if (showHotspot && !inUnlockFlow) {
            isUnlockPending = false
            isUnlockFadeRunning = false
            isUnlockFadeSuppressed = false
        } else {
            isUnlockFadeRunning = false
        }

        updateTouchableState(true)

        val shouldShowHotspot = showHotspot && settings.hotspotEnabled && !isGestureNavbarMode()
        val shouldShowCornerHotspotAfterHide = false
        val keepBackgroundLayerForHomePreview = shouldKeepBackgroundLayerForHomePreview()

        if (!showHotspot && !isShowing) {
            navBarView?.visibility = View.GONE
            if (keepBackgroundLayerForHomePreview) {
                syncDefaultBackgroundLayer(makeVisible = true)
            } else {
                updateBackgroundLayerVisibility()
                if (isGestureNavbarMode()) {
                    updateBarAndBackgroundHeight(getSystemNavigationBarHeightPx())
                }
            }
            centerGroupView?.visibility = View.GONE
            updateTaskbarWindowTouchableState()
            hotspotView?.visibility = View.GONE
            updateGestureAuxiliaryViews()
            updateWindowHeight(if (keepBackgroundLayerForHomePreview) getDesiredVisibleWindowHeight() else 0)
            Log.d(TAG, "Force hiding for disabled app (window height = 0)")
            return
        }

        if (!isShowing) return

        val bar = navBarView ?: return
        bar.clearAnimation()
        bar.animate().cancel()

        cancelCurrentAnimator()

        val shouldUseImmediateHide = !animate || (!slide && shouldUseQuickstepPlusTaskbarNoSlide())

        if (shouldUseImmediateHide) {
            bar.visibility = View.GONE
            bar.translationY = 0f
            if (keepBackgroundLayerForHomePreview) {
                syncDefaultBackgroundLayer(makeVisible = true)
            } else {
                updateBackgroundLayerVisibility()
            }
            centerGroupView?.visibility = View.GONE
            updateTaskbarWindowTouchableState()
            if (keepBackgroundLayerForHomePreview) {
                hotspotView?.visibility = View.GONE
                updateWindowHeight(getDesiredVisibleWindowHeight())
            } else if (shouldShowHotspot) {
                updateWindowHeight(context.dpToPx(settings.hotspotHeight))
            } else if (shouldShowCornerHotspotAfterHide) {
                updateWindowHeight(getGestureHotspotHeightPx())
            } else {
                updateWindowHeight(0)
            }
            hideAnimationInProgress = false
        } else {
            hideAnimationInProgress = true
            val toY = getBarHeightForAnimation()
            val shouldSlideOverlay = slide && !AnimationPerformanceHelper.shouldSkipNonEssentialAnimations()
            val animators = mutableListOf<Animator>()
            animators.add(createTranslationAnimator(bar, 0f, toY))
            if (shouldSlideOverlay) {
                backgroundView?.takeIf { it.visibility == View.VISIBLE }?.let { bg ->
                    animators.add(createTranslationAnimator(bg, 0f, toY))
                }
                centerGroupView?.takeIf { it.visibility == View.VISIBLE }?.let { center ->
                    val startTranslationY = center.translationY
                    animators.add(ObjectAnimator.ofFloat(center, View.TRANSLATION_Y, startTranslationY, startTranslationY + toY))
                }
            }
            val animator = if (animators.size == 1) {
                animators.first()
            } else {
                AnimatorSet().apply {
                    playTogether(animators)
                    duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.ANIMATION_DURATION_MS)
                    interpolator = homeUiTransitionInterpolator
                }
            }
            startAnimator(
                animator,
                onEnd = {
                    hideAnimationInProgress = false
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                    backgroundView?.translationY = 0f
                    if (keepBackgroundLayerForHomePreview) {
                        syncDefaultBackgroundLayer(makeVisible = true)
                    } else {
                        updateBackgroundLayerVisibility()
                        if (isGestureNavbarMode()) {
                            updateBarAndBackgroundHeight(getSystemNavigationBarHeightPx())
                        }
                    }
                    centerGroupView?.visibility = View.GONE
                    updateTaskbarWindowTouchableState()
                    if (keepBackgroundLayerForHomePreview) {
                        hotspotView?.visibility = View.GONE
                        updateWindowHeight(getDesiredVisibleWindowHeight())
                    } else if (shouldShowHotspot) {
                        updateWindowHeight(context.dpToPx(settings.hotspotHeight))
                    } else if (shouldShowCornerHotspotAfterHide) {
                        updateWindowHeight(getGestureHotspotHeightPx())
                    } else {
                        updateWindowHeight(0)
                    }
                }
            )
        }

        isShowing = false
        hideGestureOverlay()
        hotspotView?.visibility = if (shouldShowHotspot) View.VISIBLE else View.GONE
        updateGestureAuxiliaryViews()
        navbarAppsPanel?.hide(immediate = true, reason = "overlay_hide")
        Log.d(TAG, "Overlay hidden (animate=$animate, slide=$slide, showHotspot=$showHotspot)")
    }

    private fun updateBarAndBackgroundHeight(heightPx: Int) {
        navBarView?.let { bar ->
            val params = bar.layoutParams
            if (params != null && params.height != heightPx) {
                params.height = heightPx
                bar.layoutParams = params
            }
        }
        backgroundView?.let { bg ->
            val params = bg.layoutParams
            if (params != null && params.height != heightPx) {
                params.height = heightPx
                bg.layoutParams = params
            }
        }
        leftButtonGroup?.let { group ->
            val params = group.layoutParams
            if (params != null && params.height != heightPx) {
                params.height = heightPx
                group.layoutParams = params
            }
        }
        rightButtonGroup?.let { group ->
            val params = group.layoutParams
            if (params != null && params.height != heightPx) {
                params.height = heightPx
                group.layoutParams = params
            }
        }
        updateQuickstepHomeShadowLayout(heightPx)
    }

    private fun getWindowHeightForCurrentState(): Int {
        if (isShowing) return getDesiredVisibleWindowHeight()
        if (hotspotView?.visibility == View.VISIBLE && settings.hotspotEnabled) {
            return context.dpToPx(settings.hotspotHeight)
        }
        if (cornerHotspotView?.visibility == View.VISIBLE && settings.hotspotEnabled) {
            return getGestureHotspotHeightPx()
        }
        if (shouldKeepBackgroundLayerVisible()) {
            return getDesiredVisibleWindowHeight()
        }
        return 0
    }

    private fun updateWindowHeight(heightPx: Int) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            if (currentWindowHeight == heightPx) return
            params.height = heightPx
            windowManager.updateViewLayout(rootView, params)
            currentWindowHeight = heightPx
            updateBackgroundLayerVisibility()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window height", e)
        }
    }

    private fun updateTouchableState(touchable: Boolean) {
        try {
            isOverlayTouchable = touchable
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            val currentFlags = params.flags
            
            val newFlags = if (touchable) {
                currentFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                currentFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            if (currentFlags != newFlags) {
                params.flags = newFlags
                windowManager.updateViewLayout(rootView, params)
                Log.d(TAG, "Window touchable state updated: $touchable")
            }
            updateTaskbarWindowTouchableState()
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

    fun isGestureRevealActive(): Boolean {
        return false
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
            clearHomeButtonPreviewHint()
            lastNonLauncherForegroundAt = 0L
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            val wasHomeExitPending = isHomeExitPending
            isHomeExitPending = false
            if (!isOnHomeScreen) {
                isOnHomeScreen = true
                clearHomeExitSuppression()

                // 숨김 애니메이션을 먼저 취소: 배경 전환 시작 전 navBarView가
                // 숨김 중이면 깜빡임 발생 가능
                cancelHideAnimationOnly()

                updateQQPlusHomeState()
                Log.d(TAG, "Home screen state: true")

                if (isGestureNavbarMode() || settings.recentAppsTaskbarShowOnHome) {
                    updateTaskbarContentForCurrentMode(animate = false, syncVisibility = false)
                    syncTaskbarVisibility(animate = true)
                } else {
                    // 홈화면에서는 최근 앱 작업 표시줄 숨김 (애니메이션 적용)
                    animateTaskbarExit()
                }

                // 언락 페이드 중에는 배경 전환하지 않음 (언락 완료 후 자동 업데이트)
                if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
                    Log.d(TAG, "Home entry during unlock fade - skipping background transition")
                    return
                }

                val shouldUseCustom = shouldUseCustomBackground()
                val alreadyCustomStable =
                    shouldUseCustom &&
                        backgroundManager.wasLastAppliedCustom() &&
                        !backgroundManager.isTransitioningFromCustomToDefault()
                if (alreadyCustomStable) {
                    ensureVisualStateSync()
                    Log.d(TAG, "Home entry background already custom - skip redundant fade")
                    return
                }

                applyHomeEntryBackground()
                return
            }

            if (wasHomeExitPending) {
                // 홈 이탈이 취소됨 (패널/앱 서랍이 빠르게 열렸다 닫힌 경우)
                // 억제를 해제하고 배경 + 아이콘/버튼 모두 홈 상태로 복원
                clearHomeExitSuppression()
                updateTaskbarContentForCurrentMode(animate = false, syncVisibility = false)
                updateTaskbarIconSizeForCurrentState()
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

        clearHomeButtonPreviewHint()
        cancelPendingHomeButtonGrowAnimation()
        if (!isOnHomeScreen) return
        pendingHomeState?.let { handler.removeCallbacks(it) }

        // 배경 + 버튼 + 아이콘 전환을 동시에 즉시 시작
        isHomeExitPending = true
        startHomeExitSuppression()
        updateTaskbarIconSizeForCurrentState()
        updateNavBarBackground()

        val task = Runnable {
            pendingHomeState = null
            isHomeExitPending = false
            if (!isOnHomeScreen) return@Runnable
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state: false (debounced)")

            // 홈 -> 앱 전환 시 진입 애니메이션이 항상 보이도록
            // 한 프레임 리셋 후 재생
            if (isGestureNavbarMode()) {
                updateTaskbarWindowBounds()
            } else if (settings.recentAppsTaskbarShowOnHome) {
                syncTaskbarVisibility(animate = true)
            } else {
                playTaskbarEntryFromHomeIfNeeded()
            }

            updateNavBarBackground()
        }
        pendingHomeState = task
        handler.postDelayed(task, Constants.Timing.HOME_STATE_DEBOUNCE_MS)
    }

    private fun playTaskbarEntryFromHomeIfNeeded() {
        if (!shouldShowTaskbar()) {
            hideTaskbarImmediate()
            return
        }
        hideTaskbarImmediate()
        // 숨긴 뒤 아이콘 크기를 32dp로 즉시 적용 (GONE 상태이므로 애니메이션 없이)
        updateTaskbarIconSizeForCurrentState()
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
        Log.d(TAG, "animateTaskbarEntry() CALLED", Exception("stack trace"))
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

        val targetTranslationY = getVisibleTaskbarTranslationY()
        val startAlpha = if (view.visibility == View.VISIBLE) view.alpha else 0f
        val startTranslationY = if (view.visibility == View.VISIBLE) {
            view.translationY
        } else {
            if (shouldUseQuickstepPlusTaskbarNoSlide()) {
                targetTranslationY
            } else {
                targetTranslationY + context.dpToPx(30).toFloat()
            }
        }
        val startScaleX = if (shouldUseQuickstepPlusTaskbarNoSlide()) {
            1f
        } else if (view.visibility == View.VISIBLE) {
            view.scaleX
        } else {
            0.85f
        }
        val startScaleY = if (shouldUseQuickstepPlusTaskbarNoSlide()) {
            1f
        } else if (view.visibility == View.VISIBLE) {
            view.scaleY
        } else {
            0.85f
        }

        // 초기 상태 보정
        view.alpha = startAlpha
        view.translationY = startTranslationY
        view.scaleX = startScaleX
        view.scaleY = startScaleY
        view.visibility = View.VISIBLE

        // 애니메이션 생성
        val alphaAnim = ObjectAnimator.ofFloat(view, "alpha", startAlpha, 1f)
        val translateAnim = ObjectAnimator.ofFloat(view, "translationY", startTranslationY, targetTranslationY)
        val scaleXAnim = ObjectAnimator.ofFloat(view, "scaleX", startScaleX, 1f)
        val scaleYAnim = ObjectAnimator.ofFloat(view, "scaleY", startScaleY, 1f)

        taskbarEntryAnimator = AnimatorSet().apply {
            playTogether(alphaAnim, translateAnim, scaleXAnim, scaleYAnim)
            duration = AnimationPerformanceHelper.resolveDuration(250L)
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f
                    view.translationY = targetTranslationY
                    view.scaleX = 1f
                    view.scaleY = 1f
                    taskbarEntryAnimator = null
                    tryPlayPendingTaskbarIconSizeAnimation()
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
        Log.d(TAG, "animateTaskbarExit() CALLED", Exception("stack trace"))
        val view = centerGroupView ?: return

        // 등장 애니메이션 취소
        taskbarEntryAnimator?.cancel()
        taskbarEntryAnimator = null

        // 이미 숨겨져 있거나 퇴장 애니메이션 진행 중이면 스킵
        if (view.visibility != View.VISIBLE || taskbarExitAnimator?.isRunning == true) {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.GONE
                updateTaskbarWindowTouchableState()
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
        val targetTranslationY = if (shouldUseQuickstepPlusTaskbarNoSlide()) {
            getVisibleTaskbarTranslationY()
        } else {
            getVisibleTaskbarTranslationY() + context.dpToPx(25).toFloat()
        }
        val targetScale = if (shouldUseQuickstepPlusTaskbarNoSlide()) 1f else 0.85f
        val alphaAnim = ObjectAnimator.ofFloat(view, "alpha", currentAlpha, 0f)
        val translateAnim = ObjectAnimator.ofFloat(view, "translationY", currentTranslationY, targetTranslationY)
        val scaleXAnim = ObjectAnimator.ofFloat(view, "scaleX", currentScaleX, targetScale)
        val scaleYAnim = ObjectAnimator.ofFloat(view, "scaleY", currentScaleY, targetScale)

        taskbarExitAnimator = AnimatorSet().apply {
            playTogether(alphaAnim, translateAnim, scaleXAnim, scaleYAnim)
            duration = AnimationPerformanceHelper.resolveDuration(180L)
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
                    updateTaskbarWindowTouchableState()
                }

                override fun onAnimationCancel(animation: Animator) {
                    taskbarExitAnimator = null
                    updateTaskbarWindowTouchableState()
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
        updateTaskbarWindowTouchableState()
    }

    /**
     * 최근 앱 작업 표시줄 즉시 표시 (애니메이션 없이)
     */
    private fun showTaskbarImmediate() {
        Log.d(TAG, "showTaskbarImmediate() CALLED")
        taskbarEntryAnimator?.cancel()
        taskbarExitAnimator?.cancel()
        taskbarEntryAnimator = null
        taskbarExitAnimator = null

        centerGroupView?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationY = getVisibleTaskbarTranslationY()
            scaleX = 1f
            scaleY = 1f
        }
        updateTaskbarWindowTouchableState()
        tryPlayPendingTaskbarIconSizeAnimation()
    }

    /**
     * 현재 상태에 맞춰 최근 앱 작업 표시줄 가시성 동기화
     */
    private fun syncTaskbarVisibility(animate: Boolean) {
        updateTaskbarIconSizeForCurrentState()

        if (shouldShowTaskbar()) {
            val view = centerGroupView
            val isCurrentlyHidden =
                view == null ||
                view.visibility != View.VISIBLE ||
                view.alpha < 0.99f ||
                taskbarEntryAnimator != null ||
                taskbarExitAnimator != null

            val shouldAnimateEntry = animate && !settings.recentAppsTaskbarShowOnHome

            Log.d(TAG, "syncTaskbarVisibility: hidden=$isCurrentlyHidden, animateEntry=$shouldAnimateEntry, " +
                "showOnHome=${settings.recentAppsTaskbarShowOnHome}, " +
                "vis=${view?.visibility}, alpha=${view?.alpha}, " +
                "entryAnim=${taskbarEntryAnimator != null}, exitAnim=${taskbarExitAnimator != null}")

            if (shouldAnimateEntry || (isCurrentlyHidden && !settings.recentAppsTaskbarShowOnHome)) {
                Log.d(TAG, "syncTaskbarVisibility → animateTaskbarEntry()")
                animateTaskbarEntry()
            } else if (isCurrentlyHidden) {
                Log.d(TAG, "syncTaskbarVisibility → showTaskbarImmediate()")
                showTaskbarImmediate()
            } else {
                Log.d(TAG, "syncTaskbarVisibility → already visible, skip")
                updateTaskbarWindowTouchableState()
                tryPlayPendingTaskbarIconSizeAnimation()
            }
        } else {
            Log.d(TAG, "syncTaskbarVisibility → hideTaskbarImmediate()")
            if (isGestureNavbarMode() && isShowing && !isOnHomeScreen && !isRecentsVisible) {
                updateTaskbarWindowBounds()
                updateTaskbarWindowTouchableState()
                return
            }
            hideTaskbarImmediate()
        }
    }

    private fun updateTaskbarIconSizeForCurrentState() {
        val shouldUseLargeHomeUi = shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()
        val iconSizeDp = if (shouldUseLargeHomeUi) {
            RecentAppsTaskbar.LARGE_HOME_ICON_SIZE_DP
        } else {
            Constants.Dimension.TASKBAR_ICON_SIZE_DP
        }
        val deferHomeButtonGrowAnimation = shouldDeferHomeButtonGrowAnimation(iconSizeDp)
        val navBarFullyVisible = isShowing &&
            !isUnlockPending && !isUnlockFadeRunning && !isUnlockFadeSuppressed &&
            (navBarView?.alpha ?: 0f) > 0.9f

        val requestSignature = listOf(
            iconSizeDp,
            deferHomeButtonGrowAnimation,
            shouldUseLargeHomeUi,
            navBarFullyVisible,
            isOnHomeScreen,
            isRecentsVisible,
            isAppDrawerOpen
        ).joinToString("|")
        val now = SystemClock.elapsedRealtime()
        if (requestSignature == lastTaskbarUiRequestSignature &&
            now - lastTaskbarUiRequestAt < TASKBAR_UI_REQUEST_DEDUP_MS
        ) {
            return
        }
        lastTaskbarUiRequestSignature = requestSignature
        lastTaskbarUiRequestAt = now

        if (isGestureNavbarMode()) {
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
            if (isCreated) {
                updateTaskbarWindowBounds()
            }
        }

        recentAppsTaskbar?.deferPendingIconSizeAnimationPlayback = deferHomeButtonGrowAnimation
        recentAppsTaskbar?.setIconSizeDp(
            iconSizeDp,
            deferVisibleGrowAnimation = deferHomeButtonGrowAnimation
        )

        if (deferHomeButtonGrowAnimation) {
            schedulePendingHomeButtonGrowAnimationIfNeeded()
        } else {
            cancelPendingHomeButtonGrowAnimation(clearHint = false)
        }

        val visibleTranslationY = getVisibleTaskbarTranslationY()
        if (taskbarEntryAnimator == null && taskbarExitAnimator == null) {
            centerGroupView?.takeIf { it.visibility == View.VISIBLE }?.translationY = visibleTranslationY
        }

        if (isCreated) {
            updateTaskbarWindowBounds()
        }

        // 버튼 위치 동기화: 큰 아이콘이면 위로, 아닌 경우 원위치
        // navbar가 아직 보이지 않거나 잠금해제/페이드 중이면 애니메이션 없이 즉시 적용
        if (!deferHomeButtonGrowAnimation) {
            syncButtonPositionForCurrentState(animate = navBarFullyVisible)
        }
    }

    /**
     * 현재 아이콘 상태에 따라 버튼 위치 동기화
     */
    private fun syncButtonPositionForCurrentState(animate: Boolean = true) {
        if (isGestureNavbarMode()) {
            resetButtonPositionsImmediate()
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
            if (isShowing) {
                updateWindowHeight(getDesiredVisibleWindowHeight())
            }
            return
        }

        val shouldBeCenter = shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()
        if (shouldBeCenter && !areButtonsMovedToCenter) {
            animateButtonsToHomePosition(animate)
        } else if (shouldBeCenter && areButtonsMovedToCenter && homeButtonsAnimator?.isRunning == true) {
            // 복귀(원위치) 애니메이션 진행 중 다시 홈 위치로 돌아가야 하는 경우
            // (빠르게 앱서랍 열었다 닫은 경우). 이미 홈 방향이면 재시작하지 않음.
            animateButtonsToHomePosition(animate)
        } else if (!shouldBeCenter && areButtonsMovedToCenter) {
            if (animate) {
                animateButtonsToOriginalPosition()
            } else {
                resetButtonPositionsImmediate()
                updateWindowHeight(getDesiredVisibleWindowHeight())
            }
        }
    }

    private fun shouldDeferHomeButtonGrowAnimation(targetIconSizeDp: Int): Boolean {
        if (targetIconSizeDp <= Constants.Dimension.TASKBAR_ICON_SIZE_DP) return false
        if (!isOnHomeScreen) return false
        if (SystemClock.elapsedRealtime() >= homeButtonGrowHintUntil) return false
        if (isRecentsVisible || isAppDrawerOpen || isPanelOpen() || isImeVisible) return false
        return true
    }

    private fun schedulePendingHomeButtonGrowAnimationIfNeeded() {
        pendingHomeButtonGrowTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingHomeButtonGrowTask = null
            homeButtonGrowHintUntil = 0L
            recentAppsTaskbar?.deferPendingIconSizeAnimationPlayback = false
            val started = recentAppsTaskbar?.playPendingIconSizeAnimationIfNeeded() == true
            val stillPending = recentAppsTaskbar?.hasPendingIconSizeAnimation() == true
            if (!started && stillPending) {
                schedulePendingHomeButtonGrowAnimationIfNeeded()
                return@Runnable
            }

            val navBarFullyVisible = isShowing &&
                !isUnlockPending && !isUnlockFadeRunning && !isUnlockFadeSuppressed &&
                (navBarView?.alpha ?: 0f) > 0.9f
            syncButtonPositionForCurrentState(animate = navBarFullyVisible)
        }
        pendingHomeButtonGrowTask = task
        handler.postDelayed(task, Constants.Timing.HOME_BUTTON_GROW_ANIMATION_DELAY_MS)
    }

    private fun cancelPendingHomeButtonGrowAnimation(clearHint: Boolean = true) {
        pendingHomeButtonGrowTask?.let { handler.removeCallbacks(it) }
        pendingHomeButtonGrowTask = null
        if (clearHint) {
            homeButtonGrowHintUntil = 0L
        }
        recentAppsTaskbar?.deferPendingIconSizeAnimationPlayback = false
    }

    private fun tryPlayPendingTaskbarIconSizeAnimation() {
        if (recentAppsTaskbar?.deferPendingIconSizeAnimationPlayback == true) return
        recentAppsTaskbar?.playPendingIconSizeAnimationIfNeeded()
    }

    private fun shouldUseQuickstepPlusHomeTaskbarSize(): Boolean {
        if (!isOnHomeScreen) return false
        if (isHomeExitPending) return false
        if (isRecentsVisible || isAppDrawerOpen || isPanelOpen()) return false
        return isQuickstepPlusTaskbarFeatureActive()
    }

    private fun shouldUseQuickstepPlusHomeTaskbarSizeForDisplay(): Boolean {
        if (shouldUseQuickstepPlusHomeTaskbarSize()) return true

        val keepExpandedDuringGestureTransientExit =
            isGestureNavbarMode() &&
                isShowing &&
                !isRecentsVisible &&
                isHomeExitPending &&
                isQuickstepPlusTaskbarFeatureActive()
        if (keepExpandedDuringGestureTransientExit) return true

        // 홈 버튼/뒤로가기 preview 중: 아직 isOnHomeScreen은 false이지만 홈 전환 확정
        if (SystemClock.elapsedRealtime() < homeButtonPreviewUntil &&
            !isRecentsVisible && !isAppDrawerOpen && !isPanelOpen() && !isImeVisible &&
            isQuickstepPlusTaskbarFeatureActive()
        ) return true

        val unlockShowingHome =
            (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) &&
                unlockUseCustomOverride == true
        return unlockShowingHome && isQuickstepPlusTaskbarFeatureActive()
    }

    private fun shouldKeepBackgroundLayerForHomePreview(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (now < homeButtonPreviewUntil || now < transientBackgroundCoverUntil) &&
            !isRecentsVisible &&
            !isAppDrawerOpen &&
            !isPanelOpen() &&
            !isImeVisible &&
            !isUnlockPending &&
            !isUnlockFadeRunning &&
            !isUnlockFadeSuppressed
    }

    private fun shouldKeepBackgroundLayerVisible(): Boolean {
        if (isUnlockPending && !isUnlockFadeRunning && !isUnlockFadeSuppressed) return false
        if (currentWindowHeight <= 0) return false
        if (!shouldRenderGestureNavbarBackground()) return false
        if (isShowing) return true
        if (shouldKeepBackgroundLayerForHomePreview()) return true
        if (isUnlockFadeRunning) return true
        return false
    }

    private fun updateBackgroundLayerVisibility() {
        backgroundView?.visibility = if (shouldKeepBackgroundLayerVisible()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun pinDefaultBackgroundLayerForCustomNavTransition(durationMs: Long = CUSTOM_NAV_TRANSITION_COVER_MS) {
        val nextUntil = SystemClock.elapsedRealtime() + durationMs
        if (nextUntil > transientBackgroundCoverUntil) {
            transientBackgroundCoverUntil = nextUntil
        }
        syncDefaultBackgroundLayer(makeVisible = true)
        if (!isShowing) {
            updateWindowHeight(getDesiredVisibleWindowHeight())
        }
        updateBackgroundLayerVisibility()
    }

    private fun shouldUseQuickstepPlusTaskbarNoSlide(): Boolean {
        return isQuickstepPlusTaskbarFeatureActive() && !isGestureNavbarMode()
    }

    private fun shouldShowGestureRecentsPanel(): Boolean {
        return isGestureNavbarMode() && isRecentsVisible
    }

    private fun getGestureRecentsPanelHeightPx(barHeightPx: Int = getSystemNavigationBarHeightPx()): Int {
        return maxOf(barHeightPx, Constants.Dimension.GESTURE_RECENTS_PANEL_HEIGHT_PX)
    }

    private fun getQuickstepPlusHomeTaskbarOverflowPx(): Int {
        if (!isQuickstepPlusTaskbarFeatureActive()) return 0
        if (shouldUseQuickstepPlusHomeTaskbarSize()) {
            return getQuickstepPlusHomeTaskbarBaseOverflowPx()
        }
        return 0
    }

    private fun getQuickstepPlusHomeExpandedHeightPx(barHeightPx: Int = getSystemNavigationBarHeightPx()): Int {
        if (!isQuickstepPlusTaskbarFeatureActive()) return barHeightPx
        return if (isGestureNavbarMode()) {
            maxOf(barHeightPx, Constants.Dimension.CROP_HEIGHT_PX)
        } else {
            RecentAppsTaskbar.calculateHomeExpandedBarHeightPx(context, barHeightPx)
        }
    }

    private fun getVisibleTaskbarTranslationY(): Float {
        return recentAppsTaskbar?.getCurrentGroupTranslationY() ?: 0f
    }

    private fun getDesiredVisibleWindowHeight(): Int {
        val barHeight = getSystemNavigationBarHeightPx()
        if (isGestureNavbarMode()) {
            return when {
                shouldUseQuickstepPlusHomeTaskbarSizeForDisplay() -> getQuickstepPlusHomeExpandedHeightPx(barHeight)
                shouldShowGestureRecentsPanel() -> getGestureRecentsPanelHeightPx(barHeight)
                else -> barHeight
            }
        }
        if (areButtonsMovedToCenter) {
            return getQuickstepPlusHomeExpandedHeightPx(barHeight)
        }
        return barHeight
    }

    // ===== 홈화면 버튼 이동 애니메이션 =====

    /**
     * 홈화면 진입 시 좌우 버튼 그룹을 아이콘 클러스터 양옆으로 이동
     */
    private fun animateButtonsToHomePosition(animate: Boolean = true) {
        val left = leftButtonGroup ?: return
        val right = rightButtonGroup ?: return
        if (!isQuickstepPlusTaskbarFeatureActive()) return
        if (!shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()) return
        if (isGestureNavbarMode()) {
            resetButtonPositionsImmediate()
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
            if (isShowing) {
                updateWindowHeight(getDesiredVisibleWindowHeight())
            }
            return
        }

        homeButtonsAnimator?.cancel()

        // 윈도우 높이 확장 (버튼이 overflow 영역에 렌더링되도록)
        areButtonsMovedToCenter = true
        updateWindowHeight(getDesiredVisibleWindowHeight())

        val barHeight = getSystemNavigationBarHeightPx()
        val expandedBarHeight = getQuickstepPlusHomeExpandedHeightPx(barHeight)
        val iconCenterFromBottom = expandedBarHeight / 2
        // 버튼 현재 중심의 화면 하단으로부터의 높이
        val buttonCenterFromBottom = barHeight / 2
        // 수직 이동량 (위로 = 음수)
        val targetDy = -(iconCenterFromBottom - buttonCenterFromBottom).toFloat()

        if (animate) {
            val currentBarHeight = navBarView?.layoutParams?.height ?: barHeight
            val animSet = android.animation.AnimatorSet()
            val expandFrameThrottle = AnimationPerformanceHelper.FrameThrottle()
            val barHeightAnimator = ValueAnimator.ofInt(currentBarHeight, expandedBarHeight).apply {
                addUpdateListener { animator ->
                    if (!expandFrameThrottle.shouldDispatch(animator)) return@addUpdateListener
                    val h = animator.animatedValue as Int
                    updateBarAndBackgroundHeight(h)
                }
            }
            animSet.playTogether(
                android.animation.ObjectAnimator.ofFloat(left, "translationY", left.translationY, targetDy),
                android.animation.ObjectAnimator.ofFloat(right, "translationY", right.translationY, targetDy),
                barHeightAnimator
            )
            animSet.duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.HOME_UI_TRANSITION_DURATION_MS)
            animSet.interpolator = homeUiTransitionInterpolator
            animSet.start()
            homeButtonsAnimator = animSet
        } else {
            // 애니메이션 없이 즉시 위치 적용
            left.translationY = targetDy
            right.translationY = targetDy
            updateBarAndBackgroundHeight(expandedBarHeight)
        }
    }

    /**
     * 홈화면 이탈 시 좌우 버튼 그룹을 원래 위치로 복귀
     */
    private fun animateButtonsToOriginalPosition() {
        val left = leftButtonGroup ?: return
        val right = rightButtonGroup ?: return

        if (isGestureNavbarMode()) {
            resetButtonPositionsImmediate()
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
            if (isShowing) {
                updateWindowHeight(getDesiredVisibleWindowHeight())
            }
            return
        }

        if (!areButtonsMovedToCenter) return
        homeButtonsAnimator?.cancel()

        val barHeight = getSystemNavigationBarHeightPx()
        val currentBarViewHeight = (navBarView?.layoutParams?.height ?: barHeight)

        val animSet = android.animation.AnimatorSet()
        val collapseFrameThrottle = AnimationPerformanceHelper.FrameThrottle()
        val barHeightAnimator = ValueAnimator.ofInt(currentBarViewHeight, barHeight).apply {
            addUpdateListener { animator ->
                if (!collapseFrameThrottle.shouldDispatch(animator)) return@addUpdateListener
                val h = animator.animatedValue as Int
                updateBarAndBackgroundHeight(h)
            }
        }
        animSet.playTogether(
            android.animation.ObjectAnimator.ofFloat(left, "translationY", left.translationY, 0f),
            android.animation.ObjectAnimator.ofFloat(right, "translationY", right.translationY, 0f),
            barHeightAnimator
        )
        animSet.duration = AnimationPerformanceHelper.resolveDuration(Constants.Timing.HOME_UI_TRANSITION_DURATION_MS)
        animSet.interpolator = homeUiTransitionInterpolator
        animSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                areButtonsMovedToCenter = false
                homeButtonsAnimator = null
                updateWindowHeight(getDesiredVisibleWindowHeight())
            }
        })
        animSet.start()
        homeButtonsAnimator = animSet
    }

    /**
     * 홈 버튼 이동 상태를 즉시 초기화 (애니메이션 없이)
     */
    private fun resetButtonPositionsImmediate() {
        homeButtonsAnimator?.cancel()
        homeButtonsAnimator = null
        leftButtonGroup?.translationY = 0f
        rightButtonGroup?.translationY = 0f
        areButtonsMovedToCenter = false
    }

    /**
     * Navbar 윈도우의 터치 가능 영역 제한 (reflection, @hide API)
     * 윈도우 높이가 확장된 경우 overflow 영역은 터치 패스스루
     */
    private fun setupNavBarTouchableRegionListener(window: FrameLayout) {
        try {
            val listenerClass = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener"
            )
            val infoClass = Class.forName(
                "android.view.ViewTreeObserver\$InternalInsetsInfo"
            )
            val setTouchableInsets = infoClass.getMethod(
                "setTouchableInsets", Int::class.javaPrimitiveType
            )
            val touchableRegionField = infoClass.getDeclaredField("touchableRegion").apply { isAccessible = true }
            val TOUCHABLE_INSETS_REGION = infoClass
                .getDeclaredField("TOUCHABLE_INSETS_REGION").apply { isAccessible = true }.getInt(null)

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && args != null) {
                    val info = args[0]
                    setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
                    val region = touchableRegionField.get(info) as android.graphics.Region
                    val wh = window.height
                    val barH = getSystemNavigationBarHeightPx()

                    // 기본: navbar bar 영역 (하단)
                    region.set(0, wh - barH, window.width, wh)

                    // 버튼이 위로 이동 중이면 버튼 위치도 터치 가능 영역에 추가
                    if (areButtonsMovedToCenter) {
                        val left = leftButtonGroup
                        val right = rightButtonGroup
                        if (left != null && left.width > 0) {
                            val ly = (left.top + left.translationY).toInt()
                            region.union(android.graphics.Rect(
                                left.left, ly, left.left + left.width, ly + left.height
                            ))
                        }
                        if (right != null && right.width > 0) {
                            val ry = (right.top + right.translationY).toInt()
                            region.union(android.graphics.Rect(
                                right.left, ry, right.left + right.width, ry + right.height
                            ))
                        }
                    }
                }
                null
            }

            val addMethod = android.view.ViewTreeObserver::class.java.getMethod(
                "addOnComputeInternalInsetsListener", listenerClass
            )
            addMethod.invoke(window.viewTreeObserver, proxy)
            Log.d(TAG, "setupNavBarTouchableRegionListener: success")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set navbar touchable region listener", e)
        }
    }

    private fun getDesiredTaskbarWindowWidth(): Int {
        val groupChildCount = (centerGroupView as? ViewGroup)?.childCount ?: 0
        val iconCount = if (groupChildCount > 0) {
            groupChildCount
        } else {
            settings.recentAppsTaskbarIconCount.coerceIn(3, 7)
        }

        val iconSizeDp = recentAppsTaskbar?.getReservedIconSizeDp()
            ?: if (shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()) {
                RecentAppsTaskbar.LARGE_HOME_ICON_SIZE_DP
            } else {
                Constants.Dimension.TASKBAR_ICON_SIZE_DP
            }

        val iconSizePx = context.dpToPx(iconSizeDp)
        val halfSpacingPx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SPACING_DP / 2)
        val slotWidthPx = iconSizePx + (halfSpacingPx * 2)
        return (slotWidthPx * iconCount.coerceAtLeast(1)).coerceAtLeast(1)
    }

    private fun getDesiredTaskbarWindowHeight(): Int {
        val barHeight = getSystemNavigationBarHeightPx()
        if (isGestureNavbarMode()) {
            return when {
                shouldUseQuickstepPlusHomeTaskbarSizeForDisplay() -> getQuickstepPlusHomeExpandedHeightPx(barHeight)
                shouldShowGestureRecentsPanel() -> getGestureRecentsPanelHeightPx(barHeight)
                else -> barHeight
            }
        }
        val reservedIconSizeDp = recentAppsTaskbar?.getReservedIconSizeDp()
            ?: if (shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()) {
                RecentAppsTaskbar.LARGE_HOME_ICON_SIZE_DP
            } else {
                Constants.Dimension.TASKBAR_ICON_SIZE_DP
            }

        if (reservedIconSizeDp > Constants.Dimension.TASKBAR_ICON_SIZE_DP) {
            return if (isGestureNavbarMode()) {
                getQuickstepPlusHomeExpandedHeightPx(barHeight)
            } else {
                barHeight + RecentAppsTaskbar.calculateHomeLargeOverflowPx(
                    context = context,
                    barHeightPx = barHeight,
                    iconSizeDp = reservedIconSizeDp
                )
            }
        }
        return barHeight
    }

    
    /**
     * QQPlus 런처 홈화면 활성 상태 갱신.
     * isOnHomeScreen && currentPackage == QUICKSTEP_PLUS_PACKAGE 조건으로 판단.
     * 상태 변경 시 BackgroundManager 에 전파하고 배경을 재적용한다.
     *
     * 호출 지점:
     *   1) setHomeScreenState(true) 에서 홈 진입 확정 후
     *   2) setForegroundPackage() 에서 currentPackage 변경 후
     */
    private fun updateQQPlusHomeState() {
        val newActive = isOnHomeScreen && currentPackage.trim() == QUICKSTEP_PLUS_PACKAGE
        if (isQQPlusHomeActive == newActive) return

        isQQPlusHomeActive = newActive
        Log.d(TAG, "QQPlus home active changed: $newActive")

        // 비트맵이 아직 로드되지 않았으면 로드 (QQPlus 런처 활성 시 120px 비트맵 포함)
        if (!backgroundManager.hasBitmaps()) {
            backgroundManager.loadBackgroundBitmaps(forceReload = false)
        }

        // QQPlus 홈 활성화 시 배경 전환과 동시에 아이콘/버튼 전환도 시작
        if (newActive && isQuickstepPlusTaskbarFeatureActive()) {
            // backgroundView 복원 (기존 네비바 노출 방지)
            syncDefaultBackgroundLayer(makeVisible = true)
            updateTaskbarIconSizeForCurrentState()
        }
        updateNavBarBackground()
    }

private fun shouldUseQuickstepPlusOverlayFade(): Boolean {
        return currentPackage.trim() == QUICKSTEP_PLUS_PACKAGE || isOnHomeScreen || isAppDrawerOpen
    }

    private fun isQuickstepPlusTaskbarFeatureActive(): Boolean {
        return settings.recentAppsTaskbarEnabled && cachedLauncherPackages.contains(QUICKSTEP_PLUS_PACKAGE)
    }

    private fun isQuickstepPlusLauncherActive(): Boolean {
        return cachedLauncherPackages.contains(QUICKSTEP_PLUS_PACKAGE)
    }

    private fun getQuickstepPlusHomeTaskbarBaseOverflowPx(): Int {
        if (!isQuickstepPlusTaskbarFeatureActive()) return 0

        val barHeightPx = getSystemNavigationBarHeightPx()
        return (getQuickstepPlusHomeExpandedHeightPx(barHeightPx) - barHeightPx).coerceAtLeast(0)
    }

    private fun startHomeExitSuppression(durationMs: Long = Constants.Timing.HOME_STATE_DEBOUNCE_MS) {
        val now = SystemClock.elapsedRealtime()
        val nextUntil = now + durationMs
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
            syncDefaultBackgroundLayer(makeVisible = true)
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getDesiredVisibleWindowHeight())
            isShowing = true
        }
    }

    /**
     * 홈 진입 시 배경을 페이드 효과로 전환
     */
    private fun applyHomeEntryBackground() {
        val bar = navBarView ?: return
        syncOrientationIfNeeded("home_entry")

        // backgroundView가 숨김 상태일 수 있으므로 반드시 복원
        syncDefaultBackgroundLayer(makeVisible = true)

        val shouldUseCustom = shouldUseCustomBackground()
        backgroundManager.applyBackground(bar, shouldUseCustom, forceUpdate = true, animate = true)

        if (!shouldUseCustom) {
            syncDefaultBackgroundLayer(makeVisible = true)
        }
        updateQuickstepHomeShadowVisibility()
        Log.d(TAG, "Home entry background applied with fade (custom=$shouldUseCustom)")

        // 홈 진입 완료 후 상태 동기화 예약 (애니메이션 완료 후)
        val transitionDurationMs = AnimationPerformanceHelper.resolveDuration(Constants.Timing.BG_TRANSITION_DURATION_MS)
        handler.postDelayed({
            if (!isCreated) return@postDelayed
            ensureVisualStateSync()
        }, transitionDurationMs + 16L)
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
            applyUnlockBackgroundToBackLayer()
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
            syncDefaultBackgroundLayer(makeVisible = true)
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getDesiredVisibleWindowHeight())
            isShowing = true
            applyBackgroundImmediate(shouldUseCustomBackground())
            Log.d(TAG, "Animations cancelled and state restored to showing (including button color)")
        } else {
            Log.d(TAG, "Animations cancelled, keeping hidden state (for lock screen unlock fade)")
        }
    }

    fun setRecentsState(isRecents: Boolean) {
        if (isRecents) {
            clearHomeButtonPreviewHint()
            startRecentsTransitionGuard()
            if (isRecentsVisible) return
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null
            pendingRecentsClose?.let { handler.removeCallbacks(it) }
            pendingRecentsClose = null

            if (isGestureNavbarMode()) {
                isRecentsVisible = true
                updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
                updateTaskbarWindowBounds()
                updateTaskbarIconSizeForCurrentState()
                updateNavBarBackground()
                if (isShowing) {
                    syncTaskbarVisibility(animate = true)
                }
                return
            }

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
        if (isGestureNavbarMode()) {
            updateBarAndBackgroundHeight(getDesiredVisibleWindowHeight())
            updateTaskbarWindowBounds()
        }
        updateTaskbarIconSizeForCurrentState()
        updateNavBarBackground()
        if (isGestureNavbarMode()) {
            if (isShowing) {
                syncTaskbarVisibility(animate = true)
            } else {
                hideTaskbarImmediate()
            }
            return
        }
        if (isOnHomeScreen) {
            syncTaskbarVisibility(animate = true)
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
        val keepGestureExpandedUntilHide = isGestureNavbarMode() && isOpen && isShowing
        if (!keepGestureExpandedUntilHide) {
            updateTaskbarIconSizeForCurrentState()
        }

        if (isOpen) {
            clearHomeButtonPreviewHint()
        }

        // Nova 런처 오버레이 전환은 즉시 반영.
        // - 홈배경 -> 일반배경: 즉시 전환(페이드 없음)
        // - 일반배경 -> 홈배경: 페이드 복원
        if (!keepGestureExpandedUntilHide) {
            val shouldUseCustom = shouldUseCustomBackground()
            val animate = shouldUseCustom
            applyBackgroundImmediate(useCustom = shouldUseCustom, animate = animate)
            Log.d(TAG, "Launcher overlay immediate background: custom=$shouldUseCustom, animate=$animate")
        } else {
            Log.d(TAG, "Launcher overlay gesture hide pending - keeping expanded state until slide-out")
        }

        updateNavBarBackground()
    }

    fun setLauncherIconDragState(active: Boolean) {
        if (isLauncherIconDragActive == active) return
        isLauncherIconDragActive = active
        Log.d(TAG, "Launcher icon drag state changed: $active")
        updateTaskbarIconSizeForCurrentState()

        if (active) {
            clearHomeButtonPreviewHint()
        }

        // 런처 드래그 전환은 즉시 반영.
        // - 홈배경 -> 일반배경: 즉시 전환(페이드 없음)
        // - 일반배경 -> 홈배경: 페이드 복원
        val shouldUseCustom = shouldUseCustomBackground()
        val animate = shouldUseCustom
        applyBackgroundImmediate(useCustom = shouldUseCustom, animate = animate)
        Log.d(TAG, "Launcher drag immediate background: custom=$shouldUseCustom, animate=$animate")

        updateNavBarBackground()
    }

    fun setForegroundPackage(packageName: String, className: String = "") {
        val normalizedClass = className.trim()
        val packageChanged = currentPackage != packageName
        val classChanged = currentForegroundClassName != normalizedClass

        if (!packageChanged && !classChanged) return

        if (packageChanged) {
            currentPackage = packageName
            val nonLauncherForeground =
                packageName.isNotEmpty() &&
                    packageName != context.packageName &&
                    packageName != "com.android.systemui" &&
                    !service.isLauncherPackageForOverlay(packageName)
            if (nonLauncherForeground) {
                lastNonLauncherForegroundAt = SystemClock.elapsedRealtime()
                clearTransientBackgroundCoverHint()
                abortHomeEntryTransitionForLaunchHint(
                    packageName = packageName,
                    className = normalizedClass,
                    source = "foreground"
                )
            }
            Log.d(TAG, "Foreground package changed: $packageName")
            updateQQPlusHomeState()
            updateNavBarBackground()

            // 포그라운드 패키지 변동(IME/런처/앱 전환) 후 진입 애니메이션 우선
            syncTaskbarVisibility(animate = true)
        }

        currentForegroundClassName = normalizedClass
        updateTaskbarIconSizeForCurrentState()

        if (packageChanged || packageName == GOOGLE_QUICKSEARCHBOX_PACKAGE) {
            recentAppsManager?.onForegroundAppChanged(
                packageName = packageName,
                className = normalizedClass,
                isOnHomeScreen = isOnHomeScreen,
                isRecentsVisible = isRecentsVisible
            )
        }
    }

    private fun isCustomTaskbarMode(): Boolean {
        return settings.taskbarMode == SettingsManager.TaskbarMode.CUSTOM_APPS
    }

    private fun loadCustomTaskbarApps(): List<RecentAppsManager.RecentAppInfo> {
        val pm = context.packageManager
        return settings.taskbarCustomAppsItems
            .asSequence()
            .filter { !it.startsWith("shortcut:") }
            .mapNotNull { packageName ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val icon = CustomAppIconStore.loadDrawable(context, packageName)
                        ?: settings.iconPackPackage?.let { iconPackPackage ->
                            IconPackManager.loadDrawableForPackage(context, iconPackPackage, packageName)
                        }
                        ?: pm.getApplicationIcon(appInfo)
                    val label = pm.getApplicationLabel(appInfo)
                    RecentAppsManager.RecentAppInfo(packageName, icon, label, isResizeable = true)
                } catch (_: Exception) {
                    null
                }
            }
            .take(settings.recentAppsTaskbarIconCount.coerceIn(3, 7))
            .toList()
    }

    private fun updateTaskbarContentForCurrentMode(animate: Boolean, syncVisibility: Boolean = true) {
        if (!settings.recentAppsTaskbarEnabled) {
            hideTaskbarImmediate()
            return
        }

        updateTaskbarIconSizeForCurrentState()

        val displayedApps = if (isCustomTaskbarMode()) {
            loadCustomTaskbarApps()
        } else {
            recentAppsManager?.getRecentApps()?.take(settings.recentAppsTaskbarIconCount.coerceIn(3, 7)) ?: emptyList()
        }

        recentAppsTaskbar?.updateApps(displayedApps)
        updateTaskbarWindowBounds()

        if (!syncVisibility) {
            if (displayedApps.isEmpty()) {
                hideTaskbarImmediate()
            }
            return
        }

        if (displayedApps.isNotEmpty()) {
            syncTaskbarVisibility(animate = animate)
        } else {
            hideTaskbarImmediate()
        }
    }

    private fun shouldShowTaskbar(): Boolean {
        if (!settings.recentAppsTaskbarEnabled) return false
        if (isGestureNavbarMode()) {
            if (isHomeExitPending) return false
            if (shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()) return true
            return shouldShowGestureRecentsPanel()
        }

        if (isRecentsVisible) return false

        // 홈 상시 표시가 켜져 있으면 홈/앱 전환 중에도 숨기지 않아
        // 불필요한 재진입 애니메이션/깜빡임을 방지
        if (settings.recentAppsTaskbarShowOnHome) return true

        if (isHomeExitPending) return false
        if (isOnHomeScreen) return settings.recentAppsTaskbarShowOnHome
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

        val keepGestureExpandedUntilHide =
            isGestureNavbarMode() &&
                isShowing &&
                (isNotificationOpen || isQuickSettingsOpen)

        // 홈화면에서 패널 열림/닫힘 시 아이콘 크기 전환
        if (isOnHomeScreen && isQuickstepPlusTaskbarFeatureActive() && !keepGestureExpandedUntilHide) {
            updateTaskbarIconSizeForCurrentState()
        }

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
        updateTaskbarWindowBounds()

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
            syncDefaultBackgroundLayer(makeVisible = isShowing)

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
                params.height = getWindowHeightForCurrentState()
                windowManager.updateViewLayout(root, params)
                currentWindowHeight = params.height
                updateBackgroundLayerVisibility()
            }
            updateTaskbarWindowBounds()

            // 분할화면 오버레이 크기 재생성 (방향에 맞게)
            recreateSplitScreenOverlay()
        }

        if (orientationChanged || darkModeChanged) {
            // 진행 중인 배경 전환 취소 후 비트맵 리로드 (1회만)
            backgroundManager.cancelBackgroundTransition()
            backgroundManager.loadBackgroundBitmaps(forceReload = true)

            // forceUpdate로 배경 강제 적용 (dedup 우회)
            val bar = navBarView ?: return
            val stableHomeCustom =
                isOnHomeScreen &&
                    !isRecentsVisible &&
                    !isHomeExitPending &&
                    !isAppDrawerOpen &&
                    !isLauncherIconDragActive &&
                    !isPanelOpen() &&
                    !isImeVisible
            val shouldUseCustom = if (stableHomeCustom) true else shouldUseCustomBackground()
            backgroundManager.applyBackground(
                bar, shouldUseCustom,
                forceUpdate = true,
                animate = !orientationChanged  // 방향 전환 시는 즉시, 다크 모드만 변경 시는 페이드
            )
            syncDefaultBackgroundLayer(makeVisible = isShowing)

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

        if (isLauncherIconDragActive) {
            return false
        }

        // 홈 상태 플래그가 지연 갱신되는 동안(non-home 앱으로 전환 직후)
        // 커스텀 홈 배경이 재적용되지 않도록 포그라운드 패키지로 한 번 더 가드한다.
        val appForegroundWhileHome =
            isOnHomeScreen &&
            currentPackage.isNotEmpty() &&
            currentPackage != context.packageName &&
            currentPackage != "com.android.systemui" &&
            !isGoogleLauncherCompanionForeground() &&
            (now - lastNonLauncherForegroundAt) < HOME_APP_FOREGROUND_GRACE_MS &&
            !service.isLauncherPackageForOverlay(currentPackage)
        if (appForegroundWhileHome) {
            return false
        }

        if (
            now < homeButtonPreviewUntil &&
            !isRecentsVisible &&
            !isAppDrawerOpen &&
            !isPanelOpen() &&
            !isImeVisible
        ) {
            return true
        }

        if (isHomeExitPending || now < homeExitSuppressUntil) {
            return false
        }
        if (now < recentsTransitionGuardUntil) {
            val launcherForegroundLike =
                currentPackage.isEmpty() ||
                    service.isLauncherPackageForOverlay(currentPackage) ||
                    isGoogleLauncherCompanionForeground()
            val stableHomeDuringGuard =
                isOnHomeScreen &&
                    !isRecentsVisible &&
                    !isHomeExitPending &&
                    !isAppDrawerOpen &&
                    !isLauncherIconDragActive &&
                    !isPanelOpen() &&
                    !isImeVisible &&
                    launcherForegroundLike
            if (!stableHomeDuringGuard) {
                return false
            }
            Log.d(TAG, "Bypass recents guard on stable home")
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

    private fun shouldShowQuickstepHomeShadow(): Boolean {
        return !isGestureNavbarMode() &&
            isQuickstepPlusLauncherActive() &&
            isOnHomeScreen &&
            shouldUseCustomBackground() &&
            backgroundManager.hasBitmaps()
    }

    private fun getQuickstepHomeShadowHeightPx(containerHeightPx: Int = navBarView?.layoutParams?.height
        ?: getSystemNavigationBarHeightPx()): Int {
        val minHeightPx = getSystemNavigationBarHeightPx()
        val maxHeightPx = maxOf(minHeightPx, Constants.Dimension.CROP_HEIGHT_PX)
        return containerHeightPx.coerceIn(minHeightPx, maxHeightPx)
    }

    private fun updateQuickstepHomeShadowLayout(containerHeightPx: Int = navBarView?.layoutParams?.height
        ?: getSystemNavigationBarHeightPx()) {
        val shadowView = homeShadowView ?: return
        val params = shadowView.layoutParams as? FrameLayout.LayoutParams ?: return
        val desiredHeightPx = getQuickstepHomeShadowHeightPx(containerHeightPx)
        if (params.height != desiredHeightPx) {
            params.height = desiredHeightPx
            shadowView.layoutParams = params
        }
    }

    private fun updateQuickstepHomeShadowVisibility() {
        updateQuickstepHomeShadowLayout()
        homeShadowView?.visibility = if (shouldShowQuickstepHomeShadow()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun isGoogleLauncherCompanionForeground(): Boolean {
        if (currentPackage != GOOGLE_QUICKSEARCHBOX_PACKAGE) return false

        val cls = currentForegroundClassName.trim()
        if (cls.isBlank()) return true
        if (cls == "android.widget.FrameLayout") return true
        if (cls == "android.widget.LinearLayout") return true
        if (cls == "android.widget.ScrollView") return true
        if (cls.startsWith("android.view.")) return true

        val simpleName = cls.substringAfterLast('.')
        return simpleName.contains("SearchLauncher", ignoreCase = true) ||
            simpleName.contains("LauncherClient", ignoreCase = true) ||
            simpleName.contains("Discover", ignoreCase = true)
    }

    private fun resolveUnlockUseCustom(): Boolean {
        val override = unlockUseCustomOverride
        return override ?: backgroundManager.resolveUseCustomForUnlock(shouldUseCustomBackground())
    }

    private fun applyTransparentGestureBackground() {
        navBarView?.background = ColorDrawable(Color.TRANSPARENT)
        backgroundView?.apply {
            background = ColorDrawable(Color.TRANSPARENT)
            alpha = 0f
            visibility = View.GONE
        }
        buttonManager.updateAllButtonColors(Color.WHITE, force = true)
        updateQuickstepHomeShadowVisibility()
    }

    /**
     * 배경 즉시 적용 (트랜지션 중단 시 사용)
     * @param useCustom 커스텀 배경 사용 여부
     * @param animate 애니메이션 사용 여부 (기본값: false)
     */
    private fun applyBackgroundImmediate(useCustom: Boolean, animate: Boolean = false) {
        val bar = navBarView ?: return
        syncOrientationIfNeeded("background")
        if (!shouldRenderGestureNavbarBackground()) {
            applyTransparentGestureBackground()
            return
        }
        backgroundManager.applyBackground(
            bar,
            useCustom,
            forceUpdate = true,
            animate = animate
        )
        syncDefaultBackgroundLayer(makeVisible = isShowing)
        updateQuickstepHomeShadowVisibility()
    }

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return

        syncOrientationIfNeeded("background")

        if (!shouldRenderGestureNavbarBackground() &&
            !(isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed)
        ) {
            applyTransparentGestureBackground()
            return
        }

        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
            val shouldUseCustom = resolveUnlockUseCustom()
            backgroundManager.applyBackground(
                bar,
                shouldUseCustom,
                forceUpdate = true,
                animate = false
            )
            applyUnlockBackgroundToBackLayer()
            updateQuickstepHomeShadowVisibility()
            return
        }

        val shouldUseCustom = shouldUseCustomBackground()
        val immediateDefaultForAppLaunch =
            !shouldUseCustom &&
                currentPackage.isNotEmpty() &&
                currentPackage != context.packageName &&
                currentPackage != "com.android.systemui" &&
                !service.isLauncherPackageForOverlay(currentPackage)
        val immediateDefaultForLauncherOverlay =
            !shouldUseCustom &&
                isOnHomeScreen &&
                (isAppDrawerOpen || isLauncherIconDragActive)
        if (backgroundManager.isTransitionInProgress() &&
            backgroundManager.wasLastAppliedCustom() != shouldUseCustom
        ) {
            // 진행 중인 트랜지션을 중단하고 올바른 상태로 전환
            // 앱 런치/로딩 시작 구간은 즉시 일반배경으로 전환해 잔상 방지
            val forceAnimate =
                isOnHomeScreen &&
                    !immediateDefaultForAppLaunch &&
                    !immediateDefaultForLauncherOverlay
            applyBackgroundImmediate(shouldUseCustom, animate = forceAnimate)
            return
        }
        backgroundManager.applyBackground(
            bar,
            shouldUseCustom,
            animate = !(immediateDefaultForAppLaunch || immediateDefaultForLauncherOverlay)
        )
        syncDefaultBackgroundLayer(makeVisible = isShowing)
        updateQuickstepHomeShadowVisibility()
    }

    private fun applyUnlockBackgroundToBackLayer() {
        backgroundView?.let { bg ->
            val useUnlockCustom = resolveUnlockUseCustom()
            val unlockDrawable = if (useUnlockCustom) {
                navBarView?.background?.constantState?.newDrawable()?.mutate()
                    ?: backgroundManager.createDefaultBackgroundDrawable()
            } else {
                backgroundManager.createDefaultBackgroundDrawable()
            }
            bg.background = unlockDrawable
            bg.alpha = 0f
            bg.visibility = View.GONE
        }
        updateBackgroundLayerVisibility()
    }

    private fun restoreBackgroundLayerDefault() {
        syncDefaultBackgroundLayer()
    }

    private fun syncDefaultBackgroundLayer(makeVisible: Boolean = false, alpha: Float = 1f) {
        backgroundView?.let { bg ->
            if (!shouldRenderGestureNavbarBackground()) {
                bg.background = ColorDrawable(Color.TRANSPARENT)
                bg.alpha = 0f
                bg.visibility = View.GONE
                return@let
            }
            bg.background = backgroundManager.createDefaultBackgroundDrawable()
            bg.alpha = alpha
            if (makeVisible || shouldKeepBackgroundLayerVisible()) {
                bg.visibility = View.VISIBLE
            }
        }
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
        if (!shouldRenderGestureNavbarBackground()) {
            applyTransparentGestureBackground()
            return
        }

        // 버튼 색상 강제 동기화
        backgroundManager.forceApplyCurrentButtonColor()

        // backgroundView 가시성 보장 (숨김 애니메이션 후 복원 누락 방지)
        if (!(isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed)) {
            syncDefaultBackgroundLayer(makeVisible = true)
        }

        // 배경 레이어 상태 확인
        val shouldUseCustom = shouldUseCustomBackground()
        if (!shouldUseCustom) {
            syncDefaultBackgroundLayer()
        }

        updateQuickstepHomeShadowVisibility()
        Log.d(TAG, "Visual state sync completed (home=$isOnHomeScreen, custom=$shouldUseCustom)")
    }

// ===== 버튼 액션 처리 =====

    private fun handleButtonClick(action: NavAction) {
        if (action == NavAction.NAVBAR_APPS) {
            toggleNavbarAppsPanel()
            return
        }
        if (action == NavAction.RECENTS) {
            startRecentsTransitionGuard()
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
            if (action == NavAction.HOME || action == NavAction.BACK) {
                pinDefaultBackgroundLayerForCustomNavTransition()
            }
            if (action == NavAction.HOME) {
                startHomeButtonPreviewTransition()
            }

            val executed = service.executeAction(action)
            if (!executed && (action == NavAction.HOME || action == NavAction.BACK)) {
                clearTransientBackgroundCoverHint()
                if (action == NavAction.HOME) {
                    clearHomeButtonPreviewHint()
                    cancelPendingHomeButtonGrowAnimation()
                    updateNavBarBackground()
                }
            }
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

        // 아이콘 drawable 복사 + 모양 클리핑 적용
        val shapeMode = settings.recentAppsTaskbarIconShape
        val sourceDrawable = iconView.drawable ?: return
        val baseDrawable = sourceDrawable.constantState?.newDrawable()?.mutate() ?: sourceDrawable.mutate()
        val drawable = IconShapeMaskHelper.wrapWithShapeMask(context, baseDrawable, shapeMode)

        val icon = ImageView(context).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            applyDragIconShape(this, shapeMode)
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

    private fun startInlineTaskbarDrag(iconView: ImageView, screenX: Float, screenY: Float) {
        resetInlineTaskbarDrag()
        inlineTaskbarDragView = iconView
        inlineTaskbarDragStartX = screenX
        inlineTaskbarDragStartY = screenY
        iconView.alpha = 1f
        iconView.translationX = 0f
        iconView.translationY = 0f
        iconView.scaleX = 1.06f
        iconView.scaleY = 1.06f
        iconView.bringToFront()
    }

    private fun updateInlineTaskbarDrag(screenX: Float, screenY: Float, scale: Float) {
        val iconView = inlineTaskbarDragView ?: return
        iconView.alpha = 1f
        iconView.translationX = screenX - inlineTaskbarDragStartX
        iconView.translationY = screenY - inlineTaskbarDragStartY
        iconView.scaleX = scale
        iconView.scaleY = scale
    }

    private fun resetInlineTaskbarDrag() {
        inlineTaskbarDragView?.apply {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
        inlineTaskbarDragView = null
        inlineTaskbarDragStartX = 0f
        inlineTaskbarDragStartY = 0f
    }

    private fun startRecentsTransitionGuard(durationMs: Long = RECENTS_TRANSITION_GUARD_MS) {
        val now = SystemClock.elapsedRealtime()
        val nextUntil = now + durationMs
        if (nextUntil > recentsTransitionGuardUntil) {
            recentsTransitionGuardUntil = nextUntil
        }
        updateNavBarBackground()
        Log.d(TAG, "Recents transition guard: ${durationMs}ms")
    }

    private fun startHomeButtonPreviewTransition(durationMs: Long = HOME_BUTTON_PREVIEW_GUARD_MS) {
        if (isOnHomeScreen) return
        if (isRecentsVisible || isAppDrawerOpen || isPanelOpen() || isImeVisible) return
        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) return

        val now = SystemClock.elapsedRealtime()
        val nextUntil = now + durationMs
        if (nextUntil > homeButtonGrowHintUntil) {
            homeButtonGrowHintUntil = nextUntil
        }

        cancelHideAnimationOnly()
        pinDefaultBackgroundLayerForCustomNavTransition(durationMs)
        Log.d(TAG, "Home button cover transition: ${durationMs}ms")
    }

    fun startBackExitHomePreviewTransition(durationMs: Long = HOME_BUTTON_PREVIEW_GUARD_MS) {
        startHomeButtonPreviewTransition(durationMs)
    }

    fun abortHomeEntryTransitionForLaunchHint(
        packageName: String,
        className: String,
        source: String
    ) {
        if (!isOnHomeScreen || isRecentsVisible) return
        if (isAppDrawerOpen || isPanelOpen() || isImeVisible) return
        if (!backgroundManager.isTransitionInProgress()) return
        if (!backgroundManager.wasLastAppliedCustom()) return

        clearHomeButtonPreviewHint()
        applyBackgroundImmediate(useCustom = false, animate = false)
        Log.d(TAG, "Abort home-entry transition for app launch hint ($source): $packageName/$className")
    }

    private fun clearHomeButtonPreviewHint() {
        if (homeButtonPreviewUntil == 0L) return
        homeButtonPreviewUntil = 0L
    }

    fun clearTransientBackgroundCoverHint() {
        transientBackgroundCoverUntil = 0L
    }

    private fun applyDragIconShape(
        icon: ImageView,
        shapeMode: SettingsManager.RecentAppsTaskbarIconShape
    ) {
        icon.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0) return

                when (shapeMode) {
                    SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> {
                        outline.setOval(0, 0, width, height)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                        val radius = minOf(width, height) * Constants.Dimension.TASKBAR_SQUARE_RADIUS_RATIO
                        outline.setRoundRect(0, 0, width, height, radius)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                        outline.setRect(0, 0, width, height)
                    }
                    SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                        val radius = minOf(width, height) * 0.22f
                        outline.setRoundRect(0, 0, width, height, radius)
                    }
                }
            }
        }
        icon.clipToOutline = shapeMode != SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE
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

        // Taskbar 컴포넌트 초기화 (createNavBar 전에 해야 centerGroup 생성됨)
        if (settings.recentAppsTaskbarEnabled) {
            if (recentAppsTaskbar == null) {
                recentAppsTaskbar = RecentAppsTaskbar(context, taskbarListener)
            }
            recentAppsTaskbar?.splitScreenEnabled = settings.splitScreenTaskbarEnabled
            recentAppsTaskbar?.iconShape = settings.recentAppsTaskbarIconShape

            if (isCustomTaskbarMode()) {
                recentAppsManager?.clear()
                recentAppsManager = null
            } else {
                if (recentAppsManager == null) {
                    recentAppsManager = RecentAppsManager(context, recentAppsListener)
                }
                recentAppsManager?.setLauncherPackages(cachedLauncherPackages)
                recentAppsManager?.refreshIcons()
                if (recentAppsManager?.getRecentApps().isNullOrEmpty()) {
                    recentAppsManager?.loadInitialRecentApps()
                }
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
            destroyTaskbarWindow()
            buttonManager.clear()
            centerGroupView = null
            createNavBar()
            createHotspot()

            if (settings.recentAppsTaskbarEnabled) {
                updateTaskbarContentForCurrentMode(animate = false)
            }

            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
            currentWindowHeight = params.height
        }

        updateNavBarBackground()
        syncTaskbarVisibility(animate = false)
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

        // QQPlus 런처 감지 시 BackgroundManager에 영구 활성화
        // → 항상 120px 비트맵 사용, BOTTOM gravity로 아래 정렬 (이미지 압축 대신 위쪽 클리핑)
        if (launcherPackages.contains(QUICKSTEP_PLUS_PACKAGE)) {
            backgroundManager.setQQPlusActive(true)
        }
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
        // lockscreen에서는 custom overlay가 보이면 안 되므로 back layer도 숨긴 채 준비만 한다.
        navBarView?.alpha = 0f
        navBarView?.visibility = View.VISIBLE
        backgroundView?.alpha = 0f
        backgroundView?.visibility = View.GONE
        hotspotView?.visibility = View.GONE
        updateWindowHeight(getDesiredVisibleWindowHeight())
        updateTaskbarContentForCurrentMode(animate = false)
        if (shouldShowTaskbar()) {
            showTaskbarImmediate()
            centerGroupView?.alpha = 0f
            updateTaskbarWindowTouchableState()
        } else {
            hideTaskbarImmediate()
        }
        updateWindowHeight(getDesiredVisibleWindowHeight())

        // 홈화면이면 버튼을 미리 위로 올린 상태로 준비 (애니메이션 없이)
        if (shouldUseQuickstepPlusHomeTaskbarSizeForDisplay()) {
            animateButtonsToHomePosition(animate = false)
        }

        Log.d(TAG, "Prepared for unlock fade (home=$isOnHomeScreen, useCustom=$useCustomForUnlock)")
    }
    fun markNextShowInstant() { /* no-op */ }
}
