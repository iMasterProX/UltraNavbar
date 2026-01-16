package com.minsoo.ultranavbar.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil

/**
 * 네비바 배경 이미지 및 색상 관리자
 * - 배경 비트맵 로딩 (가로/세로)
 * - 다크 모드에 따른 배경색 결정
 * - 이미지 밝기 기반 버튼 색상 계산
 * - 배경 전환 애니메이션 (TransitionDrawable 사용)
 */
class BackgroundManager(
    private val context: Context,
    private val listener: BackgroundChangeListener
) {
    companion object {
        private const val TAG = "BackgroundManager"
    }

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Android 12 표준 애니메이션 인터폴레이터 (버튼 색상 애니메이션용)
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    // 배경 비트맵 캐시
    private var landscapeBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    // 현재 상태
    private var _isDarkMode: Boolean = false
    val isDarkMode: Boolean get() = _isDarkMode

    private var _currentButtonColor: Int = Color.WHITE
    val currentButtonColor: Int get() = _currentButtonColor

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    private var lastAppliedUseCustom: Boolean = false
    private var transitionSourceUseCustom: Boolean = false
    private var transitionTargetUseCustom: Boolean = false
    private var transitionEndAt: Long = 0L

    // 버튼 색상 애니메이터
    private var buttonColorAnimator: ValueAnimator? = null

    /**
     * 배경 변경 리스너
     */
    interface BackgroundChangeListener {
        fun onButtonColorChanged(color: Int)
        fun onBackgroundApplied(drawable: Drawable)
    }

    // ===== 초기화 =====

    /**
     * 다크 모드 상태 초기화
     */
    fun initializeDarkMode() {
        _isDarkMode = isSystemDarkMode()
        _currentButtonColor = getDefaultButtonColor()
    }

    /**
     * 방향 초기화
     */
    fun initializeOrientation(orientation: Int) {
        currentOrientation = orientation
    }

    // ===== 비트맵 로딩 =====

    /**
     * 배경 비트맵 로드
     * 설정이 활성화된 경우에만 로드
     * @param forceReload 강제 리로드 여부 (기본값 false)
     */
    fun loadBackgroundBitmaps(forceReload: Boolean = false) {
        if (!settings.homeBgEnabled) {
            recycleBitmaps()
            return
        }

        // 이미 로드된 비트맵이 있고 강제 리로드가 아니면 스킵
        if (!forceReload && landscapeBitmap != null && portraitBitmap != null) {
            Log.d(TAG, "Background bitmaps already loaded, skipping reload")
            return
        }

        // 기존 비트맵 리사이클
        recycleBitmaps()

        landscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true)
        portraitBitmap = ImageCropUtil.loadBackgroundBitmap(context, false)

        Log.d(TAG, "Background bitmaps loaded: landscape=${landscapeBitmap?.hashCode()}, portrait=${portraitBitmap?.hashCode()}")
    }

    /**
     * 비트맵 리사이클 (메모리 누수 방지)
     */
    private fun recycleBitmaps() {
        landscapeBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        landscapeBitmap = null

        portraitBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        portraitBitmap = null
    }

    /**
     * 현재 방향에 맞는 비트맵 가져오기
     */
    fun getCurrentBitmap(): Bitmap? {
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        val bitmap = if (isLandscape) landscapeBitmap else portraitBitmap
        return bitmap
    }

    /**
     * 비트맵이 로드되었는지 확인
     */
    fun hasBitmaps(): Boolean {
        return landscapeBitmap != null || portraitBitmap != null
    }

    // ===== 방향 처리 =====

    /**
     * 방향 변경 처리
     * @return 방향이 변경되었으면 true
     */
    fun handleOrientationChange(newOrientation: Int): Boolean {
        if (currentOrientation == newOrientation) return false

        Log.d(TAG, "Orientation changed: ${getOrientationName(currentOrientation)} -> ${getOrientationName(newOrientation)}")
        currentOrientation = newOrientation
        return true
    }

    /**
     * 실제 시스템 방향과 동기화
     * @return 동기화가 필요했으면 true
     */
    fun syncOrientationWithSystem(): Boolean {
        val actualOrientation = getActualOrientation()
        if (currentOrientation != actualOrientation) {
            Log.w(TAG, "Orientation mismatch! cached=$currentOrientation, actual=$actualOrientation - resyncing")
            currentOrientation = actualOrientation
            return true
        }
        return false
    }

    private fun getActualOrientation(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
            ?: return context.resources.configuration.orientation

        val rotation = display.rotation
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            size.x = bounds.width()
            size.y = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            display.getRealSize(size)
        }

        val naturalPortrait = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            size.x < size.y
        } else {
            size.x > size.y
        }

        val isPortrait = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            naturalPortrait
        } else {
            !naturalPortrait
        }

        return if (isPortrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }

    fun forceOrientationSync(orientation: Int) {
        Log.d(TAG, "Force orientation sync: ${getOrientationName(currentOrientation)} -> ${getOrientationName(orientation)}")
        currentOrientation = orientation
    }

    private fun getOrientationName(orientation: Int): String {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
    }

    // ===== 다크 모드 처리 =====

    /**
     * 시스템 다크 모드 확인
     */
    private fun isSystemDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 다크 모드 업데이트
     * @return 변경되었으면 true
     */
    fun updateDarkMode(): Boolean {
        val newDarkMode = isSystemDarkMode()
        if (_isDarkMode != newDarkMode) {
            _isDarkMode = newDarkMode
            Log.d(TAG, "Dark mode changed: $_isDarkMode")
            return true
        }
        return false
    }

    fun getDefaultBackgroundColor(): Int {
        return if (_isDarkMode) Color.BLACK else Color.WHITE
    }

    /**
     * 다크 모드에 따른 기본 버튼 색상
     */
    fun getDefaultButtonColor(): Int {
        return if (_isDarkMode) Color.WHITE else Color.DKGRAY
    }

    /**
     * 비트맵 밝기 기반 버튼 색상 계산 (Android 12 스타일)
     * 밝은 이미지 -> 검은 버튼, 어두운 이미지 -> 흰 버튼
     */
    fun calculateButtonColorForBitmap(bitmap: Bitmap): Int {
        val sampleSize = Constants.Threshold.LUMINANCE_SAMPLE_SIZE
        val width = bitmap.width
        val height = bitmap.height

        var totalLuminance = 0.0
        var sampleCount = 0

        val stepX = maxOf(1, width / sampleSize)
        val stepY = maxOf(1, height / sampleSize)

        var x = 0
        while (x < width) {
            var y = 0
            while (y < height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // ITU-R BT.709 상대 휘도 공식
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                totalLuminance += luminance
                sampleCount++
                y += stepY
            }
            x += stepX
        }

        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else Constants.Threshold.BRIGHTNESS_THRESHOLD

        return if (avgLuminance > Constants.Threshold.BRIGHTNESS_THRESHOLD) Color.BLACK else Color.WHITE
    }

    private fun resolveHomeBackgroundButtonColor(bitmap: Bitmap?, defaultBgColor: Int): Int {
        return when (settings.homeBgButtonColorMode) {
            SettingsManager.HomeBgButtonColorMode.AUTO -> {
                if (bitmap != null) {
                    calculateButtonColorForBitmap(bitmap)
                } else {
                    if (isColorLight(defaultBgColor)) Color.BLACK else Color.WHITE
                }
            }
            SettingsManager.HomeBgButtonColorMode.WHITE -> Color.WHITE
            SettingsManager.HomeBgButtonColorMode.BLACK -> Color.BLACK
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > Constants.Threshold.BRIGHTNESS_THRESHOLD
    }

    /**
     * 버튼 색상 업데이트 (애니메이션 지원)
     */
    fun updateButtonColor(targetColor: Int, animate: Boolean = true) {
        if (_currentButtonColor == targetColor) return

        if (!animate) {
            buttonColorAnimator?.cancel()
            _currentButtonColor = targetColor
            listener.onButtonColorChanged(targetColor)
            return
        }

        // 이미 같은 색상으로 애니메이션 중이면 스킵
        if (buttonColorAnimator?.isRunning == true && _currentButtonColor == targetColor) return

        buttonColorAnimator?.cancel()
        
        val startColor = _currentButtonColor
        buttonColorAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = Constants.Timing.BG_TRANSITION_DURATION_MS // 배경 전환 시간과 맞춤
            interpolator = android12Interpolator
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                _currentButtonColor = color
                listener.onButtonColorChanged(color)
            }
            start()
        }
        
        Log.d(TAG, "Button color transition: ${getColorName(startColor)} -> ${getColorName(targetColor)}")
    }

    private fun getColorName(color: Int): String {
        return when (color) {
            Color.WHITE -> "WHITE"
            Color.BLACK -> "BLACK"
            Color.DKGRAY -> "DARK_GRAY"
            else -> "0x${Integer.toHexString(color)}"
        }
    }

    // ===== 배경 적용 =====

    /**
     * 커스텀 이미지 배경 사용 여부 판단
     * 모든 방해 요소(최근 앱, 앱 서랍, 알림 패널, 키보드)가 없을 때만 홈 배경 표시
     */
    fun shouldUseCustomBackground(
        isOnHomeScreen: Boolean,
        isRecentsVisible: Boolean,
        isAppDrawerOpen: Boolean,
        isPanelOpen: Boolean,
        isImeVisible: Boolean
    ): Boolean {
        // 홈 화면에 있고, 홈 배경 설정이 켜져 있으며, 방해 요소가 없어야 함
        return isOnHomeScreen && settings.homeBgEnabled &&
                !isRecentsVisible && !isAppDrawerOpen && !isPanelOpen && !isImeVisible
    }

    /**
     * 배경을 뷰에 적용 (TransitionDrawable을 이용한 크로스페이드)
     * @param targetView 배경을 적용할 뷰
     * @param useCustom 커스텀 배경 사용 여부
     */
    fun applyBackground(
        targetView: View,
        useCustom: Boolean,
        forceUpdate: Boolean = false,
        animate: Boolean = true
    ) {
        val defaultBgColor = getDefaultBackgroundColor()
        val targetDrawable: Drawable
        val targetButtonColor: Int
        val previousUseCustom = lastAppliedUseCustom
        lastAppliedUseCustom = useCustom

        // 1. 목표 배경 및 버튼 색상 결정
        if (useCustom) {
            val bitmap = getCurrentBitmap()
            if (bitmap != null) {
                targetDrawable = BitmapDrawable(context.resources, bitmap).apply {
                    gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                }
            } else {
                targetDrawable = ColorDrawable(defaultBgColor)
            }
            targetButtonColor = resolveHomeBackgroundButtonColor(bitmap, defaultBgColor)
        } else {
            targetDrawable = ColorDrawable(defaultBgColor)
            targetButtonColor = getDefaultButtonColor()
        }

        // 2. 버튼 색상 업데이트 (애니메이션 포함)
        updateButtonColor(targetButtonColor, animate = animate)

        // 3. 배경 전환 처리
        val currentBg = targetView.background

        if (!animate) {
            transitionEndAt = 0L
            targetView.background = targetDrawable
            targetView.setLayerType(View.LAYER_TYPE_NONE, null)
            listener.onBackgroundApplied(targetDrawable)
            return
        }

        // 첫 실행이거나 배경이 없으면 즉시 적용
        if (currentBg == null) {
            transitionEndAt = 0L
            targetView.background = targetDrawable
            listener.onBackgroundApplied(targetDrawable)
            return
        }

        // 최적화: 완전히 동일한 객체/색상이고 강제 업데이트가 아니면 스킵
        if (!forceUpdate) {
            if (currentBg is ColorDrawable && targetDrawable is ColorDrawable && currentBg.color == targetDrawable.color) {
                return
            }
            if (currentBg is BitmapDrawable && targetDrawable is BitmapDrawable && currentBg.bitmap === targetDrawable.bitmap) {
                return
            }
        }

        // 이전 배경의 마지막 상태를 시작점으로 사용
        val startDrawable = if (currentBg is TransitionDrawable) {
            // 현재 실행 중인 트랜지션의 마지막 레이어(도착점)를 가져옴
            currentBg.getDrawable(1)
        } else {
            currentBg
        }

        // TransitionDrawable 생성 및 애니메이션 시작
        // startDrawable -> targetDrawable
        val transition = TransitionDrawable(arrayOf(startDrawable, targetDrawable))
        transition.isCrossFadeEnabled = true // 크로스페이드 활성화
        targetView.background = transition
        
        // 하드웨어 가속 적용 (애니메이션 동안만)
        targetView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        transition.startTransition(Constants.Timing.BG_TRANSITION_DURATION_MS.toInt())
        transitionSourceUseCustom = previousUseCustom
        transitionTargetUseCustom = useCustom
        transitionEndAt = SystemClock.elapsedRealtime() + Constants.Timing.BG_TRANSITION_DURATION_MS

        // 애니메이션 종료 후 레이어 타입 복원 등을 위한 지연 실행
        // (TransitionDrawable은 리스너가 없으므로 Handler 사용)
        targetView.postDelayed({
            targetView.setLayerType(View.LAYER_TYPE_NONE, null)
            // 최종 상태로 고정 (메모리 절약 및 구조 단순화)
            // 단, 애니메이션 도중 다른 배경이 적용되었을 수 있으므로 체크 필요
            if (targetView.background === transition) {
                targetView.background = targetDrawable
                listener.onBackgroundApplied(targetDrawable)
            }
        }, Constants.Timing.BG_TRANSITION_DURATION_MS)

        Log.d(TAG, "Background transition started: ${startDrawable.javaClass.simpleName} -> ${targetDrawable.javaClass.simpleName}")
    }

    fun resolveUseCustomForUnlock(currentUseCustom: Boolean): Boolean {
        if (SystemClock.elapsedRealtime() >= transitionEndAt) {
            return currentUseCustom
        }
        if (transitionSourceUseCustom && !transitionTargetUseCustom) {
            return true
        }
        return currentUseCustom
    }

    fun isTransitionInProgress(): Boolean {
        return SystemClock.elapsedRealtime() < transitionEndAt
    }

    fun isTransitioningFromCustomToDefault(): Boolean {
        return isTransitionInProgress() && transitionSourceUseCustom && !transitionTargetUseCustom
    }

    fun wasLastAppliedCustom(): Boolean {
        return lastAppliedUseCustom
    }

    /**
     * 진행 중인 배경 전환 애니메이션 취소
     * TransitionDrawable은 별도 취소 없이 뷰에서 교체되면 자동 종료됨.
     */
    fun cancelBackgroundTransition() {
        buttonColorAnimator?.cancel()
        transitionEndAt = 0L
    }

    // ===== 정리 =====

    /**
     * 리소스 정리
     */
    fun cleanup() {
        recycleBitmaps()
        buttonColorAnimator?.cancel()
    }
}
