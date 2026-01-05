package com.minsoo.ultranavbar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import android.os.Looper
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
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil

/**
 * 네비게이션 바 오버레이
 * 화면 하단에 고정되는 커스텀 네비게이션 바 (TYPE_ACCESSIBILITY_OVERLAY)
 */
class NavBarOverlay(private val service: NavBarAccessibilityService) {

    companion object {
        private const val TAG = "NavBarOverlay"
        private const val ANIMATION_DURATION = 200L
        private const val BG_TRANSITION_DURATION = 350  // 배경 전환 애니메이션 시간 (ms)
        private const val DEFAULT_NAV_BUTTON_DP = 48
    }

    private val context: Context = service
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settings: SettingsManager = SettingsManager.getInstance(context)

    private var rootView: FrameLayout? = null
    private var backgroundView: View? = null  // 항상 검은색 - 시스템 네비바 가림용
    private var navBarView: ViewGroup? = null
    private var gestureOverlayView: View? = null  // 스와이프 다운 감지용 투명 오버레이
    private var hotspotView: View? = null
    private var panelButton: ImageButton? = null
    private var backButton: ImageButton? = null

    private var isShowing = true
    private var isCreated = false
    private var gestureShowTime: Long = 0  // 제스처로 보여준 시간 (자동숨김 방지용)
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    private var isOnHomeScreen = false
    private var isRecentsVisible = false
    private var isImeVisible = false

    // “패널이 열림/닫힘” UI 상태(서비스 감지값으로 동기화 + 버튼 클릭 시 낙관 토글)
    private var isPanelOpenUi: Boolean = false

    private var landscapeBgBitmap: Bitmap? = null
    private var portraitBgBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null

    // 다크 모드 관련 색상
    private var isDarkMode: Boolean = false
    private var currentButtonColor: Int = Color.WHITE  // 현재 버튼 색상
    private val allButtons = mutableListOf<ImageButton>()  // 모든 버튼 참조
    private var darkModeTransitionTime: Long = 0  // 다크 모드 전환 시간 (자동숨김 방지용)

    // 핫스팟 스와이프 감지용 변수
    private var hotspotTouchStartY: Float = 0f
    private val SWIPE_THRESHOLD_DP = 30  // 스와이프로 인식하는 최소 거리 (dp)

    // 네비바 스와이프 다운 감지용 변수
    private var navBarTouchStartY: Float = 0f
    private var isGestureShown: Boolean = false  // 제스처로 보여진 상태인지
    private var gestureAutoHideRunnable: Runnable? = null  // 제스처 자동 숨김 타이머

    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isCreated) return

        try {
            currentOrientation = context.resources.configuration.orientation
            isDarkMode = isSystemDarkMode()  // 다크 모드 초기화
            currentButtonColor = getDefaultButtonColor()  // 초기 버튼 색상

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

            loadBackgroundBitmaps()
            updateNavBarBackground()

            // 초기 패널 버튼 상태(기본: 닫힘)
            updatePanelButtonState(isOpen = false)

            Log.i(TAG, "Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay", e)
        }
    }

    private fun loadBackgroundBitmaps() {
        if (!settings.homeBgEnabled) return

        // 기존 비트맵 참조 해제 후 새로 로드
        landscapeBgBitmap = null
        portraitBgBitmap = null

        landscapeBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, true)
        portraitBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, false)

        Log.d(
            TAG,
            "Background bitmaps loaded: landscape=${landscapeBgBitmap?.hashCode()}, portrait=${portraitBgBitmap?.hashCode()}"
        )
    }

    private fun getSystemNavigationBarHeightPx(): Int {
        val res = context.resources
        val isPortrait = currentOrientation == Configuration.ORIENTATION_PORTRAIT
        val resName =
            if (isPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
        val id = res.getIdentifier(resName, "dimen", "android")
        val h = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (h > 0) h else dpToPx(DEFAULT_NAV_BUTTON_DP)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createNavBar() {
        val barHeightPx = getSystemNavigationBarHeightPx()
        val buttonSizePx = dpToPx(DEFAULT_NAV_BUTTON_DP)
        val buttonSpacingPx = dpToPx(8)

        // 배경 레이어 (시스템 네비바 가리기용) - 다크 모드에 따라 색상 변경
        val defaultBgColor = getDefaultBackgroundColor()
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

        val bar = RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }

            setBackgroundColor(defaultBgColor)
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            clipChildren = false
        }

        // Left group: Back / Home / Recents
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = createNavButton(NavAction.BACK, R.drawable.ic_sysbar_back, buttonSizePx)
        leftGroup.addView(backButton)
        addSpacer(leftGroup, buttonSpacingPx)
        leftGroup.addView(createNavButton(NavAction.HOME, R.drawable.ic_sysbar_home, buttonSizePx))
        addSpacer(leftGroup, buttonSpacingPx)
        leftGroup.addView(
            createNavButton(NavAction.RECENTS, R.drawable.ic_sysbar_recent, buttonSizePx)
        )

        val leftParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        bar.addView(leftGroup, leftParams)

        // Right group: Screenshot / Notifications(panel)
        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightGroup.addView(
            createNavButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture, buttonSizePx)
        )
        addSpacer(rightGroup, buttonSpacingPx)
        panelButton = createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx)
        rightGroup.addView(panelButton)

        val rightParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        bar.addView(rightGroup, rightParams)

        navBarView = bar
        rootView?.addView(navBarView)

        // 스와이프 다운 감지용 투명 오버레이 (제스처로 보여진 경우에만 활성화)
        val swipeThresholdPx = dpToPx(SWIPE_THRESHOLD_DP)
        gestureOverlayView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE  // 기본적으로 숨김

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        navBarTouchStartY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.rawY - navBarTouchStartY
                        if (deltaY >= swipeThresholdPx) {
                            Log.d(TAG, "Gesture overlay swipe down detected, hiding overlay")
                            hideGestureOverlay()
                            hide(animate = true, showHotspot = true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val deltaY = event.rawY - navBarTouchStartY
                        // 스와이프가 아닌 탭이면 오버레이 숨기고 버튼 클릭 허용
                        if (deltaY < swipeThresholdPx) {
                            hideGestureOverlay()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        rootView?.addView(gestureOverlayView)

        // 배경 재적용(홈화면 + 옵션일 때)
        updateNavBarBackground()
        updateBackButtonRotation(animate = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHotspot() {
        val hotspotHeightPx = dpToPx(settings.hotspotHeight)
        val swipeThresholdPx = dpToPx(SWIPE_THRESHOLD_DP)

        hotspotView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                hotspotHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }

            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE

            setOnTouchListener { _, event ->
                if (settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return@setOnTouchListener false
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 터치 시작 위치 기록
                        hotspotTouchStartY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 위로 스와이프 감지 (Y좌표가 감소하면 위로 이동)
                        val deltaY = hotspotTouchStartY - event.rawY
                        if (deltaY >= swipeThresholdPx) {
                            Log.d(TAG, "Hotspot swipe up detected, showing overlay")
                            show(fade = false, fromGesture = true)
                            // 스와이프 완료 후 다음 제스처를 위해 초기화
                            hotspotTouchStartY = event.rawY
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 터치 종료 시에도 스와이프 체크
                        val deltaY = hotspotTouchStartY - event.rawY
                        if (deltaY >= swipeThresholdPx) {
                            Log.d(TAG, "Hotspot swipe up on release, showing overlay")
                            show(fade = false, fromGesture = true)
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        rootView?.addView(hotspotView)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createNavButton(
        action: NavAction,
        iconResId: Int,
        sizePx: Int
    ): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

            val rippleColor = ColorStateList.valueOf(currentButtonColor)
            val maskDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = sizePx / 2f
                setColor(currentButtonColor)
            }
            background = RippleDrawable(rippleColor, null, maskDrawable)

            elevation = dpToPx(4).toFloat()
            stateListAnimator = null

            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(iconResId)
            setColorFilter(currentButtonColor)  // 초기 버튼 색상 적용
            contentDescription = action.displayName

            // 버튼 리스트에 추가
            allButtons.add(this)

            setOnTouchListener { _, event ->
                if (settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return@setOnTouchListener true
                }
                false
            }

            setOnClickListener {
                if (action == NavAction.NOTIFICATIONS) {
                    // 현재 UI가 "열림"이면 -> 닫기(올리기)
                    // 현재 UI가 "닫힘"이면 -> 열기(내리기)
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

            setOnLongClickListener {
                if (action == NavAction.HOME) {
                    service.executeAction(NavAction.ASSIST)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun addSpacer(parent: ViewGroup, width: Int) {
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }
        parent.addView(spacer)
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val barHeightPx = getSystemNavigationBarHeightPx()

        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            width = WindowManager.LayoutParams.MATCH_PARENT
            height = barHeightPx
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }
    }

    fun show(fade: Boolean = false, fromGesture: Boolean = false) {
        if (fromGesture) {
            gestureShowTime = android.os.SystemClock.elapsedRealtime()
            showGestureOverlay()  // 스와이프 다운 감지 활성화
            scheduleGestureAutoHide()  // 4초 후 자동 숨김 예약
        }

        // 윈도우 높이를 네비바 높이로 복원 (핫스팟만 보이던 상태에서 복원)
        updateWindowHeight(getSystemNavigationBarHeightPx())

        // 이미 보이는 상태에서 fade 요청이 오면 fade 애니메이션만 적용
        if (isShowing) {
            if (fade) {
                // 잠금화면→홈화면 페이드 시에는 서브 검은배경 숨김
                backgroundView?.visibility = View.GONE
                navBarView?.let { bar ->
                    bar.clearAnimation()
                    val alphaAnim = AlphaAnimation(0f, 1f).apply {
                        duration = ANIMATION_DURATION
                        setAnimationListener(object : Animation.AnimationListener {
                            override fun onAnimationStart(animation: Animation?) {}
                            override fun onAnimationEnd(animation: Animation?) {
                                // 페이드 완료 후 서브 검은배경 다시 보이게
                                backgroundView?.visibility = View.VISIBLE
                            }
                            override fun onAnimationRepeat(animation: Animation?) {}
                        })
                    }
                    bar.startAnimation(alphaAnim)
                }
            }
            return
        }

        // fade 전환 시에는 서브 검은배경을 숨겨서 자연스럽게 보이도록
        if (fade) {
            backgroundView?.visibility = View.GONE
        } else {
            backgroundView?.visibility = View.VISIBLE
        }

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.visibility = View.VISIBLE

            if (fade) {
                bar.alpha = 0f
                bar.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .withEndAction {
                        // 페이드 완료 후 서브 검은배경 다시 보이게
                        backgroundView?.visibility = View.VISIBLE
                    }
                    .start()
            } else {
                val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                    duration = ANIMATION_DURATION
                }
                bar.startAnimation(slideUp)
            }

            hotspotView?.visibility = View.GONE
            isShowing = true
            Log.d(TAG, "Overlay shown (fade=$fade, fromGesture=$fromGesture)")
        }
    }

    /**
     * 자동 숨김 가능 여부 - 3초 후 자동 숨김 허용
     * 다크 모드 전환 중에도 자동 숨김 차단 (1초)
     */
    fun canAutoHide(): Boolean {
        // 다크 모드 전환 중에는 자동 숨김 차단 (시스템 UI 일시적 변화로 인한 오동작 방지)
        val darkModeElapsed = android.os.SystemClock.elapsedRealtime() - darkModeTransitionTime
        if (darkModeElapsed < 1000) {
            Log.d(TAG, "Auto-hide blocked: dark mode transition in progress")
            return false
        }

        if (!isGestureShown) return true
        val elapsed = android.os.SystemClock.elapsedRealtime() - gestureShowTime
        return elapsed > 3000  // 3초
    }

    fun hide(animate: Boolean = true, showHotspot: Boolean = true) {
        if (!isShowing) return

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()

            val shouldShowHotspot = showHotspot && settings.hotspotEnabled

            if (!animate) {
                bar.visibility = View.GONE
                backgroundView?.visibility = View.GONE
                // 윈도우 크기를 핫스팟 영역만 차지하도록 축소 (터치 통과를 위해)
                if (shouldShowHotspot) {
                    updateWindowHeight(dpToPx(settings.hotspotHeight))
                }
            } else {
                val slideDown = TranslateAnimation(0f, 0f, 0f, bar.height.toFloat()).apply {
                    duration = ANIMATION_DURATION
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            bar.visibility = View.GONE
                            backgroundView?.visibility = View.GONE
                            // 애니메이션 완료 후 윈도우 크기 축소
                            if (shouldShowHotspot) {
                                updateWindowHeight(dpToPx(settings.hotspotHeight))
                            }
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                bar.startAnimation(slideDown)
            }

            hotspotView?.visibility = if (shouldShowHotspot) View.VISIBLE else View.GONE

            isShowing = false
            hideGestureOverlay()  // 제스처 오버레이 숨김 및 상태 리셋
            Log.d(TAG, "Overlay hidden (animate=$animate)")
        }
    }

    private fun updateWindowHeight(heightPx: Int) {
        try {
            val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
            params.height = heightPx
            windowManager.updateViewLayout(rootView, params)
            Log.d(TAG, "Window height updated to $heightPx")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window height", e)
        }
    }

    fun refreshSettings() {
        if (!isCreated) return

        loadBackgroundBitmaps()

        rootView?.let { root ->
            root.removeAllViews()
            createNavBar()
            createHotspot()

            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
        }

        updateNavBarBackground()
        updatePanelButtonState(isOpen = isPanelOpenUi)
    }

    fun handleOrientationChange(newOrientation: Int) {
        if (currentOrientation == newOrientation) return
        currentOrientation = newOrientation

        val orientationName = if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        Log.d(TAG, "Orientation changed to: $orientationName")

        // 높이가 바뀔 수 있으므로 레이아웃 파라미터 갱신
        rootView?.let { root ->
            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
        }

        // 배경 이미지 다시 로드 (회전 시 올바른 이미지 보장)
        loadBackgroundBitmaps()
        updateNavBarBackground()
    }

    fun destroy() {
        if (!isCreated) return

        try {
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null
            cancelGestureAutoHide()  // 자동 숨김 타이머 정리
            windowManager.removeView(rootView)
            rootView = null
            backgroundView = null
            navBarView = null
            gestureOverlayView = null
            hotspotView = null
            panelButton = null
            backButton = null
            allButtons.clear()  // 버튼 리스트 정리
            isCreated = false
            Log.i(TAG, "Overlay destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy overlay", e)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun setHomeScreenState(onHome: Boolean) {
        if (onHome) {
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            if (isOnHomeScreen) return
            isOnHomeScreen = true
            Log.d(TAG, "Home screen state set: true")
            updateNavBarBackground()
            return
        }

        if (!isOnHomeScreen) return
        pendingHomeState?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingHomeState = null
            if (!isOnHomeScreen) return@Runnable
            isOnHomeScreen = false
            Log.d(TAG, "Home screen state set: false (debounced)")
            updateNavBarBackground()
        }
        pendingHomeState = task
        handler.postDelayed(task, 200)
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
            handler.postDelayed(task, 200)
            return
        }

        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
        if (!isRecentsVisible) return
        isRecentsVisible = false
        updateNavBarBackground()
    }

    /**
     * 알림 패널 버튼 상태 업데이트
     * - 서비스가 감지한 "실제 패널 상태"로 이 메서드를 호출해 UI를 맞추는 용도
     */
    fun updatePanelButtonState(isOpen: Boolean) {
        isPanelOpenUi = isOpen

        val rotation = if (isOpen) 180f else 0f
        panelButton?.animate()
            ?.rotation(rotation)
            ?.setDuration(ANIMATION_DURATION)
            ?.start()

        panelButton?.contentDescription =
            if (isOpen) {
                context.getString(R.string.notification_panel_close)
            } else {
                context.getString(R.string.notification_panel_open)
            }
    }

    fun setImeVisible(visible: Boolean) {
        if (isImeVisible == visible) return
        isImeVisible = visible
        updateBackButtonRotation(animate = true)
    }

    private fun updateBackButtonRotation(animate: Boolean) {
        val targetRotation = if (isImeVisible) -90f else 0f
        backButton?.let { button ->
            button.animate().cancel()
            if (animate) {
                button.animate()
                    .rotation(targetRotation)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            } else {
                button.rotation = targetRotation
            }
        }
    }

    // Android 12 표준 애니메이션 인터폴레이터 (STANDARD_DECELERATE)
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var bgAnimator: ValueAnimator? = null

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return
        val currentBg = bar.background
        val defaultBgColor = getDefaultBackgroundColor()

        val shouldUseImageBackground =
            isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible

        if (shouldUseImageBackground) {
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            val targetBitmap = if (isLandscape) landscapeBgBitmap else portraitBgBitmap

            Log.d(TAG, "updateNavBarBackground: orientation=$currentOrientation, isLandscape=$isLandscape, " +
                    "targetHash=${targetBitmap?.hashCode()}, landscapeHash=${landscapeBgBitmap?.hashCode()}, portraitHash=${portraitBgBitmap?.hashCode()}")

            if (targetBitmap != null) {
                val currentBitmap = (currentBg as? BitmapDrawable)?.bitmap
                if (currentBitmap !== targetBitmap) {
                    Log.d(TAG, "Applying new background image. isLandscape=$isLandscape, currentHash=${currentBitmap?.hashCode()}, targetHash=${targetBitmap.hashCode()}")

                    val bgDrawable = BitmapDrawable(context.resources, targetBitmap).apply {
                        gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                    }

                    // 커스텀 배경일 때는 이미지 밝기에 따라 버튼 색상 결정 (다크모드 무관)
                    val buttonColor = calculateButtonColorForBitmap(targetBitmap)
                    updateAllButtonColors(buttonColor)

                    // 배경에서 이미지 배경으로 전환: 페이드 인
                    val needsFade = currentBg is ColorDrawable || currentBg?.alpha == 0
                    if (needsFade) {
                        bgDrawable.alpha = 0
                        bar.background = bgDrawable
                        animateBackgroundAlpha(bgDrawable, 0, 255)
                    } else {
                        bar.background = bgDrawable
                    }
                }
            } else {
                if ((currentBg as? ColorDrawable)?.color != defaultBgColor) {
                    Log.d(TAG, "Fallback to default background (pre-cropped image not loaded).")
                    bar.background = ColorDrawable(defaultBgColor)
                }
                updateAllButtonColors(getDefaultButtonColor())
            }
        } else {
            // 기본 배경: 다크 모드에 따라 흰색/검은색 배경 + 회색/흰색 버튼
            updateAllButtonColors(getDefaultButtonColor())

            val isCurrentlyImage = currentBg is BitmapDrawable && currentBg.alpha > 0
            if (isCurrentlyImage) {
                Log.d(TAG, "Applying default background for app/recents view. DarkMode: $isDarkMode")
                // 이미지 배경에서 기본 배경으로: 페이드 아웃 후 기본 배경으로 설정
                animateBackgroundAlpha(currentBg as BitmapDrawable, 255, 0) {
                    bar.background = ColorDrawable(defaultBgColor)
                    backgroundView?.setBackgroundColor(defaultBgColor)
                }
            } else if ((currentBg as? ColorDrawable)?.color != defaultBgColor) {
                bar.background = ColorDrawable(defaultBgColor)
                backgroundView?.setBackgroundColor(defaultBgColor)
            }
        }
    }

    private fun animateBackgroundAlpha(
        drawable: BitmapDrawable,
        fromAlpha: Int,
        toAlpha: Int,
        onEnd: (() -> Unit)? = null
    ) {
        bgAnimator?.cancel()
        bgAnimator = ValueAnimator.ofInt(fromAlpha, toAlpha).apply {
            duration = BG_TRANSITION_DURATION.toLong()
            interpolator = android12Interpolator
            addUpdateListener { animation ->
                drawable.alpha = animation.animatedValue as Int
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                })
            }
            start()
        }
    }

    /**
     * 제스처 오버레이 표시 (스와이프 다운 감지 활성화)
     */
    private fun showGestureOverlay() {
        gestureOverlayView?.visibility = View.VISIBLE
        isGestureShown = true
    }

    /**
     * 제스처 오버레이 숨김
     */
    private fun hideGestureOverlay() {
        gestureOverlayView?.visibility = View.GONE
        isGestureShown = false
        cancelGestureAutoHide()  // 자동 숨김 타이머 취소
    }

    /**
     * 제스처로 표시된 네비바 3초 후 자동 숨김 예약
     */
    private fun scheduleGestureAutoHide() {
        cancelGestureAutoHide()  // 기존 타이머 취소
        gestureAutoHideRunnable = Runnable {
            if (isShowing && isGestureShown) {
                Log.d(TAG, "Gesture auto-hide triggered after 3 seconds")
                hide(animate = true, showHotspot = true)
            }
        }
        handler.postDelayed(gestureAutoHideRunnable!!, 3000)  // 3초 후 자동 숨김
    }

    /**
     * 제스처 자동 숨김 타이머 취소
     */
    private fun cancelGestureAutoHide() {
        gestureAutoHideRunnable?.let { handler.removeCallbacks(it) }
        gestureAutoHideRunnable = null
    }

    /**
     * 전체화면 상태 설정 (stub - canAutoHide()로 처리)
     */
    fun setFullscreenState(fullscreen: Boolean) {
        // no-op: canAutoHide()가 4초 후 자동 숨김을 허용함
    }

    fun prepareForUnlockHomeInstant() { /* no-op */ }
    fun markNextShowInstant() { /* no-op */ }

    fun reloadBackgroundImages() {
        loadBackgroundBitmaps()
        updateNavBarBackground()
    }

    /**
     * 시스템 다크 모드 여부 확인
     */
    private fun isSystemDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 다크 모드에 따른 기본 배경 색상
     */
    private fun getDefaultBackgroundColor(): Int {
        return if (isDarkMode) Color.BLACK else Color.WHITE
    }

    /**
     * 다크 모드에 따른 기본 버튼 색상 (앱/잠금화면 등 검은/흰 배경용)
     */
    private fun getDefaultButtonColor(): Int {
        return if (isDarkMode) Color.WHITE else Color.DKGRAY
    }

    /**
     * 비트맵의 평균 밝기를 계산하여 버튼 색상 결정 (Android 12 스타일)
     * 밝은 이미지 -> 검은 버튼, 어두운 이미지 -> 흰 버튼
     */
    private fun calculateButtonColorForBitmap(bitmap: Bitmap): Int {
        // 성능을 위해 작은 샘플 영역만 분석
        val sampleSize = 10
        val width = bitmap.width
        val height = bitmap.height

        var totalLuminance = 0.0
        var sampleCount = 0

        val stepX = maxOf(1, width / sampleSize)
        val stepY = maxOf(1, height / sampleSize)

        var x = 0
        while (x < width) {
            var y = 0
            while (y < height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // 상대 휘도 공식 (ITU-R BT.709)
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                totalLuminance += luminance
                sampleCount++
                y += stepY
            }
            x += stepX
        }

        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else 128.0

        // 밝기 임계값 128 (0-255 범위의 중간값)
        return if (avgLuminance > 128) Color.BLACK else Color.WHITE
    }

    /**
     * 모든 버튼의 아이콘 색상 업데이트
     */
    private fun updateAllButtonColors(color: Int) {
        if (currentButtonColor == color) return
        currentButtonColor = color

        allButtons.forEach { button ->
            button.setColorFilter(color)
        }
        Log.d(TAG, "Button colors updated to ${if (color == Color.WHITE) "WHITE" else if (color == Color.BLACK) "BLACK" else "GRAY"}")
    }

    /**
     * 다크 모드 변경 시 호출
     */
    fun updateDarkMode() {
        val newDarkMode = isSystemDarkMode()
        if (isDarkMode != newDarkMode) {
            isDarkMode = newDarkMode
            darkModeTransitionTime = android.os.SystemClock.elapsedRealtime()  // 전환 시간 기록
            Log.d(TAG, "Dark mode changed: $isDarkMode")

            // 배경 및 버튼 색상 업데이트
            updateNavBarBackground()
        }
    }
}
