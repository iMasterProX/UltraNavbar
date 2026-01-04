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
import android.graphics.drawable.TransitionDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
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
    private var navBarView: ViewGroup? = null
    private var hotspotView: View? = null
    private var panelButton: ImageButton? = null
    private var backButton: ImageButton? = null

    private var isShowing = true
    private var isCreated = false
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

    // 핫스팟 스와이프 감지용 변수
    private var hotspotTouchStartY: Float = 0f
    private val SWIPE_THRESHOLD_DP = 30  // 스와이프로 인식하는 최소 거리 (dp)

    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isCreated) return

        try {
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

        landscapeBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, true)
        portraitBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, false)

        Log.d(
            TAG,
            "Background bitmaps loaded: landscape=${landscapeBgBitmap != null}, portrait=${portraitBgBitmap != null}"
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

        val bar = RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }

            setBackgroundColor(Color.BLACK)
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
                            show(fade = false)
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
                            show(fade = false)
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

            val rippleColor = ColorStateList.valueOf(Color.WHITE)
            val maskDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = sizePx / 2f
                setColor(Color.WHITE)
            }
            background = RippleDrawable(rippleColor, null, maskDrawable)

            elevation = dpToPx(4).toFloat()
            stateListAnimator = null

            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(iconResId)
            contentDescription = action.displayName

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

    fun show(fade: Boolean = false) {
        if (isShowing) return

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.visibility = View.VISIBLE

            if (fade) {
                val alphaAnim = AlphaAnimation(0f, 1f).apply {
                    duration = ANIMATION_DURATION
                }
                bar.startAnimation(alphaAnim)
            } else {
                val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                    duration = ANIMATION_DURATION
                }
                bar.startAnimation(slideUp)
            }

            hotspotView?.visibility = View.GONE
            isShowing = true
            Log.d(TAG, "Overlay shown (fade=$fade)")
        }
    }

    fun hide(animate: Boolean = true, showHotspot: Boolean = true) {
        if (!isShowing) return

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()

            if (!animate) {
                bar.visibility = View.GONE
            } else {
                val slideDown = TranslateAnimation(0f, 0f, 0f, bar.height.toFloat()).apply {
                    duration = ANIMATION_DURATION
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            bar.visibility = View.GONE
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                bar.startAnimation(slideDown)
            }

            hotspotView?.visibility =
                if (showHotspot && settings.hotspotEnabled) View.VISIBLE else View.GONE

            isShowing = false
            Log.d(TAG, "Overlay hidden (animate=$animate)")
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

        Log.d(
            TAG,
            "Orientation changed to: ${
                if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
            }"
        )

        // 높이가 바뀔 수 있으므로 레이아웃 파라미터 갱신
        rootView?.let { root ->
            val params = createLayoutParams()
            windowManager.updateViewLayout(root, params)
        }

        updateNavBarBackground()
    }

    fun destroy() {
        if (!isCreated) return

        try {
            pendingHomeState?.let { handler.removeCallbacks(it) }
            pendingHomeState = null
            pendingRecentsState?.let { handler.removeCallbacks(it) }
            pendingRecentsState = null
            windowManager.removeView(rootView)
            rootView = null
            navBarView = null
            hotspotView = null
            panelButton = null
            backButton = null
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

    private fun updateNavBarBackground() {
        val bar = navBarView ?: return
        val currentBg = bar.background

        val shouldUseImageBackground =
            isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible

        if (shouldUseImageBackground) {
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            val targetBitmap = if (isLandscape) landscapeBgBitmap else portraitBgBitmap

            if (targetBitmap != null) {
                val currentBitmap = (currentBg as? BitmapDrawable)?.bitmap
                if (currentBitmap !== targetBitmap) {
                    Log.d(TAG, "Applying new pre-cropped background image. Landscape: $isLandscape")

                    val bgDrawable = BitmapDrawable(context.resources, targetBitmap).apply {
                        gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                    }

                    // 검은 배경에서 이미지 배경으로 전환 시 페이드 애니메이션 적용 (Android 12 스타일)
                    if (currentBg is ColorDrawable || currentBg is TransitionDrawable) {
                        val fromDrawable = if (currentBg is TransitionDrawable) {
                            currentBg.getDrawable(currentBg.numberOfLayers - 1)
                        } else {
                            currentBg
                        }
                        val transition = TransitionDrawable(arrayOf(fromDrawable, bgDrawable)).apply {
                            isCrossFadeEnabled = true  // 자연스러운 크로스페이드
                        }
                        bar.background = transition
                        transition.startTransition(BG_TRANSITION_DURATION)
                    } else {
                        bar.background = bgDrawable
                    }
                }
            } else {
                if ((currentBg as? ColorDrawable)?.color != Color.BLACK) {
                    Log.d(TAG, "Fallback to black background (pre-cropped image not loaded).")
                    bar.background = ColorDrawable(Color.BLACK)
                }
            }
        } else {
            if ((currentBg as? ColorDrawable)?.color != Color.BLACK) {
                Log.d(TAG, "Applying black background for app/recents view.")
                val blackDrawable = ColorDrawable(Color.BLACK)

                // 이미지 배경에서 검은 배경으로 전환 시 페이드 애니메이션 적용 (Android 12 스타일)
                if (currentBg is BitmapDrawable || currentBg is TransitionDrawable) {
                    val fromDrawable = if (currentBg is TransitionDrawable) {
                        currentBg.getDrawable(currentBg.numberOfLayers - 1)
                    } else {
                        currentBg
                    }
                    val transition = TransitionDrawable(arrayOf(fromDrawable, blackDrawable)).apply {
                        isCrossFadeEnabled = true  // 자연스러운 크로스페이드
                    }
                    bar.background = transition
                    transition.startTransition(BG_TRANSITION_DURATION)
                } else {
                    bar.background = blackDrawable
                }
            }
        }
    }

    fun reloadBackgroundImages() {
        loadBackgroundBitmaps()
        updateNavBarBackground()
    }
}
