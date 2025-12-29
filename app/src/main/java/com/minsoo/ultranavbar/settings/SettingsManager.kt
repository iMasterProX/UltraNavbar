package com.minsoo.ultranavbar.settings

import android.content.Context
import android.content.SharedPreferences
import com.minsoo.ultranavbar.model.HideMode

class SettingsManager private constructor(context: Context) {

    companion object {
        private const val PREF_NAME = "UltraNavbarSettings"
        
        // Keys
        private const val KEY_BAR_HEIGHT = "bar_height"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_AUTO_HIDE_VIDEO = "auto_hide_video"
        private const val KEY_HIDE_MODE = "hide_mode"
        private const val KEY_HOTSPOT_ENABLED = "hotspot_enabled"
        private const val KEY_HOTSPOT_HEIGHT = "hotspot_height"
        private const val KEY_HOME_BG_ENABLED = "home_bg_enabled"
        private const val KEY_IGNORE_STYLUS = "ignore_stylus" // 와콤 스타일러스 무시
        private const val KEY_LONG_PRESS_ACTION = "long_press_action" // 홈버튼 길게 누르기

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var barHeight: Int
        get() = prefs.getInt(KEY_BAR_HEIGHT, 48)
        set(value) = prefs.edit().putInt(KEY_BAR_HEIGHT, value).apply()

    var buttonSize: Int
        get() = prefs.getInt(KEY_BUTTON_SIZE, 48)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SIZE, value).apply()

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

    // 0: Assistant (Default), 1: Google App, etc.
    var longPressAction: Int
        get() = prefs.getInt(KEY_LONG_PRESS_ACTION, 0) 
        set(value) = prefs.edit().putInt(KEY_LONG_PRESS_ACTION, value).apply()

    // 패키지별 숨김 설정 (블랙리스트/화이트리스트용)
    private fun getPackageKey(packageName: String) = "pkg_$packageName"

    fun shouldHideForPackage(packageName: String): Boolean {
        // 간단한 예시 구현
        return false 
    }
}