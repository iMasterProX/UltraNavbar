package com.minsoo.ultranavbar.service

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.minsoo.ultranavbar.IInputService
import kotlin.system.exitProcess

/**
 * Shizuku UserService로 실행되는 입력 서비스
 * Shell UID (2000)로 실행되어 InputManager에 직접 접근 가능
 */
class InputService : IInputService.Stub() {

    companion object {
        private const val TAG = "InputService"
    }

    override fun destroy() {
        Log.d(TAG, "Service destroyed")
        exitProcess(0)
    }

    /**
     * 단일 키 이벤트 주입
     */
    override fun injectKeyEvent(keyCode: Int, metaState: Int): Int {
        return try {
            Log.d(TAG, "Injecting key event: keyCode=$keyCode, metaState=$metaState")

            val now = SystemClock.uptimeMillis()

            // KEY_DOWN 이벤트
            val downEvent = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD
            )

            // KEY_UP 이벤트
            val upEvent = KeyEvent(
                now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD
            )

            // InputManager를 통해 이벤트 주입
            val inputManager = getInputManager()
            if (inputManager != null) {
                val injectMethod = inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    KeyEvent::class.java,
                    Int::class.javaPrimitiveType
                )

                // INJECT_INPUT_EVENT_MODE_ASYNC = 0
                // INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
                // INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
                val mode = 0 // ASYNC

                injectMethod.invoke(inputManager, downEvent, mode)
                injectMethod.invoke(inputManager, upEvent, mode)

                Log.d(TAG, "Key event injected successfully")
                0 // Success
            } else {
                Log.e(TAG, "InputManager not available")
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject key event", e)
            -1
        }
    }

    /**
     * 키 조합 주입 (예: Ctrl+Z)
     */
    override fun injectKeyCombo(keyCodes: IntArray): Int {
        return try {
            Log.d(TAG, "Injecting key combo: ${keyCodes.contentToString()}")

            val inputManager = getInputManager() ?: return -1
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                KeyEvent::class.java,
                Int::class.javaPrimitiveType
            )

            val now = SystemClock.uptimeMillis()
            val mode = 0 // ASYNC

            // 모든 키 DOWN (순서대로)
            for (keyCode in keyCodes) {
                val metaState = calculateMetaState(keyCodes, keyCode)
                val downEvent = KeyEvent(
                    now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD
                )
                injectMethod.invoke(inputManager, downEvent, mode)
            }

            // 모든 키 UP (역순)
            for (keyCode in keyCodes.reversed()) {
                val metaState = calculateMetaState(keyCodes, keyCode)
                val upEvent = KeyEvent(
                    now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD
                )
                injectMethod.invoke(inputManager, upEvent, mode)
            }

            Log.d(TAG, "Key combo injected successfully")
            0 // Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject key combo", e)
            -1
        }
    }

    /**
     * 키 조합에서 현재까지 눌린 modifier 키의 meta state 계산
     */
    private fun calculateMetaState(keyCodes: IntArray, upToKeyCode: Int): Int {
        var metaState = 0
        for (keyCode in keyCodes) {
            if (keyCode == upToKeyCode) break
            metaState = metaState or when (keyCode) {
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
                else -> 0
            }
        }
        return metaState
    }

    /**
     * InputManager 인스턴스 가져오기 (리플렉션)
     */
    private fun getInputManager(): Any? {
        return try {
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val getInstanceMethod = inputManagerClass.getMethod("getInstance")
            getInstanceMethod.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get InputManager", e)
            null
        }
    }
}
