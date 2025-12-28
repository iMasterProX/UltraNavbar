package com.minsoo.ultranavbar.settings

import android.content.Context
import android.content.SharedPreferences
import com.minsoo.ultranavbar.model.HideMode

/**
 * 앱 설정 관리자
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ultra_navbar_prefs"

        // 오버레이 설정
        private const val KEY_BAR_HEIGHT = "bar_height"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_BUTTON_SPACING = "button_spacing"
        private const val KEY_BAR_OPACITY = "bar_opacity"
        private const val KEY_BAR_COLOR = "bar_color"

        // 자동 숨김 설정
        private const val KEY_AUTO_HIDE_VIDEO = "auto_hide_video"
        private const val KEY_HIDE_MODE = "hide_mode"
        private const val KEY_APP_LIST = "app_list"

        // 재호출 설정
        private const val KEY_HOTSPOT_ENABLED = "hotspot_enabled"
        private const val KEY_HOTSPOT_HEIGHT = "hotspot_height"

        // 홈 화면 배경 이미지 설정
        private const val KEY_HOME_BG_ENABLED = "home_bg_enabled"
        private const val KEY_HOME_BG_LANDSCAPE = "home_bg_landscape"  // 가로 모드 이미지 파일명
        private const val KEY_HOME_BG_PORTRAIT = "home_bg_portrait"    // 세로 모드 이미지 파일명

        // 크롭 높이 (px)
        const val CROP_HEIGHT_PX = 72

        // 기본값 (LG 울트라탭 2000x1200 기준)
        const val DEFAULT_BAR_HEIGHT = 48          // dp
        const val DEFAULT_BUTTON_SIZE = 40         // dp
        const val DEFAULT_BUTTON_SPACING = 24      // dp
        const val DEFAULT_BAR_OPACITY = 0.85f
        const val DEFAULT_BAR_COLOR = 0xFF000000.toInt()  // 검정
        const val DEFAULT_HOTSPOT_HEIGHT = 8       // dp

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // 바 높이 (dp)
    var barHeight: Int
        get() = prefs.getInt(KEY_BAR_HEIGHT, DEFAULT_BAR_HEIGHT)
        set(value) = prefs.edit().putInt(KEY_BAR_HEIGHT, value).apply()

    // 버튼 크기 (dp)
    var buttonSize: Int
        get() = prefs.getInt(KEY_BUTTON_SIZE, DEFAULT_BUTTON_SIZE)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SIZE, value).apply()

    // 버튼 간격 (dp)
    var buttonSpacing: Int
        get() = prefs.getInt(KEY_BUTTON_SPACING, DEFAULT_BUTTON_SPACING)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SPACING, value).apply()

    // 바 투명도 (0.0 ~ 1.0)
    var barOpacity: Float
        get() = prefs.getFloat(KEY_BAR_OPACITY, DEFAULT_BAR_OPACITY)
        set(value) = prefs.edit().putFloat(KEY_BAR_OPACITY, value).apply()

    // 바 배경색 (ARGB)
    var barColor: Int
        get() = prefs.getInt(KEY_BAR_COLOR, DEFAULT_BAR_COLOR)
        set(value) = prefs.edit().putInt(KEY_BAR_COLOR, value).apply()

    // 영상 재생 시 자동 숨김
    var autoHideOnVideo: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_VIDEO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE_VIDEO, value).apply()

    // 숨김 모드
    var hideMode: HideMode
        get() = HideMode.valueOf(prefs.getString(KEY_HIDE_MODE, HideMode.BLACKLIST.name) ?: HideMode.BLACKLIST.name)
        set(value) = prefs.edit().putString(KEY_HIDE_MODE, value.name).apply()

    // 앱 목록 (패키지명 집합)
    var appList: Set<String>
        get() = prefs.getStringSet(KEY_APP_LIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_APP_LIST, value).apply()

    // 재호출 핫스팟 활성화
    var hotspotEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTSPOT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HOTSPOT_ENABLED, value).apply()

    // 재호출 핫스팟 높이 (dp)
    var hotspotHeight: Int
        get() = prefs.getInt(KEY_HOTSPOT_HEIGHT, DEFAULT_HOTSPOT_HEIGHT)
        set(value) = prefs.edit().putInt(KEY_HOTSPOT_HEIGHT, value).apply()

    // 홈 화면 배경 이미지 활성화
    var homeBgEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOME_BG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_BG_ENABLED, value).apply()

    // 가로 모드 배경 이미지 파일명
    var homeBgLandscape: String?
        get() = prefs.getString(KEY_HOME_BG_LANDSCAPE, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_LANDSCAPE, value).apply()

    // 세로 모드 배경 이미지 파일명
    var homeBgPortrait: String?
        get() = prefs.getString(KEY_HOME_BG_PORTRAIT, null)
        set(value) = prefs.edit().putString(KEY_HOME_BG_PORTRAIT, value).apply()

    /**
     * 해당 패키지에서 바를 숨겨야 하는지 판단
     */
    fun shouldHideForPackage(packageName: String): Boolean {
        val isInList = appList.contains(packageName)
        return when (hideMode) {
            HideMode.BLACKLIST -> isInList      // 블랙리스트: 목록에 있으면 숨김
            HideMode.WHITELIST -> !isInList     // 화이트리스트: 목록에 없으면 숨김
        }
    }

    /**
     * 앱 목록에 패키지 추가
     */
    fun addApp(packageName: String) {
        appList = appList + packageName
    }

    /**
     * 앱 목록에서 패키지 제거
     */
    fun removeApp(packageName: String) {
        appList = appList - packageName
    }
}
