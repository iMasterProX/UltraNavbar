package com.minsoo.ultranavbar.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 제스처 처리 핸들러
 * - 핫스팟 스와이프 업 감지
 * - 네비바 스와이프 다운 감지
 * - 제스처 자동 숨김 타이머 관리
 * - 스타일러스 무시 옵션 처리
 */
class GestureHandler(
    private val context: Context,
    private val listener: GestureListener
) {
    companion object {
        private const val TAG = "GestureHandler"
    }

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())
    private val swipeThresholdPx: Int

    // 제스처 상태
    private var _isGestureShown: Boolean = false
    val isGestureShown: Boolean get() = _isGestureShown

    private var gestureShowTime: Long = 0
    private var gestureAutoHideRunnable: Runnable? = null

    // 터치 시작 위치
    private var touchStartY: Float = 0f

    init {
        swipeThresholdPx = context.dpToPx(Constants.Dimension.SWIPE_THRESHOLD_DP)
    }

    /**
     * 제스처 이벤트 리스너
     */
    interface GestureListener {
        fun onSwipeUpDetected()
        fun onSwipeDownDetected()
        fun onGestureAutoHide()
        fun onGestureOverlayTapped()  // 탭으로 오버레이 숨김 요청
    }

    // ===== 핫스팟 터치 처리 =====

    /**
     * 핫스팟 뷰에 터치 리스너 설정
     */
    fun setupHotspotTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            if (shouldIgnoreTouch(event)) {
                return@setOnTouchListener false
            }
            handleHotspotTouch(event)
        }
    }

    private fun handleHotspotTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = touchStartY - event.rawY // 위로 스와이프 (Y 감소)
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Hotspot swipe up detected")
                    listener.onSwipeUpDetected()
                    touchStartY = event.rawY // 다음 제스처를 위해 초기화
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val deltaY = touchStartY - event.rawY
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Hotspot swipe up on release")
                    listener.onSwipeUpDetected()
                }
                return true
            }
        }
        return false
    }

    // ===== 네비바 터치 처리 (제스처 오버레이) =====

    /**
     * 제스처 오버레이 뷰에 터치 리스너 설정
     */
    fun setupGestureOverlayTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            handleGestureOverlayTouch(event)
        }
    }

    private fun handleGestureOverlayTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - touchStartY // 아래로 스와이프 (Y 증가)
                if (deltaY >= swipeThresholdPx) {
                    Log.d(TAG, "Gesture overlay swipe down detected")
                    listener.onSwipeDownDetected()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val deltaY = event.rawY - touchStartY
                // 스와이프가 아닌 탭이면 버튼 클릭 허용을 위해 오버레이 숨김 요청
                if (deltaY < swipeThresholdPx) {
                    Log.d(TAG, "Gesture overlay tapped, requesting hide")
                    listener.onGestureOverlayTapped()
                }
                return true
            }
        }
        return false
    }

    // ===== 스타일러스 처리 =====

    private fun shouldIgnoreTouch(event: MotionEvent): Boolean {
        return settings.ignoreStylus && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
    }

    /**
     * 버튼에 스타일러스 무시 터치 리스너 설정
     */
    fun setupButtonTouchListener(button: View) {
        button.setOnTouchListener { _, event ->
            if (shouldIgnoreTouch(event)) {
                return@setOnTouchListener true // 이벤트 소비하여 클릭 방지
            }
            false // 정상 처리
        }
    }

    // ===== 제스처 상태 관리 =====

    /**
     * 제스처로 네비바 표시됨을 기록
     */
    fun markGestureShow() {
        _isGestureShown = true
        gestureShowTime = SystemClock.elapsedRealtime()
        scheduleAutoHide()
    }

    /**
     * 제스처 오버레이 숨김
     */
    fun hideGestureOverlay() {
        _isGestureShown = false
        cancelAutoHide()
    }

    /**
     * 제스처 표시 후 경과 시간
     */
    fun getGestureElapsedTime(): Long {
        return SystemClock.elapsedRealtime() - gestureShowTime
    }

    // ===== 자동 숨김 타이머 =====

    private fun scheduleAutoHide() {
        cancelAutoHide()
        gestureAutoHideRunnable = Runnable {
            if (_isGestureShown) {
                Log.d(TAG, "Gesture auto-hide triggered after ${Constants.Timing.GESTURE_AUTO_HIDE_MS}ms")
                listener.onGestureAutoHide()
                _isGestureShown = false
            }
        }
        handler.postDelayed(gestureAutoHideRunnable!!, Constants.Timing.GESTURE_AUTO_HIDE_MS)
    }

    private fun cancelAutoHide() {
        gestureAutoHideRunnable?.let { handler.removeCallbacks(it) }
        gestureAutoHideRunnable = null
    }

    // ===== 자동 숨김 가능 여부 =====

    /**
     * 자동 숨김이 가능한지 확인
     * 제스처로 표시된 경우 일정 시간 동안 차단
     */
    fun canAutoHide(): Boolean {
        if (!_isGestureShown) return true

        val elapsed = getGestureElapsedTime()
        return elapsed > Constants.Timing.GESTURE_AUTO_HIDE_MS
    }

    // ===== 정리 =====

    fun cleanup() {
        cancelAutoHide()
    }
}
