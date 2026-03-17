package com.minsoo.ultranavbar.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity

import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.minsoo.ultranavbar.R as AppR
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
        // Material3 동적 색상 팔레트를 가져오지 못할 때 폴백
        private const val UNIFIED_DEFAULT_BG_COLOR = 0xFF000000.toInt()
        private const val MATERIAL3_NEUTRAL_PREFIX = "system_neutral1"
        private const val MATERIAL3_ACCENT_PREFIX = "system_accent1"
        private const val DAY_MID_BLEND_RATIO = 0.55f
        private const val NIGHT_MID_BLEND_RATIO = 0.55f
        private const val DAY_ACCENT_BLEND_RATIO = 0.2f
        private const val NIGHT_ACCENT_BLEND_RATIO = 0.18f
        private const val DAY_NAVBAR_MIN_LIGHTNESS = 0.84f
        private const val DAY_NAVBAR_MAX_LIGHTNESS = 0.96f
        private const val NIGHT_NAVBAR_MIN_LIGHTNESS = 0.14f
        private const val NIGHT_NAVBAR_MAX_LIGHTNESS = 0.26f
        private const val DAY_NAVBAR_MAX_SATURATION = 0.26f
        private const val NIGHT_NAVBAR_MAX_SATURATION = 0.24f
        private const val SOFT_DARK_BUTTON_COLOR = 0xFF6B6B6B.toInt()
    }

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Android 12 표준 애니메이션 인터폴레이터 (버튼 색상 애니메이션용)
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    // 배경 비트맵 캐시 (일반 모드)
    private var landscapeBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    // 다크 모드 전용 배경 비트맵 캐시
    private var darkLandscapeBitmap: Bitmap? = null
    private var darkPortraitBitmap: Bitmap? = null

    // 현재 상태
    private var _isDarkMode: Boolean = false
    val isDarkMode: Boolean get() = _isDarkMode

    private var _currentButtonColor: Int = Color.WHITE
    val currentButtonColor: Int get() = _currentButtonColor

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var lastLoggedUnifiedColor: Int = Int.MIN_VALUE
    private var lastLoggedUnifiedSource: String = ""

    private var lastAppliedUseCustom: Boolean = false
    private var transitionSourceUseCustom: Boolean = false
    private var transitionTargetUseCustom: Boolean = false
    private var transitionEndAt: Long = 0L

    // 트랜지션 추적용 ID (충돌 방지)
    private var transitionId: Int = 0
    private var pendingTransitionCallback: Runnable? = null
    private var transitionTargetView: View? = null

    // 버튼 색상 애니메이터
    private var buttonColorAnimator: ValueAnimator? = null

    /**
     * 배경 변경 리스너
     */
    interface BackgroundChangeListener {
        fun onButtonColorChanged(color: Int)
        fun onBackgroundApplied(drawable: Drawable)
    }

    private data class ResolvedUnifiedColor(
        val color: Int,
        val source: String
    )

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
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isBitmapLoading = false

    fun loadBackgroundBitmaps(forceReload: Boolean = false, onComplete: Runnable? = null) {
        if (!settings.homeBgEnabled) {
            recycleBitmaps()
            onComplete?.run()
            return
        }

        // 이미 로드된 비트맵이 있고 강제 리로드가 아니면 스킵
        val normalLoaded = landscapeBitmap != null && portraitBitmap != null
        val darkLoaded = !settings.homeBgDarkEnabled ||
                         (darkLandscapeBitmap != null && darkPortraitBitmap != null)

        if (!forceReload && normalLoaded && darkLoaded) {
            Log.d(TAG, "Background bitmaps already loaded, skipping reload")
            onComplete?.run()
            return
        }

        if (isBitmapLoading) {
            Log.d(TAG, "Background bitmaps already loading, skipping")
            return
        }
        isBitmapLoading = true

        val loadDark = settings.homeBgDarkEnabled
        Thread {
            try {
                val newLandscape = ImageCropUtil.loadBackgroundBitmap(context, true, false)
                val newPortrait = ImageCropUtil.loadBackgroundBitmap(context, false, false)
                val newDarkLandscape = if (loadDark) ImageCropUtil.loadBackgroundBitmap(context, true, true) else null
                val newDarkPortrait = if (loadDark) ImageCropUtil.loadBackgroundBitmap(context, false, true) else null

                mainHandler.post {
                    recycleBitmaps()
                    landscapeBitmap = newLandscape
                    portraitBitmap = newPortrait
                    if (loadDark) {
                        darkLandscapeBitmap = newDarkLandscape
                        darkPortraitBitmap = newDarkPortrait
                    }
                    isBitmapLoading = false
                    Log.d(TAG, "Background bitmaps loaded (async): landscape=${landscapeBitmap?.hashCode()}, portrait=${portraitBitmap?.hashCode()}")
                    onComplete?.run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading background bitmaps", e)
                mainHandler.post {
                    isBitmapLoading = false
                    onComplete?.run()
                }
            }
        }.start()
    }

    /**
     * 비트맵 참조 해제
     * 명시적 recycle() 호출 제거: TransitionDrawable이 아직 참조 중인 bitmap을
     * recycle하면 "Canvas: trying to use a recycled bitmap" 크래시 발생.
     * API 12+ 에서는 Bitmap이 관리 힙에 할당되므로 GC가 자동 회수함.
     */
    private fun recycleBitmaps() {
        landscapeBitmap = null
        portraitBitmap = null
        darkLandscapeBitmap = null
        darkPortraitBitmap = null
    }

    /**
     * 현재 방향 및 다크 모드 상태에 맞는 비트맵 가져오기
     * 다크 모드 배경이 활성화되어 있고 다크 모드일 때:
     * - 다크 모드 전용 배경이 있으면 사용
     * - 없으면 일반 배경으로 폴백
     */
    fun getCurrentBitmap(): Bitmap? {
        // 실제 시스템 방향을 직접 확인 (캐시된 값 대신)
        // onConfigurationChanged가 호출되지 않는 경우에도 정확한 방향을 반영
        syncOrientationWithSystem()
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE

        // 실제 시스템 다크 모드 상태를 직접 확인 (캐시된 값 대신)
        // onConfigurationChanged가 호출되지 않는 경우에도 정확한 상태를 반영
        syncDarkModeState()

        // 다크 모드이고 다크 모드 배경이 활성화된 경우
        if (_isDarkMode && settings.homeBgDarkEnabled) {
            val darkBitmap = if (isLandscape) darkLandscapeBitmap else darkPortraitBitmap
            if (darkBitmap != null) {
                return darkBitmap
            }
            // 다크 모드 배경이 없으면 일반 배경으로 폴백
            Log.d(TAG, "Dark mode bitmap not found, falling back to normal bitmap")
        }

        // 일반 배경 반환
        return if (isLandscape) landscapeBitmap else portraitBitmap
    }

    /**
     * 비트맵이 로드되었는지 확인
     * 다크 모드일 때는 다크 모드 비트맵도 확인 (설정된 경우)
     */
    fun hasBitmaps(): Boolean {
        val hasNormalBitmaps = landscapeBitmap != null || portraitBitmap != null

        // 다크 모드이고 다크 모드 배경이 활성화된 경우
        if (_isDarkMode && settings.homeBgDarkEnabled) {
            val hasDarkBitmaps = darkLandscapeBitmap != null || darkPortraitBitmap != null
            // 다크 모드 비트맵이 있거나 일반 비트맵이 있으면 true (폴백)
            return hasDarkBitmaps || hasNormalBitmaps
        }

        return hasNormalBitmaps
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
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()

        // 기기의 자연 방향을 감지하여 회전에 따른 실제 방향을 결정
        val naturalPortrait = if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) {
            w < h
        } else {
            w > h
        }

        val isPortrait = if (rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180) {
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
        val newDarkMode = isEffectiveDarkMode()
        if (_isDarkMode != newDarkMode) {
            _isDarkMode = newDarkMode
            Log.d(TAG, "Dark mode changed: $_isDarkMode")
            return true
        }
        return false
    }

    fun getDefaultBackgroundColor(): Int {
        if (settings.unifiedNormalBgColorEnabled) {
            val resolved = resolveUnifiedMaterial3Color()
            if (resolved.color != lastLoggedUnifiedColor || resolved.source != lastLoggedUnifiedSource) {
                lastLoggedUnifiedColor = resolved.color
                lastLoggedUnifiedSource = resolved.source
                Log.d(
                    TAG,
                    "Unified material color: #${Integer.toHexString(resolved.color)} " +
                        "(dark=$_isDarkMode, source=${resolved.source})"
                )
            }
            return resolved.color
        }

        // 실제 시스템 다크 모드 상태를 직접 확인
        syncDarkModeState()
        return if (_isDarkMode) Color.BLACK else Color.WHITE
    }

    /**
     * 다크 모드에 따른 기본 버튼 색상
     */
    fun getDefaultButtonColor(): Int {
        if (settings.unifiedNormalBgColorEnabled) {
            val unifiedColor = resolveUnifiedMaterial3Color().color
            return if (isColorLight(unifiedColor)) SOFT_DARK_BUTTON_COLOR else Color.WHITE
        }

        // 실제 시스템 다크 모드 상태를 직접 확인
        syncDarkModeState()
        return if (_isDarkMode) Color.WHITE else Color.DKGRAY
    }

    private fun resolveUnifiedMaterial3Color(): ResolvedUnifiedColor {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ResolvedUnifiedColor(UNIFIED_DEFAULT_BG_COLOR, "legacy_fallback")
        }

        syncDarkModeState()

        resolveUnifiedColorFromMaterialTheme()?.let {
            val adjusted = fitToAndroid12NavBarRange(it, _isDarkMode)
            return ResolvedUnifiedColor(adjusted, "material_theme")
        }

        val neutralPalette = loadMaterial3PaletteSorted(MATERIAL3_NEUTRAL_PREFIX)
        val accentPalette = loadMaterial3PaletteSorted(MATERIAL3_ACCENT_PREFIX)

        if (neutralPalette.isEmpty() && accentPalette.isEmpty()) {
            return ResolvedUnifiedColor(UNIFIED_DEFAULT_BG_COLOR, "hard_fallback")
        }

        val resolvedColor = if (_isDarkMode) {
            resolveUnifiedNightColor(neutralPalette, accentPalette)
        } else {
            resolveUnifiedDayColor(neutralPalette, accentPalette)
        }

        val adjustedColor = fitToAndroid12NavBarRange(resolvedColor, _isDarkMode)

        return ResolvedUnifiedColor(adjustedColor, "system_palette")
    }

    private fun resolveUnifiedColorFromMaterialTheme(): Int? {
        return try {
            val baseThemedContext = ContextThemeWrapper(context, AppR.style.Theme_UltraNavbar)
            val themedContext = DynamicColors.wrapContextIfAvailable(baseThemedContext)

            val surface = MaterialColors.getColor(
                themedContext,
                MaterialR.attr.colorSurface,
                UNIFIED_DEFAULT_BG_COLOR
            )
            val surfaceVariant = MaterialColors.getColor(
                themedContext,
                MaterialR.attr.colorSurfaceVariant,
                surface
            )
            val background = MaterialColors.getColor(
                themedContext,
                android.R.attr.colorBackground,
                surface
            )
            val primaryContainer = MaterialColors.getColor(
                themedContext,
                MaterialR.attr.colorPrimaryContainer,
                surfaceVariant
            )
            val secondaryContainer = MaterialColors.getColor(
                themedContext,
                MaterialR.attr.colorSecondaryContainer,
                surfaceVariant
            )

            val base = ColorUtils.blendARGB(surface, surfaceVariant, 0.5f)
            val brightest = listOf(surface, background, primaryContainer, secondaryContainer)
                .maxByOrNull { calculateLuminance(it) } ?: base
            val darkest = listOf(surface, background, surfaceVariant, secondaryContainer, primaryContainer)
                .minByOrNull { calculateLuminance(it) } ?: base

            val useDarkTone = _isDarkMode

            val midpoint = if (useDarkTone) {
                ColorUtils.blendARGB(base, darkest, NIGHT_MID_BLEND_RATIO)
            } else {
                ColorUtils.blendARGB(base, brightest, DAY_MID_BLEND_RATIO)
            }

            val accentRepresentative = if (useDarkTone) secondaryContainer else primaryContainer
            val accentBlendRatio = if (useDarkTone) NIGHT_ACCENT_BLEND_RATIO else DAY_ACCENT_BLEND_RATIO

            ColorUtils.blendARGB(midpoint, accentRepresentative, accentBlendRatio)
        } catch (_: Exception) {
            null
        }
    }

    private fun fitToAndroid12NavBarRange(color: Int, darkMode: Boolean): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)

        if (darkMode) {
            hsl[2] = hsl[2].coerceIn(NIGHT_NAVBAR_MIN_LIGHTNESS, NIGHT_NAVBAR_MAX_LIGHTNESS)
            hsl[1] = hsl[1].coerceAtMost(NIGHT_NAVBAR_MAX_SATURATION)
        } else {
            hsl[2] = hsl[2].coerceIn(DAY_NAVBAR_MIN_LIGHTNESS, DAY_NAVBAR_MAX_LIGHTNESS)
            hsl[1] = hsl[1].coerceAtMost(DAY_NAVBAR_MAX_SATURATION)
        }

        return ColorUtils.HSLToColor(hsl)
    }

    private fun resolveUnifiedDayColor(neutralPalette: List<Int>, accentPalette: List<Int>): Int {
        val dayBase = when {
            neutralPalette.isNotEmpty() -> midpointColorByLuminance(neutralPalette, towardBright = true)
            accentPalette.isNotEmpty() -> midpointColorByLuminance(accentPalette, towardBright = true)
            else -> UNIFIED_DEFAULT_BG_COLOR
        }

        if (accentPalette.isEmpty()) return dayBase

        val dayAccentRepresentative = midpointColorByLuminance(accentPalette, towardBright = true)
        return ColorUtils.blendARGB(dayBase, dayAccentRepresentative, DAY_ACCENT_BLEND_RATIO)
    }

    private fun resolveUnifiedNightColor(neutralPalette: List<Int>, accentPalette: List<Int>): Int {
        val nightBase = when {
            neutralPalette.isNotEmpty() -> midpointColorByLuminance(neutralPalette, towardBright = false)
            accentPalette.isNotEmpty() -> midpointColorByLuminance(accentPalette, towardBright = false)
            else -> UNIFIED_DEFAULT_BG_COLOR
        }

        if (accentPalette.isEmpty()) return nightBase

        val nightAccentRepresentative = midpointColorByLuminance(accentPalette, towardBright = false)
        return ColorUtils.blendARGB(nightBase, nightAccentRepresentative, NIGHT_ACCENT_BLEND_RATIO)
    }

    private fun midpointColorByLuminance(palette: List<Int>, towardBright: Boolean): Int {
        if (palette.isEmpty()) return UNIFIED_DEFAULT_BG_COLOR

        val midIndex = palette.size / 2
        val edgeIndex = if (towardBright) {
            palette.lastIndex
        } else {
            0
        }

        val midBlendRatio = if (towardBright) DAY_MID_BLEND_RATIO else NIGHT_MID_BLEND_RATIO

        return ColorUtils.blendARGB(palette[midIndex], palette[edgeIndex], midBlendRatio)
    }

    private fun loadMaterial3PaletteSorted(prefix: String): List<Int> {
        val palette = linkedSetOf<Int>()
        for (name in buildToneResourceNames(prefix)) {
            val resId = context.resources.getIdentifier(name, "color", "android")
            if (resId == 0) continue
            try {
                palette += context.getColor(resId)
            } catch (_: Exception) {
                // ignore and continue with available tones
            }
        }

        return palette.sortedBy { color ->
            calculateLuminance(color)
        }
    }

    private fun buildToneResourceNames(prefix: String): List<String> {
        val names = linkedSetOf<String>()

        // Pixel/Monet 계열(0~100 톤 표기)
        for (tone in 0..100 step 10) {
            names += "${prefix}_$tone"
        }

        // OEM/프레임워크 호환(0~1000 톤 표기)
        for (tone in 0..1000 step 100) {
            names += "${prefix}_$tone"
        }

        return names.toList()
    }

    private fun calculateLuminance(color: Int): Double {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * 캐시된 다크 모드 상태를 실제 시스템 상태와 동기화
     */
    private fun syncDarkModeState() {
        val actualDarkMode = isEffectiveDarkMode()
        if (_isDarkMode != actualDarkMode) {
            Log.d(TAG, "Dark mode state synced: $actualDarkMode")
            _isDarkMode = actualDarkMode
        }
    }

    private fun isEffectiveDarkMode(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val configured = when (uiModeManager?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> null
        }
        return configured ?: isSystemDarkMode()
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

        return if (avgLuminance > Constants.Threshold.BRIGHTNESS_THRESHOLD) SOFT_DARK_BUTTON_COLOR else Color.WHITE
    }

    private fun resolveHomeBackgroundButtonColor(bitmap: Bitmap?, defaultBgColor: Int): Int {
        return when (settings.homeBgButtonColorMode) {
            SettingsManager.HomeBgButtonColorMode.AUTO -> {
                if (bitmap != null) {
                    calculateButtonColorForBitmap(bitmap)
                } else {
                    if (isColorLight(defaultBgColor)) SOFT_DARK_BUTTON_COLOR else Color.WHITE
                }
            }
            SettingsManager.HomeBgButtonColorMode.WHITE -> Color.WHITE
            SettingsManager.HomeBgButtonColorMode.BLACK -> SOFT_DARK_BUTTON_COLOR
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > Constants.Threshold.BRIGHTNESS_THRESHOLD
    }

    // 애니메이션 목표 색상 (취소 시에도 최종 상태 보장용)
    private var buttonColorAnimationTarget: Int = Color.WHITE

    /**
     * 버튼 색상 업데이트 (애니메이션 지원)
     * 강화된 상태 동기화: 애니메이션 종료/취소 시 최종 상태 보장
     */
    fun updateButtonColor(targetColor: Int, animate: Boolean = true) {
        // 이미 목표 색상과 같으면 스킵 (애니메이션 중 포함)
        if (_currentButtonColor == targetColor && buttonColorAnimationTarget == targetColor) {
            return
        }

        // [ANIMATION DISABLED] 버튼 색상 애니메이션 비활성화 - 항상 즉시 적용
        buttonColorAnimator?.cancel()
        buttonColorAnimationTarget = targetColor
        _currentButtonColor = targetColor
        listener.onButtonColorChanged(targetColor)
    }

    /**
     * 현재 버튼 색상을 강제로 적용 (상태 동기화용)
     * 애니메이션 없이 현재 설정된 목표 색상을 즉시 적용
     */
    fun forceApplyCurrentButtonColor() {
        val targetColor = buttonColorAnimationTarget
        if (_currentButtonColor != targetColor) {
            buttonColorAnimator?.cancel()
            _currentButtonColor = targetColor
            listener.onButtonColorChanged(targetColor)
        }
    }

    // ===== 배경 적용 =====

    /**
     * 커스텀 이미지 배경 사용 여부 판단
     * 모든 방해 요소(최근 앱, 앱 서랍, 알림 패널, 키보드)가 없을 때만 홈 배경 표시
     * 앱 설정 화면에서는 일반 배경 사용
     */
    fun shouldUseCustomBackground(
        isOnHomeScreen: Boolean,
        isRecentsVisible: Boolean,
        isAppDrawerOpen: Boolean,
        isPanelOpen: Boolean,
        isImeVisible: Boolean,
        currentPackage: String = ""
    ): Boolean {
        // 앱 설정 화면(자신의 패키지)에서는 커스텀 배경 사용 안 함
        if (currentPackage == context.packageName) {
            return false
        }

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

        // 1. 목표 배경 및 버튼 색상 결정 (dedup 체크 전에 먼저 계산)
        val targetDrawable: Drawable
        val targetButtonColor: Int

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

        // 2. 버튼 색상은 항상 업데이트 (dedup과 무관하게 상태 동기화 보장)
        // 배경 전환이 스킵되더라도 버튼 색상은 올바르게 설정되어야 함
        updateButtonColor(targetButtonColor, animate = animate)

        // 연속 앱 전환 시 중복 페이드 방지 (로딩화면 → 실제 앱)
        // 이미 같은 목표로 설정되어 있고, 트랜지션 진행 중이거나 최근 완료된 경우 스킵
        if (!forceUpdate && lastAppliedUseCustom == useCustom) {
            val now = SystemClock.elapsedRealtime()
            val recentlyCompleted = now < transitionEndAt + Constants.Timing.TRANSITION_DEDUP_GRACE_MS
            if (isTransitionInProgress() || recentlyCompleted) {
                Log.d(TAG, "Skipping redundant background transition (already useCustom=$useCustom, " +
                        "inProgress=${isTransitionInProgress()}, recentlyCompleted=$recentlyCompleted)")
                return
            }
        }

        val previousUseCustom = lastAppliedUseCustom
        lastAppliedUseCustom = useCustom

        // 3. 진행 중인 트랜지션 콜백 취소 (충돌 방지)
        cancelPendingTransitionCallback()

        // 4. 배경 전환 처리
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

        // [ANIMATION DISABLED] TransitionDrawable 크로스페이드 비활성화 - ANR 방지
        // 즉시 최종 배경 적용 (animate=true여도 즉시 적용)
        transitionEndAt = 0L
        transitionSourceUseCustom = previousUseCustom
        transitionTargetUseCustom = useCustom
        targetView.background = targetDrawable
        targetView.setLayerType(View.LAYER_TYPE_NONE, null)
        listener.onBackgroundApplied(targetDrawable)
        Log.d(TAG, "Background applied instantly: ${targetDrawable.javaClass.simpleName}")
    }

    /**
     * 진행 중인 트랜지션 콜백 취소
     */
    private fun cancelPendingTransitionCallback() {
        pendingTransitionCallback?.let { callback ->
            transitionTargetView?.removeCallbacks(callback)
            Log.d(TAG, "Pending transition callback cancelled")
        }
        pendingTransitionCallback = null
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
        buttonColorAnimator = null
        cancelPendingTransitionCallback()
        transitionId++ // 기존 콜백 무효화
        transitionEndAt = 0L
    }

    // ===== 정리 =====

    /**
     * 리소스 정리
     */
    fun cleanup() {
        recycleBitmaps()
        buttonColorAnimator?.cancel()
        buttonColorAnimator = null
        cancelPendingTransitionCallback()
        transitionTargetView = null
    }
}
