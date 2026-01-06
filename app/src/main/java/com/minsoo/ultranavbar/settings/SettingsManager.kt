package com.minsoo.ultranavbar.settings

import android.content.Context
import android.content.SharedPreferences
import com.minsoo.ultranavbar.model.HideMode

class SettingsManager private constructor(context: Context) {

    companion object {
        private const val PREF_NAME = "UltraNavbarSettings"
        const val CROP_HEIGHT_PX = 72 // 배경 크롭 높이 (px)

        // Keys
        private const val KEY_AUTO_HIDE_VIDEO = "auto_hide_video"
        private const val KEY_HIDE_MODE = "hide_mode"
        private const val KEY_HOTSPOT_ENABLED = "hotspot_enabled"
        private const val KEY_HOTSPOT_HEIGHT = "hotspot_height"
        private const val KEY_HOME_BG_ENABLED = "home_bg_enabled"
        private const val KEY_IGNORE_STYLUS = "ignore_stylus"
        private const val KEY_LONG_PRESS_ACTION = "long_press_action"
        private const val KEY_SHORTCUT_NAME = "shortcut_name"
        private const val KEY_BATTERY_OPT_REQUESTED = "battery_opt_requested"
        private const val KEY_PREVIEW_FILTER_OPACITY = "preview_filter_opacity"
        private const val KEY_APP_LIST = "app_list" // 저장된 앱 목록 (패키지명 set)
        
        // Background Image Filenames (Stored in prefs to track validity, though file existence is primary check)
        private const val KEY_HOME_BG_LANDSCAPE = "home_bg_landscape"
        private const val KEY_HOME_BG_PORTRAIT = "home_bg_portrait"

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var autoHideOnVideo: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_VIDEO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE_VIDEO, value).apply()

    var hideMode: HideMode
        get() {
            val name = prefs.getString(KEY_HIDE_MODE, HideMode.BLACKLIST.name)
            return try {
                HideMode.valueOf(name!!)
            } catch (e: Exception) {
                HideMode.BLACKLIST
            }
        }
        set(value) = prefs.edit().putString(KEY_HIDE_MODE, value.name).apply()

    var hotspotEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTSPOT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HOTSPOT_ENABLED, value).apply()

    var hotspotHeight: Int
        get() = prefs.getInt(KEY_HOTSPOT_HEIGHT, 16)
        set(value) = prefs.edit().putInt(KEY_HOTSPOT_HEIGHT, value).apply()

    var homeBgEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOME_BG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_BG_ENABLED, value).apply()
        
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
        
    // 앱 목록 (블랙리스트/화이트리스트용)
    var appList: Set<String>
        get() = prefs.getStringSet(KEY_APP_LIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_APP_LIST, value).apply()

    // 배경 이미지 설정 여부/경로 (실제 로딩은 ImageCropUtil에서 파일 유무로 판단하지만 설정값 유지용)
    var homeBgLandscape: String?
        get() = prefs.getString(KEY_HOME_BG_LANDSCAPE, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_LANDSCAPE, value).apply()

    var homeBgPortrait: String?
        get() = prefs.getString(KEY_HOME_BG_PORTRAIT, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_PORTRAIT, value).apply()

    fun shouldHideForPackage(packageName: String): Boolean {
        // HideMode에 따라 appList에 포함되었는지 확인
        val inList = appList.contains(packageName)
        return when (hideMode) {
            HideMode.BLACKLIST -> inList // 블랙리스트 모드면 리스트에 있을 때 숨김
            HideMode.WHITELIST -> !inList // 화이트리스트 모드면 리스트에 없을 때 숨김
        }
    }
}
