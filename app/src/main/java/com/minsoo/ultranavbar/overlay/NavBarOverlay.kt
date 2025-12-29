package com.minsoo.ultranavbar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.drawable.shapes.RectShape
import android.content.res.ColorStateList
import android.app.WallpaperManager
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.NavAction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil

/**
 * 네비게이션 바 오버레이
 *
 * 화면 하단에 고정되는 커스텀 네비게이션 바
 * TYPE_ACCESSIBILITY_OVERLAY를 사용하여 일관된 표시
 */
class NavBarOverlay(private val service: NavBarAccessibilityService) {

    companion object {
        private const val TAG = "NavBarOverlay"
        private const val ANIMATION_DURATION = 200L

        // 버튼(터치 영역)은 기본 48dp로 고정
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

    private var isShowing = true
    private var isCreated = false

    // 현재 화면 방향
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    // 홈 화면 상태
    private var isOnHomeScreen = false

    // 최근 앱 화면 상태
    private var isRecentsVisible = false

    // 캐시된 배경 비트맵
    private var landscapeBgBitmap: Bitmap? = null
    private var portraitBgBitmap: Bitmap? = null



    /**
     * 오버레이 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    fun create() {
        if (isCreated) return

        try {
            currentOrientation = context.resources.configuration.orientation

            // 루트 컨테이너 생성
            rootView = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 네비게이션 바 생성
            createNavBar()

            // 재호출 핫스팟 생성
            createHotspot()

            // WindowManager에 추가
            val params = createLayoutParams()
            windowManager.addView(rootView, params)

            isCreated = true
            isShowing = true

            // 배경 이미지 캐시 로드
            loadBackgroundBitmaps()

            Log.i(TAG, "Overlay created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay", e)
        }
    }

    /**
     * 배경 비트맵 로드
     */
    private fun loadBackgroundBitmaps() {
        if (settings.homeBgEnabled) {
            landscapeBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, true)
            portraitBgBitmap = ImageCropUtil.loadBackgroundBitmap(context, false)
            Log.d(
                TAG,
                "Background bitmaps loaded: landscape=${landscapeBgBitmap != null}, portrait=${portraitBgBitmap != null}"
            )
        }
    }

    /**
     * 시스템 기본 네비게이션바 높이(px) 가져오기
     * - portrait: navigation_bar_height
     * - landscape: navigation_bar_height_landscape
     */
    private fun getSystemNavigationBarHeightPx(): Int {
        val res = context.resources
        val isPortrait = currentOrientation == Configuration.ORIENTATION_PORTRAIT

        val resName = if (isPortrait) "navigation_bar_height" else "navigation_bar_height_landscape"
        val id = res.getIdentifier(resName, "dimen", "android")

        val h = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (h > 0) h else dpToPx(DEFAULT_NAV_BUTTON_DP)
    }

    /**
     * 네비게이션 바 뷰 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createNavBar() {
        val barHeightPx = getSystemNavigationBarHeightPx()
        val buttonSizePx = dpToPx(DEFAULT_NAV_BUTTON_DP)
        val buttonSpacingPx = dpToPx(8)

        navBarView = android.widget.RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.BLACK)
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            // 자식 뷰의 그림자가 잘리지 않도록 설정
            clipChildren = false
        }

        // 왼쪽 버튼 그룹 (Back, Home, Recents)
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        leftGroup.addView(createNavButton(NavAction.BACK, R.drawable.ic_sysbar_back, buttonSizePx))
        addSpacer(leftGroup, buttonSpacingPx)
        leftGroup.addView(createNavButton(NavAction.HOME, R.drawable.ic_sysbar_home, buttonSizePx))
        addSpacer(leftGroup, buttonSpacingPx)
        leftGroup.addView(createNavButton(NavAction.RECENTS, R.drawable.ic_sysbar_recent, buttonSizePx))

        val leftLayoutParams = android.widget.RelativeLayout.LayoutParams(
            android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
        }
        (navBarView as android.widget.RelativeLayout).addView(leftGroup, leftLayoutParams)

        // 오른쪽 버튼 그룹 (Capture, Panel)
        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        rightGroup.addView(createNavButton(NavAction.TAKE_SCREENSHOT, R.drawable.ic_sysbar_capture, buttonSizePx))
        addSpacer(rightGroup, buttonSpacingPx)
        panelButton = createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx)
        rightGroup.addView(panelButton)

        val rightLayoutParams = android.widget.RelativeLayout.LayoutParams(
            android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
        }
        (navBarView as android.widget.RelativeLayout).addView(rightGroup, rightLayoutParams)

        rootView?.addView(navBarView)
    }

    /**
     * 재호출 핫스팟 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createHotspot() {
        val hotspotHeightPx = dpToPx(settings.hotspotHeight)

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
                // 와콤 스타일러스 필터링
                if (settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return@setOnTouchListener false
                }

                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "Hotspot touched, showing overlay")
                    show(fade = false) // 핫스팟으로 호출 시 슬라이드 업
                    true
                } else {
                    false
                }
            }
        }

        rootView?.addView(hotspotView)
    }

    /**
     * 네비게이션 버튼 생성 (리플 효과 적용)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createNavButton(
        action: NavAction,
        iconResId: Int,
        sizePx: Int
    ): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

                    // 흰색 리플 효과 적용 (content를 null로 하여 테마 영향 최소화)
                    val rippleColor = ColorStateList.valueOf(Color.WHITE)
                    val maskDrawable = ShapeDrawable(RectShape()) // 리플 범위 마스크
                    background = RippleDrawable(rippleColor, null, maskDrawable)
            
                    // 그림자 효과 (Android 12+ 얕은 그림자 시뮬레이션: elevation 사용)
                    elevation = dpToPx(4).toFloat()
                    stateListAnimator = null // 기본 누름 효과 제거하고 리플만 사용
            // 확대(MATRIX/FIT_CENTER) 제거: 흐릿/과확대 방지
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)

            setImageResource(iconResId)
            contentDescription = action.displayName

            // 터치 리스너에 스타일러스 필터링 추가
            setOnTouchListener { _, event ->
                if (settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    return@setOnTouchListener true // 이벤트를 소비하여 클릭 리스너 방지
                }
                // 기본 클릭 처리를 위해 false 반환
                false
            }

            setOnClickListener { service.executeAction(action) }

            setOnLongClickListener {
                if (action == NavAction.HOME) {
                    // 구글 어시스턴트 호출
                    service.executeAction(NavAction.ASSIST)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * 스페이서 추가
     */
    private fun addSpacer(parent: ViewGroup, width: Int) {
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }
        parent.addView(spacer)
    }

    /**
     * WindowManager LayoutParams 생성
     */
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

    /**
     * 오버레이 표시
     *
     * @param fade true면 잠금해제 등에서 페이드 인 효과를 추가로 적용
     */
    fun show(fade: Boolean = false) {
        if (isShowing) return

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.visibility = View.VISIBLE

            if (fade) {
                // 페이드 인 등장
                val alphaAnim = AlphaAnimation(0f, 1f).apply {
                    duration = ANIMATION_DURATION
                }
                bar.startAnimation(alphaAnim)
            } else {
                // 기본 슬라이드 업
                val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                    duration = ANIMATION_DURATION
                }
                bar.startAnimation(slideUp)
            }
        }

        hotspotView?.visibility = View.GONE
        isShowing = true
        Log.d(TAG, "Overlay shown (fade=$fade)")
    }

    /**
     * 오버레이 숨김
     *
     * @param animate false면 즉시 숨김(화면 OFF 등에서 깜빡임/애니메이션 방지)
     */
    fun hide(animate: Boolean = true) {
        if (!isShowing) return

        navBarView?.let { bar ->
            bar.clearAnimation()
            bar.animate().cancel()

            if (!animate) {
                bar.visibility = View.GONE
            } else {
                val slideDown = TranslateAnimation(0f, 0f, 0f, bar.height.toFloat()).apply {
                    duration = ANIMATION_DURATION
                    setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            bar.visibility = View.GONE
                        }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                bar.startAnimation(slideDown)
            }
        }

        // 핫스팟 활성화
        if (settings.hotspotEnabled) {
            hotspotView?.visibility = View.VISIBLE
        }

        isShowing = false
        Log.d(TAG, "Overlay hidden (animate=$animate)")
    }

    /**
     * 설정 새로고침
     */
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
    }

    /**
     * 화면 방향 변경 처리
     */
    fun handleOrientationChange(newOrientation: Int) {
        if (currentOrientation == newOrientation) return

        currentOrientation = newOrientation
        Log.d(
            TAG,
            "Orientation changed to: ${if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}"
        )
        
        // 방향 전환 시 배경 업데이트를 위해 refreshSettings 호출 대신 배경만 업데이트 시도
        // 뷰 재생성을 최소화
        updateNavBarBackground()
    }

    /**
     * 오버레이 제거
     */
    fun destroy() {
        if (!isCreated) return

        try {
            windowManager.removeView(rootView)
            rootView = null
            navBarView = null
            hotspotView = null
            isCreated = false
            Log.i(TAG, "Overlay destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy overlay", e)
        }
    }

    /**
     * dp를 px로 변환
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * 홈 화면 상태 설정
     */
    fun setHomeScreenState(onHome: Boolean) {
        if (isOnHomeScreen == onHome) return

        isOnHomeScreen = onHome
        Log.d(TAG, "Home screen state set: $onHome")
        updateNavBarBackground()
    }

    /**
     * 최근 앱 화면 상태 설정
     */
    fun setRecentsState(isRecents: Boolean) {
        if (isRecentsVisible == isRecents) return
        isRecentsVisible = isRecents
        updateNavBarBackground()
    }

    /**
     * 알림 패널 버튼 상태 업데이트 (회전 애니메이션)
     */
    fun updatePanelButtonState(isOpen: Boolean) {
        val rotation = if (isOpen) 180f else 0f
        panelButton?.animate()?.rotation(rotation)?.setDuration(ANIMATION_DURATION)?.start()
    }

    /**
     * 네비바 배경 업데이트
     */
    private fun updateNavBarBackground() {
        val bar = navBarView ?: return
        val currentBg = bar.background

        // 1. 목표 배경 상태 결정
        // 홈 화면이고, 커스텀 배경이 켜져 있고, 최근 앱 화면이 아닐 때만 이미지 배경
        val shouldUseImageBackground = isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible

        if (shouldUseImageBackground) {
            // 2. 방향에 맞는, 미리 크롭된 비트맵 배경 적용
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            val targetBitmap = if (isLandscape) landscapeBgBitmap else portraitBgBitmap

            if (targetBitmap != null) {
                // 현재 배경이 적용하려는 비트맵과 다른 경우에만 새로 설정
                val currentBitmap = (currentBg as? BitmapDrawable)?.bitmap
                if (currentBitmap !== targetBitmap) {
                    Log.d(TAG, "Applying new pre-cropped background image. Landscape: $isLandscape")
                    // 찌그러짐을 방지하기 위해 gravity 설정
                    val bgDrawable = BitmapDrawable(context.resources, targetBitmap).apply {
                        gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                    }
                    bar.background = bgDrawable
                }
            } else {
                // 적용할 이미지가 없으면 검은색 배경으로 (Fallback)
                if ((currentBg as? ColorDrawable)?.color != Color.BLACK) {
                    Log.d(TAG, "Fallback to black background (pre-cropped image not loaded).")
                    bar.background = ColorDrawable(Color.BLACK)
                }
            }
        } else {
            // 3. 그 외의 모든 경우 (앱, 최근 앱 화면 등)는 검은색 배경 적용
            if ((currentBg as? ColorDrawable)?.color != Color.BLACK) {
                Log.d(TAG, "Applying black background for app/recents view.")
                bar.background = ColorDrawable(Color.BLACK)
            }
        }
    }

    /**
     * 배경 이미지 다시 로드
     */
    fun reloadBackgroundImages() {
        loadBackgroundBitmaps()
        updateNavBarBackground()
    }
}