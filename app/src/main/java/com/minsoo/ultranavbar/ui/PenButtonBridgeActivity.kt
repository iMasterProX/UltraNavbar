package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import com.minsoo.ultranavbar.model.PaintFunction
import com.minsoo.ultranavbar.settings.SettingsManager
import kotlin.concurrent.thread

/**
 * 펜 버튼 브릿지 Activity
 *
 * 시스템 펜 버튼 설정에서 이 Activity를 호출하면:
 * 1. 설정된 페인팅 기능에 해당하는 키 이벤트를 주입
 * 2. 즉시 종료하여 이전 앱으로 돌아감
 *
 * 사용법:
 * - 버튼 A: PenButtonBridgeActivity (action=A)
 * - 버튼 B: PenButtonBridgeActivity (action=B)
 */
class PenButtonBridgeActivity : Activity() {

    companion object {
        private const val TAG = "PenButtonBridge"
        const val EXTRA_BUTTON = "button" // "A" or "B"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val button = intent.getStringExtra(EXTRA_BUTTON) ?: "A"
        Log.d(TAG, "Bridge activity started for button: $button")

        val settings = SettingsManager.getInstance(this)

        val actionType = if (button == "A") settings.penAActionType else settings.penBActionType
        val paintFunctionName = if (button == "A") settings.penAPaintFunction else settings.penBPaintFunction

        Log.d(TAG, "Action type: $actionType, paint function: $paintFunctionName")

        if (actionType == "PAINT_FUNCTION" && paintFunctionName != null) {
            val function = PaintFunction.fromName(paintFunctionName)
            if (function != null) {
                // 백그라운드 스레드에서 실행 (ANR 방지)
                executePaintFunctionAsync(function)
            } else {
                Log.e(TAG, "Unknown paint function: $paintFunctionName")
            }
        }

        // 즉시 종료
        finish()
    }

    /**
     * 페인팅 기능 비동기 실행
     */
    private fun executePaintFunctionAsync(function: PaintFunction) {
        thread {
            executePaintFunction(function)
        }
    }

    /**
     * 페인팅 기능 실행 (키 이벤트 주입)
     */
    private fun executePaintFunction(function: PaintFunction) {
        Log.d(TAG, "Executing paint function: ${function.name}, keyCode: ${function.keyCode}, meta: ${function.metaState}")

        // 방법 1: Runtime.exec()으로 input keyevent 실행
        val success = injectKeyEventViaShell(function.keyCode, function.metaState)

        if (!success) {
            // 방법 2: Instrumentation (fallback - 보통 실패함)
            Log.w(TAG, "Shell method failed, trying Instrumentation...")
            injectKeyEventViaInstrumentation(function.keyCode, function.metaState)
        }
    }

    /**
     * Shell 명령어로 키 이벤트 주입
     * Android의 'input keycombination' 명령을 사용하여 조합키 지원
     */
    private fun injectKeyEventViaShell(keyCode: Int, metaState: Int): Boolean {
        return try {
            val command = buildInputCommand(keyCode, metaState)
            Log.d(TAG, "Executing shell command: $command")

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()

            // 에러 출력 확인
            val errorStream = process.errorStream.bufferedReader().readText()
            if (errorStream.isNotEmpty()) {
                Log.w(TAG, "Shell stderr: $errorStream")
            }

            Log.d(TAG, "Shell command exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shell injection failed", e)
            false
        }
    }

    /**
     * input 명령어 생성
     */
    private fun buildInputCommand(keyCode: Int, metaState: Int): String {
        val keyCodes = mutableListOf<Int>()

        // Meta 키 추가 (Android KeyEvent 코드 사용)
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            keyCodes.add(113) // KEYCODE_CTRL_LEFT
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            keyCodes.add(59) // KEYCODE_SHIFT_LEFT
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            keyCodes.add(57) // KEYCODE_ALT_LEFT
        }

        // 메인 키 추가
        keyCodes.add(keyCode)

        return if (keyCodes.size > 1) {
            // 조합키: keycombination 사용
            "input keycombination ${keyCodes.joinToString(" ")}"
        } else {
            // 단일 키: keyevent 사용
            "input keyevent $keyCode"
        }
    }

    /**
     * Instrumentation으로 키 이벤트 주입 (보통 권한 없어서 실패)
     */
    private fun injectKeyEventViaInstrumentation(keyCode: Int, metaState: Int): Boolean {
        return try {
            val instrumentation = android.app.Instrumentation()

            // Key down
            val downEvent = KeyEvent(
                0, 0,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState
            )
            instrumentation.sendKeySync(downEvent)

            // Key up
            val upEvent = KeyEvent(
                0, 0,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState
            )
            instrumentation.sendKeySync(upEvent)

            Log.d(TAG, "Instrumentation injection succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Instrumentation injection failed", e)
            false
        }
    }
}
