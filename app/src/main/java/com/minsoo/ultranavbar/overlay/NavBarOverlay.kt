package com.minsoo.ultranavbar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
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
    private var navBarView: LinearLayout? = null
    private var hotspotView: View? = null

    private var isShowing = true
    private var isCreated = false

    // 현재 화면 방향
    private var currentOrientation = Configuration.ORIENTATION_LANDSCAPE

    // 홈 화면 상태
    private var isOnHomeScreen = false

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
        val barHeightPx = dpToPx(settings.barHeight)
        val buttonSizePx = dpToPx(settings.buttonSize)
        val buttonSpacingPx = dpToPx(settings.buttonSpacing)

        navBarView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            ).apply {
                gravity = Gravity.BOTTOM
            }
            // 기본 배경색 (홈 화면이 아닐 때)
            setBackgroundColor(applyOpacity(settings.barColor, settings.barOpacity))
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
        }

        // 홈 화면이면 배경 이미지 적용
        updateNavBarBackground()

        // 왼쪽 버튼 그룹 (Back, Home, Recents)
        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        // Back 버튼
        leftGroup.addView(createNavButton(
            NavAction.BACK,
            R.drawable.ic_sysbar_back_default,
            R.drawable.ic_sysbar_back_pressed,
            buttonSizePx
        ))

        addSpacer(leftGroup, buttonSpacingPx)

        // Home 버튼
        leftGroup.addView(createNavButton(
            NavAction.HOME,
            R.drawable.ic_sysbar_home_default,
            R.drawable.ic_sysbar_home_pressed,
            buttonSizePx
        ))

        addSpacer(leftGroup, buttonSpacingPx)

        // Recents 버튼
        leftGroup.addView(createNavButton(
            NavAction.RECENTS,
            R.drawable.ic_sysbar_recent_default,
            R.drawable.ic_sysbar_recent_pressed,
            buttonSizePx
        ))

        navBarView?.addView(leftGroup)

        // 오른쪽 버튼 그룹 (Notifications)
        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        // Notifications 버튼
        rightGroup.addView(createNavButton(
            NavAction.NOTIFICATIONS,
            R.drawable.ic_sysbar_menu_default,
            R.drawable.ic_sysbar_menu_pressed,
            buttonSizePx
        ))

        navBarView?.addView(rightGroup)

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
     * 네비게이션 버튼 생성
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createNavButton(
        action: NavAction,
        normalResId: Int,
        pressedResId: Int,
        size: Int
    ): ImageButton {
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = null
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = action.displayName

            // 상태별 드로어블 설정
            val stateDrawable = createStateListDrawable(normalResId, pressedResId)
            setImageDrawable(stateDrawable)

            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.isPressed = false
                        service.executeAction(action)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * 상태별 드로어블 생성
     */
    private fun createStateListDrawable(normalResId: Int, pressedResId: Int): StateListDrawable {
        val stateList = StateListDrawable()

        val pressedDrawable = ContextCompat.getDrawable(context, pressedResId)
        val normalDrawable = ContextCompat.getDrawable(context, normalResId)

        stateList.addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
        stateList.addState(intArrayOf(), normalDrawable)

        return stateList
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
        val barHeightPx = dpToPx(settings.barHeight)

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
            bar.startAnimation(AlphaAnimation(0f, 1f).apply {
                duration = ANIMATION_DURATION
            })
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
            bar.startAnimation(AlphaAnimation(1f, 0f).apply {
                duration = ANIMATION_DURATION
            })
            bar.postDelayed({
                bar.visibility = View.GONE
            }, ANIMATION_DURATION)
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

        // 배경 이미지 캐시 다시 로드
        loadBackgroundBitmaps()

        // 기존 뷰 제거 후 재생성
        rootView?.let { root ->
            root.removeAllViews()
            createNavBar()
            createHotspot()

            // 레이아웃 파라미터 업데이트
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
    private fun applyOpacity(color: Int, opacity: Float): Int {
        val alpha = (opacity * 255).toInt()
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
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
     * 네비바 배경 업데이트
     * - 홈 화면이고 배경 이미지가 있으면 이미지 사용
     * - 그 외에는 검정색 배경
     */
    private fun updateNavBarBackground() {
        navBarView?.let { bar ->
            if (isOnHomeScreen && settings.homeBgEnabled) {
                // 홈 화면: 배경 이미지 적용
                val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
                val bitmap = if (isLandscape) landscapeBgBitmap else portraitBgBitmap

                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    drawable.alpha = (settings.barOpacity * 255).toInt()
                    bar.background = drawable
                    Log.d(TAG, "Applied home screen background image (landscape=$isLandscape)")
                } else {
                    // 이미지가 없으면 기본 색상
                    bar.setBackgroundColor(applyOpacity(settings.barColor, settings.barOpacity))
                    Log.d(TAG, "No background image, using default color")
                }
            } else {
                // 홈 화면이 아님: 검정색 배경
                bar.setBackgroundColor(applyOpacity(settings.barColor, settings.barOpacity))
                Log.d(TAG, "Not on home screen, using default color")
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
