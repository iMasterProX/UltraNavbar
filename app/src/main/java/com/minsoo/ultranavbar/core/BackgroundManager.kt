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
 *
 * - 배경 비트맵 로딩 (가로/세로)
 * - 다크 모드에 따른 배경색 결정
 * - 이미지 밝기 기반 버튼 색상 계산
 * - 배경 전환 애니메이션 (TransitionDrawable 사용)
 * - QQPlus 런처 홈화면 전용 120px 크롭 배경 지원
 */
class BackgroundManager(
    private val context: Context,
    private val listener: BackgroundChangeListener
) {
    companion object {
        private const val TAG = "BackgroundManager"
        private const val UNIFIED_DEFAULT_BG_COLOR    = 0xFF000000.toInt()
        private const val MATERIAL3_NEUTRAL_PREFIX    = "system_neutral1"
        private const val MATERIAL3_ACCENT_PREFIX     = "system_accent1"
        private const val DAY_MID_BLEND_RATIO         = 0.55f
        private const val NIGHT_MID_BLEND_RATIO       = 0.55f
        private const val DAY_ACCENT_BLEND_RATIO      = 0.2f
        private const val NIGHT_ACCENT_BLEND_RATIO    = 0.18f
        private const val DAY_NAVBAR_MIN_LIGHTNESS    = 0.84f
        private const val DAY_NAVBAR_MAX_LIGHTNESS    = 0.96f
        private const val NIGHT_NAVBAR_MIN_LIGHTNESS  = 0.14f
        private const val NIGHT_NAVBAR_MAX_LIGHTNESS  = 0.26f
        private const val DAY_NAVBAR_MAX_SATURATION   = 0.26f
        private const val NIGHT_NAVBAR_MAX_SATURATION = 0.24f
        private const val SOFT_DARK_BUTTON_COLOR      = 0xFF6B6B6B.toInt()

        data class WidgetPalette(
            val surface: Int,
            val surfaceVariant: Int,
            val progressTint: Int,
            val textPrimary: Int,
            val textSecondary: Int,
            val textTertiary: Int,
            val iconTint: Int,
            val batteryUnknown: Int,
            val batteryHigh: Int,
            val batteryMedium: Int,
            val batteryLow: Int
        )

        private fun fallbackWidgetPalette(context: Context): WidgetPalette {
            return WidgetPalette(
                surface = context.getColor(AppR.color.widget_surface),
                surfaceVariant = context.getColor(AppR.color.widget_surface_variant),
                progressTint = context.getColor(AppR.color.widget_progress_tint),
                textPrimary = context.getColor(AppR.color.widget_text_primary),
                textSecondary = context.getColor(AppR.color.widget_text_secondary),
                textTertiary = context.getColor(AppR.color.widget_text_tertiary),
                iconTint = context.getColor(AppR.color.widget_icon_tint),
                batteryUnknown = context.getColor(AppR.color.widget_battery_unknown),
                batteryHigh = context.getColor(AppR.color.widget_battery_high),
                batteryMedium = context.getColor(AppR.color.widget_battery_medium),
                batteryLow = context.getColor(AppR.color.widget_battery_low)
            )
        }

        fun resolveWidgetPalette(context: Context): WidgetPalette {
            val settings = SettingsManager.getInstance(context)
            if (!settings.unifiedNormalBgColorEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return fallbackWidgetPalette(context)
            }

            return try {
                val base = ContextThemeWrapper(context, AppR.style.Theme_UltraNavbar)
                val themed = DynamicColors.wrapContextIfAvailable(base)
                val isDarkMode =
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES

                val surface = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorSurfaceContainerHigh,
                    context.getColor(AppR.color.widget_surface)
                )
                val surfaceVariant = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorSurfaceVariant,
                    context.getColor(AppR.color.widget_surface_variant)
                )
                val onSurface = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorOnSurface,
                    context.getColor(AppR.color.widget_text_primary)
                )
                val onSurfaceVariant = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorOnSurfaceVariant,
                    context.getColor(AppR.color.widget_text_secondary)
                )
                val primary = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorPrimary,
                    context.getColor(AppR.color.widget_battery_high)
                )
                val tertiary = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorTertiary,
                    context.getColor(AppR.color.widget_battery_medium)
                )
                val error = MaterialColors.getColor(
                    themed,
                    MaterialR.attr.colorError,
                    context.getColor(AppR.color.widget_battery_low)
                )

                WidgetPalette(
                    surface = ColorUtils.blendARGB(surface, surfaceVariant, if (isDarkMode) 0.12f else 0.08f),
                    surfaceVariant = ColorUtils.blendARGB(surfaceVariant, surface, if (isDarkMode) 0.18f else 0.12f),
                    progressTint = ColorUtils.blendARGB(primary, surfaceVariant, if (isDarkMode) 0.08f else 0.14f),
                    textPrimary = onSurface,
                    textSecondary = onSurfaceVariant,
                    textTertiary = ColorUtils.blendARGB(onSurfaceVariant, surface, if (isDarkMode) 0.22f else 0.32f),
                    iconTint = ColorUtils.blendARGB(onSurfaceVariant, primary, if (isDarkMode) 0.12f else 0.08f),
                    batteryUnknown = ColorUtils.blendARGB(onSurfaceVariant, surface, if (isDarkMode) 0.30f else 0.40f),
                    batteryHigh = primary,
                    batteryMedium = ColorUtils.blendARGB(tertiary, primary, if (isDarkMode) 0.15f else 0.08f),
                    batteryLow = error
                )
            } catch (_: Exception) {
                fallbackWidgetPalette(context)
            }
        }
    }

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val android12Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        AnimationPerformanceHelper.applyGlobalSettings()
    }

    // 일반 배경 비트맵 캐시 (시스템 네비바 높이 기준)
    private var landscapeBitmap: Bitmap? = null
    private var portraitBitmap: Bitmap? = null

    // 다크 모드 전용 배경 비트맵 캐시
    private var darkLandscapeBitmap: Bitmap? = null
    private var darkPortraitBitmap: Bitmap? = null

    // QQPlus 런처 홈화면 전용 배경 비트맵 캐시 (120px 크롭)
    private var qqplusLandscapeBitmap: Bitmap? = null
    private var qqplusPortraitBitmap: Bitmap? = null

    // QQPlus 런처 홈화면 전용 다크 모드 배경 비트맵 캐시
    private var qqplusDarkLandscapeBitmap: Bitmap? = null
    private var qqplusDarkPortraitBitmap: Bitmap? = null

    /**
     * QQPlus 런처 활성 상태 (홈 여부와 무관)
     * true  → 항상 120px 크롭 비트맵 사용 (BOTTOM gravity로 아래 정렬, 위쪽 클리핑)
     * false → 시스템 네비바 높이(예: 72px) 크롭 비트맵 사용
     */
    private var isQQPlusActive: Boolean = false

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

    private var transitionId: Int = 0
    private var pendingTransitionCallback: Runnable? = null
    private var transitionTargetView: View? = null

    private var buttonColorAnimator: ValueAnimator? = null
    private var buttonColorAnimationTarget: Int = Color.WHITE

    interface BackgroundChangeListener {
        fun onButtonColorChanged(color: Int)
        fun onBackgroundApplied(drawable: Drawable)
    }

    private data class ResolvedUnifiedColor(val color: Int, val source: String)

    // ===== 초기화 =====

    fun initializeDarkMode() {
        _isDarkMode = isSystemDarkMode()
        _currentButtonColor = getDefaultButtonColor()
    }

    fun initializeOrientation(orientation: Int) {
        currentOrientation = orientation
    }

    // ===== QQPlus 상태 관리 =====

    /**
     * QQPlus 런처 활성 상태 설정 (런처 감지 시 한 번 설정, 홈 진입/이탈과 무관).
     * true 설정 시 항상 120px 크롭 비트맵을 사용하며, BitmapDrawable은
     * BOTTOM gravity로 아래 정렬되어 뷰 높이가 72px이면 아래 72px만 보이고
     * 120px로 확장되면 전체 이미지가 드러남.
     */
    fun setQQPlusActive(active: Boolean) {
        if (isQQPlusActive == active) return
        isQQPlusActive = active
        Log.d(TAG, "QQPlus launcher active: $active")
    }

    // ===== 비트맵 로딩 =====

    /**
     * 배경 비트맵 로드.
     * 일반 / 다크 / QQPlus / QQPlus 다크 총 8개 비트맵을 한 번에 로드한다.
     */
    fun loadBackgroundBitmaps(forceReload: Boolean = false) {
        if (!settings.homeBgEnabled) {
            recycleBitmaps()
            return
        }

        val normalLoaded  = landscapeBitmap != null && portraitBitmap != null
        val darkLoaded    = !settings.homeBgDarkEnabled ||
                            (darkLandscapeBitmap != null && darkPortraitBitmap != null)
        val qqplusLoaded  = qqplusLandscapeBitmap != null && qqplusPortraitBitmap != null

        if (!forceReload && normalLoaded && darkLoaded && qqplusLoaded) {
            Log.d(TAG, "Background bitmaps already loaded, skipping reload")
            return
        }

        recycleBitmaps()

        // 일반 배경 로드
        landscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true,  false, isQQPlus = false)
        portraitBitmap  = ImageCropUtil.loadBackgroundBitmap(context, false, false, isQQPlus = false)

        // 다크 모드 배경 로드
        if (settings.homeBgDarkEnabled) {
            darkLandscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true,  true, isQQPlus = false)
            darkPortraitBitmap  = ImageCropUtil.loadBackgroundBitmap(context, false, true, isQQPlus = false)
            Log.d(TAG, "Dark bitmaps loaded: land=${darkLandscapeBitmap?.hashCode()}, port=${darkPortraitBitmap?.hashCode()}")
        }

        // QQPlus 전용 배경 로드 (120px 크롭)
        qqplusLandscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true,  false, isQQPlus = true)
        qqplusPortraitBitmap  = ImageCropUtil.loadBackgroundBitmap(context, false, false, isQQPlus = true)

        // QQPlus 다크 모드 배경 로드
        if (settings.homeBgDarkEnabled) {
            qqplusDarkLandscapeBitmap = ImageCropUtil.loadBackgroundBitmap(context, true,  true, isQQPlus = true)
            qqplusDarkPortraitBitmap  = ImageCropUtil.loadBackgroundBitmap(context, false, true, isQQPlus = true)
            Log.d(TAG, "QQPlus dark bitmaps loaded: land=${qqplusDarkLandscapeBitmap?.hashCode()}, port=${qqplusDarkPortraitBitmap?.hashCode()}")
        }

        Log.d(TAG, "All bitmaps loaded: normal=(${landscapeBitmap?.hashCode()}, ${portraitBitmap?.hashCode()}), " +
                "qqplus=(${qqplusLandscapeBitmap?.hashCode()}, ${qqplusPortraitBitmap?.hashCode()})")
    }

    private fun recycleBitmaps() {
        landscapeBitmap           = null
        portraitBitmap            = null
        darkLandscapeBitmap       = null
        darkPortraitBitmap        = null
        qqplusLandscapeBitmap     = null
        qqplusPortraitBitmap      = null
        qqplusDarkLandscapeBitmap = null
        qqplusDarkPortraitBitmap  = null
    }

    /**
     * 현재 방향 / 다크 모드 / QQPlus 활성 상태에 맞는 비트맵 반환.
     *
     * 우선순위:
     *  QQPlus 활성 → QQPlus 다크(없으면 QQPlus 일반) → QQPlus 일반(없으면 일반 폴백)
     *  QQPlus 비활성 → 다크(없으면 일반) → 일반
     */
    fun getCurrentBitmap(): Bitmap? {
        syncOrientationWithSystem()
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        syncDarkModeState()

        if (isQQPlusActive) {
            if (_isDarkMode && settings.homeBgDarkEnabled) {
                val bmp = if (isLandscape) qqplusDarkLandscapeBitmap else qqplusDarkPortraitBitmap
                if (bmp != null) return bmp
                Log.d(TAG, "QQPlus dark bitmap not found, falling back to QQPlus normal")
            }
            val bmp = if (isLandscape) qqplusLandscapeBitmap else qqplusPortraitBitmap
            if (bmp != null) return bmp
            Log.d(TAG, "QQPlus bitmap not found, falling back to normal bitmap")
        }

        if (_isDarkMode && settings.homeBgDarkEnabled) {
            val bmp = if (isLandscape) darkLandscapeBitmap else darkPortraitBitmap
            if (bmp != null) return bmp
            Log.d(TAG, "Dark bitmap not found, falling back to normal")
        }

        return if (isLandscape) landscapeBitmap else portraitBitmap
    }

    fun hasBitmaps(): Boolean {
        val hasNormal = landscapeBitmap != null || portraitBitmap != null
        if (isQQPlusActive) {
            val hasQQ = qqplusLandscapeBitmap != null || qqplusPortraitBitmap != null
            if (_isDarkMode && settings.homeBgDarkEnabled) {
                val hasQQDark = qqplusDarkLandscapeBitmap != null || qqplusDarkPortraitBitmap != null
                return hasQQDark || hasQQ || hasNormal
            }
            return hasQQ || hasNormal
        }
        if (_isDarkMode && settings.homeBgDarkEnabled) {
            val hasDark = darkLandscapeBitmap != null || darkPortraitBitmap != null
            return hasDark || hasNormal
        }
        return hasNormal
    }

    // ===== 방향 처리 =====

    fun handleOrientationChange(newOrientation: Int): Boolean {
        if (currentOrientation == newOrientation) return false
        Log.d(TAG, "Orientation: ${orientationName(currentOrientation)} -> ${orientationName(newOrientation)}")
        currentOrientation = newOrientation
        return true
    }

    fun syncOrientationWithSystem(): Boolean {
        val actual = getActualOrientation()
        if (currentOrientation != actual) {
            Log.w(TAG, "Orientation mismatch! cached=$currentOrientation, actual=$actual — resyncing")
            currentOrientation = actual
            return true
        }
        return false
    }

    private fun getActualOrientation(): Int {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
            ?: return context.resources.configuration.orientation
        val rotation = display.rotation
        val bounds   = windowManager.currentWindowMetrics.bounds
        val w = bounds.width(); val h = bounds.height()
        val naturalPortrait = if (rotation == android.view.Surface.ROTATION_0 ||
                                   rotation == android.view.Surface.ROTATION_180) w < h else w > h
        val isPortrait = if (rotation == android.view.Surface.ROTATION_0 ||
                              rotation == android.view.Surface.ROTATION_180) naturalPortrait else !naturalPortrait
        return if (isPortrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
    }

    fun forceOrientationSync(orientation: Int) {
        Log.d(TAG, "Force orientation sync: ${orientationName(currentOrientation)} -> ${orientationName(orientation)}")
        currentOrientation = orientation
    }

    private fun orientationName(o: Int) = if (o == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

    // ===== 다크 모드 처리 =====

    private fun isSystemDarkMode(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun updateDarkMode(): Boolean {
        val newDark = isEffectiveDarkMode()
        if (_isDarkMode != newDark) { _isDarkMode = newDark; Log.d(TAG, "Dark mode: $_isDarkMode"); return true }
        return false
    }

    fun getDefaultBackgroundColor(): Int {
        if (settings.unifiedNormalBgColorEnabled) {
            val resolved = resolveUnifiedMaterial3Color()
            if (resolved.color != lastLoggedUnifiedColor || resolved.source != lastLoggedUnifiedSource) {
                lastLoggedUnifiedColor  = resolved.color
                lastLoggedUnifiedSource = resolved.source
                Log.d(TAG, "Unified material color: #${Integer.toHexString(resolved.color)} (dark=$_isDarkMode, src=${resolved.source})")
            }
            return resolved.color
        }
        syncDarkModeState()
        return if (_isDarkMode) Color.BLACK else Color.WHITE
    }

    fun createDefaultBackgroundDrawable(): Drawable {
        return ColorDrawable(getDefaultBackgroundColor())
    }

    fun getDefaultButtonColor(): Int {
        if (settings.unifiedNormalBgColorEnabled) {
            val unified = resolveUnifiedMaterial3Color().color
            return if (isColorLight(unified)) SOFT_DARK_BUTTON_COLOR else Color.WHITE
        }
        syncDarkModeState()
        return if (_isDarkMode) Color.WHITE else Color.DKGRAY
    }

    private fun resolveUnifiedMaterial3Color(): ResolvedUnifiedColor {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return ResolvedUnifiedColor(UNIFIED_DEFAULT_BG_COLOR, "legacy_fallback")
        syncDarkModeState()
        resolveUnifiedColorFromMaterialTheme()?.let { c ->
            return ResolvedUnifiedColor(fitToAndroid12NavBarRange(c, _isDarkMode), "material_theme")
        }
        val neutral = loadMaterial3PaletteSorted(MATERIAL3_NEUTRAL_PREFIX)
        val accent  = loadMaterial3PaletteSorted(MATERIAL3_ACCENT_PREFIX)
        if (neutral.isEmpty() && accent.isEmpty())
            return ResolvedUnifiedColor(UNIFIED_DEFAULT_BG_COLOR, "hard_fallback")
        val resolved = if (_isDarkMode) resolveUnifiedNightColor(neutral, accent)
                       else             resolveUnifiedDayColor(neutral, accent)
        return ResolvedUnifiedColor(fitToAndroid12NavBarRange(resolved, _isDarkMode), "system_palette")
    }

    private fun resolveUnifiedColorFromMaterialTheme(): Int? {
        return try {
            val base  = ContextThemeWrapper(context, AppR.style.Theme_UltraNavbar)
            val themed = DynamicColors.wrapContextIfAvailable(base)
            val surface          = MaterialColors.getColor(themed, MaterialR.attr.colorSurface, UNIFIED_DEFAULT_BG_COLOR)
            val surfaceVariant   = MaterialColors.getColor(themed, MaterialR.attr.colorSurfaceVariant, surface)
            val background       = MaterialColors.getColor(themed, android.R.attr.colorBackground, surface)
            val primaryContainer = MaterialColors.getColor(themed, MaterialR.attr.colorPrimaryContainer, surfaceVariant)
            val secondaryCont    = MaterialColors.getColor(themed, MaterialR.attr.colorSecondaryContainer, surfaceVariant)
            val blended = ColorUtils.blendARGB(surface, surfaceVariant, 0.5f)
            val brightest = listOf(surface, background, primaryContainer, secondaryCont)
                .maxByOrNull { calculateLuminance(it) } ?: blended
            val darkest = listOf(surface, background, surfaceVariant, secondaryCont, primaryContainer)
                .minByOrNull { calculateLuminance(it) } ?: blended
            val mid = if (_isDarkMode) ColorUtils.blendARGB(blended, darkest, NIGHT_MID_BLEND_RATIO)
                      else             ColorUtils.blendARGB(blended, brightest, DAY_MID_BLEND_RATIO)
            val accentRep   = if (_isDarkMode) secondaryCont else primaryContainer
            val accentRatio = if (_isDarkMode) NIGHT_ACCENT_BLEND_RATIO else DAY_ACCENT_BLEND_RATIO
            ColorUtils.blendARGB(mid, accentRep, accentRatio)
        } catch (_: Exception) { null }
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

    private fun resolveUnifiedDayColor(neutral: List<Int>, accent: List<Int>): Int {
        val base = if (neutral.isNotEmpty()) midpoint(neutral, true)
                   else if (accent.isNotEmpty()) midpoint(accent, true)
                   else UNIFIED_DEFAULT_BG_COLOR
        if (accent.isEmpty()) return base
        return ColorUtils.blendARGB(base, midpoint(accent, true), DAY_ACCENT_BLEND_RATIO)
    }

    private fun resolveUnifiedNightColor(neutral: List<Int>, accent: List<Int>): Int {
        val base = if (neutral.isNotEmpty()) midpoint(neutral, false)
                   else if (accent.isNotEmpty()) midpoint(accent, false)
                   else UNIFIED_DEFAULT_BG_COLOR
        if (accent.isEmpty()) return base
        return ColorUtils.blendARGB(base, midpoint(accent, false), NIGHT_ACCENT_BLEND_RATIO)
    }

    private fun midpoint(palette: List<Int>, bright: Boolean): Int {
        if (palette.isEmpty()) return UNIFIED_DEFAULT_BG_COLOR
        val mid  = palette.size / 2
        val edge = if (bright) palette.lastIndex else 0
        val r    = if (bright) DAY_MID_BLEND_RATIO else NIGHT_MID_BLEND_RATIO
        return ColorUtils.blendARGB(palette[mid], palette[edge], r)
    }

    private fun loadMaterial3PaletteSorted(prefix: String): List<Int> {
        val set = linkedSetOf<Int>()
        val names = linkedSetOf<String>()
        for (t in 0..100 step 10)   names += "${prefix}_$t"
        for (t in 0..1000 step 100) names += "${prefix}_$t"
        for (name in names) {
            val id = context.resources.getIdentifier(name, "color", "android")
            if (id == 0) continue
            try { set += context.getColor(id) } catch (_: Exception) {}
        }
        return set.sortedBy { calculateLuminance(it) }
    }

    private fun calculateLuminance(color: Int): Double {
        return 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
    }

    private fun syncDarkModeState() {
        val actual = isEffectiveDarkMode()
        if (_isDarkMode != actual) { Log.d(TAG, "Dark mode synced: $actual"); _isDarkMode = actual }
    }

    private fun isEffectiveDarkMode(): Boolean {
        val mgr = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return when (mgr?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO  -> false
            else                         -> isSystemDarkMode()
        }
    }

    fun calculateButtonColorForBitmap(bitmap: Bitmap): Int {
        val sz = Constants.Threshold.LUMINANCE_SAMPLE_SIZE
        val stepX = maxOf(1, bitmap.width  / sz)
        val stepY = maxOf(1, bitmap.height / sz)
        var total = 0.0; var count = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val px = bitmap.getPixel(x, y)
                total += 0.2126 * Color.red(px) + 0.7152 * Color.green(px) + 0.0722 * Color.blue(px)
                count++
                y += stepY
            }
            x += stepX
        }
        val avg = if (count > 0) total / count else Constants.Threshold.BRIGHTNESS_THRESHOLD.toDouble()
        return if (avg > Constants.Threshold.BRIGHTNESS_THRESHOLD) SOFT_DARK_BUTTON_COLOR else Color.WHITE
    }

    private fun resolveHomeBackgroundButtonColor(bitmap: Bitmap?, defaultBgColor: Int): Int {
        return when (settings.homeBgButtonColorMode) {
            SettingsManager.HomeBgButtonColorMode.AUTO ->
                if (bitmap != null) calculateButtonColorForBitmap(bitmap)
                else if (isColorLight(defaultBgColor)) SOFT_DARK_BUTTON_COLOR else Color.WHITE
            SettingsManager.HomeBgButtonColorMode.WHITE -> Color.WHITE
            SettingsManager.HomeBgButtonColorMode.BLACK -> SOFT_DARK_BUTTON_COLOR
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val lum = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
        return lum > Constants.Threshold.BRIGHTNESS_THRESHOLD
    }

    fun updateButtonColor(targetColor: Int, animate: Boolean = true) {
        if (_currentButtonColor == targetColor && buttonColorAnimationTarget == targetColor) return
        val durationMs = AnimationPerformanceHelper.resolveDuration(Constants.Timing.BG_TRANSITION_DURATION_MS)
        if (!animate || durationMs <= 0L || AnimationPerformanceHelper.shouldSkipNonEssentialAnimations()) {
            buttonColorAnimator?.cancel()
            buttonColorAnimationTarget = targetColor
            _currentButtonColor = targetColor
            listener.onButtonColorChanged(targetColor)
            return
        }
        if (buttonColorAnimator?.isRunning == true && buttonColorAnimationTarget == targetColor) return
        buttonColorAnimationTarget = targetColor
        buttonColorAnimator?.cancel()
        val start = _currentButtonColor
        val frameThrottle = AnimationPerformanceHelper.FrameThrottle()
        buttonColorAnimator = ValueAnimator.ofArgb(start, targetColor).apply {
            duration    = durationMs
            interpolator = android12Interpolator
            addUpdateListener { anim ->
                if (!frameThrottle.shouldDispatch(anim)) return@addUpdateListener
                _currentButtonColor = anim.animatedValue as Int
                listener.onButtonColorChanged(_currentButtonColor)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    frameThrottle.reset()
                    if (_currentButtonColor != targetColor) {
                        _currentButtonColor = targetColor
                        listener.onButtonColorChanged(targetColor)
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    frameThrottle.reset()
                }
            })
            start()
        }
        Log.d(TAG, "Button color: ${colorName(start)} -> ${colorName(targetColor)}")
    }

    fun forceApplyCurrentButtonColor() {
        val target = buttonColorAnimationTarget
        if (_currentButtonColor != target) {
            buttonColorAnimator?.cancel()
            _currentButtonColor = target
            listener.onButtonColorChanged(target)
        }
    }

    private fun colorName(c: Int) = when (c) {
        Color.WHITE            -> "WHITE"
        Color.BLACK            -> "BLACK"
        Color.DKGRAY           -> "DARK_GRAY"
        SOFT_DARK_BUTTON_COLOR -> "SOFT_DARK(#6B6B6B)"
        else                   -> "0x${Integer.toHexString(c)}"
    }

    // ===== 배경 적용 =====

    fun shouldUseCustomBackground(
        isOnHomeScreen: Boolean,
        isRecentsVisible: Boolean,
        isAppDrawerOpen: Boolean,
        isPanelOpen: Boolean,
        isImeVisible: Boolean,
        currentPackage: String = ""
    ): Boolean {
        if (currentPackage == context.packageName) return false
        return isOnHomeScreen && settings.homeBgEnabled &&
                !isRecentsVisible && !isAppDrawerOpen && !isPanelOpen && !isImeVisible
    }

    fun applyBackground(
        targetView: View,
        useCustom: Boolean,
        forceUpdate: Boolean = false,
        animate: Boolean = true
    ) {
        val defaultBgColor = getDefaultBackgroundColor()
        val targetDrawable: Drawable
        val targetButtonColor: Int

        if (useCustom) {
            val bitmap = getCurrentBitmap()
            targetDrawable = if (bitmap != null) {
                BitmapDrawable(context.resources, bitmap).apply {
                    // BOTTOM 정렬: 120px 비트맵이 72px 뷰에 표시될 때 아래 72px만 보이고
                    // 뷰 높이가 커지면 위쪽이 자연스럽게 드러남 (이미지 압축 없음)
                    gravity = Gravity.FILL_HORIZONTAL or Gravity.BOTTOM
                }
            } else ColorDrawable(defaultBgColor)
            targetButtonColor = resolveHomeBackgroundButtonColor(bitmap, defaultBgColor)
        } else {
            targetDrawable    = createDefaultBackgroundDrawable()
            targetButtonColor = getDefaultButtonColor()
        }

        updateButtonColor(targetButtonColor, animate = animate)

        if (!forceUpdate && lastAppliedUseCustom == useCustom) {
            val now = SystemClock.elapsedRealtime()
            val recentDone = now < transitionEndAt + Constants.Timing.TRANSITION_DEDUP_GRACE_MS
            if (isTransitionInProgress() || recentDone) return
        }

        val prevUseCustom = lastAppliedUseCustom
        lastAppliedUseCustom = useCustom
        cancelPendingTransitionCallback()

        val currentBg = targetView.background
        val durationMs = AnimationPerformanceHelper.resolveDuration(Constants.Timing.BG_TRANSITION_DURATION_MS)
        if (!animate || durationMs <= 0L) {
            transitionEndAt = 0L
            targetView.background = targetDrawable
            listener.onBackgroundApplied(targetDrawable)
            return
        }
        if (AnimationPerformanceHelper.shouldSkipNonEssentialAnimations() ||
            AnimationPerformanceHelper.shouldSimplifyBackgroundTransitions()) {
            transitionEndAt = 0L
            targetView.background = targetDrawable
            listener.onBackgroundApplied(targetDrawable)
            return
        }
        if (currentBg == null) {
            transitionEndAt = 0L
            targetView.background = targetDrawable
            listener.onBackgroundApplied(targetDrawable)
            return
        }
        if (!forceUpdate) {
            if (currentBg is ColorDrawable && targetDrawable is ColorDrawable && currentBg.color == targetDrawable.color) return
            if (currentBg is BitmapDrawable && targetDrawable is BitmapDrawable && currentBg.bitmap === targetDrawable.bitmap) return
        }

        val startDrawable = if (currentBg is TransitionDrawable) currentBg.getDrawable(1) else currentBg
        val transition = TransitionDrawable(arrayOf(startDrawable, targetDrawable)).apply { isCrossFadeEnabled = false }
        // HARDWARE 레이어 사용하지 않음: 일부 기기에서 레이어 생성 시 1프레임 깜빡임 발생
        targetView.background = transition
        transition.startTransition(durationMs.toInt())

        transitionSourceUseCustom = prevUseCustom
        transitionTargetUseCustom = useCustom
        transitionEndAt = SystemClock.elapsedRealtime() + durationMs

        val currentId = ++transitionId
        transitionTargetView = targetView
        val callback = Runnable {
            if (currentId != transitionId) return@Runnable
            pendingTransitionCallback = null
            if (targetView.background === transition) {
                targetView.background = targetDrawable
                listener.onBackgroundApplied(targetDrawable)
                Log.d(TAG, "Transition completed: ${targetDrawable.javaClass.simpleName}")
            }
        }
        pendingTransitionCallback = callback
        targetView.postDelayed(callback, durationMs)

        Log.d(TAG, "Transition started (id=$currentId, qqplusActive=$isQQPlusActive): " +
                "${startDrawable.javaClass.simpleName} -> ${targetDrawable.javaClass.simpleName}")
    }

    private fun cancelPendingTransitionCallback() {
        pendingTransitionCallback?.let { cb ->
            transitionTargetView?.removeCallbacks(cb)
            Log.d(TAG, "Pending transition callback cancelled")
        }
        pendingTransitionCallback = null
    }

    fun resolveUseCustomForUnlock(currentUseCustom: Boolean): Boolean {
        if (SystemClock.elapsedRealtime() >= transitionEndAt) return currentUseCustom
        if (transitionSourceUseCustom && !transitionTargetUseCustom) return true
        return currentUseCustom
    }

    fun isTransitionInProgress(): Boolean = SystemClock.elapsedRealtime() < transitionEndAt
    fun isTransitioningFromCustomToDefault(): Boolean =
        isTransitionInProgress() && transitionSourceUseCustom && !transitionTargetUseCustom
    fun wasLastAppliedCustom(): Boolean = lastAppliedUseCustom

    fun cancelBackgroundTransition() {
        buttonColorAnimator?.cancel(); buttonColorAnimator = null
        cancelPendingTransitionCallback()
        transitionId++; transitionEndAt = 0L
    }

    // ===== 정리 =====

    fun cleanup() {
        recycleBitmaps()
        buttonColorAnimator?.cancel(); buttonColorAnimator = null
        cancelPendingTransitionCallback()
        transitionTargetView = null
    }
}
