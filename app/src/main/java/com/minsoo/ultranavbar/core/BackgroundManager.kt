package com.minsoo.ultranavbar.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil

/**
 * 네비바 배경 이미지 및 색상 관리자
 * - 배경 비트맵 로딩 (가로/세로)
 * - 다크 모드에 따른 배경색 결정
 * - 이미지 밝기 기반 버튼 색상 계산
 * - 배경 전환 애니메이션
 */
class BackgroundManager(
    private val context: Context,
    private val listener: BackgroundChangeListener
) {
    companion object {
        private const val TAG = "BackgroundManager"
    }

    // Android 12 표준 애니메이션 인터폴레이터
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var bgAnimator: ValueAnimator? = null

    private val settings: SettingsManager = SettingsManager.getInstance(context)

    // 배경 비트맵 캐시
    private var landscapeBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    // 현재 상태
    private var _isDarkMode: Boolean = false
    val isDarkMode: Boolean get() = _isDarkMode

    private var _currentButtonColor: Int = Color.WHITE
    val currentButtonColor: Int get() = _currentButtonColor

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

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
        Log.d(TAG, "getCurrentBitmap: orientation=${getOrientationName(currentOrientation)}, returning ${if (isLandscape) "landscape" else "portrait"} bitmap (hash=${bitmap?.hashCode()})")
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
        val actualOrientation = context.resources.configuration.orientation
        if (currentOrientation != actualOrientation) {
            Log.w(TAG, "Orientation mismatch! cached=$currentOrientation, actual=$actualOrientation - resyncing")
            currentOrientation = actualOrientation
            // 방향이 바뀌었으므로 비트맵 리로드 필요 없음 (이미 로드되어 있음)
            return true
        }
        return false
    }

    /**
     * 방향 강제 동기화 (조건 없이 지정된 방향으로 설정)
     * 전체화면 모드 복귀 시 사용
     */
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
            val newButtonColor = getDefaultButtonColor()
            _currentButtonColor = newButtonColor
            // 다크 모드 전환 시 버튼 색상 즉시 업데이트
            listener.onButtonColorChanged(newButtonColor)
            Log.d(TAG, "Dark mode changed: $_isDarkMode, button color: ${getColorName(newButtonColor)}")
            return true
        }
        return false
    }

    // ===== 색상 계산 =====

    /**
     * 다크 모드에 따른 기본 배경 색상
     */
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

    /**
     * 버튼 색상 업데이트
     */
    fun updateButtonColor(color: Int) {
        if (_currentButtonColor != color) {
            _currentButtonColor = color
            listener.onButtonColorChanged(color)
            Log.d(TAG, "Button color updated: ${getColorName(color)}")
        }
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
     */
    fun shouldUseCustomBackground(isOnHomeScreen: Boolean, isRecentsVisible: Boolean): Boolean {
        return isOnHomeScreen && settings.homeBgEnabled && !isRecentsVisible
    }

    /**
     * 배경을 뷰에 적용
     * @param targetView 배경을 적용할 뷰
     * @param useCustom 커스텀 배경 사용 여부
     * @param forceUpdate 강제 업데이트 여부
     */
    fun applyBackground(targetView: View, useCustom: Boolean, forceUpdate: Boolean = false) {
        val currentBg = targetView.background
        val defaultBgColor = getDefaultBackgroundColor()

        if (useCustom) {
            applyCustomBackground(targetView, currentBg, defaultBgColor)
        } else {
            applyDefaultBackground(targetView, currentBg, defaultBgColor)
        }
    }

    private fun applyCustomBackground(targetView: View, currentBg: Drawable?, defaultBgColor: Int) {
        val targetBitmap = getCurrentBitmap()

        if (targetBitmap != null) {
            val currentBitmap = (currentBg as? BitmapDrawable)?.bitmap
            if (currentBitmap !== targetBitmap) {
                Log.d(TAG, "Applying custom background image")

                val bgDrawable = BitmapDrawable(context.resources, targetBitmap).apply {
                    gravity = Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
                }

                // 이미지 밝기에 따라 버튼 색상 결정
                val buttonColor = calculateButtonColorForBitmap(targetBitmap)
                updateButtonColor(buttonColor)

                // 색상 배경에서 이미지로 전환 시 페이드 인
                val needsFade = currentBg is ColorDrawable || currentBg?.alpha == 0
                if (needsFade) {
                    bgDrawable.alpha = 0
                    targetView.background = bgDrawable
                    animateBackgroundAlpha(bgDrawable, 0, 255)
                } else {
                    targetView.background = bgDrawable
                }

                listener.onBackgroundApplied(bgDrawable)
            }
        } else {
            // 비트맵이 없으면 기본 배경 사용
            if ((currentBg as? ColorDrawable)?.color != defaultBgColor) {
                Log.d(TAG, "Fallback to default background (bitmap not loaded)")
                targetView.background = ColorDrawable(defaultBgColor)
                updateButtonColor(getDefaultButtonColor())
            }
        }
    }

    private fun applyDefaultBackground(targetView: View, currentBg: Drawable?, defaultBgColor: Int) {
        updateButtonColor(getDefaultButtonColor())

        val isCurrentlyImage = currentBg is BitmapDrawable && currentBg.alpha > 0
        if (isCurrentlyImage) {
            Log.d(TAG, "Transitioning from image to default background")
            // 이미지에서 기본 배경으로 페이드 아웃
            animateBackgroundAlpha(currentBg as BitmapDrawable, 255, 0) {
                val defaultDrawable = ColorDrawable(defaultBgColor)
                targetView.background = defaultDrawable
                listener.onBackgroundApplied(defaultDrawable)
            }
        } else if ((currentBg as? ColorDrawable)?.color != defaultBgColor) {
            val defaultDrawable = ColorDrawable(defaultBgColor)
            targetView.background = defaultDrawable
            listener.onBackgroundApplied(defaultDrawable)
        }
    }

    // ===== 애니메이션 =====

    private fun animateBackgroundAlpha(
        drawable: BitmapDrawable,
        fromAlpha: Int,
        toAlpha: Int,
        onEnd: (() -> Unit)? = null
    ) {
        bgAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.cancel()
        }
        bgAnimator = ValueAnimator.ofInt(fromAlpha, toAlpha).apply {
            duration = Constants.Timing.BG_TRANSITION_DURATION_MS
            interpolator = android12Interpolator
            addUpdateListener { animation ->
                drawable.alpha = animation.animatedValue as Int
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var wasCancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        wasCancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!wasCancelled) {
                            onEnd()
                        }
                    }
                })
            }
            start()
        }
    }

    /**
     * 진행 중인 배경 전환 애니메이션 취소
     */
    fun cancelBackgroundTransition() {
        bgAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.cancel()
        }
        bgAnimator = null
    }

    // ===== 정리 =====

    /**
     * 리소스 정리
     */
    fun cleanup() {
        bgAnimator?.cancel()
        bgAnimator = null
        recycleBitmaps()
    }
}
