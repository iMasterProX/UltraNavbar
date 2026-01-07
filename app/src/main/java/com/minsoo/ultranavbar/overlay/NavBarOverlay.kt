package com.minsoo.ultranavbar.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.BackgroundManager
import com.minsoo.ultranavbar.core.ButtonManager
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.core.GestureHandler
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

    // === 뷰 참조 ===
    private var rootView: FrameLayout? = null
    private var backgroundView: View? = null
    private var navBarView: ViewGroup? = null
    private var gestureOverlayView: View? = null
    private var hotspotView: View? = null

    // === 상태 ===
    private var isShowing = true
    private var isCreated = false
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    // 시스템 상태
    private var isOnHomeScreen = false
    private var isRecentsVisible = false
    private var isImeVisible = false
    private var isPanelOpenUi = false

    // 다크 모드 전환 추적
    private var darkModeTransitionTime: Long = 0

    // 디바운스용 Runnable
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null

    // 숨김 애니메이션 진행 중 추적 (홈 화면 복귀 시 복원 여부 결정)
    private var hideAnimationInProgress: Boolean = false

    // 잠금화면 해제 시 페이드 애니메이션 사용 플래그
    private var pendingFadeShow: Boolean = false

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
            }

            createNavBar()
            createHotspot()

            val params = createLayoutParams()
            windowManager.addView(rootView, params)

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
    }

    fun destroy() {
        if (!isCreated) return

        try {
            cancelPendingTasks()
            gestureHandler.cleanup()
            backgroundManager.cleanup()
            buttonManager.clear()

            windowManager.removeView(rootView)
            rootView = null
            backgroundView = null
            navBarView = null
            gestureOverlayView = null
            hotspotView = null

            isCreated = false
            Log.i(TAG, "Overlay destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy overlay", e)
        }
    }

    private fun cancelPendingTasks() {
        pendingHomeState?.let { handler.removeCallbacks(it) }
        pendingHomeState = null
        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
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
            clipChildren = false
        }

        // Left group: Back / Home / Recents
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        leftGroup.addView(
            buttonManager.createNavButton(NavAction.BACK, R.drawable.ic_sysbar_back, buttonSizePx, initialButtonColor)
        )
        buttonManager.addSpacerToGroup(leftGroup, buttonSpacingPx)
        leftGroup.addView(
            buttonManager.createNavButton(NavAction.HOME, R.drawable.ic_sysbar_home, buttonSizePx, initialButtonColor)
        )
        buttonManager.addSpacerToGroup(leftGroup, buttonSpacingPx)
        leftGroup.addView(
            buttonManager.createNavButton(NavAction.RECENTS, R.drawable.ic_sysbar_recent, buttonSizePx, initialButtonColor)
        )

        val leftParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        bar.addView(leftGroup, leftParams)

        // Right group: Screenshot / Notifications
        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        rightGroup.addView(
            buttonManager.createNavButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture, buttonSizePx, initialButtonColor)
        )
        buttonManager.addSpacerToGroup(rightGroup, buttonSpacingPx)
        rightGroup.addView(
            buttonManager.createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx, initialButtonColor)
        )

        val rightParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        bar.addView(rightGroup, rightParams)

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
        val isPortrait = currentOrientation == Configuration.ORIENTATION_PORTRAIT
        val resName = if (isPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
        val id = res.getIdentifier(resName, "dimen", "android")
        val h = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (h > 0) h else context.dpToPx(Constants.Dimension.NAV_BUTTON_SIZE_DP)
    }

    // ===== 표시/숨김 =====

    fun show(fade: Boolean = false, fromGesture: Boolean = false) {
        if (fromGesture) {
            gestureHandler.markGestureShow()
            showGestureOverlay()
        }

        // 잠금화면 해제 시 pendingFadeShow가 설정되어 있으면 페이드 사용
        val shouldFade = fade || pendingFadeShow
        if (pendingFadeShow) {
            pendingFadeShow = false
            Log.d(TAG, "Using pending fade animation (from lock screen unlock)")
        }

        // 숨겨진 상태에서 다시 보일 때 방향 및 배경 동기화 (전체화면 후 복귀 시 필수)
        val wasHidden = !isShowing
        if (wasHidden) {
            syncOrientationAndBackground()
        }

        updateWindowHeight(getSystemNavigationBarHeightPx())

        if (isShowing) {
            if (shouldFade) {
                backgroundView?.visibility = View.GONE
                navBarView?.let { bar ->
                    bar.clearAnimation()
                    // ObjectAnimator로 실제 alpha 속성 애니메이션
                    bar.alpha = 0f
                    ObjectAnimator.ofFloat(bar, "alpha", 0f, 1f).apply {
                        duration = Constants.Timing.ANIMATION_DURATION_MS
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                backgroundView?.visibility = View.VISIBLE
                            }
                        })
                        start()
                    }
                }
            }
            return
        }

        if (shouldFade) {
            backgroundView?.visibility = View.GONE
        } else {
            backgroundView?.visibility = View.VISIBLE
        }

        navBarView?.let { bar ->
            bar.clearAnimation()

            if (shouldFade) {
                // ObjectAnimator로 실제 alpha 속성 애니메이션
                bar.alpha = 0f
                bar.visibility = View.VISIBLE
                ObjectAnimator.ofFloat(bar, "alpha", 0f, 1f).apply {
                    duration = Constants.Timing.ANIMATION_DURATION_MS
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            backgroundView?.visibility = View.VISIBLE
                        }
                    })
                    start()
                }
            } else {
                bar.visibility = View.VISIBLE
                val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                    duration = Constants.Timing.ANIMATION_DURATION_MS
                }
                bar.startAnimation(slideUp)
            }

            hotspotView?.visibility = View.GONE
            isShowing = true
            Log.d(TAG, "Overlay shown (fade=$shouldFade, fromGesture=$fromGesture)")
        }
    }

    fun hide(animate: Boolean = true, showHotspot: Boolean = true) {
        if (!isShowing) return

        // 숨길 때 pendingFadeShow 초기화
        pendingFadeShow = false

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()

            val shouldShowHotspot = showHotspot && settings.hotspotEnabled

            if (!animate) {
                bar.visibility = View.GONE
                backgroundView?.visibility = View.GONE
                if (shouldShowHotspot) {
                    updateWindowHeight(context.dpToPx(settings.hotspotHeight))
                } else {
                    // 핫스팟 없이 숨길 때는 윈도우 높이를 0으로 설정하여 터치 차단 방지
                    updateWindowHeight(0)
                }
                hideAnimationInProgress = false
            } else {
                // 숨김 애니메이션 시작 표시
                hideAnimationInProgress = true
                val slideDown = TranslateAnimation(0f, 0f, 0f, bar.height.toFloat()).apply {
                    duration = Constants.Timing.ANIMATION_DURATION_MS
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            hideAnimationInProgress = false
                            bar.visibility = View.GONE
                            backgroundView?.visibility = View.GONE
                            if (shouldShowHotspot) {
                                updateWindowHeight(context.dpToPx(settings.hotspotHeight))
                            } else {
                                // 핫스팟 없이 숨길 때는 윈도우 높이를 0으로 설정하여 터치 차단 방지
                                updateWindowHeight(0)
                            }
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                bar.startAnimation(slideDown)
            }

            hotspotView?.visibility = if (shouldShowHotspot) View.VISIBLE else View.GONE
            isShowing = false
            hideGestureOverlay()
            Log.d(TAG, "Overlay hidden (animate=$animate, showHotspot=$showHotspot)")
        }
    }

    private fun updateWindowHeight(heightPx: Int) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            params.height = heightPx
            windowManager.updateViewLayout(rootView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window height", e)
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
            if (isOnHomeScreen) return
            isOnHomeScreen = true
            Log.d(TAG, "Home screen state: true")

            // 진행 중인 애니메이션 취소 및 상태 복원
            cancelAnimationsAndRestoreState()

            // 홈 화면 복귀 시 방향 동기화 (전체화면 앱 종료 후 필수)
            syncOrientationAndBackground()
            return
        }

        if (!isOnHomeScreen) return
        pendingHomeState?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingHomeState = null
            if (!isOnHomeScreen) return@Runnable
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state: false (debounced)")
            updateNavBarBackground()
        }
        pendingHomeState = task
        handler.postDelayed(task, Constants.Timing.HOME_STATE_DEBOUNCE_MS)
    }

    /**
     * 진행 중인 애니메이션 취소 및 뷰 상태 복원
     * 페이드 애니메이션 중간에 홈 화면 복귀 시 어중간한 상태 방지
     *
     * 숨김 애니메이션 진행 중이면: 취소 후 표시 상태로 복원
     * 의도적으로 숨겨진 상태면 (잠금화면 등): 취소만 하고 숨김 유지
     */
    private fun cancelAnimationsAndRestoreState() {
        // 애니메이션 취소
        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()
        }

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
            backgroundView?.visibility = View.VISIBLE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())
            isShowing = true
            Log.d(TAG, "Animations cancelled and state restored to showing")
        } else {
            Log.d(TAG, "Animations cancelled, keeping hidden state (for lock screen unlock fade)")
        }
    }

    fun setRecentsState(isRecents: Boolean) {
        if (isRecents) {
            if (isRecentsVisible) return
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null

            if (!isOnHomeScreen) {
                isRecentsVisible = true
                updateNavBarBackground()
                return
            }

            val task = Runnable {
                pendingRecentsState = null
                if (isRecentsVisible) return@Runnable
                isRecentsVisible = true
                updateNavBarBackground()
            }
            pendingRecentsState = task
            handler.postDelayed(task, Constants.Timing.RECENTS_STATE_DEBOUNCE_MS)
            return
        }

        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
        if (!isRecentsVisible) return
        isRecentsVisible = false
        updateNavBarBackground()
    }

    fun setImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        buttonManager.updateBackButtonRotation(visible, animate = true)
    }

    fun updatePanelButtonState(isOpen: Boolean) {
        isPanelOpenUi = isOpen
        buttonManager.updatePanelButtonState(isOpen)
        buttonManager.updatePanelButtonDescription(
            isOpen,
            context.getString(R.string.notification_panel_open),
            context.getString(R.string.notification_panel_close)
        )
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
        }

        // 비트맵이 없는 경우에만 로드 (이미 로드되어 있으면 스킵)
        backgroundManager.loadBackgroundBitmaps(forceReload = false)
        updateNavBarBackground()
    }

    // ===== 다크 모드 =====

    fun updateDarkMode() {
        checkDarkModeChange()
        // 다크 모드 변경 시 배경 업데이트
        updateNavBarBackground()
    }

    // ===== 배경 업데이트 =====

    /**
     * 방향 및 배경 강제 동기화
     * 전체화면 모드 복귀 시 또는 홈 화면 복귀 시 호출
     */
    private fun syncOrientationAndBackground() {
        val actualOrientation = context.resources.configuration.orientation
        val actualOrientationName = if (actualOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val cachedOrientationName = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

        Log.d(TAG, "syncOrientationAndBackground: cached=$cachedOrientationName, actual=$actualOrientationName")

        // 항상 실제 시스템 방향으로 동기화
        if (currentOrientation != actualOrientation) {
            Log.d(TAG, "Orientation mismatch detected, forcing sync")
            currentOrientation = actualOrientation
            backgroundManager.forceOrientationSync(actualOrientation)
        }

        // 비트맵이 없는 경우에만 로드 (불필요한 재로드 방지)
        backgroundManager.loadBackgroundBitmaps(forceReload = false)
        updateNavBarBackground()
    }

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return

        // 방향 동기화 확인 - NavBarOverlay의 currentOrientation도 함께 동기화
        if (backgroundManager.syncOrientationWithSystem()) {
            val actualOrientation = context.resources.configuration.orientation
            if (currentOrientation != actualOrientation) {
                Log.d(TAG, "Syncing NavBarOverlay orientation: $currentOrientation -> $actualOrientation")
                currentOrientation = actualOrientation
            }
        }

        val shouldUseCustom = backgroundManager.shouldUseCustomBackground(isOnHomeScreen, isRecentsVisible)
        backgroundManager.applyBackground(bar, shouldUseCustom)

        // backgroundView도 동기화
        if (!shouldUseCustom) {
            backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())
        }
    }

    // ===== 버튼 액션 처리 =====

    private fun handleButtonClick(action: NavAction) {
        if (action == NavAction.NOTIFICATIONS) {
            val nextOpen = !isPanelOpenUi
            if (nextOpen) {
                service.executeAction(NavAction.NOTIFICATIONS)
            } else {
                service.executeAction(NavAction.DISMISS_NOTIFICATION_SHADE)
            }
            updatePanelButtonState(isOpen = nextOpen)
        } else {
            service.executeAction(action)
        }
    }

    private fun handleButtonLongClick(action: NavAction): Boolean {
        return if (action == NavAction.HOME) {
            service.executeAction(NavAction.ASSIST)
            true
        } else {
            false
        }
    }

    // ===== 설정 새로고침 =====

    fun refreshSettings() {
        if (!isCreated) return

        // 설정 새로고침 시 강제 리로드
        backgroundManager.loadBackgroundBitmaps(forceReload = true)

        rootView?.let { root ->
            root.removeAllViews()
            buttonManager.clear()
            createNavBar()
            createHotspot()

            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
        }

        updateNavBarBackground()
        buttonManager.updatePanelButtonState(isPanelOpenUi)
    }

    fun reloadBackgroundImages() {
        // 배경 이미지 변경 시 강제 리로드
        backgroundManager.loadBackgroundBitmaps(forceReload = true)
        updateNavBarBackground()
    }

    // ===== 유틸리티 =====

    /**
     * 잠금화면 해제 시 페이드 애니메이션 준비
     * 화면 켜질 때 잠금화면이 활성화된 경우 호출되어 다음 show()에서 페이드 사용
     */
    fun prepareForUnlockFade() {
        pendingFadeShow = true
        Log.d(TAG, "Prepared for unlock fade animation")
    }
    fun markNextShowInstant() { /* no-op */ }
}
