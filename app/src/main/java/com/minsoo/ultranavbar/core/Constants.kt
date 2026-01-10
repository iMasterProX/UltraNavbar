package com.minsoo.ultranavbar.core

import android.util.TypedValue
import android.content.Context


object Constants {

    
    object Dimension {
        const val NAV_BUTTON_SIZE_DP = 48
        const val BUTTON_SPACING_DP = 8
        const val NAV_BAR_PADDING_DP = 16
        const val SWIPE_THRESHOLD_DP = 30
        const val DEFAULT_HOTSPOT_HEIGHT_DP = 24
        const val MIN_IME_HEIGHT_DP = 80
        const val MIN_NAV_BAR_HEIGHT_DP = 40
        const val MIN_PANEL_HEIGHT_DP = 100
        const val CROP_HEIGHT_PX = 72
    }

    
    object Timing {
        const val ANIMATION_DURATION_MS = 200L
        const val BG_TRANSITION_DURATION_MS = 350L
        const val GESTURE_AUTO_HIDE_MS = 3000L
        const val DARK_MODE_DEBOUNCE_MS = 1000L
        const val STATE_CHECK_DELAY_MS = 50L
        const val UNLOCK_FADE_WINDOW_MS = 1500L
        const val HOME_STATE_DEBOUNCE_MS = 350L  
        const val RECENTS_STATE_DEBOUNCE_MS = 150L  
        const val FULLSCREEN_POLLING_MS = 300L
        const val IME_VISIBILITY_DELAY_MS = 500L
    }

    
    object Threshold {
        const val NAV_BAR_VISIBLE_RATIO = 0.7f
        const val GESTURE_ONLY_HEIGHT_DP = 24f
        const val BRIGHTNESS_THRESHOLD = 128.0
        const val LUMINANCE_SAMPLE_SIZE = 10
    }

    
    object Rotation {
        const val PANEL_OPEN = 180f
        const val PANEL_CLOSED = 0f
        const val IME_ACTIVE = -90f
        const val IME_INACTIVE = 0f
    }

    
    object FileName {
        const val LANDSCAPE_BG = "navbar_bg_landscape.png"
        const val PORTRAIT_BG = "navbar_bg_portrait.png"
    }

    
    object Action {
        const val SETTINGS_CHANGED = "com.minsoo.ultranavbar.SETTINGS_CHANGED"
        const val RELOAD_BACKGROUND = "com.minsoo.ultranavbar.RELOAD_BACKGROUND"
    }
}


fun Context.dpToPx(dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()
}

fun Context.dpToPx(dp: Float): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        resources.displayMetrics
    ).toInt()
}
