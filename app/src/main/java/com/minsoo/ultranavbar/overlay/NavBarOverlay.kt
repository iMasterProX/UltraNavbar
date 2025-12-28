package com.minsoo.ultranavbar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
    }

    private val context: Context = service
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settings: SettingsManager = SettingsManager.getInstance(context)

    private var rootView: FrameLayout? = null
    private var navBarView: ViewGroup? = null
    private var hotspotView: View? = null

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
            Log.d(TAG, "Background bitmaps loaded: landscape=${landscapeBgBitmap != null}, portrait=${portraitBgBitmap != null}")
        }
    }

    /**
     * 네비게이션 바 뷰 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createNavBar() {
        val buttonSizePx = dpToPx(settings.buttonSize)
        val barHeightPx = buttonSizePx + dpToPx(16) // 바 높이를 버튼 크기에 따라 유동적으로 조절
        val buttonSpacingPx = dpToPx(8)

        // Use RelativeLayout for flexible alignment
        navBarView = android.widget.RelativeLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.BLACK) // As per after.png
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
        }

        // Set initial background color
        navBarView?.setBackgroundColor(Color.BLACK)

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
        rightGroup.addView(createNavButton(NavAction.NOTIFICATIONS, R.drawable.ic_sysbar_panel, buttonSizePx))

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
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "Hotspot touched, showing overlay")
                    show()
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
    private fun createNavButton(
        action: NavAction,
        iconResId: Int,
        size: Int
    ): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)

            // Add ripple effect for feedback
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)

            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setImageResource(iconResId)
            contentDescription = action.displayName

            setOnClickListener {
                service.executeAction(action)
            }

            setOnLongClickListener {
                if (action == NavAction.HOME) {
                    service.executeAction(NavAction.ASSIST)
                    true // 이벤트 소비됨
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
        val barHeightPx = dpToPx(settings.buttonSize + 16) // 버튼 크기 + 상하패딩

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
     */
    fun show() {
        if (isShowing) return

        navBarView?.let { bar ->
            bar.visibility = View.VISIBLE
            val slideUp = TranslateAnimation(0f, 0f, bar.height.toFloat(), 0f).apply {
                duration = ANIMATION_DURATION
            }
            bar.startAnimation(slideUp)
        }

        hotspotView?.visibility = View.GONE
        isShowing = true
        Log.d(TAG, "Overlay shown")
    }

    /**
     * 오버레이 숨김
     */
    fun hide() {
        if (!isShowing) return

        navBarView?.let { bar ->
            val slideDown = TranslateAnimation(0f, 0f, 0f, bar.height.toFloat()).apply {
                duration = ANIMATION_DURATION
                setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        bar.visibility = View.GONE
                    }
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
            }
            bar.startAnimation(slideDown)
        }

        // 핫스팟 활성화
        if (settings.hotspotEnabled) {
            hotspotView?.visibility = View.VISIBLE
        }

        isShowing = false
        Log.d(TAG, "Overlay hidden")
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
        Log.d(TAG, "Orientation changed to: ${if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}")

        refreshSettings()
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
     * 색상에 투명도 적용
     */
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
     * 네비바 배경 업데이트
     */
    private fun updateNavBarBackground() {
        navBarView?.let { bar ->
            val oldBg = bar.background ?: ColorDrawable(Color.BLACK)
            val newBg = if (isRecentsVisible) {
                Log.d(TAG, "On Recents screen, preparing black background")
                ColorDrawable(Color.BLACK)
            } else if (isOnHomeScreen && settings.homeBgEnabled) {
                val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
                val bitmap = if (isLandscape) landscapeBgBitmap else portraitBgBitmap
                if (bitmap != null) {
                    Log.d(TAG, "Preparing home screen background image (landscape=$isLandscape)")
                    BitmapDrawable(context.resources, bitmap)
                } else {
                    Log.d(TAG, "No background image, preparing default color")
                    ColorDrawable(Color.BLACK)
                }
            } else {
                Log.d(TAG, "Not on home screen, preparing default color")
                ColorDrawable(Color.BLACK)
            }

            val transition = TransitionDrawable(arrayOf(oldBg, newBg))
            bar.background = transition
            transition.startTransition(300) // 300ms fade duration
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