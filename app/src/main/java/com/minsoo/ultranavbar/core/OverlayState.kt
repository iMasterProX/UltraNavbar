package com.minsoo.ultranavbar.core

/**
 * 오버레이의 상태를 정의하는 sealed class
 * 명확한 상태 전이를 보장
 */
sealed class OverlayState {
    /** 네비바가 완전히 표시된 상태 */
    data class Visible(
        val showMethod: ShowMethod = ShowMethod.NORMAL
    ) : OverlayState()

    /** 네비바가 숨겨지고 핫스팟만 표시된 상태 */
    object HiddenWithHotspot : OverlayState()

    /** 네비바와 핫스팟 모두 숨겨진 상태 (잠금화면 등) */
    object FullyHidden : OverlayState()

    /** 네비바가 표시되는 방법 */
    enum class ShowMethod {
        NORMAL,     // 일반 표시
        GESTURE,    // 제스처로 표시 (자동 숨김 적용)
        FADE        // 페이드 효과로 표시
    }
}

/**
 * 시스템 상태를 추적하는 데이터 클래스
 */
data class SystemState(
    val isOnHomeScreen: Boolean = false,
    val isRecentsVisible: Boolean = false,
    val isFullscreen: Boolean = false,
    val isLockScreen: Boolean = false,
    val isImeVisible: Boolean = false,
    val isNotificationPanelOpen: Boolean = false,
    val isWallpaperPreviewVisible: Boolean = false,
    val currentPackage: String = "",
    val orientation: Int = android.content.res.Configuration.ORIENTATION_UNDEFINED,
    val isDarkMode: Boolean = false
) {
    /**
     * 오버레이가 숨겨져야 하는지 판단
     */
    fun shouldHideOverlay(
        hideAppList: Set<String>,
        isBlacklistMode: Boolean
    ): Boolean {
        // 잠금화면에서는 항상 숨김
        if (isLockScreen) return true

        // 배경화면 미리보기에서는 숨김
        if (isWallpaperPreviewVisible) return true

        // 전체화면 모드에서는 숨김
        if (isFullscreen) return true

        // 앱별 숨김 설정
        return if (isBlacklistMode) {
            hideAppList.contains(currentPackage)
        } else {
            currentPackage.isNotEmpty() && !hideAppList.contains(currentPackage)
        }
    }

    /**
     * 커스텀 배경 이미지를 사용해야 하는지 판단
     */
    fun shouldUseCustomBackground(homeBgEnabled: Boolean): Boolean {
        return isOnHomeScreen && homeBgEnabled && !isRecentsVisible
    }
}

/**
 * 상태 변경 이벤트
 */
sealed class StateChangeEvent {
    data class HomeScreenChanged(val isHome: Boolean) : StateChangeEvent()
    data class RecentsChanged(val isVisible: Boolean) : StateChangeEvent()
    data class FullscreenChanged(val isFullscreen: Boolean) : StateChangeEvent()
    data class LockScreenChanged(val isLocked: Boolean) : StateChangeEvent()
    data class ImeChanged(val isVisible: Boolean) : StateChangeEvent()
    data class NotificationPanelChanged(val isOpen: Boolean) : StateChangeEvent()
    data class PackageChanged(val packageName: String) : StateChangeEvent()
    data class OrientationChanged(val orientation: Int) : StateChangeEvent()
    data class DarkModeChanged(val isDark: Boolean) : StateChangeEvent()
}
