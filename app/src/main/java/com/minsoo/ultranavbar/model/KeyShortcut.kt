package com.minsoo.ultranavbar.model

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 키보드 단축키 데이터 모델
 */
data class KeyShortcut(
    val id: String,  // 고유 ID
    val name: String,  // 사용자 지정 이름
    val modifiers: Set<Int>,  // Ctrl, Shift, Alt, Meta 등
    val keyCode: Int,  // 주요 키 코드
    val actionType: ActionType,  // 액션 타입
    val actionData: String  // 액션 데이터 (패키지명, URI, 설정 액션 등)
) {
    enum class ActionType {
        APP,  // 앱 실행
        SHORTCUT,  // 바로가기 실행
        SETTINGS,  // 시스템 설정 열기
        CUSTOM_ACTION  // 커스텀 액션 (향후 확장용)
    }

    companion object {
        // 수정자 키 상수
        const val MODIFIER_CTRL = KeyEvent.KEYCODE_CTRL_LEFT
        const val MODIFIER_SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT
        const val MODIFIER_ALT = KeyEvent.KEYCODE_ALT_LEFT
        const val MODIFIER_META = KeyEvent.KEYCODE_META_LEFT

        /**
         * JSON에서 KeyShortcut 생성
         */
        fun fromJson(json: JSONObject): KeyShortcut {
            val modifiersArray = json.getJSONArray("modifiers")
            val modifiers = mutableSetOf<Int>()
            for (i in 0 until modifiersArray.length()) {
                modifiers.add(modifiersArray.getInt(i))
            }

            return KeyShortcut(
                id = json.getString("id"),
                name = json.getString("name"),
                modifiers = modifiers,
                keyCode = json.getInt("keyCode"),
                actionType = ActionType.valueOf(json.getString("actionType")),
                actionData = json.getString("actionData")
            )
        }
    }

    /**
     * KeyShortcut을 JSON으로 변환
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("modifiers", JSONArray(modifiers.toList()))
            put("keyCode", keyCode)
            put("actionType", actionType.name)
            put("actionData", actionData)
        }
    }

    /**
     * 단축키 문자열 표현 (예: "Ctrl + Shift + 1")
     */
    fun getDisplayString(): String {
        val parts = mutableListOf<String>()

        if (MODIFIER_CTRL in modifiers) parts.add("Ctrl")
        if (MODIFIER_SHIFT in modifiers) parts.add("Shift")
        if (MODIFIER_ALT in modifiers) parts.add("Alt")
        if (MODIFIER_META in modifiers) parts.add("Meta")

        // 키 이름 변환
        val keyName = getKeyName(keyCode)
        parts.add(keyName)

        return parts.joinToString(" + ")
    }

    /**
     * 키 코드를 읽기 쉬운 이름으로 변환
     */
    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_DEL -> "Delete"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Del"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            else -> "Key$keyCode"
        }
    }

    /**
     * 현재 눌린 키들이 이 단축키와 일치하는지 확인
     */
    fun matches(pressedModifiers: Set<Int>, pressedKeyCode: Int): Boolean {
        return modifiers == pressedModifiers && keyCode == pressedKeyCode
    }
}
