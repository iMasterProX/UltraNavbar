package com.minsoo.ultranavbar.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
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
    private var isAppDrawerOpen = false
    private var isImeVisible = false
    private var isNotificationPanelOpen = false
    private var isQuickSettingsOpen = false
    private var isPanelButtonOpen = false

    // 다크 모드 전환 추적
    private var darkModeTransitionTime: Long = 0

    // 디바운스용 Runnable
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null
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
            currentWindowHeight = -1

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
        pendingPanelClose?.let { handler.removeCallbacks(it) }
        pendingPanelClose = null
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() >= bounds.height()) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        } else {
            val dm = context.resources.displayMetrics
            if (dm.widthPixels >= dm.heightPixels) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        }
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
                            restoreBackgroundLayerDefault()
                        }
                        backgroundView?.alpha = 1f
                        backgroundView?.visibility = if (!unlockFadeCancelled && !isUnlockPending) {
                            View.VISIBLE
                        } else {
                            View.GONE
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

    fun resetUnlockFadeState() {
        isUnlockPending = false
        isUnlockFadeRunning = false
        isUnlockFadeSuppressed = false
        restoreBackgroundLayerDefault()
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

                // 버튼 색상도 현재 배경에 맞게 복원
                // backgroundManager.updateButtonColor()를 통해 _currentButtonColor도 함께 업데이트
                val currentBg = bar.background
                val buttonColor = if (currentBg is BitmapDrawable && currentBg.bitmap != null) {
                    backgroundManager.calculateButtonColorForBitmap(currentBg.bitmap)
                } else {
                    backgroundManager.getDefaultButtonColor()
                }
                backgroundManager.updateButtonColor(buttonColor)
            }
            backgroundView?.alpha = 1f
            backgroundView?.visibility = View.VISIBLE
            hotspotView?.visibility = View.GONE
            updateWindowHeight(getSystemNavigationBarHeightPx())
            isShowing = true
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
        updateNavBarBackground()
    }

    fun setAppDrawerState(isOpen: Boolean) {
        if (isAppDrawerOpen == isOpen) return
        isAppDrawerOpen = isOpen
        Log.d(TAG, "App Drawer state changed: $isOpen")
        updateNavBarBackground()
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
        syncOrientationIfNeeded("sync")
        updateNavBarBackground()
    }

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return

        syncOrientationIfNeeded("background")

        if (isUnlockPending || isUnlockFadeRunning || isUnlockFadeSuppressed) {
            val shouldUseCustom = backgroundManager.shouldUseCustomBackground(
                isOnHomeScreen = true,
                isRecentsVisible = false,
                isAppDrawerOpen = false,
                isPanelOpen = false,
                isImeVisible = false
            )
            backgroundManager.applyBackground(
                bar,
                shouldUseCustom,
                forceUpdate = true,
                animate = false
            )
            applyUnlockBackgroundToBackLayer()
            return
        }

        val shouldUseCustom = backgroundManager.shouldUseCustomBackground(
            isOnHomeScreen = isOnHomeScreen,
            isRecentsVisible = isRecentsVisible,
            isAppDrawerOpen = isAppDrawerOpen,
            isPanelOpen = isPanelOpen(),
            isImeVisible = isImeVisible
        )
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

// ===== 버튼 액션 처리 =====

    private fun handleButtonClick(action: NavAction) {
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
            currentWindowHeight = params.height
        }

        updateNavBarBackground()
        buttonManager.updatePanelButtonState(isPanelOpen())
    }

    fun refreshBackgroundStyle() {
        if (!isCreated) return
        updateNavBarBackground()
    }

    fun reloadBackgroundImages() {
        // 배경 이미지 변경 시 강제 리로드
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
            val shouldUseCustom = backgroundManager.shouldUseCustomBackground(
                isOnHomeScreen = true,
                isRecentsVisible = false,
                isAppDrawerOpen = false,
                isPanelOpen = false,
                isImeVisible = false
            )
            backgroundManager.applyBackground(
                bar,
                shouldUseCustom,
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

        Log.d(TAG, "Prepared for unlock fade animation (window pre-expanded, custom background applied)")
    }
    fun markNextShowInstant() { /* no-op */ }
}
