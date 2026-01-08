package com.minsoo.ultranavbar.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 오버레이 상태를 중앙에서 관리하는 클래스
 * 상태 전이 규칙과 디바운싱을 처리
 */
class OverlayStateManager(
    private val listener: StateChangeListener
) {
    companion object {
        private const val TAG = "OverlayStateManager"
    }

    private val handler = Handler(Looper.getMainLooper())

    // 현재 시스템 상태
    private var _systemState = SystemState()
    val systemState: SystemState get() = _systemState

    // 현재 오버레이 상태
    private var _overlayState: OverlayState = OverlayState.Visible()
    val overlayState: OverlayState get() = _overlayState

    // 제스처 관련 타이밍
    private var gestureShowTime: Long = 0
    private var darkModeTransitionTime: Long = 0

    // 디바운스용 Runnable
    private var pendingHomeState: Runnable? = null
    private var pendingRecentsState: Runnable? = null
    private var gestureAutoHideRunnable: Runnable? = null

    /**
     * 상태 변경 리스너 인터페이스
     */
    interface StateChangeListener {
        fun onOverlayStateChanged(newState: OverlayState, oldState: OverlayState)
        fun onSystemStateChanged(newState: SystemState, event: StateChangeEvent)
        fun onBackgroundUpdateRequired()
        fun onButtonColorsUpdateRequired(isDarkMode: Boolean)
    }

    // ===== 상태 업데이트 메서드 =====

    fun updateHomeScreenState(isHome: Boolean) {
        if (isHome) {
            cancelPendingHomeState()
            if (_systemState.isOnHomeScreen) return

            _systemState = _systemState.copy(isOnHomeScreen = true)
            Log.d(TAG, "Home screen state: true")
            listener.onSystemStateChanged(_systemState, StateChangeEvent.HomeScreenChanged(true))
            listener.onBackgroundUpdateRequired()
        } else {
            if (!_systemState.isOnHomeScreen) return

            cancelPendingHomeState()
            pendingHomeState = Runnable {
                pendingHomeState = null
                if (!_systemState.isOnHomeScreen) return@Runnable

                _systemState = _systemState.copy(isOnHomeScreen = false)
                Log.d(TAG, "Home screen state: false (debounced)")
                listener.onSystemStateChanged(_systemState, StateChangeEvent.HomeScreenChanged(false))
                listener.onBackgroundUpdateRequired()
            }
            handler.postDelayed(pendingHomeState!!, Constants.Timing.HOME_STATE_DEBOUNCE_MS)
        }
    }

    fun updateRecentsState(isRecents: Boolean) {
        if (isRecents) {
            if (_systemState.isRecentsVisible) return
            cancelPendingRecentsState()

            if (!_systemState.isOnHomeScreen) {
                _systemState = _systemState.copy(isRecentsVisible = true)
                listener.onSystemStateChanged(_systemState, StateChangeEvent.RecentsChanged(true))
                listener.onBackgroundUpdateRequired()
                return
            }

            pendingRecentsState = Runnable {
                pendingRecentsState = null
                if (_systemState.isRecentsVisible) return@Runnable

                _systemState = _systemState.copy(isRecentsVisible = true)
                listener.onSystemStateChanged(_systemState, StateChangeEvent.RecentsChanged(true))
                listener.onBackgroundUpdateRequired()
            }
            handler.postDelayed(pendingRecentsState!!, Constants.Timing.RECENTS_STATE_DEBOUNCE_MS)
        } else {
            cancelPendingRecentsState()
            if (!_systemState.isRecentsVisible) return

            _systemState = _systemState.copy(isRecentsVisible = false)
            listener.onSystemStateChanged(_systemState, StateChangeEvent.RecentsChanged(false))
            listener.onBackgroundUpdateRequired()
        }
    }

    fun updateFullscreenState(isFullscreen: Boolean) {
        if (_systemState.isFullscreen == isFullscreen) return

        _systemState = _systemState.copy(isFullscreen = isFullscreen)
        Log.d(TAG, "Fullscreen state: $isFullscreen")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.FullscreenChanged(isFullscreen))
    }

    fun updateLockScreenState(isLocked: Boolean) {
        if (_systemState.isLockScreen == isLocked) return

        _systemState = _systemState.copy(isLockScreen = isLocked)
        Log.d(TAG, "Lock screen state: $isLocked")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.LockScreenChanged(isLocked))
    }

    fun updateImeState(isVisible: Boolean) {
        if (_systemState.isImeVisible == isVisible) return

        _systemState = _systemState.copy(isImeVisible = isVisible)
        Log.d(TAG, "IME state: $isVisible")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.ImeChanged(isVisible))
    }

    fun updateNotificationPanelState(isOpen: Boolean) {
        if (_systemState.isNotificationPanelOpen == isOpen) return

        _systemState = _systemState.copy(isNotificationPanelOpen = isOpen)
        Log.d(TAG, "Notification panel state: $isOpen")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.NotificationPanelChanged(isOpen))
    }

    fun updateCurrentPackage(packageName: String) {
        if (_systemState.currentPackage == packageName) return

        _systemState = _systemState.copy(currentPackage = packageName)
        listener.onSystemStateChanged(_systemState, StateChangeEvent.PackageChanged(packageName))
    }

    fun updateOrientation(orientation: Int) {
        if (_systemState.orientation == orientation) return

        _systemState = _systemState.copy(orientation = orientation)
        Log.d(TAG, "Orientation: $orientation")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.OrientationChanged(orientation))
        listener.onBackgroundUpdateRequired()
    }

    fun updateDarkMode(isDark: Boolean) {
        if (_systemState.isDarkMode == isDark) return

        _systemState = _systemState.copy(isDarkMode = isDark)
        darkModeTransitionTime = SystemClock.elapsedRealtime()
        Log.d(TAG, "Dark mode: $isDark")
        listener.onSystemStateChanged(_systemState, StateChangeEvent.DarkModeChanged(isDark))
        listener.onButtonColorsUpdateRequired(isDark)
        listener.onBackgroundUpdateRequired()
    }

    fun updateWallpaperPreviewState(isVisible: Boolean) {
        if (_systemState.isWallpaperPreviewVisible == isVisible) return
        _systemState = _systemState.copy(isWallpaperPreviewVisible = isVisible)
    }

    // ===== 오버레이 상태 전이 =====

    fun showOverlay(method: OverlayState.ShowMethod = OverlayState.ShowMethod.NORMAL) {
        val oldState = _overlayState
        val newState = OverlayState.Visible(method)

        if (method == OverlayState.ShowMethod.GESTURE) {
            gestureShowTime = SystemClock.elapsedRealtime()
            scheduleGestureAutoHide()
        }

        _overlayState = newState
        listener.onOverlayStateChanged(newState, oldState)
    }

    fun hideOverlay(showHotspot: Boolean = true) {
        val oldState = _overlayState
        val newState = if (showHotspot) {
            OverlayState.HiddenWithHotspot
        } else {
            OverlayState.FullyHidden
        }

        cancelGestureAutoHide()
        _overlayState = newState
        listener.onOverlayStateChanged(newState, oldState)
    }

    // ===== 자동 숨김 관련 =====

    /**
     * 자동 숨김이 가능한지 확인
     * 다크 모드 전환 중이거나 제스처로 표시된 직후에는 차단
     */
    fun canAutoHide(): Boolean {
        // 다크 모드 전환 중에는 차단
        val darkModeElapsed = SystemClock.elapsedRealtime() - darkModeTransitionTime
        if (darkModeElapsed < Constants.Timing.DARK_MODE_DEBOUNCE_MS) {
            Log.d(TAG, "Auto-hide blocked: dark mode transition")
            return false
        }

        // 제스처로 표시된 경우 일정 시간 동안 차단
        val currentState = _overlayState
        if (currentState is OverlayState.Visible &&
            currentState.showMethod == OverlayState.ShowMethod.GESTURE) {
            val elapsed = SystemClock.elapsedRealtime() - gestureShowTime
            if (elapsed < Constants.Timing.GESTURE_AUTO_HIDE_MS) {
                return false
            }
        }

        return true
    }

    private fun scheduleGestureAutoHide() {
        cancelGestureAutoHide()
        gestureAutoHideRunnable = Runnable {
            val currentState = _overlayState
            if (currentState is OverlayState.Visible &&
                currentState.showMethod == OverlayState.ShowMethod.GESTURE) {
                Log.d(TAG, "Gesture auto-hide triggered")
                hideOverlay(showHotspot = true)
            }
        }
        handler.postDelayed(gestureAutoHideRunnable!!, Constants.Timing.GESTURE_AUTO_HIDE_MS)
    }

    private fun cancelGestureAutoHide() {
        gestureAutoHideRunnable?.let { handler.removeCallbacks(it) }
        gestureAutoHideRunnable = null
    }

    private fun cancelPendingHomeState() {
        pendingHomeState?.let { handler.removeCallbacks(it) }
        pendingHomeState = null
    }

    private fun cancelPendingRecentsState() {
        pendingRecentsState?.let { handler.removeCallbacks(it) }
        pendingRecentsState = null
    }

    /**
     * 정리 - 서비스 종료 시 호출
     */
    fun cleanup() {
        cancelPendingHomeState()
        cancelPendingRecentsState()
        cancelGestureAutoHide()
    }
}
