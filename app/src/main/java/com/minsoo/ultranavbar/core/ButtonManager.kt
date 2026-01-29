package com.minsoo.ultranavbar.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.minsoo.ultranavbar.model.NavAction
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import kotlin.math.abs

/**
 * 네비게이션 버튼 생성 및 스타일 관리
 * - 버튼 생성 (아이콘, 리플 효과)
 * - 버튼 색상 업데이트
 * - 버튼 회전 애니메이션
 * - 스페이서 생성
 */
class ButtonManager(
    private val context: Context,
    private val listener: ButtonActionListener
) {
    companion object {
        private const val TAG = "ButtonManager"
        private const val BLINK_DEBOUNCE_MS = 500L
    }

    // 관리 중인 모든 버튼
    private val _allButtons = mutableListOf<ImageButton>()
    val allButtons: List<ImageButton> get() = _allButtons

    // 특수 버튼 참조
    private var _panelButton: ImageButton? = null
    val panelButton: ImageButton? get() = _panelButton

    private var _backButton: ImageButton? = null
    val backButton: ImageButton? get() = _backButton

    // 현재 버튼 색상 (-1은 초기화되지 않음을 의미)
    private var currentColor: Int = -1
    
    // 색상 애니메이터
    private var colorAnimator: ValueAnimator? = null

    // 알림 깜빡임 애니메이터
    private var notificationBlinkAnimator: ValueAnimator? = null

    // 알림 깜빡임 디바운스 타임스탬프
    private var lastBlinkStartTime: Long = 0

    /**
     * 버튼 액션 리스너
     */
    interface ButtonActionListener {
        fun onButtonClick(action: NavAction)
        fun onButtonLongClick(action: NavAction): Boolean
        fun shouldIgnoreTouch(toolType: Int): Boolean
    }

    // ===== 버튼 생성 =====

    /**
     * 네비게이션 버튼 생성
     */
    fun createNavButton(
        action: NavAction,
        iconResId: Int,
        sizePx: Int,
        initialColor: Int
    ): ImageButton {
        currentColor = initialColor

        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

            // 리플 효과 설정 - Android 12 스타일 회색 (다크/라이트 모드 모두 보임)
            val rippleColor = ColorStateList.valueOf(0x33808080) // 20% 불투명 회색
            val maskDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = sizePx / 2f
                setColor(Color.GRAY)
            }
            background = RippleDrawable(rippleColor, null, maskDrawable)

            elevation = context.dpToPx(4).toFloat()
            stateListAnimator = null

            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(iconResId)
            setColorFilter(initialColor)
            contentDescription = action.displayName

            // 버튼 리스트에 추가
            _allButtons.add(this)

            // 특수 버튼 참조 저장
            when (action) {
                NavAction.NOTIFICATIONS -> _panelButton = this
                NavAction.BACK -> _backButton = this
                else -> {}
            }

            // 스타일러스 무시 터치 리스너
            setOnTouchListener { _, event ->
                if (listener.shouldIgnoreTouch(event.getToolType(0))) {
                    return@setOnTouchListener true
                }
                false
            }

            // 클릭 리스너
            setOnClickListener {
                listener.onButtonClick(action)
            }

            // 롱클릭 리스너
            setOnLongClickListener {
                listener.onButtonLongClick(action)
            }
        }
    }

    /**
     * 스페이서 생성
     */
    fun createSpacer(widthPx: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        }
    }

    /**
     * 그룹에 스페이서 추가
     */
    fun addSpacerToGroup(parent: ViewGroup, widthPx: Int) {
        parent.addView(createSpacer(widthPx))
    }

    // ===== 버튼 스타일 업데이트 =====

    /**
     * 모든 버튼의 아이콘 색상 업데이트
     * @param color 새 색상
     * @param force true면 현재 색상과 같아도 강제 업데이트
     */
    fun updateAllButtonColors(color: Int, force: Boolean = false) {
        if (!force && currentColor == color) return
        currentColor = color

        _allButtons.forEach { button ->
            button.setColorFilter(color)
        }

        Log.d(TAG, "All button colors updated to ${getColorName(color)} (force=$force, buttons=${_allButtons.size})")
    }


    private fun getColorName(color: Int): String {
        return when (color) {
            Color.WHITE -> "WHITE"
            Color.BLACK -> "BLACK"
            Color.DKGRAY -> "DARK_GRAY"
            else -> "0x${Integer.toHexString(color)}"
        }
    }

    // ===== 패널 버튼 상태 =====

    /**
     * 패널 버튼 회전 상태 업데이트
     */
    fun updatePanelButtonState(isOpen: Boolean, animate: Boolean = true) {
        val rotation = if (isOpen) Constants.Rotation.PANEL_OPEN else Constants.Rotation.PANEL_CLOSED

        _panelButton?.let { button ->
            if (abs(button.rotation - rotation) < 0.5f) {
                button.animate().cancel()
                button.rotation = rotation
                return
            }
            if (animate) {
                button.animate().cancel()
                button.animate()
                    .rotation(rotation)
                    .setDuration(Constants.Timing.ANIMATION_DURATION_MS)
                    .withLayer()
                    .start()
            } else {
                button.rotation = rotation
            }
        }
    }

    /**
     * 패널 버튼 접근성 설명 업데이트
     */
    fun updatePanelButtonDescription(isOpen: Boolean, openText: String, closeText: String) {
        _panelButton?.contentDescription = if (isOpen) closeText else openText
    }

    // ===== 뒤로가기 버튼 상태 =====

    /**
     * 뒤로가기 버튼 회전 (IME 상태)
     */
    fun updateBackButtonRotation(isImeVisible: Boolean, animate: Boolean = true) {
        val targetRotation = if (isImeVisible) {
            Constants.Rotation.IME_ACTIVE
        } else {
            Constants.Rotation.IME_INACTIVE
        }

        _backButton?.let { button ->
            button.animate().cancel()
            if (animate) {
                button.animate()
                    .rotation(targetRotation)
                    .setDuration(Constants.Timing.ANIMATION_DURATION_MS)
                    .withLayer()
                    .start()
            } else {
                button.rotation = targetRotation
            }
        }
    }

    // ===== 알림 깜빡임 =====

    /**
     * 알림 깜빡임 시작
     * 디바운스 보호로 500ms 이내 중복 호출 방지
     */
    fun startNotificationBlink() {
        val now = System.currentTimeMillis()

        // 이미 깜빡이는 중이면 무시
        if (notificationBlinkAnimator?.isRunning == true) {
            return
        }

        // 디바운스 체크 (최근 500ms 이내에 시작했으면 무시)
        if (now - lastBlinkStartTime < BLINK_DEBOUNCE_MS) {
            Log.d(TAG, "Notification blink debounced")
            return
        }

        stopNotificationBlink()
        val button = _panelButton ?: return

        lastBlinkStartTime = now

        notificationBlinkAnimator = ValueAnimator.ofFloat(1.0f, 0.4f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                button.alpha = animator.animatedValue as Float
            }
            start()
        }
        Log.d(TAG, "Notification blink started")
    }

    /**
     * 알림 깜빡임 중지
     */
    fun stopNotificationBlink() {
        notificationBlinkAnimator?.cancel()
        notificationBlinkAnimator = null
        _panelButton?.alpha = 1f
    }

    // ===== 정리 =====

    /**
     * 모든 버튼 참조 정리
     */
    fun clear() {
        stopNotificationBlink()
        colorAnimator?.cancel()
        _allButtons.clear()
        _panelButton = null
        _backButton = null
        // 색상 초기화하여 다음 업데이트 시 강제 적용되도록 함
        currentColor = -1
    }
}
