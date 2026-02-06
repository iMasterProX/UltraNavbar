package com.minsoo.ultranavbar.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 최근 앱 작업 표시줄 UI 관리
 *
 * 중앙 LinearLayout에 원형 앱 아이콘 표시
 * 탭: 앱 전환
 * 위로 슬라이드: 분할화면 실행
 */
class RecentAppsTaskbar(
    private val context: Context,
    private val listener: TaskbarActionListener
) {
    companion object {
        private const val TAG = "RecentAppsTaskbar"
        const val MOVE_THRESHOLD_DP = 10        // 움직임 인식 임계값
        const val DRAG_START_THRESHOLD_DP = 20  // 드래그 시작 임계값
        const val SPLIT_TRIGGER_DP = 80         // 분할화면 트리거 거리
    }

    /**
     * Taskbar 액션 리스너
     */
    interface TaskbarActionListener {
        fun onAppTapped(packageName: String)
        fun onAppDraggedToSplit(packageName: String)
        fun onDragStateChanged(isDragging: Boolean, progress: Float)  // 드래그 상태 콜백
        fun shouldIgnoreTouch(toolType: Int): Boolean
    }

    private var centerGroup: LinearLayout? = null
    private val iconViews = mutableListOf<ImageView>()
    private var currentApps = listOf<RecentAppsManager.RecentAppInfo>()

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
     * - 탭 (움직임 없이 짧게 터치): 앱 전환
     * - 위로 슬라이드: 분할화면 실행
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(
        iconView: ImageView,
        app: RecentAppsManager.RecentAppInfo
    ) {
        var startRawX = 0f
        var startRawY = 0f
        var downTime = 0L
        var isDraggingUp = false
        var hasMoved = false
        val moveThresholdPx = context.dpToPx(MOVE_THRESHOLD_DP)
        val dragStartThresholdPx = context.dpToPx(DRAG_START_THRESHOLD_DP)
        val splitTriggerPx = context.dpToPx(SPLIT_TRIGGER_DP)

        iconView.setOnTouchListener { view, event ->
            if (listener.shouldIgnoreTouch(event.getToolType(0))) {
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    isDraggingUp = false
                    hasMoved = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startRawX
                    val deltaY = startRawY - event.rawY  // 양수 = 위로

                    // 움직임 감지
                    if (!hasMoved && (abs(deltaX) > moveThresholdPx || abs(deltaY) > moveThresholdPx)) {
                        hasMoved = true
                    }

                    // 위로 드래그 시작 감지
                    if (!isDraggingUp && deltaY > dragStartThresholdPx) {
                        isDraggingUp = true
                    }

                    if (isDraggingUp) {
                        // 드래그 진행률 계산 (0.0 ~ 1.0+)
                        val progress = (deltaY / splitTriggerPx).coerceAtLeast(0f)

                        // 아이콘 위로 이동 (제한 없이)
                        view.translationY = -deltaY

                        // 스케일 효과
                        val scale = 1f + (progress.coerceAtMost(1f) * 0.3f)
                        view.scaleX = scale
                        view.scaleY = scale

                        // 드래그 상태 콜백 (오버레이 표시용)
                        listener.onDragStateChanged(true, progress)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = startRawY - event.rawY
                    val elapsed = SystemClock.uptimeMillis() - downTime

                    // 드래그 상태 종료 콜백
                    if (isDraggingUp) {
                        listener.onDragStateChanged(false, 0f)
                    }

                    // 애니메이션으로 원위치 복귀
                    view.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()

                    when {
                        // 위로 충분히 드래그 → 분할화면
                        isDraggingUp && deltaY > splitTriggerPx -> {
                            Log.d(TAG, "Slide to split: ${app.packageName}, deltaY=$deltaY")
                            listener.onAppDraggedToSplit(app.packageName)
                        }
                        // 탭: 움직임 없이 터치
                        !hasMoved -> {
                            Log.d(TAG, "Tap: ${app.packageName}")
                            listener.onAppTapped(app.packageName)
                        }
                        // 그 외 (드래그했지만 충분하지 않음): 아무것도 안 함
                        else -> {
                            Log.d(TAG, "No action: hasMoved=$hasMoved, isDraggingUp=$isDraggingUp, deltaY=$deltaY")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingUp) {
                        listener.onDragStateChanged(false, 0f)
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
}
