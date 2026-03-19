package com.minsoo.ultranavbar.core

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 최근 앱 작업 표시줄 UI 관리
 *
 * 중앙 LinearLayout에 앱 아이콘 표시
 * 탭: 앱 전환
 * 길게 누른 뒤 드래그: 분할화면 실행
 */
class RecentAppsTaskbar(
    private val context: Context,
    private val listener: TaskbarActionListener
) {
    companion object {
        private const val TAG = "RecentAppsTaskbar"
        const val MOVE_THRESHOLD_DP = 10 // 움직임 인식 임계값
        const val SPLIT_TRIGGER_DP = 80 // 분할화면 트리거 거리
        const val LONG_PRESS_TIME_MS = 400L
        private const val PRESSED_SCALE = 1.1f
        private const val CLICK_FEEDBACK_DURATION = 200L
        const val LARGE_HOME_ICON_SIZE_DP = 55
        const val LARGE_HOME_BOTTOM_PADDING_DP = 18
        private const val LARGE_HOME_OVERFLOW_EXTRA_DP = 36
        private const val LARGE_HOME_GROUP_OFFSET_DP = 12
        // 작업표시줄(32dp) 드래그 아이콘을 앱 즐겨찾기 패널 아이콘(48dp) 크기까지 키움
        private val TASKBAR_DRAG_MAX_SCALE =
            (NavbarAppsPanel.ICON_SIZE_DP.toFloat() / Constants.Dimension.TASKBAR_ICON_SIZE_DP.toFloat())
                .coerceAtLeast(1f)

        fun calculateHomeLargeBottomPaddingPx(
            context: Context,
            barHeightPx: Int,
            iconSizeDp: Int
        ): Int {
            if (iconSizeDp <= Constants.Dimension.TASKBAR_ICON_SIZE_DP) return 0
            val iconSizePx = context.dpToPx(iconSizeDp)
            return (iconSizePx - barHeightPx).coerceAtLeast(0) + context.dpToPx(LARGE_HOME_BOTTOM_PADDING_DP)
        }

        fun calculateHomeLargeOverflowPx(
            context: Context,
            barHeightPx: Int,
            iconSizeDp: Int
        ): Int {
            val bottomPadding = calculateHomeLargeBottomPaddingPx(context, barHeightPx, iconSizeDp)
            if (bottomPadding == 0) return 0
            return bottomPadding + context.dpToPx(LARGE_HOME_OVERFLOW_EXTRA_DP)
        }

        /**
         * 홈화면에서 확장된 네비바 높이 계산
         * 버튼이 올라간 위치의 중심을 기준으로 바 높이를 잡음
         */
        fun calculateHomeExpandedBarHeightPx(
            context: Context,
            barHeightPx: Int
        ): Int {
            val iconSizePx = context.dpToPx(LARGE_HOME_ICON_SIZE_DP)
            val bottomPaddingPx = (iconSizePx - barHeightPx).coerceAtLeast(0) +
                    context.dpToPx(LARGE_HOME_BOTTOM_PADDING_DP)
            val groupOffsetPx = context.dpToPx(LARGE_HOME_GROUP_OFFSET_DP)
            val iconCenterFromBottom = (bottomPaddingPx - groupOffsetPx) + iconSizePx / 2
            return (iconCenterFromBottom * 2)
        }
    }

    /**
     * Taskbar 액션 리스너
     */
    interface TaskbarActionListener {
        fun onAppTapped(packageName: String, iconView: View? = null)
        fun onAppDraggedToSplit(packageName: String)
        fun onDragStateChanged(isDragging: Boolean, progress: Float) // 드래그 상태 콜백
        fun onDragIconUpdate(screenX: Float, screenY: Float, scale: Float) // 드래그 아이콘 좌표 업데이트
        fun onDragStart(iconView: ImageView, screenX: Float, screenY: Float) // 드래그 시작 (아이콘 정보 전달)
        fun onDragEnd() // 드래그 종료
        fun shouldIgnoreTouch(toolType: Int): Boolean
        fun isSplitDragAllowed(): Boolean
        fun onIconSizeAnimationEnd() // 아이콘 크기 애니메이션 완료
    }

    /** 분할화면 드래그 활성화 여부 (false면 탭만 가능) */
    var splitScreenEnabled: Boolean = false

    /** 최근 앱 아이콘 모양 */
    var iconShape: SettingsManager.RecentAppsTaskbarIconShape =
        SettingsManager.RecentAppsTaskbarIconShape.SQUARE

    /**
     * NavBarOverlay의 entry/exit 애니메이션이 translationY를 제어 중일 때 true.
     * true인 동안에는 내부에서 group.translationY를 덮어쓰지 않음.
     */
    var isExternalTranslationControlled: Boolean = false
    var deferPendingIconSizeAnimationPlayback: Boolean = false

    private var centerGroup: LinearLayout? = null
    private val iconViews = mutableListOf<ImageView>()
    private var currentApps = listOf<RecentAppsManager.RecentAppInfo>()
    private var currentIconSizeDp = Constants.Dimension.TASKBAR_ICON_SIZE_DP
    private var renderedIconSizeDp = Constants.Dimension.TASKBAR_ICON_SIZE_DP.toFloat()
    private var reservedIconSizeDp = Constants.Dimension.TASKBAR_ICON_SIZE_DP
    private var pendingAnimatedTargetSizeDp: Float? = null
    private var currentBarHeightPx = 0
    private var iconSizeAnimator: ValueAnimator? = null
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val iconSizeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * Center group 생성
     * 기존 상태를 초기화하여 updateApps가 제대로 동작하도록 함
     */
    fun createCenterGroup(barHeightPx: Int, buttonColor: Int): LinearLayout {
// 기존 상태 초기화 (updateApps의 중복 체크를 통과하기 위해)
        iconViews.clear()
        currentApps = emptyList()
        currentBarHeightPx = barHeightPx
        renderedIconSizeDp = currentIconSizeDp.toFloat()
        reservedIconSizeDp = currentIconSizeDp
        pendingAnimatedTargetSizeDp = null

        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
// 클리핑 비활성화 - 아이콘이 밖으로 나갈 수 있게
            clipChildren = false
            clipToPadding = false
            layoutTransition = null // layout 변경 시 시스템 애니메이션 방지
        }
        centerGroup = group
        updateCenterGroupPadding(renderedIconSizeDp)
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
        val sizePx = context.dpToPx(renderedIconSizeDp)
        for (app in apps) {
            val iconView = createIconView(app, sizePx)
            setupTouchListener(iconView, app)
            group.addView(iconView)
            iconViews.add(iconView)
        }

        playPendingIconSizeAnimationIfNeeded()

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
        iconSizeAnimator?.cancel()
        iconSizeAnimator = null
        renderedIconSizeDp = currentIconSizeDp.toFloat()
        reservedIconSizeDp = currentIconSizeDp
        pendingAnimatedTargetSizeDp = null
        centerGroup?.removeAllViews()
        iconViews.clear()
        currentApps = emptyList()
    }

    fun setIconSizeDp(iconSizeDp: Int, deferVisibleGrowAnimation: Boolean = false) {
        val normalized = iconSizeDp.coerceAtLeast(Constants.Dimension.TASKBAR_ICON_SIZE_DP)
        // 이미 같은 목표로 애니메이션 중이거나 완료된 경우 중복 호출 무시
        // (iconSizeAnimator가 실행 중이어도 같은 목표면 재시작하지 않음)
        if (currentIconSizeDp == normalized && pendingAnimatedTargetSizeDp == null) return

        val startSizeDp = renderedIconSizeDp
        val targetSizeDp = normalized.toFloat()
        val sizeChanged = abs(startSizeDp - targetSizeDp) > 0.01f
        currentIconSizeDp = normalized
        reservedIconSizeDp = ceil(maxOf(startSizeDp, targetSizeDp).toDouble()).toInt()

        iconSizeAnimator?.cancel()
        iconSizeAnimator = null

        val group = centerGroup
        val shouldAnimate =
            group != null &&
                group.visibility == View.VISIBLE &&
                group.isAttachedToWindow &&
                currentApps.isNotEmpty() &&
                iconViews.isNotEmpty() &&
                sizeChanged

        if (deferVisibleGrowAnimation && sizeChanged && targetSizeDp > startSizeDp) {
            pendingAnimatedTargetSizeDp = targetSizeDp
            if (iconViews.isEmpty() && currentApps.isNotEmpty()) {
                val apps = currentApps
                currentApps = emptyList()
                updateApps(apps)
            } else {
                updateCenterGroupPadding(renderedIconSizeDp)
            }
            return
        }

        if (shouldAnimate) {
            pendingAnimatedTargetSizeDp = null
            animateIconSizeChange(
                fromSizeDp = startSizeDp,
                toSizeDp = targetSizeDp,
                group = group ?: return
            )
            return
        }

        if (sizeChanged && targetSizeDp > startSizeDp) {
            pendingAnimatedTargetSizeDp = targetSizeDp
            if (iconViews.isEmpty() && currentApps.isNotEmpty()) {
                val apps = currentApps
                currentApps = emptyList()
                updateApps(apps)
            } else {
                updateCenterGroupPadding(renderedIconSizeDp)
            }
            return
        }

        pendingAnimatedTargetSizeDp = null
        renderedIconSizeDp = targetSizeDp
        reservedIconSizeDp = normalized
        if (iconViews.isNotEmpty()) {
            applyIconSizeImmediately(normalized)
        } else if (currentApps.isNotEmpty()) {
            val apps = currentApps
            currentApps = emptyList()
            updateApps(apps)
        } else {
            updateCenterGroupPadding(renderedIconSizeDp)
        }
        listener.onIconSizeAnimationEnd()
    }

    fun playPendingIconSizeAnimationIfNeeded(): Boolean {
        if (deferPendingIconSizeAnimationPlayback) return false

        val targetSizeDp = pendingAnimatedTargetSizeDp ?: return false
        val group = centerGroup ?: return false

        val canAnimate =
            group.visibility == View.VISIBLE &&
                group.isAttachedToWindow &&
                currentApps.isNotEmpty() &&
                iconViews.isNotEmpty() &&
                abs(renderedIconSizeDp - targetSizeDp) > 0.01f

        if (!canAnimate) {
            return false
        }

        pendingAnimatedTargetSizeDp = null
        animateIconSizeChange(
            fromSizeDp = renderedIconSizeDp,
            toSizeDp = targetSizeDp,
            group = group
        )
        return true
    }

    fun getCurrentGroupTranslationY(): Float {
        return centerGroup?.translationY ?: calculateGroupTranslationY(renderedIconSizeDp)
    }

    fun getReservedIconSizeDp(): Int {
        return reservedIconSizeDp.coerceAtLeast(Constants.Dimension.TASKBAR_ICON_SIZE_DP)
    }

    private fun updateCenterGroupPadding(iconSizeDp: Float) {
        val group = centerGroup ?: return
        val bottomPadding = calculateBottomPaddingPx(iconSizeDp)
        group.setPadding(group.paddingLeft, group.paddingTop, group.paddingRight, bottomPadding)
        if (!isExternalTranslationControlled) {
            group.translationY = calculateGroupTranslationY(iconSizeDp)
        }
    }

    private fun applyIconSizeImmediately(iconSizeDp: Int) {
        val group = centerGroup ?: return
        val sizePx = context.dpToPx(iconSizeDp)
        val spacingPx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SPACING_DP / 2)
        renderedIconSizeDp = iconSizeDp.toFloat()
        updateCenterGroupPadding(renderedIconSizeDp)

        for (iconView in iconViews) {
            val params = iconView.layoutParams as? LinearLayout.LayoutParams ?: continue
            params.width = sizePx
            params.height = sizePx
            params.marginStart = spacingPx
            params.marginEnd = spacingPx
            iconView.layoutParams = params
            iconView.invalidateOutline()
        }
    }

    private fun animateIconSizeChange(
        fromSizeDp: Float,
        toSizeDp: Float,
        group: LinearLayout
    ) {
        val startTranslationY = group.translationY
        val targetTranslationY = calculateGroupTranslationY(toSizeDp)
        val startBottomPadding = group.paddingBottom
        val targetBottomPadding = calculateBottomPaddingPx(toSizeDp)

        iconSizeAnimator = ValueAnimator.ofFloat(fromSizeDp, toSizeDp).apply {
            duration = Constants.Timing.BG_TRANSITION_DURATION_MS
            interpolator = iconSizeInterpolator
            addUpdateListener { animator ->
                val animatedSizeDp = animator.animatedValue as Float
                val progress = animator.animatedFraction

                renderedIconSizeDp = animatedSizeDp
                applyAnimatedIconSize(animatedSizeDp)

                val bottomPadding = lerpInt(startBottomPadding, targetBottomPadding, progress)
                group.setPadding(group.paddingLeft, group.paddingTop, group.paddingRight, bottomPadding)

                if (!isExternalTranslationControlled) {
                    group.translationY = lerpFloat(startTranslationY, targetTranslationY, progress)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var wasCancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    iconSizeAnimator = null
                    if (wasCancelled) return

                    // outline 최종 갱신
                    for (iconView in iconViews) {
                        iconView.invalidateOutline()
                    }
                    renderedIconSizeDp = toSizeDp
                    reservedIconSizeDp = currentIconSizeDp
                    applyIconSizeImmediately(currentIconSizeDp)
                    listener.onIconSizeAnimationEnd()
                }
            })
            start()
        }
    }

    private fun applyAnimatedIconSize(iconSizeDp: Float) {
        val sizePx = context.dpToPx(iconSizeDp)
        val spacingPx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SPACING_DP / 2)

        for (iconView in iconViews) {
            val params = iconView.layoutParams as? LinearLayout.LayoutParams ?: continue
            var changed = false
            if (params.width != sizePx) {
                params.width = sizePx
                changed = true
            }
            if (params.height != sizePx) {
                params.height = sizePx
                changed = true
            }
            if (params.marginStart != spacingPx) {
                params.marginStart = spacingPx
                changed = true
            }
            if (params.marginEnd != spacingPx) {
                params.marginEnd = spacingPx
                changed = true
            }
            if (changed) {
                iconView.layoutParams = params
            }
        }
    }

    private fun calculateBottomPaddingPx(iconSizeDp: Float): Int {
        if (currentBarHeightPx <= 0) return 0
        val baseIconSizeDp = Constants.Dimension.TASKBAR_ICON_SIZE_DP.toFloat()
        if (iconSizeDp <= baseIconSizeDp) return 0

        val iconSizePx = context.dpToPx(iconSizeDp)
        return (iconSizePx - currentBarHeightPx).coerceAtLeast(0) + context.dpToPx(LARGE_HOME_BOTTOM_PADDING_DP)
    }

    private fun lerpFloat(start: Float, end: Float, progress: Float): Float {
        return start + ((end - start) * progress)
    }

    private fun lerpInt(start: Int, end: Int, progress: Float): Int {
        return (start + ((end - start) * progress)).toInt()
    }

    private fun calculateGroupTranslationY(iconSizeDp: Float): Float {
        if (currentBarHeightPx <= 0) return 0f

        val baseIconSizeDp = Constants.Dimension.TASKBAR_ICON_SIZE_DP.toFloat()
        val largeIconSizeDp = LARGE_HOME_ICON_SIZE_DP.toFloat()
        val smallTranslationY = -((currentBarHeightPx - context.dpToPx(baseIconSizeDp)).coerceAtLeast(0) / 2f)
        val largeTranslationY = context.dpToPx(LARGE_HOME_GROUP_OFFSET_DP).toFloat()

        if (iconSizeDp <= baseIconSizeDp) {
            return smallTranslationY
        }

        if (iconSizeDp >= largeIconSizeDp) {
            return largeTranslationY
        }

        val progress = (iconSizeDp - baseIconSizeDp) / (largeIconSizeDp - baseIconSizeDp)
        return lerpFloat(smallTranslationY, largeTranslationY, progress)
    }

    /**
     * 아이콘 뷰 생성
     */
    private fun createIconView(
        app: RecentAppsManager.RecentAppInfo,
        sizePx: Int
    ): ImageView {
        val spacingPx = context.dpToPx(Constants.Dimension.TASKBAR_ICON_SPACING_DP / 2)
        val shapeMode = iconShape
        val iconDrawable =
            IconShapeMaskHelper.wrapWithShapeMask(context, app.icon, shapeMode)

        return ImageView(context).apply {
            val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = spacingPx
                marginEnd = spacingPx
            }
            layoutParams = params
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(iconDrawable)
            contentDescription = app.label

// 모양별 클리핑
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val width = view.width
                    val height = view.height
                    if (width <= 0 || height <= 0) {
                        return
                    }
                    when (shapeMode) {
                        SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> {
                            outline.setOval(0, 0, width, height)
                        }
                        SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                            val radius = minOf(width, height) * Constants.Dimension.TASKBAR_SQUARE_RADIUS_RATIO
                            outline.setRoundRect(0, 0, width, height, radius)
                        }
                        SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                            outline.setRect(0, 0, width, height)
                        }
                        SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                            val radius = minOf(width, height) * 0.22f
                            outline.setRoundRect(0, 0, width, height, radius)
                        }
                    }
                }
            }
            clipToOutline = shapeMode != SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE

// 그림자 없음
            elevation = 0f

// Ripple 효과
            val rippleColor = ColorStateList.valueOf(0x33808080)
            val maskDrawable = when (shapeMode) {
                SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                    IconShapeMaskHelper.createSquircleRippleMask(context)
                }
                else -> {
                    GradientDrawable().apply {
                        when (shapeMode) {
                            SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> {
                                shape = GradientDrawable.OVAL
                            }
                            SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = sizePx * Constants.Dimension.TASKBAR_SQUARE_RADIUS_RATIO
                            }
                            SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = sizePx * 0.22f
                            }
                            SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> {
                                shape = GradientDrawable.RECTANGLE
                            }
                        }
                        setColor(android.graphics.Color.GRAY)
                    }
                }
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
            if (!splitScreenEnabled || hasMoved || !listener.isSplitDragAllowed()) {
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
                    // 누름 피드백: 아이콘 확대 + 리플
                    view.animate().cancel()
                    view.animate()
                        .scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(CLICK_FEEDBACK_DURATION)
                        .setInterpolator(AccelerateInterpolator())
                        .start()
                    view.isPressed = true
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
                        view.isPressed = false
                    }

                    if (isDragging) {
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                        val rawProgress = (distance / splitTriggerPx).coerceAtLeast(0f)
                        val dragProgress = rawProgress.coerceAtMost(1f)
                        val scale = 1f + ((TASKBAR_DRAG_MAX_SCALE - 1f) * dragProgress)

                        listener.onDragIconUpdate(event.rawX, event.rawY, scale)

                        val zoneFactor = splitZoneFactor(event.rawX, event.rawY)
                        listener.onDragStateChanged(true, rawProgress * zoneFactor)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)
                    view.isPressed = false

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
                    // 놓음 피드백: 아이콘 원래 크기로 복귀 애니메이션
                    view.animate().cancel()
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(CLICK_FEEDBACK_DURATION)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    when {
                        isDragging -> {
                            val zoneFactor = splitZoneFactor(event.rawX, event.rawY)
                            val shouldLaunchSplit = distance > splitTriggerPx && zoneFactor > 0.5f

                            if (distance > splitTriggerPx && !shouldLaunchSplit) {
                                Toast.makeText(context, R.string.split_screen_zone_cancelled, Toast.LENGTH_SHORT).show()
                            }

                            if (shouldLaunchSplit) {
                                Log.d(TAG, "Long-press drag to split: ${app.packageName}, distance=$distance")
                                listener.onAppDraggedToSplit(app.packageName)
                            } else {
                                Log.d(TAG, "Long-press drag cancelled: ${app.packageName}, distance=$distance")
                            }
                        }
                        !hasMoved && !longPressTriggered -> {
                            Log.d(TAG, "Tap: ${app.packageName}")
                            listener.onAppTapped(app.packageName, view)
                        }
                        else -> {
                            Log.d(TAG, "No action: hasMoved=$hasMoved, longPressTriggered=$longPressTriggered")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    view.isPressed = false

                    if (isDragging) {
                        listener.onDragStateChanged(false, 0f)
                        listener.onDragEnd()
                        view.alpha = 1f
                    }
                    view.translationY = 0f
                    // 취소 시 원래 크기로 복귀
                    view.animate().cancel()
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(CLICK_FEEDBACK_DURATION)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
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
