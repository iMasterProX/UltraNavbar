package com.minsoo.ultranavbar.core


sealed class OverlayState {
    
    data class Visible(
        val showMethod: ShowMethod = ShowMethod.NORMAL
    ) : OverlayState()

    
    object HiddenWithHotspot : OverlayState()

    
    object FullyHidden : OverlayState()

    
    enum class ShowMethod {
        NORMAL,     
        GESTURE,    
        FADE        
    }
}


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
    
    fun shouldHideOverlay(
        hideAppList: Set<String>,
        isBlacklistMode: Boolean
    ): Boolean {
        
        if (isLockScreen) return true

        
        if (isWallpaperPreviewVisible) return true

        
        if (isFullscreen) return true

        
        return if (isBlacklistMode) {
            hideAppList.contains(currentPackage)
        } else {
            currentPackage.isNotEmpty() && !hideAppList.contains(currentPackage)
        }
    }

    
    fun shouldUseCustomBackground(homeBgEnabled: Boolean): Boolean {
        return isOnHomeScreen && homeBgEnabled && !isRecentsVisible
    }
}


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
