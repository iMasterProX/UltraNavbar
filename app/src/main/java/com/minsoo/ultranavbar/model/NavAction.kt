package com.minsoo.ultranavbar.model

import android.accessibilityservice.AccessibilityService

/**
 * 네비게이션 바 버튼 액션 정의
 */
enum class NavAction(
    val displayName: String,
    val globalActionId: Int?
) {
    BACK("뒤로가기", AccessibilityService.GLOBAL_ACTION_BACK),
    HOME("홈", AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS("최근 앱", AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS("알림 패널", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS("빠른 설정", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    POWER_DIALOG("전원 메뉴", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
    LOCK_SCREEN("화면 잠금", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    TAKE_SCREENSHOT("스크린샷", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    ASSIST("어시스턴트", null),
    NONE("없음", null);

    companion object {
        fun fromName(name: String): NavAction {
            return entries.find { it.name == name } ?: NONE
        }
    }
}

/**
 * 숨김 모드 정의
 */
enum class HideMode {
    BLACKLIST,  // 선택한 앱에서만 숨김
    WHITELIST   // 선택한 앱에서만 표시
}
