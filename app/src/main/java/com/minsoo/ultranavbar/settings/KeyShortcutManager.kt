package com.minsoo.ultranavbar.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.minsoo.ultranavbar.model.KeyShortcut
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 키보드 단축키 관리자
 * SharedPreferences를 사용하여 단축키를 저장/로드
 */
class KeyShortcutManager private constructor(context: Context) {

    companion object {
        private const val TAG = "KeyShortcutManager"
        private const val PREFS_NAME = "keyboard_shortcuts"
        private const val KEY_SHORTCUTS = "shortcuts"

        @Volatile
        private var instance: KeyShortcutManager? = null

        fun getInstance(context: Context): KeyShortcutManager {
            return instance ?: synchronized(this) {
                instance ?: KeyShortcutManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 메모리 캐시
    private val shortcuts = mutableListOf<KeyShortcut>()
    private var loaded = false

    /**
     * 모든 단축키 로드
     */
    fun loadShortcuts(): List<KeyShortcut> {
        if (loaded) {
            return shortcuts.toList()
        }

        shortcuts.clear()
        val json = prefs.getString(KEY_SHORTCUTS, null)
        if (json != null) {
            try {
                val jsonArray = JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val shortcut = KeyShortcut.fromJson(jsonArray.getJSONObject(i))
                    shortcuts.add(shortcut)
                }
                Log.d(TAG, "Loaded ${shortcuts.size} shortcuts")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load shortcuts", e)
            }
        }

        loaded = true
        return shortcuts.toList()
    }

    /**
     * 단축키 저장
     */
    private fun saveShortcuts() {
        try {
            val jsonArray = JSONArray()
            shortcuts.forEach { shortcut ->
                jsonArray.put(shortcut.toJson())
            }
            prefs.edit()
                .putString(KEY_SHORTCUTS, jsonArray.toString())
                .apply()
            Log.d(TAG, "Saved ${shortcuts.size} shortcuts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save shortcuts", e)
        }
    }

    /**
     * 단축키 추가
     */
    fun addShortcut(shortcut: KeyShortcut) {
        loadShortcuts()
        shortcuts.add(shortcut)
        saveShortcuts()
    }

    /**
     * 단축키 업데이트
     */
    fun updateShortcut(id: String, updatedShortcut: KeyShortcut) {
        loadShortcuts()
        val index = shortcuts.indexOfFirst { it.id == id }
        if (index >= 0) {
            shortcuts[index] = updatedShortcut
            saveShortcuts()
        }
    }

    /**
     * 단축키 삭제
     */
    fun deleteShortcut(id: String) {
        loadShortcuts()
        shortcuts.removeAll { it.id == id }
        saveShortcuts()
    }

    /**
     * ID로 단축키 찾기
     */
    fun getShortcut(id: String): KeyShortcut? {
        loadShortcuts()
        return shortcuts.firstOrNull { it.id == id }
    }

    /**
     * 모든 단축키 가져오기
     */
    fun getAllShortcuts(): List<KeyShortcut> {
        return loadShortcuts()
    }

    /**
     * 고유 ID 생성
     */
    fun generateId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 키 조합으로 단축키 찾기
     */
    fun findShortcut(modifiers: Set<Int>, keyCode: Int): KeyShortcut? {
        loadShortcuts()
        Log.d(TAG, "findShortcut: looking for modifiers=$modifiers, keyCode=$keyCode in ${shortcuts.size} shortcuts")
        shortcuts.forEach { shortcut ->
            val matches = shortcut.matches(modifiers, keyCode)
            Log.d(TAG, "  Checking ${shortcut.name}: modifiers=${shortcut.modifiers}, keyCode=${shortcut.keyCode}, matches=$matches")
        }
        val result = shortcuts.firstOrNull { it.matches(modifiers, keyCode) }
        Log.d(TAG, "findShortcut: result=${result?.name ?: "null"}")
        return result
    }

    /**
     * 단축키 중복 확인
     */
    fun isDuplicate(modifiers: Set<Int>, keyCode: Int, excludeId: String? = null): Boolean {
        loadShortcuts()
        return shortcuts.any {
            it.matches(modifiers, keyCode) && (excludeId == null || it.id != excludeId)
        }
    }

    /**
     * 모든 단축키 삭제
     */
    fun clearAllShortcuts() {
        shortcuts.clear()
        prefs.edit().remove(KEY_SHORTCUTS).apply()
        Log.d(TAG, "Cleared all shortcuts")
    }
}
