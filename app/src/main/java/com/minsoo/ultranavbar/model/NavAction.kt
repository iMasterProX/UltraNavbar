package com.minsoo.ultranavbar.model

import android.accessibilityservice.AccessibilityService
import android.os.Build

/**
 * 네비게이션 바 버튼 액션 정의
 */
enum class NavAction(
    val displayName: String,
    val globalActionId: Int?
) {
    BACK("Back", AccessibilityService.GLOBAL_ACTION_BACK),
    HOME("Home", AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS("Recents", AccessibilityService.GLOBAL_ACTION_RECENTS),

    // 알림 패널 내리기(열기)
    NOTIFICATIONS("Notification shade", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),

    // 알림 패널 올리기(닫기) - Android 12(API 31)+
    DISMISS_NOTIFICATION_SHADE(
        "Close notification shade",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        else null
    ),

    QUICK_SETTINGS("Quick settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    POWER_DIALOG("Power menu", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
    LOCK_SCREEN("Lock screen", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    TAKE_SCREENSHOT("Screenshot", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),

    ASSIST("Assistant", null),
    NONE("None", null);

    companion object {
        fun fromName(name: String): NavAction =
            entries.find { it.name == name } ?: NONE
    }
}
