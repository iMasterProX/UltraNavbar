package com.minsoo.ultranavbar.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.WindowManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 최근 앱 작업 표시줄 UI 관리
 *
 * 중앙 LinearLayout에 원형 앱 아이콘 표시
 * 탭: 앱 전환
 * 길게 누른 뒤 드래그: 분할화면 실행
 */
class RecentAppsTaskbar(
    private val context: Context,
    private val listener: TaskbarActionListener
) {
    companion object {
        private const val TAG = "RecentAppsTaskbar"
        const val MOVE_THRESHOLD_DP = 10        // 움직임 인식 임계값
        const val SPLIT_TRIGGER_DP = 80         // 분할화면 트리거 거리
        const val LONG_PRESS_TIME_MS = 400L
    }

    /**
     * Taskbar 액션 리스너
     */
    interface TaskbarActionListener {
        fun onAppTapped(packageName: String)
        fun onAppDraggedToSplit(packageName: String)
        fun onDragStateChanged(isDragging: Boolean, progress: Float)  // 드래그 상태 콜백
        fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float)  // 드래그 아이콘 좌표 업데이트
        fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float)  // 드래그 시작 (아이콘 정보 전달)
        fun onDragEnd()  // 드래그 종료
        fun shouldIgnoreTouch(toolType: Int): Boolean
    }

    /** 분할화면 드래그 활성화 여부 (false면 탭만 가능) */
    var splitScreenEnabled: Boolean = false

    private var centerGroup: LinearLayout? = null
    private val iconViews = mutableListOf<ImageView>()
    private var currentApps = listOf<RecentAppsManager.RecentAppInfo>()
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Center group 생성
     * 기존 상태를 초기화하여 updateApps가 제대로 동작하도록 함
     */
    fun createCenterGroup(barHeightPx: Int, buttonColor: Int): LinearLayout {
        // 기존 상태 초기화 (updateApps의 중복 체크를 통과하기 위해)
        iconViews.clear()
        currentApps = emptyList()

        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            // 클리핑 비활성화 - 아이콘이 밖으로 나갈 수 있게
            clipChildren = false
            clipToPadding = false
        }
        centerGroup = group
        return group
    }

    /**
     * 앱 목록 업데이트
     */
    fun updateApps(apps: List<RecentAppsManager.RecentAppInfo>) {
        // 변경사항 없으면 스킵 (깜빡임 방지)
        if (apps == currentApps) {
            return
        }

        currentApps = apps
        val group = centerGroup ?: return

        // 기존 아이콘 제거
        iconViews.clear()
        group.removeAllViews()

        // 새 아이콘 추가
        val sizePx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SIZE_DP)
        for (app in apps) {
            val iconView = createCircularIconView(app, sizePx)
            setupTouchListener(iconView, app)
            group.addView(iconView)
            iconViews.add(iconView)
        }

        Log.d(TAG, "Updated ${apps.size} app icons")
    }

    /**
     * 아이콘 색상 업데이트 (현재는 no-op, 앱 아이콘 원본 색상 유지)
     */
    fun updateIconColors(color: Int) {
        // 앱 아이콘은 색상 필터를 적용하지 않음
    }

    /**
     * 초기화
     */
    fun clear() {
        centerGroup?.removeAllViews()
        iconViews.clear()
        currentApps = emptyList()
    }

    /**
     * 원형 아이콘 뷰 생성
     */
    private fun createCircularIconView(
        app: RecentAppsManager.RecentAppInfo,
        sizePx: Int
    ): ImageView {
        val spacingPx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SPACING_DP / 2)

        return ImageView(context).apply {
            val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = spacingPx
                marginEnd = spacingPx
            }
            layoutParams = params
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(app.icon)
            contentDescription = app.label

            // 원형 클리핑
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true

            // 그림자 없음
            elevation = 0f

            // Ripple 효과
            val rippleColor = ColorStateList.valueOf(0x33808080)
            val maskDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.GRAY)
            }
            foreground = RippleDrawable(rippleColor, null, maskDrawable)
        }
    }

    /**
     * 터치 리스너 설정
     * - 탭: 앱 전환
     * - 길게 누른 뒤 드래그: 분할화면 실행
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(
        iconView: ImageView,
        app: RecentAppsManager.RecentAppInfo
    ) {
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false
        var hasMoved = false
        var longPressTriggered = false
        val moveThresholdPx = context.dpToPx(MOVE_THRESHOLD_DP)
        val splitTriggerPx = context.dpToPx(SPLIT_TRIGGER_DP)

        val longPressRunnable = Runnable {
            if (!splitScreenEnabled || hasMoved) {
                return@Runnable
            }
            longPressTriggered = true
            isDragging = true
            iconView.animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(90L)
                .start()
            listener.onDragStateChanged(true, 0f)
            iconView.alpha = 0f
            listener.onDragStart(iconView, startRawX, startRawY)
        }

        iconView.setOnTouchListener { view, event ->
            if (listener.shouldIgnoreTouch(event.getToolType(0))) {
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    hasMoved = false
                    longPressTriggered = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    if (splitScreenEnabled) {
                        view.postDelayed(longPressRunnable, LONG_PRESS_TIME_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startRawX
                    val deltaY = startRawY - event.rawY

                    // 움직임 감지
                    if (!hasMoved && (abs(deltaX) > moveThresholdPx || abs(deltaY) > moveThresholdPx)) {
                        hasMoved = true
                        if (!longPressTriggered) {
                            view.removeCallbacks(longPressRunnable)
                        }
                    }

                    if (isDragging) {
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                        val rawProgress = (distance / splitTriggerPx).coerceAtLeast(0f)
                        val scale = 1f + (rawProgress.coerceAtMost(1f) * 0.3f)

                        listener.onDragIconUpdate(event.rawX, event.rawY, scale)

                        val zoneFactor = splitZoneFactor(event.rawX, event.rawY)
                        listener.onDragStateChanged(true, rawProgress * zoneFactor)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)

                    val deltaX = event.rawX - startRawX
                    val deltaY = startRawY - event.rawY
                    val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

                    // 드래그 상태 종료 콜백
                    if (isDragging) {
                        listener.onDragStateChanged(false, 0f)
                        listener.onDragEnd()
                        view.alpha = 1f
                    }

                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f

                    when {
                        isDragging -> {
                            if (distance > splitTriggerPx && splitZoneFactor(event.rawX, event.rawY) > 0.5f) {
                                Log.d(TAG, "Long-press drag to split: ${app.packageName}, distance=$distance")
                                listener.onAppDraggedToSplit(app.packageName)
                            } else {
                                Log.d(TAG, "Long-press drag cancelled: ${app.packageName}, distance=$distance")
                            }
                        }
                        !hasMoved && !longPressTriggered -> {
                            Log.d(TAG, "Tap: ${app.packageName}")
                            listener.onAppTapped(app.packageName)
                        }
                        else -> {
                            Log.d(TAG, "No action: hasMoved=$hasMoved, longPressTriggered=$longPressTriggered")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)

                    if (isDragging) {
                        listener.onDragStateChanged(false, 0f)
                        listener.onDragEnd()
                        view.alpha = 1f
                    }
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 손가락 위치의 분할화면 영역 진입도 (0.0 ~ 1.0)
     * 가로: 오른쪽 절반, 세로: 아래쪽 절반
     */
    private fun splitZoneFactor(rawX: Float, rawY: Float): Float {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val isLandscape = screenWidth > screenHeight

        val transitionPx = if (isLandscape) screenWidth * 0.1f else screenHeight * 0.1f
        val midPoint = if (isLandscape) screenWidth / 2f else screenHeight / 2f
        val pos = if (isLandscape) rawX else rawY

        return ((pos - midPoint + transitionPx / 2f) / transitionPx).coerceIn(0f, 1f)
    }
}
