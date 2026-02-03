package com.minsoo.ultranavbar.model

import android.view.KeyEvent

/**
 * LG UltraTab 시스템 예약 단축키 목록
 * 사용자 정의 단축키가 시스템 단축키와 충돌하지 않도록 관리
 */
object ReservedShortcuts {

    data class Reserved(
        val modifiers: Set<Int>,
        val keyCode: Int,
        val description: String,
        val descriptionEn: String
    )

    /**
     * 시스템 예약 단축키 목록
     * key1.png, key2.png를 기반으로 작성됨
     */
    val list = listOf(
        // Ctrl 조합
        Reserved(
            setOf(KeyEvent.KEYCODE_CTRL_LEFT),
            KeyEvent.KEYCODE_A,
            "앱 서랍",
            "App Drawer"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_CTRL_LEFT),
            KeyEvent.KEYCODE_W,
            "위젯",
            "Widgets"
        ),

        // Search (Meta) 조합
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_DPAD_LEFT,
            "홈",
            "Home"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_DEL,
            "뒤로",
            "Back"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_N,
            "알림",
            "Notifications"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_SLASH,
            "단축키 목록",
            "Shortcuts List"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_SPACE,
            "키보드 레이아웃 전환",
            "Keyboard Layout"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_B,
            "브라우저",
            "Browser"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_P,
            "음악",
            "Music"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_E,
            "이메일",
            "Email"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_C,
            "주소록",
            "Contacts"
        ),
        Reserved(
            setOf(KeyEvent.KEYCODE_META_LEFT),
            KeyEvent.KEYCODE_L,
            "캘린더",
            "Calendar"
        ),

        // Alt 조합
        Reserved(
            setOf(KeyEvent.KEYCODE_ALT_LEFT),
            KeyEvent.KEYCODE_TAB,
            "최근 앱",
            "Recent Apps"
        )
    )

    /**
     * 주어진 modifier와 keyCode 조합이 시스템 예약 단축키인지 확인
     */
    fun isReserved(modifiers: Set<Int>, keyCode: Int): Boolean {
        // modifier 정규화 (LEFT/RIGHT를 LEFT로 통일)
        val normalizedModifiers = modifiers.map { normalizeModifier(it) }.toSet()

        return list.any { reserved ->
            reserved.modifiers == normalizedModifiers && reserved.keyCode == keyCode
        }
    }

    /**
     * 주어진 조합에 해당하는 시스템 단축키 설명 반환
     */
    fun getDescription(modifiers: Set<Int>, keyCode: Int, useEnglish: Boolean = false): String? {
        val normalizedModifiers = modifiers.map { normalizeModifier(it) }.toSet()

        val reserved = list.find {
            it.modifiers == normalizedModifiers && it.keyCode == keyCode
        }

        return if (useEnglish) reserved?.descriptionEn else reserved?.description
    }

    /**
     * Modifier 키를 LEFT 버전으로 정규화
     * (CTRL_RIGHT -> CTRL_LEFT, SHIFT_RIGHT -> SHIFT_LEFT 등)
     */
    private fun normalizeModifier(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
            else -> keyCode
        }
    }

    /**
     * 모든 예약 단축키 목록 반환 (UI 표시용)
     */
    fun getAllReservedShortcuts(useEnglish: Boolean = false): List<Pair<String, String>> {
        return list.map { reserved ->
            val keyCombo = buildString {
                if (reserved.modifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT)) {
                    append("Ctrl + ")
                }
                if (reserved.modifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) {
                    append("Shift + ")
                }
                if (reserved.modifiers.contains(KeyEvent.KEYCODE_ALT_LEFT)) {
                    append("Alt + ")
                }
                if (reserved.modifiers.contains(KeyEvent.KEYCODE_META_LEFT)) {
                    append("Search + ")
                }
                append(getKeyName(reserved.keyCode))
            }

            val description = if (useEnglish) reserved.descriptionEn else reserved.description
            keyCombo to description
        }
    }

    /**
     * KeyCode를 사람이 읽을 수 있는 문자열로 변환
     */
    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_SLASH -> "/"
            else -> "Key$keyCode"
        }
    }
}
