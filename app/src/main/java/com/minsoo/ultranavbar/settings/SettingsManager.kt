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

        // Keyboard shortcuts
        private const val KEY_KEYBOARD_SHORTCUTS_ENABLED = "keyboard_shortcuts_enabled"

        // Keyboard orientation lock
        private const val KEY_KEYBOARD_ORIENTATION_LOCK = "keyboard_orientation_lock"
        private const val KEY_THIRD_PARTY_KEYBOARD_ACCEPTED = "third_party_keyboard_accepted"

        // Navigation button layout
        private const val KEY_NAV_BUTTONS_SWAPPED = "nav_buttons_swapped"

        // Recent Apps Taskbar
        private const val KEY_RECENT_APPS_TASKBAR_ENABLED = "recent_apps_taskbar_enabled"

        // Shizuku-based auto touch (coordinate)
        private const val KEY_SHIZUKU_AUTO_TOUCH_ENABLED = "shizuku_auto_touch_enabled"

        // Wacom Pen Settings
        private const val KEY_PEN_POINTER_ENABLED = "pen_pointer_enabled"
        private const val KEY_PEN_IGNORE_NAV_GESTURES = "pen_ignore_nav_gestures"
        private const val KEY_PEN_A_ACTION_TYPE = "pen_a_action_type"
        private const val KEY_PEN_B_ACTION_TYPE = "pen_b_action_type"
        private const val KEY_PEN_A_APP_PACKAGE = "pen_a_app_package"
        private const val KEY_PEN_A_APP_ACTIVITY = "pen_a_app_activity"
        private const val KEY_PEN_B_APP_PACKAGE = "pen_b_app_package"
        private const val KEY_PEN_B_APP_ACTIVITY = "pen_b_app_activity"
        private const val KEY_PEN_A_SHORTCUT_PACKAGE = "pen_a_shortcut_package"
        private const val KEY_PEN_A_SHORTCUT_ID = "pen_a_shortcut_id"
        private const val KEY_PEN_B_SHORTCUT_PACKAGE = "pen_b_shortcut_package"
        private const val KEY_PEN_B_SHORTCUT_ID = "pen_b_shortcut_id"
        private const val KEY_PEN_A_PAINT_FUNCTION = "pen_a_paint_function"
        private const val KEY_PEN_B_PAINT_FUNCTION = "pen_b_paint_function"

        // 터치 포인트 설정 (좌표 기반 - Shizuku 필요)
        private const val KEY_PEN_A_TOUCH_X = "pen_a_touch_x"
        private const val KEY_PEN_A_TOUCH_Y = "pen_a_touch_y"
        private const val KEY_PEN_B_TOUCH_X = "pen_b_touch_x"
        private const val KEY_PEN_B_TOUCH_Y = "pen_b_touch_y"

        // 노드 선택 설정 (접근성 노드 기반 - Shizuku 불필요)
        private const val KEY_PEN_A_NODE_ID = "pen_a_node_id"
        private const val KEY_PEN_A_NODE_TEXT = "pen_a_node_text"
        private const val KEY_PEN_A_NODE_CLASS = "pen_a_node_class"
        private const val KEY_PEN_A_NODE_DESC = "pen_a_node_desc"
        private const val KEY_PEN_A_NODE_PACKAGE = "pen_a_node_package"
        private const val KEY_PEN_B_NODE_ID = "pen_b_node_id"
        private const val KEY_PEN_B_NODE_TEXT = "pen_b_node_text"
        private const val KEY_PEN_B_NODE_CLASS = "pen_b_node_class"
        private const val KEY_PEN_B_NODE_DESC = "pen_b_node_desc"
        private const val KEY_PEN_B_NODE_PACKAGE = "pen_b_node_package"

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

    // 키보드 단축키 기능 활성화 여부
    var keyboardShortcutsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_SHORTCUTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_SHORTCUTS_ENABLED, value).apply()

    // 키보드 연결 시 화면 방향 고정 (0=끄기, 1=가로, 2=세로)
    var keyboardOrientationLock: Int
        get() = prefs.getInt(KEY_KEYBOARD_ORIENTATION_LOCK, 0)
        set(value) = prefs.edit().putInt(KEY_KEYBOARD_ORIENTATION_LOCK, value).apply()

    // 서드파티 키보드 사용 동의 여부
    var thirdPartyKeyboardAccepted: Boolean
        get() = prefs.getBoolean(KEY_THIRD_PARTY_KEYBOARD_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_THIRD_PARTY_KEYBOARD_ACCEPTED, value).apply()

    // 네비게이션 버튼 좌우 반전 (Android 12L 스타일)
    var navButtonsSwapped: Boolean
        get() = prefs.getBoolean(KEY_NAV_BUTTONS_SWAPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_NAV_BUTTONS_SWAPPED, value).apply()

    // 최근 앱 작업 표시줄
    var recentAppsTaskbarEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECENT_APPS_TASKBAR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RECENT_APPS_TASKBAR_ENABLED, value).apply()

    // Shizuku 기반 자동 터치 (좌표 기반)
    var shizukuAutoTouchEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_AUTO_TOUCH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHIZUKU_AUTO_TOUCH_ENABLED, value).apply()

    // Wacom 펜 포인터 표시
    var penPointerEnabled: Boolean
        get() = prefs.getBoolean(KEY_PEN_POINTER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PEN_POINTER_ENABLED, value).apply()

    // 펜 사용 시 네비바 제스처 무시
    var penIgnoreNavGestures: Boolean
        get() = prefs.getBoolean(KEY_PEN_IGNORE_NAV_GESTURES, false)
        set(value) = prefs.edit().putBoolean(KEY_PEN_IGNORE_NAV_GESTURES, value).apply()

    // 펜 버튼 A 액션 타입
    var penAActionType: String
        get() = prefs.getString(KEY_PEN_A_ACTION_TYPE, "NONE") ?: "NONE"
        set(value) = prefs.edit().putString(KEY_PEN_A_ACTION_TYPE, value).apply()

    // 펜 버튼 B 액션 타입
    var penBActionType: String
        get() = prefs.getString(KEY_PEN_B_ACTION_TYPE, "NONE") ?: "NONE"
        set(value) = prefs.edit().putString(KEY_PEN_B_ACTION_TYPE, value).apply()

    // 펜 버튼 A - 앱 패키지
    var penAAppPackage: String?
        get() = prefs.getString(KEY_PEN_A_APP_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_APP_PACKAGE, value).apply()

    // 펜 버튼 A - 앱 액티비티
    var penAAppActivity: String?
        get() = prefs.getString(KEY_PEN_A_APP_ACTIVITY, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_APP_ACTIVITY, value).apply()

    // 펜 버튼 B - 앱 패키지
    var penBAppPackage: String?
        get() = prefs.getString(KEY_PEN_B_APP_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_APP_PACKAGE, value).apply()

    // 펜 버튼 B - 앱 액티비티
    var penBAppActivity: String?
        get() = prefs.getString(KEY_PEN_B_APP_ACTIVITY, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_APP_ACTIVITY, value).apply()

    // 펜 버튼 A - 바로가기 패키지
    var penAShortcutPackage: String?
        get() = prefs.getString(KEY_PEN_A_SHORTCUT_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_SHORTCUT_PACKAGE, value).apply()

    // 펜 버튼 A - 바로가기 ID
    var penAShortcutId: String?
        get() = prefs.getString(KEY_PEN_A_SHORTCUT_ID, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_SHORTCUT_ID, value).apply()

    // 펜 버튼 B - 바로가기 패키지
    var penBShortcutPackage: String?
        get() = prefs.getString(KEY_PEN_B_SHORTCUT_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_SHORTCUT_PACKAGE, value).apply()

    // 펜 버튼 B - 바로가기 ID
    var penBShortcutId: String?
        get() = prefs.getString(KEY_PEN_B_SHORTCUT_ID, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_SHORTCUT_ID, value).apply()

    // 펜 버튼 A - 페인팅 기능
    var penAPaintFunction: String?
        get() = prefs.getString(KEY_PEN_A_PAINT_FUNCTION, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_PAINT_FUNCTION, value).apply()

    // 펜 버튼 B - 페인팅 기능
    var penBPaintFunction: String?
        get() = prefs.getString(KEY_PEN_B_PAINT_FUNCTION, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_PAINT_FUNCTION, value).apply()

    // 펜 버튼 A - 터치 포인트 좌표
    var penATouchX: Float
        get() = prefs.getFloat(KEY_PEN_A_TOUCH_X, -1f)
        set(value) = prefs.edit().putFloat(KEY_PEN_A_TOUCH_X, value).apply()

    var penATouchY: Float
        get() = prefs.getFloat(KEY_PEN_A_TOUCH_Y, -1f)
        set(value) = prefs.edit().putFloat(KEY_PEN_A_TOUCH_Y, value).apply()

    // 펜 버튼 B - 터치 포인트 좌표
    var penBTouchX: Float
        get() = prefs.getFloat(KEY_PEN_B_TOUCH_X, -1f)
        set(value) = prefs.edit().putFloat(KEY_PEN_B_TOUCH_X, value).apply()

    var penBTouchY: Float
        get() = prefs.getFloat(KEY_PEN_B_TOUCH_Y, -1f)
        set(value) = prefs.edit().putFloat(KEY_PEN_B_TOUCH_Y, value).apply()

    // 펜 버튼 A - 노드 선택 정보
    var penANodeId: String?
        get() = prefs.getString(KEY_PEN_A_NODE_ID, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_NODE_ID, value).apply()

    var penANodeText: String?
        get() = prefs.getString(KEY_PEN_A_NODE_TEXT, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_NODE_TEXT, value).apply()

    var penANodeClass: String?
        get() = prefs.getString(KEY_PEN_A_NODE_CLASS, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_NODE_CLASS, value).apply()

    var penANodeDesc: String?
        get() = prefs.getString(KEY_PEN_A_NODE_DESC, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_NODE_DESC, value).apply()

    var penANodePackage: String?
        get() = prefs.getString(KEY_PEN_A_NODE_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_A_NODE_PACKAGE, value).apply()

    // 펜 버튼 B - 노드 선택 정보
    var penBNodeId: String?
        get() = prefs.getString(KEY_PEN_B_NODE_ID, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_NODE_ID, value).apply()

    var penBNodeText: String?
        get() = prefs.getString(KEY_PEN_B_NODE_TEXT, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_NODE_TEXT, value).apply()

    var penBNodeClass: String?
        get() = prefs.getString(KEY_PEN_B_NODE_CLASS, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_NODE_CLASS, value).apply()

    var penBNodeDesc: String?
        get() = prefs.getString(KEY_PEN_B_NODE_DESC, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_NODE_DESC, value).apply()

    var penBNodePackage: String?
        get() = prefs.getString(KEY_PEN_B_NODE_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PEN_B_NODE_PACKAGE, value).apply()

    /**
     * 노드 정보가 설정되어 있는지 확인
     */
    fun hasNodeInfo(button: String): Boolean {
        return if (button == "A") {
            !penANodeId.isNullOrEmpty() || !penANodeText.isNullOrEmpty() || !penANodeDesc.isNullOrEmpty()
        } else {
            !penBNodeId.isNullOrEmpty() || !penBNodeText.isNullOrEmpty() || !penBNodeDesc.isNullOrEmpty()
        }
    }

    /**
     * 터치 포인트가 설정되어 있는지 확인
     */
    fun hasTouchPoint(button: String): Boolean {
        return if (button == "A") {
            penATouchX >= 0 && penATouchY >= 0
        } else {
            penBTouchX >= 0 && penBTouchY >= 0
        }
    }

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

    /**
     * 펜 버튼 커스텀 기능이 활성화되어 있는지 확인
     * 버튼 A 또는 B 중 하나라도 NONE이 아니면 활성화된 것으로 간주
     */
    fun isPenCustomFunctionEnabled(): Boolean {
        return penAActionType != "NONE" || penBActionType != "NONE"
    }
}
