package com.minsoo.ultranavbar.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.minsoo.ultranavbar.model.KeyShortcut
import com.minsoo.ultranavbar.settings.KeyShortcutManager
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 키보드 이벤트 처리 헬퍼
 * AccessibilityService에서 키 이벤트를 감지하고 단축키를 실행
 */
class KeyEventHandler(private val context: Context) {

    companion object {
        private const val TAG = "KeyEventHandler"
    }

    private val shortcutManager = KeyShortcutManager.getInstance(context)
    private val settingsManager = SettingsManager.getInstance(context)

    // 현재 눌려있는 수정자 키 추적
    private val pressedModifiers = mutableSetOf<Int>()

    // 길게 누름 감지용 타임스탬프 맵 (keyCode -> pressTime)
    private val keyPressTimestamps = mutableMapOf<Int, Long>()

    // IME (키보드) 표시 상태
    private var isImeVisible: Boolean = false

    /**
     * IME 표시 상태 업데이트
     */
    fun setImeVisible(visible: Boolean) {
        isImeVisible = visible
    }

    /**
     * 키 이벤트 처리
     * @return true: 이벤트 소비, false: 이벤트 전파
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        // 키보드 단축키 기능이 비활성화되어 있으면 이벤트 전파
        if (!settingsManager.keyboardShortcutsEnabled) {
            return false
        }

        // 실제 KeyEvent의 메타 상태와 내부 추적 상태가 다르면 동기화
        // (앱 전환, 포커스 손실 등으로 modifier가 stuck 될 수 있음)
        syncModifiersWithEvent(event)

        // 텍스트 입력 중 (IME 표시 중)에는 단축키 비활성화
        if (isImeVisible) {
            Log.d(TAG, "IME is visible, shortcuts disabled")
            return false
        }

        val keyCode = event.keyCode
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"

        Log.d(TAG, "Key event: keyCode=$keyCode, action=$action, modifiers=$pressedModifiers")

        // 수정자 키 추적 (좌/우 정규화)
        if (isModifierKey(keyCode)) {
            val normalizedKey = normalizeModifier(keyCode)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressedModifiers.add(normalizedKey)
                    Log.d(TAG, "Modifier pressed: $normalizedKey, current modifiers: $pressedModifiers")
                }
                KeyEvent.ACTION_UP -> {
                    pressedModifiers.remove(normalizedKey)
                    Log.d(TAG, "Modifier released: $normalizedKey, current modifiers: $pressedModifiers")
                }
            }
            // 수정자 키 자체는 전파
            return false
        }

        // 일반 키 처리
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 키 눌림 시간 기록
                keyPressTimestamps[keyCode] = System.currentTimeMillis()

                // 일반 누름 단축키 확인 (modifier + 키 조합)
                if (pressedModifiers.isNotEmpty()) {
                    Log.d(TAG, "Searching for normal press shortcut with modifiers=$pressedModifiers, keyCode=$keyCode")
                    val normalShortcut = shortcutManager.findShortcut(pressedModifiers.toSet(), keyCode, isLongPress = false)
                    if (normalShortcut != null) {
                        Log.d(TAG, "Normal press shortcut matched: ${normalShortcut.name} (${normalShortcut.getDisplayString()})")
                        executeShortcut(normalShortcut)
                        return true  // 이벤트 소비
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                // 키를 누르고 있었던 시간 계산
                val pressTime = keyPressTimestamps.remove(keyCode)
                if (pressTime != null) {
                    val holdDuration = System.currentTimeMillis() - pressTime

                    // 길게 누름 단축키 확인 (단일 키만, modifier 없이)
                    if (pressedModifiers.isEmpty()) {
                        Log.d(TAG, "Searching for long press shortcut with keyCode=$keyCode, holdDuration=$holdDuration")
                        val longPressShortcut = shortcutManager.findShortcut(emptySet(), keyCode, isLongPress = true)

                        if (longPressShortcut != null && holdDuration >= longPressShortcut.longPressThreshold) {
                            Log.d(TAG, "Long press shortcut matched: ${longPressShortcut.name} (${longPressShortcut.getDisplayString()}), held for ${holdDuration}ms")
                            executeShortcut(longPressShortcut)
                            return true  // 이벤트 소비
                        }
                    }
                }
            }
        }

        return false  // 이벤트 전파
    }

    /**
     * 수정자 키 확인
     */
    private fun isModifierKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
            else -> false
        }
    }

    /**
     * 수정자 키 정규화 (RIGHT -> LEFT)
     */
    private fun normalizeModifier(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
        else -> keyCode
    }

    /**
     * 단축키 실행
     */
    private fun executeShortcut(shortcut: KeyShortcut) {
        try {
            when (shortcut.actionType) {
                KeyShortcut.ActionType.APP -> {
                    executeAppShortcut(shortcut.actionData)
                }
                KeyShortcut.ActionType.SHORTCUT -> {
                    executeIntentShortcut(shortcut.actionData)
                }
                KeyShortcut.ActionType.SETTINGS -> {
                    executeSettingsShortcut(shortcut.actionData)
                }
                KeyShortcut.ActionType.CUSTOM_ACTION -> {
                    executeCustomAction(shortcut.actionData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute shortcut: ${shortcut.name}", e)
        }
    }

    /**
     * 앱 실행
     */
    private fun executeAppShortcut(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Launched app: $packageName")
        } else {
            Log.w(TAG, "Could not launch app: $packageName")
        }
    }

    /**
     * Intent 바로가기 실행
     */
    private fun executeIntentShortcut(intentUri: String) {
        val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.d(TAG, "Executed intent shortcut: $intentUri")
    }

    /**
     * 시스템 설정 실행
     */
    private fun executeSettingsShortcut(action: String) {
        val intent = when (action) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "app_settings" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> Intent(action)  // 커스텀 액션
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.d(TAG, "Opened settings: $action")
    }

    /**
     * 커스텀 액션 실행 (향후 확장용)
     */
    private fun executeCustomAction(actionData: String) {
        Log.d(TAG, "Custom action not implemented yet: $actionData")
    }

    /**
     * 실제 KeyEvent의 메타 상태와 내부 추적 상태 동기화
     * 앱 전환 등으로 UP 이벤트를 놓친 stuck modifier 해소
     */
    private fun syncModifiersWithEvent(event: KeyEvent) {
        val meta = event.metaState
        val expectedModifiers = mutableSetOf<Int>()
        if (meta and KeyEvent.META_CTRL_ON != 0) expectedModifiers.add(KeyEvent.KEYCODE_CTRL_LEFT)
        if (meta and KeyEvent.META_SHIFT_ON != 0) expectedModifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT)
        if (meta and KeyEvent.META_ALT_ON != 0) expectedModifiers.add(KeyEvent.KEYCODE_ALT_LEFT)
        if (meta and KeyEvent.META_META_ON != 0) expectedModifiers.add(KeyEvent.KEYCODE_META_LEFT)

        // 내부 상태에 있지만 실제로는 눌려있지 않은 modifier 제거
        val stuckModifiers = pressedModifiers - expectedModifiers
        if (stuckModifiers.isNotEmpty()) {
            Log.d(TAG, "Clearing stuck modifiers: $stuckModifiers (meta=$meta)")
            pressedModifiers.removeAll(stuckModifiers)
        }
    }

    /**
     * 수정자 키 상태 초기화 (포커스 상실 등)
     */
    fun resetModifiers() {
        pressedModifiers.clear()
    }
}
