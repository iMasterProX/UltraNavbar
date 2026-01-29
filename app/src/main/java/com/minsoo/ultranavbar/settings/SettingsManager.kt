package com.minsoo.ultranavbar.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsManager private constructor(context: Context) {

    enum class HomeBgButtonColorMode {
        AUTO,
        WHITE,
        BLACK
    }

    companion object {
        private const val PREF_NAME = "UltraNavbarSettings"
        const val CROP_HEIGHT_PX = 72 // 배경 크롭 높이 (px)

        // Keys
        private const val KEY_NAVBAR_ENABLED = "navbar_enabled"
        private const val KEY_HOTSPOT_ENABLED = "hotspot_enabled"
        private const val KEY_HOTSPOT_HEIGHT = "hotspot_height"
        private const val KEY_HOME_BG_ENABLED = "home_bg_enabled"
        private const val KEY_HOME_BG_BUTTON_COLOR_MODE = "home_bg_button_color_mode"
        private const val KEY_IGNORE_STYLUS = "ignore_stylus"
        private const val KEY_LONG_PRESS_ACTION = "long_press_action"
        private const val KEY_SHORTCUT_NAME = "shortcut_name"
        private const val KEY_BATTERY_OPT_REQUESTED = "battery_opt_requested"
        private const val KEY_PREVIEW_FILTER_OPACITY = "preview_filter_opacity"
        private const val KEY_DISABLED_APPS = "disabled_apps" // 비활성화할 앱 목록 (패키지명 set)
        private const val KEY_SHORTCUT_DISABLED_APPS = "shortcut_disabled_apps" // 키보드 단축키 비활성화 앱 목록

        // Background Image Filenames (Stored in prefs to track validity, though file existence is primary check)
        private const val KEY_HOME_BG_LANDSCAPE = "home_bg_landscape"
        private const val KEY_HOME_BG_PORTRAIT = "home_bg_portrait"

        // Dark Mode Background Image Filenames
        private const val KEY_HOME_BG_DARK_LANDSCAPE = "home_bg_dark_landscape"
        private const val KEY_HOME_BG_DARK_PORTRAIT = "home_bg_dark_portrait"
        private const val KEY_HOME_BG_DARK_ENABLED = "home_bg_dark_enabled"

        // Setup wizard completion
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        // Battery notification
        private const val KEY_BATTERY_NOTIFICATION_ENABLED = "battery_notification_enabled"
        private const val KEY_BATTERY_LOW_THRESHOLD = "battery_low_threshold"
        private const val KEY_BATTERY_PERSISTENT_NOTIFICATION = "battery_persistent_notification"

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var navbarEnabled: Boolean
        get() = prefs.getBoolean(KEY_NAVBAR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NAVBAR_ENABLED, value).apply()

    var hotspotEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTSPOT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HOTSPOT_ENABLED, value).apply()

    var hotspotHeight: Int
        get() = prefs.getInt(KEY_HOTSPOT_HEIGHT, 16)
        set(value) = prefs.edit().putInt(KEY_HOTSPOT_HEIGHT, value).apply()

    var homeBgEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOME_BG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_BG_ENABLED, value).apply()

    var homeBgButtonColorMode: HomeBgButtonColorMode
        get() {
            val stored = prefs.getString(
                KEY_HOME_BG_BUTTON_COLOR_MODE,
                HomeBgButtonColorMode.AUTO.name
            ) ?: HomeBgButtonColorMode.AUTO.name
            return try {
                HomeBgButtonColorMode.valueOf(stored)
            } catch (e: IllegalArgumentException) {
                HomeBgButtonColorMode.AUTO
            }
        }
        set(value) = prefs.edit().putString(KEY_HOME_BG_BUTTON_COLOR_MODE, value.name).apply()

    var ignoreStylus: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_STYLUS, false)
        set(value) = prefs.edit().putBoolean(KEY_IGNORE_STYLUS, value).apply()

    var longPressAction: String?
        get() = prefs.getString(KEY_LONG_PRESS_ACTION, null)
        set(value) = prefs.edit().putString(KEY_LONG_PRESS_ACTION, value).apply()

    var shortcutName: String?
        get() = prefs.getString(KEY_SHORTCUT_NAME, null)
        set(value) = prefs.edit().putString(KEY_SHORTCUT_NAME, value).apply()

    var batteryOptRequested: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPT_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, value).apply()

    var previewFilterOpacity: Int
        get() = prefs.getInt(KEY_PREVIEW_FILTER_OPACITY, 13)
        set(value) = prefs.edit().putInt(KEY_PREVIEW_FILTER_OPACITY, value.coerceIn(0, 100)).apply()

    // 커스텀 네비바를 비활성화할 앱 목록
    var disabledApps: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISABLED_APPS, value).apply()

    // 키보드 단축키를 비활성화할 앱 목록
    var shortcutDisabledApps: Set<String>
        get() = prefs.getStringSet(KEY_SHORTCUT_DISABLED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SHORTCUT_DISABLED_APPS, value).apply()

    // 배경 이미지 설정 여부/경로 (실제 로딩은 ImageCropUtil에서 파일 유무로 판단하지만 설정값 유지용)
    var homeBgLandscape: String?
        get() = prefs.getString(KEY_HOME_BG_LANDSCAPE, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_LANDSCAPE, value).apply()

    var homeBgPortrait: String?
        get() = prefs.getString(KEY_HOME_BG_PORTRAIT, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_PORTRAIT, value).apply()

    // 다크 모드 전용 배경 이미지 설정
    var homeBgDarkEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOME_BG_DARK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_BG_DARK_ENABLED, value).apply()

    var homeBgDarkLandscape: String?
        get() = prefs.getString(KEY_HOME_BG_DARK_LANDSCAPE, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_DARK_LANDSCAPE, value).apply()

    var homeBgDarkPortrait: String?
        get() = prefs.getString(KEY_HOME_BG_DARK_PORTRAIT, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_DARK_PORTRAIT, value).apply()

    // 초기 설정 완료 여부
    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var batteryNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_NOTIFICATION_ENABLED, value).apply()

    var batteryLowThreshold: Int
        get() = prefs.getInt(KEY_BATTERY_LOW_THRESHOLD, 20)
        set(value) = prefs.edit().putInt(KEY_BATTERY_LOW_THRESHOLD, value.coerceIn(5, 50)).apply()

    var batteryPersistentNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PERSISTENT_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PERSISTENT_NOTIFICATION, value).apply()

    /**
     * 해당 패키지에서 커스텀 네비바를 비활성화해야 하는지 확인
     */
    fun isAppDisabled(packageName: String): Boolean {
        return disabledApps.contains(packageName)
    }

    /**
     * 해당 패키지에서 키보드 단축키를 비활성화해야 하는지 확인
     */
    fun isShortcutDisabledForApp(packageName: String): Boolean {
        return shortcutDisabledApps.contains(packageName)
    }
}
