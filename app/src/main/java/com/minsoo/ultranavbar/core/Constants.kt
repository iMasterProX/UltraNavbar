package com.minsoo.ultranavbar.core

import android.util.TypedValue
import android.content.Context

/**
 * 앱 전체에서 사용되는 상수 정의
 */
object Constants {

    // ===== 치수 관련 (dp) =====
    object Dimension {
        const val NAV_BUTTON_SIZE_DP = 48
        const val BUTTON_SPACING_DP = 8
        const val NAV_BAR_PADDING_DP = 16
        const val SWIPE_THRESHOLD_DP = 30
        const val DEFAULT_HOTSPOT_HEIGHT_DP = 24
        const val MIN_IME_HEIGHT_DP = 80
        const val MIN_NAV_BAR_HEIGHT_DP = 40
        const val CROP_HEIGHT_PX = 72
        const val TASKBAR_ICON_SIZE_DP = 36
        const val TASKBAR_ICON_SPACING_DP = 6
    }

    // ===== 타이밍 관련 (ms) =====
    object Timing {
        const val ANIMATION_DURATION_MS = 200L
        const val BG_TRANSITION_DURATION_MS = 350L
        const val GESTURE_AUTO_HIDE_MS = 3000L
        const val DARK_MODE_DEBOUNCE_MS = 1000L
        const val STATE_CHECK_DELAY_MS = 50L
        const val HOME_STATE_DEBOUNCE_MS = 350L  // 증가: 빠른 상태 토글 방지 (200 -> 350)
        const val RECENTS_STATE_DEBOUNCE_MS = 150L  // 증가: 빠른 상태 토글 방지 (100 -> 150)
        const val PANEL_CLOSE_DEBOUNCE_MS = 150L
        const val FULLSCREEN_POLLING_MS = 300L
        const val IME_VISIBILITY_DELAY_MS = 500L
        const val UNLOCK_FADE_DELAY_MS = 400L
        const val TRANSITION_DEDUP_GRACE_MS = 500L  // 로딩화면→앱 전환 시 중복 페이드 방지 유예 시간
        const val SPLIT_SCREEN_LAUNCH_DELAY_MS = 1000L
    }

    // ===== 비율 및 임계값 =====
    object Threshold {
        const val NAV_BAR_VISIBLE_RATIO = 0.7f
        const val GESTURE_ONLY_HEIGHT_DP = 24f
        const val BRIGHTNESS_THRESHOLD = 128.0
        const val LUMINANCE_SAMPLE_SIZE = 10
        const val NOTIFICATION_PANEL_MIN_HEIGHT_RATIO = 0.25f  // 화면 높이의 25% 이상이어야 패널로 판단
    }

    // ===== 회전 각도 =====
    object Rotation {
        const val PANEL_OPEN = 180f
        const val PANEL_CLOSED = 0f
        const val IME_ACTIVE = -90f
        const val IME_INACTIVE = 0f
    }

    // ===== 파일명 =====
    object FileName {
        const val LANDSCAPE_BG = "navbar_bg_landscape.png"
        const val PORTRAIT_BG = "navbar_bg_portrait.png"
    }

    // ===== 브로드캐스트 액션 =====
    object Action {
        const val SETTINGS_CHANGED = "com.minsoo.ultranavbar.SETTINGS_CHANGED"
        const val RELOAD_BACKGROUND = "com.minsoo.ultranavbar.RELOAD_BACKGROUND"
        const val UPDATE_BUTTON_COLORS = "com.minsoo.ultranavbar.UPDATE_BUTTON_COLORS"
    }
}

/**
 * dp를 px로 변환하는 확장 함수
 */
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
