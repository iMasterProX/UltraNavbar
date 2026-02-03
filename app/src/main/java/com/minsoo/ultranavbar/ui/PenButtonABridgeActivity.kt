package com.minsoo.ultranavbar.ui

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.minsoo.ultranavbar.model.PaintFunction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ShizukuHelper
import kotlin.concurrent.thread

/**
 * 펜 버튼 A 브릿지 Activity
 * 시스템 펜 설정에서 이 Activity를 버튼 A에 할당합니다.
 *
 * Theme.NoDisplay를 사용하므로 onResume() 전에 finish()를 호출해야 합니다.
 */
class PenButtonABridgeActivity : Activity() {

    companion object {
        private const val TAG = "PenButtonABridge"

        // === 타이밍 설정 ===
        // 첫 시도 전 대기 시간
        private const val INITIAL_DELAY_MS = 2L
        // 재시도 간격
        private const val RETRY_DELAY_MS = 2L
        // 최대 재시도 횟수 (dispatchGesture용)
        private const val MAX_RETRIES = 250
        // Shizuku tap 재시도 횟수 (0 = 재시도 없이 1회만 실행)
        private const val SHIZUKU_MAX_RETRIES = 0
        // 키 이벤트 주입용 딜레이
        private const val KEY_INJECT_DELAY_MS = 300L

        // 세션 ID - 새 버튼 누름 시 증가, 이전 재시도 무효화
        @Volatile
        private var currentSessionId = 0L

        // 공유 핸들러
        private val sharedHandler = Handler(Looper.getMainLooper())
    }

    private var mySessionId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 새 세션 시작 - 이전 재시도 모두 무효화
        mySessionId = ++currentSessionId
        Log.d(TAG, "Button A bridge started, session: $mySessionId")

        val settings = SettingsManager.getInstance(this)
        val actionType = settings.penAActionType

        Log.d(TAG, "Action type: $actionType")

        when (actionType) {
            "PAINT_FUNCTION" -> {
                val paintFunctionName = settings.penAPaintFunction
                Log.d(TAG, "Paint function: $paintFunctionName")
                if (paintFunctionName != null) {
                    val function = PaintFunction.fromName(paintFunctionName)
                    if (function != null) {
                        injectKeyEventWithDelay(function.keyCode, function.metaState)
                    }
                }
            }
            "TOUCH_POINT" -> {
                val x = settings.penATouchX
                val y = settings.penATouchY
                Log.d(TAG, "Touch point: ($x, $y)")
                if (x >= 0 && y >= 0) {
                    performAutoTouch(x, y)
                }
            }
            "SHORTCUT" -> {
                val shortcutUri = settings.penAShortcutPackage  // URI 저장됨
                Log.d(TAG, "Shortcut URI: $shortcutUri")
                if (shortcutUri != null) {
                    launchShortcut(shortcutUri)
                }
            }
        }

        // Theme.NoDisplay는 onResume() 전에 finish() 필수
        moveTaskToBack(true)
        finish()
    }

    private fun injectKeyEventWithDelay(keyCode: Int, metaState: Int) {
        thread {
            try {
                Thread.sleep(KEY_INJECT_DELAY_MS)
                val success = ShizukuHelper.injectKeyEvent(keyCode, metaState)
                Log.d(TAG, "Key injection result: $success")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject key event", e)
            }
        }
    }

    private fun launchShortcut(shortcutUri: String) {
        try {
            val shortcutIntent = Intent.parseUri(shortcutUri, Intent.URI_INTENT_SCHEME)
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(shortcutIntent)
            Log.d(TAG, "Shortcut launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch shortcut", e)
        }
    }

    /**
     * 자동 터치 수행 (Shizuku 우선, fallback to dispatchGesture)
     */
    private fun performAutoTouch(x: Float, y: Float) {
        // Shizuku 사용 가능하면 재시도 방식으로 실행
        if (ShizukuHelper.hasShizukuPermission()) {
            performShizukuTapWithRetry(x, y, 0, mySessionId)
            return
        }

        // Shizuku 없으면 기존 dispatchGesture 방식 사용
        Log.d(TAG, "Shizuku not available, using dispatchGesture")
        performTouchWithRetry(x, y, 0, mySessionId)
    }

    /**
     * Shizuku를 통한 터치를 재시도 메커니즘과 함께 수행
     * 새 버튼 누름 시 이전 재시도는 자동 취소됨
     *
     * input tap 명령은 성공해도 실제 터치가 등록되지 않을 수 있어서
     * 여러 번 시도하여 안정성 향상
     */
    private fun performShizukuTapWithRetry(x: Float, y: Float, retryCount: Int, sessionId: Long) {
        val delay = if (retryCount == 0) INITIAL_DELAY_MS else RETRY_DELAY_MS

        sharedHandler.postDelayed({
            // 세션이 바뀌었으면 (새 버튼 누름) 이 재시도는 무시
            if (sessionId != currentSessionId) {
                Log.d(TAG, "Shizuku session $sessionId expired (current: $currentSessionId), stopping")
                return@postDelayed
            }

            // Shizuku tap 실행
            val success = ShizukuHelper.injectTap(x, y)

            if (!success) {
                // 명령 자체가 실패하면 dispatchGesture로 fallback
                Log.w(TAG, "Shizuku tap command failed, falling back to dispatchGesture")
                performTouchWithRetry(x, y, 0, sessionId)
                return@postDelayed
            }

            // 명령은 성공했지만, 실제 터치 등록 여부는 알 수 없음
            // 최대 재시도 횟수까지 계속 시도
            if (retryCount < SHIZUKU_MAX_RETRIES) {
                performShizukuTapWithRetry(x, y, retryCount + 1, sessionId)
            } else {
                Log.d(TAG, "Shizuku tap completed $SHIZUKU_MAX_RETRIES attempts at ($x, $y), session: $sessionId")
            }
        }, delay)
    }

    /**
     * 터치 제스처를 재시도 메커니즘과 함께 수행
     *
     * 주의: dispatchGesture API의 한계로 인해 불안정할 수 있음
     * Shizuku를 사용하면 더 안정적인 터치 주입 가능
     */
    private fun performTouchWithRetry(x: Float, y: Float, retryCount: Int, sessionId: Long) {
        val delay = if (retryCount == 0) INITIAL_DELAY_MS else RETRY_DELAY_MS

        sharedHandler.postDelayed({
            // 세션이 바뀌었으면 (새 버튼 누름) 이 재시도는 무시
            if (sessionId != currentSessionId) {
                Log.d(TAG, "Session $sessionId expired (current: $currentSessionId), stopping retry")
                return@postDelayed
            }

            val service = NavBarAccessibilityService.instance
            if (service == null) {
                Log.e(TAG, "AccessibilityService not running")
                return@postDelayed
            }

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Auto touch SUCCESS at ($x, $y) on attempt ${retryCount + 1}, session: $sessionId")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // 세션 체크 - 새 버튼 누름이 왔으면 재시도 안함
                    if (sessionId != currentSessionId) {
                        Log.d(TAG, "Session $sessionId expired, not retrying")
                        return
                    }

                    if (retryCount < MAX_RETRIES) {
                        performTouchWithRetry(x, y, retryCount + 1, sessionId)
                    } else {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) reached, giving up")
                    }
                }
            }

            service.performTap(x, y, callback, 0L)
        }, delay)
    }
}
