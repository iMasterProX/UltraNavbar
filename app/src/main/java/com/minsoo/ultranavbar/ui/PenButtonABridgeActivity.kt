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
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager

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
        private const val INITIAL_DELAY_MS = 100L
        // 재시도 간격
        private const val RETRY_DELAY_MS = 100L
        // 최대 재시도 횟수 (dispatchGesture용)
        private const val MAX_RETRIES = 5
        // 노드 클릭 재시도 설정
        private const val NODE_CLICK_DELAY_MS = 50L
        private const val NODE_CLICK_MAX_RETRIES = 5

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
            "NODE_CLICK" -> {
                // UI 요소 클릭 (화면 방향 무관)
                val nodeId = settings.penANodeId
                val nodeText = settings.penANodeText
                val nodeDesc = settings.penANodeDesc
                val nodePackage = settings.penANodePackage
                Log.d(TAG, "Node click: id=$nodeId, text=$nodeText, desc=$nodeDesc")
                performNodeClick(nodeId, nodeText, nodeDesc, nodePackage)
            }
        }

        // Theme.NoDisplay는 onResume() 전에 finish() 필수
        moveTaskToBack(true)
        finish()
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
     * UI 요소 클릭 (화면 방향 무관)
     * 재시도 메커니즘 포함 - 50ms 간격으로 시도
     */
    private fun performNodeClick(nodeId: String?, nodeText: String?, nodeDesc: String?, nodePackage: String?) {
        performNodeClickWithRetry(nodeId, nodeText, nodeDesc, nodePackage, 0, mySessionId)
    }

    /**
     * UI 요소 클릭을 재시도 메커니즘과 함께 수행
     * 새 버튼 누름 시 이전 재시도는 자동 취소됨
     */
    private fun performNodeClickWithRetry(
        nodeId: String?,
        nodeText: String?,
        nodeDesc: String?,
        nodePackage: String?,
        retryCount: Int,
        sessionId: Long
    ) {
        sharedHandler.postDelayed({
            // 세션이 바뀌었으면 (새 버튼 누름) 이 재시도는 무시
            if (sessionId != currentSessionId) {
                Log.d(TAG, "Node click session $sessionId expired (current: $currentSessionId), stopping")
                return@postDelayed
            }

            val service = NavBarAccessibilityService.instance
            if (service == null) {
                Log.e(TAG, "AccessibilityService not running for node click")
                return@postDelayed
            }

            val success = service.performNodeClick(nodeId, nodeText, nodeDesc, nodePackage)
            Log.d(TAG, "Node click result: $success on attempt ${retryCount + 1}, session: $sessionId")

            if (!success && retryCount < NODE_CLICK_MAX_RETRIES) {
                // 실패 시 재시도
                performNodeClickWithRetry(nodeId, nodeText, nodeDesc, nodePackage, retryCount + 1, sessionId)
            } else if (!success) {
                Log.e(TAG, "Node click max retries ($NODE_CLICK_MAX_RETRIES) reached, giving up")
            }
        }, NODE_CLICK_DELAY_MS)
    }

    /**
     * 자동 터치 수행 (dispatchGesture 사용)
     */
    private fun performAutoTouch(x: Float, y: Float) {
        Log.d(TAG, "Using dispatchGesture for auto touch")
        performTouchWithRetry(x, y, 0, mySessionId)
    }

    /**
     * 터치 제스처를 재시도 메커니즘과 함께 수행
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
