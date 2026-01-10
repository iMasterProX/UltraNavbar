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
 * - BackgroundManager: 배경 이미지/색상 관리 (하드웨어 가속 페이드)
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
    private var isAllAppsVisible = false
    private var isImeVisible = false
    private var isPanelOpenUi = false
    private var isNotificationPanelOpen = false

    // 다크 모드 전환 추적
    private var darkModeTransitionTime: Long = 0

    // 디바운스용 Runnable
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null

    // 숨김 애니메이션 진행 중 추적
    private var hideAnimationInProgress: Boolean = false

    // 잠금화면 해제 시 페이드 애니메이션 사용 플래그
    private var pendingFadeShow: Boolean = false
    private var unlockFadePrepared: Boolean = false
    private var isLockScreenActive: Boolean = false
    private var isCustomBackgroundActive: Boolean = false
    private var unlockFadeRequested: Boolean = false
    private var unlockFadeInProgress: Boolean = false
    private var unlockFadeAnimator: ObjectAnimator? = null
    private var unlockFadeSuppressionUntil: Long = 0

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
            resetUnlockFadeState()
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

    // ===== 표시/숨김 (하드웨어 가속 페이드) =====

    fun show(fade: Boolean = false, fromGesture: Boolean = false) {
        if (fromGesture) {
            gestureHandler.markGestureShow()
            showGestureOverlay()
        }

        rootView?.visibility = View.VISIBLE
        setWindowTouchable(true)

        val shouldFade = fade || pendingFadeShow || unlockFadePrepared
        val wasPreparedForFade = pendingFadeShow || unlockFadePrepared

        if (pendingFadeShow) {
            pendingFadeShow = false
            Log.d(TAG, "Using pending fade animation (from lock screen unlock)")
        }

        val wasHidden = !isShowing
        if (wasHidden && !wasPreparedForFade) {
            syncOrientationAndBackground()
        }

        // 이미 표시 중인 경우
        if (isShowing) {
            updateWindowHeight(getSystemNavigationBarHeightPx())
            val currentAlpha = navBarView?.alpha ?: 1f
            val shouldAnimateFade = shouldFade && currentAlpha < 1f
            val suppressForUnlockFade = unlockFadeRequested || wasPreparedForFade || unlockFadeInProgress

            if (suppressForUnlockFade) {
                applyUnlockCustomBackgroundIfAvailable()
                beginUnlockFadeSuppression()

                if (!shouldAnimateFade) {
                    if (unlockFadePrepared) {
                        unlockFadePrepared = false
                    }
                    unlockFadeInProgress = false
                    maybeFinishUnlockFadeSuppression(force = true)
                }
            } else {
                showBackgroundLayer()
            }

            if (!shouldAnimateFade) {
                return
            }

            // 알파값만 변경하는 페이드 인
            navBarView?.let { bar ->
                bar.clearAnimation()
                enableHardwareAccelForNavBar()
                if (suppressForUnlockFade) {
                    startUnlockFadeAnimation(bar, bar.alpha)
                } else {
                    ObjectAnimator.ofFloat(bar, "alpha", bar.alpha, 1f).apply {
                        duration = Constants.Timing.ANIMATION_DURATION_MS
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                disableHardwareAccelForNavBar()
                            }
                        })
                        start()
                    }
                }
            }
            return
        }

        // 숨겨진 상태에서 표시
        navBarView?.let { bar ->
            bar.clearAnimation()
            if (shouldFade) {
                if (!wasPreparedForFade) {
                    val suppressForUnlockFade = unlockFadeRequested || wasPreparedForFade || unlockFadeInProgress
                    bar.alpha = 0f
                    bar.visibility = View.VISIBLE

                    if (suppressForUnlockFade) {
                        applyUnlockCustomBackgroundIfAvailable()
                        beginUnlockFadeSuppression()
                    } else {
                        showBackgroundLayer()
                    }

                    hotspotView?.visibility = View.GONE
                    updateWindowHeight(getSystemNavigationBarHeightPx())

                    enableHardwareAccelForNavBar()
                    if (suppressForUnlockFade) {
                        startUnlockFadeAnimation(bar, 0f)
                    } else {
                        ObjectAnimator.ofFloat(bar, "alpha", 0f, 1f).apply {
                            duration = Constants.Timing.ANIMATION_DURATION_MS
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    disableHardwareAccelForNavBar()
                                }
                            })
                            start()
                        }
                    }
                } else {
                    applyUnlockCustomBackgroundIfAvailable()
                    beginUnlockFadeSuppression()
                    enableHardwareAccelForNavBar()
                    startUnlockFadeAnimation(bar, 0f)
                }
            } else {
                updateWindowHeight(getSystemNavigationBarHeightPx())
                maybeFinishUnlockFadeSuppression()
                bar.alpha = 1f
                bar.visibility = View.VISIBLE
                hotspotView?.visibility = View.GONE

                val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                    duration = Constants.Timing.ANIMATION_DURATION_MS
                }
                bar.startAnimation(slideUp)
            }
        }

        isShowing = true
        Log.d(TAG, "Overlay shown (fade=$shouldFade, fromGesture=$fromGesture, prepared=$wasPreparedForFade)")
    }

    fun hide(animate: Boolean = true, showHotspot: Boolean = true) {
        val shouldShowHotspot = showHotspot && settings.hotspotEnabled

        if (!showHotspot && isLockScreenActive) {
            navBarView?.visibility = View.GONE
            backgroundView?.visibility = View.GONE
            gestureOverlayView?.visibility = View.GONE
            hotspotView?.visibility = View.GONE
            rootView?.visibility = View.GONE
            setWindowTouchable(false)
            updateWindowHeight(getSystemNavigationBarHeightPx())
            hideAnimationInProgress = false
            isShowing = false
            resetUnlockFadeState()
            hideGestureOverlay()
            Log.d(TAG, "Hidden for lock screen (window height kept)")
            return
        }

        if (!showHotspot && !isShowing) {
            navBarView?.visibility = View.GONE
            backgroundView?.visibility = View.GONE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(0)
            Log.d(TAG, "Force hiding for disabled app (window height = 0)")
            return
        }

        if (!isShowing) return

        pendingFadeShow = false
        unlockFadePrepared = false
        resetUnlockFadeState()

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()

            if (!animate) {
                bar.visibility = View.GONE
                backgroundView?.visibility = View.GONE
                if (shouldShowHotspot) {
                    updateWindowHeight(context.dpToPx(settings.hotspotHeight))
                } else {
                    updateWindowHeight(0)
                }
                hideAnimationInProgress = false
            } else {
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
                                updateWindowHeight(0)
                            }
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                bar.startAnimation(slideDown)
            }
        }

        hotspotView?.visibility = if (shouldShowHotspot) View.VISIBLE else View.GONE
        isShowing = false
        hideGestureOverlay()
        Log.d(TAG, "Overlay hidden (animate=$animate, showHotspot=$showHotspot)")
    }

    private fun setWindowTouchable(touchable: Boolean) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            val newFlags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (params.flags == newFlags) return
            params.flags = newFlags
            windowManager.updateViewLayout(rootView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window touchable flag", e)
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

    // ===== 하드웨어 가속 관리 (NavBar용) =====

    private fun enableHardwareAccelForNavBar() {
        navBarView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        Log.d(TAG, "Hardware acceleration enabled for navBar fade")
    }

    private fun disableHardwareAccelForNavBar() {
        navBarView?.setLayerType(View.LAYER_TYPE_NONE, null)
        Log.d(TAG, "Hardware acceleration disabled for navBar")
    }

    // ===== 잠금화면 해제 페이드 관리 =====

    private fun shouldUseCustomBackgroundNow(): Boolean {
        if (!settings.homeBgEnabled) return false
        if (isLockScreenActive) return true
        if (isNotificationPanelOpen) return false
        return isOnHomeScreen
    }

    private fun isUnlockFadeSuppressionActive(): Boolean {
        return unlockFadeRequested || unlockFadeInProgress || unlockFadePrepared || pendingFadeShow
    }

    private fun isUnlockFadeSuppressionForced(): Boolean {
        return unlockFadeSuppressionUntil > SystemClock.elapsedRealtime()
    }

    private fun setUnlockFadeSuppressionWindow() {
        unlockFadeSuppressionUntil = SystemClock.elapsedRealtime() +
                Constants.Timing.UNLOCK_FADE_START_DELAY_MS +
                Constants.Timing.ANIMATION_DURATION_MS + 50L
    }

    private fun beginUnlockFadeSuppression() {
        unlockFadeInProgress = true
        setUnlockFadeSuppressionWindow()
        backgroundView?.alpha = 0f
        backgroundView?.visibility = View.GONE
    }

    private fun endUnlockFadeSuppression() {
        unlockFadeRequested = false
        unlockFadeInProgress = false
        unlockFadeSuppressionUntil = 0
        showBackgroundLayer()
    }

    private fun resetUnlockFadeState() {
        unlockFadeRequested = false
        unlockFadeInProgress = false
        unlockFadePrepared = false
        pendingFadeShow = false
        unlockFadeSuppressionUntil = 0
        cancelUnlockFadeAnimation()
    }

    private fun finishUnlockFadeAnimation() {
        unlockFadePrepared = false
        unlockFadeInProgress = false
        unlockFadeAnimator = null
        disableHardwareAccelForNavBar()
        maybeFinishUnlockFadeSuppression(force = true)
    }

    private fun startUnlockFadeAnimation(bar: View, fromAlpha: Float) {
        if (unlockFadeAnimator != null) return
        setUnlockFadeSuppressionWindow()

        unlockFadeAnimator = ObjectAnimator.ofFloat(bar, "alpha", fromAlpha, 1f).apply {
            duration = Constants.Timing.ANIMATION_DURATION_MS
            startDelay = Constants.Timing.UNLOCK_FADE_START_DELAY_MS
            addListener(object : AnimatorListenerAdapter() {
                private var handled = false

                private fun handleFinish() {
                    if (handled) return
                    handled = true
                    finishUnlockFadeAnimation()
                }

                override fun onAnimationCancel(animation: Animator) {
                    handleFinish()
                }

                override fun onAnimationEnd(animation: Animator) {
                    handleFinish()
                }
            })
            start()
        }
    }

    private fun cancelUnlockFadeAnimation() {
        unlockFadeAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.cancel()
        }
        unlockFadeAnimator = null
        disableHardwareAccelForNavBar()
    }

    private fun isCustomBackgroundReady(): Boolean {
        if (!settings.homeBgEnabled) return true
        val bar = navBarView ?: return false
        val bg = bar.background as? BitmapDrawable ?: return false
        return bg.bitmap != null && bg.alpha == 255
    }

    private fun maybeFinishUnlockFadeSuppression(force: Boolean = false) {
        if (!isUnlockFadeSuppressionActive()) return
        if (unlockFadeAnimator != null || unlockFadePrepared) {
            if (!force || shouldUseCustomBackgroundNow()) return
        }

        if (!unlockFadeInProgress && !force) return

        val barAlpha = navBarView?.alpha ?: 1f
        if (barAlpha < 1f) return

        if (shouldUseCustomBackgroundNow()) {
            if (!isCustomBackgroundReady()) return
        }

        endUnlockFadeSuppression()
    }

    private fun showBackgroundLayer() {
        if (isUnlockFadeSuppressionActive() || isUnlockFadeSuppressionForced()) return
        backgroundView?.alpha = 1f
        backgroundView?.visibility = View.VISIBLE
    }

    private fun applyUnlockCustomBackgroundIfAvailable(): Boolean {
        if (!shouldUseCustomBackgroundNow()) return false
        val bar = navBarView ?: return false

        backgroundManager.forceOrientationSync(context.resources.configuration.orientation)
        backgroundManager.loadBackgroundBitmaps(forceReload = false)

        val bitmap = backgroundManager.getCurrentBitmap() ?: return false
        isCustomBackgroundActive = true
        backgroundManager.applyBackground(bar, useCustom = true, forceUpdate = true)
        bar.background?.alpha = 255

        return true
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

    fun canAutoHide(): Boolean {
        checkDarkModeChange()

        val darkModeElapsed = SystemClock.elapsedRealtime() - darkModeTransitionTime
        if (darkModeElapsed < Constants.Timing.DARK_MODE_DEBOUNCE_MS) {
            Log.d(TAG, "Auto-hide blocked: dark mode transition")
            return false
        }

        if (!gestureHandler.canAutoHide()) {
            return false
        }

        return true
    }

    private fun checkDarkModeChange() {
        if (backgroundManager.updateDarkMode()) {
            darkModeTransitionTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Dark mode change detected early: ${backgroundManager.isDarkMode}")
        }
    }

    // ===== 상태 업데이트 =====

    fun setHomeScreenState(onHome: Boolean, immediate: Boolean = false) {
        if (onHome) {
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            if (isOnHomeScreen) return

            isOnHomeScreen = true
            Log.d(TAG, "Home screen state: true")

            if (!isUnlockFadeSuppressionActive()) {
                val needsRestore = hideAnimationInProgress || !isShowing
                if (needsRestore) {
                    cancelAnimationsAndRestoreState(restoreBackground = false)
                }
            }

            if (isRecentsVisible) {
                pendingRecentsState?.let { handler.removeCallbacks(it) }
                pendingRecentsState = null
                isRecentsVisible = false
            }

            if (isAllAppsVisible) {
                isAllAppsVisible = false
            }

            syncOrientationAndBackground()
            return
        }

        if (!isOnHomeScreen) return
        pendingHomeState?.let { handler.removeCallbacks(it) }

        if (immediate) {
            pendingHomeState = null
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state: false (immediate)")
            updateNavBarBackground()
            return
        }

        val task = Runnable {
            pendingHomeState = null
            if (!isOnHomeScreen) return@Runnable
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state: false (debounced)")
            updateNavBarBackground()
        }
        pendingHomeState = task
        handler.postDelayed(task, Constants.Timing.HOME_BG_DEBOUNCE_MS)
    }

    fun setAllAppsState(isVisible: Boolean) {
        if (isAllAppsVisible == isVisible) return
        isAllAppsVisible = isVisible
        Log.d(TAG, "All apps state: $isAllAppsVisible")
        updateNavBarBackground()
    }

    fun setNotificationPanelState(isOpen: Boolean) {
        if (isNotificationPanelOpen == isOpen) return
        isNotificationPanelOpen = isOpen
        Log.d(TAG, "Notification panel state: $isNotificationPanelOpen")
        updateNavBarBackground()
    }

    private fun cancelAnimationsAndRestoreState(restoreBackground: Boolean = true) {
        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()
        }

        if (restoreBackground) {
            backgroundManager.cancelBackgroundTransition()
            if (isCustomBackgroundActive) {
                (navBarView?.background as? BitmapDrawable)?.alpha = 255
            }
        }

        val shouldRestoreToShowing = hideAnimationInProgress || isShowing
        hideAnimationInProgress = false

        if (shouldRestoreToShowing) {
            navBarView?.let { bar ->
                bar.alpha = 1f
                bar.visibility = View.VISIBLE
                bar.translationY = 0f

                if (restoreBackground) {
                    val currentBg = bar.background
                    val buttonColor = if (currentBg is BitmapDrawable && currentBg.bitmap != null) {
                        backgroundManager.calculateButtonColorForBitmap(currentBg.bitmap)
                    } else {
                        backgroundManager.getDefaultButtonColor()
                    }
                    backgroundManager.updateButtonColor(buttonColor)
                }
            }

            showBackgroundLayer()
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())
            isShowing = true
            Log.d(TAG, "Animations cancelled and state restored to showing (including button color)")
        } else {
            Log.d(TAG, "Animations cancelled, keeping hidden state (for lock screen unlock fade)")
        }
    }

    fun setRecentsState(isRecents: Boolean, immediate: Boolean = false) {
        if (isRecents) {
            if (isRecentsVisible) return
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null

            if (immediate) {
                isRecentsVisible = true
                Log.d(TAG, "Recents state: true (immediate)")
                updateNavBarBackground()
                return
            }

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

    fun setLockScreenActive(active: Boolean) {
        if (isLockScreenActive == active) return
        val wasLockScreenActive = isLockScreenActive
        isLockScreenActive = active

        if (active) {
            resetUnlockFadeState()
        } else if (wasLockScreenActive && !active) {
            unlockFadeRequested = true
            handler.post { updateNavBarBackground() }
        }
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

        backgroundManager.loadBackgroundBitmaps(forceReload = false)
        updateNavBarBackground()
    }

    // ===== 다크 모드 =====

    fun updateDarkMode() {
        checkDarkModeChange()
        updateNavBarBackground()
    }

    // ===== 배경 업데이트 =====

    private fun syncOrientationAndBackground() {
        val actualOrientation = context.resources.configuration.orientation
        val actualOrientationName = if (actualOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val cachedOrientationName = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "syncOrientationAndBackground: cached=$cachedOrientationName, actual=$actualOrientationName")

        if (currentOrientation != actualOrientation) {
            Log.d(TAG, "Orientation mismatch detected, forcing sync")
            currentOrientation = actualOrientation
            backgroundManager.forceOrientationSync(actualOrientation)
        }

        backgroundManager.loadBackgroundBitmaps(forceReload = false)
        updateNavBarBackground()
    }

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return
        val wasCustomBackgroundActive = isCustomBackgroundActive
        val currentBg = bar.background
        val isDefaultBackground = currentBg !is BitmapDrawable || currentBg.alpha <= 0

        if (isUnlockFadeSuppressionForced()) {
            backgroundView?.alpha = 0f
            backgroundView?.visibility = View.GONE
        }

        if (backgroundManager.syncOrientationWithSystem()) {
            val actualOrientation = context.resources.configuration.orientation
            if (currentOrientation != actualOrientation) {
                Log.d(TAG, "Syncing NavBarOverlay orientation: $currentOrientation -> $actualOrientation")
                currentOrientation = actualOrientation
            }
        }

        backgroundView?.setBackgroundColor(backgroundManager.getDefaultBackgroundColor())

        val isUnlockSuppressed = isUnlockFadeSuppressionActive()
        if (isUnlockSuppressed) {
            if (!shouldUseCustomBackgroundNow()) {
                isCustomBackgroundActive = false
                backgroundManager.applyBackground(bar, useCustom = false, forceUpdate = true)
                maybeFinishUnlockFadeSuppression()
                return
            }

            backgroundManager.loadBackgroundBitmaps(forceReload = false)
            val bitmap = backgroundManager.getCurrentBitmap()
            if (bitmap != null) {
                isCustomBackgroundActive = true
                backgroundManager.applyBackground(bar, useCustom = true, forceUpdate = true)
                maybeFinishUnlockFadeSuppression()
                return
            }

            isCustomBackgroundActive = false
            backgroundManager.applyBackground(bar, useCustom = false, forceUpdate = true)
            return
        }

        val shouldUseCustom = shouldUseCustomBackgroundNow()
        isCustomBackgroundActive = shouldUseCustom

        backgroundManager.applyBackground(
            bar,
            useCustom = shouldUseCustom,
            forceFadeFromCustom = wasCustomBackgroundActive && !shouldUseCustom,
            forceFadeToCustom = shouldUseCustom && (!wasCustomBackgroundActive || isDefaultBackground)
        )

        if (isShowing) showBackgroundLayer()
    }

    private fun handleButtonClick(action: NavAction) {
        if (action == NavAction.NOTIFICATIONS) {
            val nextOpen = !isPanelOpenUi
            setNotificationPanelState(nextOpen)

            if (nextOpen) {
                service.executeAction(NavAction.NOTIFICATIONS)
            } else {
                service.executeAction(NavAction.DISMISS_NOTIFICATION_SHADE)
            }
            updatePanelButtonState(isOpen = nextOpen)
        } else if (action == NavAction.RECENTS) {
            setRecentsState(true, immediate = true)
            setHomeScreenState(false, immediate = true)
            service.executeAction(action)
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
        backgroundManager.loadBackgroundBitmaps(forceReload = true)
        updateNavBarBackground()
    }

    // ===== 유틸리티 =====

    fun prepareForUnlockFade() {
        unlockFadeRequested = true
        if (isShowing) {
            return
        }

        pendingFadeShow = true
        unlockFadePrepared = true

        navBarView?.clearAnimation()
        navBarView?.animate()?.cancel()
        navBarView?.translationY = 0f

        navBarView?.let { bar ->
            val shouldUseCustom = backgroundManager.shouldUseCustomBackground(isOnHomeScreen = true, isRecentsVisible = false)
            isCustomBackgroundActive = shouldUseCustom

            backgroundManager.forceOrientationSync(context.resources.configuration.orientation)
            backgroundManager.loadBackgroundBitmaps(forceReload = false)

            val hasCustomBitmap = !shouldUseCustom || backgroundManager.getCurrentBitmap() != null
            if (hasCustomBitmap) {
                backgroundManager.applyBackground(bar, shouldUseCustom, forceUpdate = true)
            }
            bar.background?.alpha = 255
        }

        navBarView?.alpha = 0f
        navBarView?.visibility = View.VISIBLE
        backgroundView?.visibility = View.GONE
        hotspotView?.visibility = View.GONE
        updateWindowHeight(getSystemNavigationBarHeightPx())

        Log.d(TAG, "Prepared for unlock fade animation (window pre-expanded, custom background applied)")
    }

    fun markNextShowInstant() { /* no-op */ }
}
