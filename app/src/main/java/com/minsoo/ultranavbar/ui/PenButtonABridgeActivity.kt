package com.minsoo.ultranavbar.ui

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.PenButtonHandler

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
        // 첫 시도 전 대기 시간 — Activity 전환 완료 + 포커스 안정화를 위해 충분한 딜레이
        // Theme.NoDisplay finish() 후 이전 앱으로 포커스가 완전히 복귀해야 제스처가 전달됨
        private const val INITIAL_DELAY_MS = 350L
        // 재시도 간격 — 이전 제스처가 완전히 종료된 후 다시 시도
        private const val RETRY_DELAY_MS = 300L
        // 최대 재시도 횟수 (dispatchGesture용)
        private const val MAX_RETRIES = 8
        // 노드 클릭 재시도 설정
        private const val NODE_CLICK_DELAY_MS = 80L
        private const val NODE_CLICK_MAX_RETRIES = 6
        // 자동터치 계열 롱프레스 감지
        private const val SHORT_PRESS_DELAY_MS = 120L
        private const val HOLD_RECHECK_DELAY_MS = 60L
        private const val LONG_PRESS_HOLD_THRESHOLD_MS = 2000L
        private const val MIN_VALID_TAP_HOLD_MS = 8L
        private const val RELEASE_WAIT_GRACE_MS = 300L
        private const val LONG_PRESS_RECONFIG_COOLDOWN_MS = 1200L

        // 세션 ID - 새 버튼 누름 시 증가, 이전 재시도 무효화
        @Volatile
        private var currentSessionId = 0L

        @Volatile
        private var pendingPressId = 0L
        @Volatile
        private var lastHandledPressId = 0L
        @Volatile
        private var lastReconfigureAt = 0L
        private var pendingSingleAction: Runnable? = null

        // 공유 핸들러
        private val sharedHandler = Handler(Looper.getMainLooper())
    }

    private var mySessionId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentPressId = PenButtonHandler.getCurrentPenButtonPressId(true)
        if (currentPressId > currentSessionId) {
            currentSessionId = currentPressId
        }
        mySessionId = currentSessionId
        Log.d(TAG, "Button A bridge started, session: $mySessionId, pressId: $currentPressId")

        val settings = SettingsManager.getInstance(this)
        val actionType = settings.penAActionType

        Log.d(TAG, "Action type: $actionType")

        if (actionType == "TOUCH_POINT" || actionType == "NODE_CLICK") {
            handleLongPressAwareAutoTouch(settings, actionType)
            moveTaskToBack(true)
            finish()
            return
        }

        when (actionType) {
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

    private fun handleLongPressAwareAutoTouch(settings: SettingsManager, actionType: String) {
        val pressId = PenButtonHandler.getCurrentPenButtonPressId(true)
        if (pressId <= 0L) {
            Log.d(TAG, "No valid press id for A; skipping")
            return
        }

        if (pressId == lastHandledPressId) {
            Log.d(TAG, "Press already handled (A): pressId=$pressId")
            return
        }

        if (pressId == pendingPressId) {
            Log.d(TAG, "Duplicate bridge launch for same press (A): pressId=$pressId")
            return
        }

        pendingSingleAction?.let { sharedHandler.removeCallbacks(it) }
        pendingPressId = pressId
        if (pressId > currentSessionId) {
            currentSessionId = pressId
        }
        mySessionId = pressId

        val pressStartAt = PenButtonHandler.getCurrentPenButtonDownAtMs(true).takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        val singlePressAction = object : Runnable {
            override fun run() {
                if (pressId != pendingPressId) return

                val latestPressId = PenButtonHandler.getCurrentPenButtonPressId(true)
                if (latestPressId != pressId) {
                    Log.d(TAG, "Press changed before handling (A): expected=$pressId, latest=$latestPressId")
                    pendingSingleAction = null
                    pendingPressId = 0L
                    return
                }

                if (PenButtonHandler.isPenButtonCurrentlyPressed(true)) {
                    val downAt = PenButtonHandler.getCurrentPenButtonDownAtMs(true)
                    val pressedElapsed = if (downAt > 0L) {
                        SystemClock.elapsedRealtime() - downAt
                    } else {
                        SystemClock.elapsedRealtime() - pressStartAt
                    }
                    if (pressedElapsed >= LONG_PRESS_HOLD_THRESHOLD_MS) {
                        pendingSingleAction = null
                        pendingPressId = 0L
                        lastHandledPressId = pressId
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReconfigureAt >= LONG_PRESS_RECONFIG_COOLDOWN_MS) {
                            startDirectReconfigure(actionType)
                            lastReconfigureAt = now
                            Log.d(TAG, "Long-press detected while pressed: ${pressedElapsed}ms (A)")
                        } else {
                            Log.d(TAG, "Long-press skipped by cooldown (A)")
                        }
                        return
                    }
                    sharedHandler.postDelayed(this, HOLD_RECHECK_DELAY_MS)
                    return
                }

                val releasedPressId = PenButtonHandler.getLastReleasedPenButtonPressId(true)
                if (releasedPressId < pressId) {
                    val waited = SystemClock.elapsedRealtime() - pressStartAt
                    if (waited < LONG_PRESS_HOLD_THRESHOLD_MS + RELEASE_WAIT_GRACE_MS) {
                        sharedHandler.postDelayed(this, HOLD_RECHECK_DELAY_MS)
                        return
                    }
                    Log.d(TAG, "Release info missing for pressId=$pressId after ${waited}ms (A)")
                    pendingSingleAction = null
                    pendingPressId = 0L
                    lastHandledPressId = pressId
                    return
                }

                pendingSingleAction = null
                pendingPressId = 0L
                val holdDuration = PenButtonHandler.getLastPenButtonHoldDurationMs(true)
                Log.d(TAG, "Hold check (A): holdDuration=${holdDuration}ms")
                if (holdDuration >= LONG_PRESS_HOLD_THRESHOLD_MS) {
                    lastHandledPressId = pressId
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReconfigureAt >= LONG_PRESS_RECONFIG_COOLDOWN_MS) {
                        startDirectReconfigure(actionType)
                        lastReconfigureAt = now
                        Log.d(TAG, "Long-press detected by hold duration: ${holdDuration}ms (A)")
                    } else {
                        Log.d(TAG, "Long-press release skipped by cooldown (A)")
                    }
                    return
                }

                if (holdDuration in 1 until MIN_VALID_TAP_HOLD_MS) {
                    Log.d(TAG, "Ignored ultra-short pen pulse: ${holdDuration}ms (A)")
                    lastHandledPressId = pressId
                    return
                }

                lastHandledPressId = pressId
                executeAutoTouchAction(settings, actionType)
            }
        }

        pendingSingleAction = singlePressAction
        sharedHandler.postDelayed(singlePressAction, SHORT_PRESS_DELAY_MS)
    }

    private fun executeAutoTouchAction(settings: SettingsManager, actionType: String) {
        when (actionType) {
            "TOUCH_POINT" -> {
                val x = settings.penATouchX
                val y = settings.penATouchY
                Log.d(TAG, "Single-press confirmed: touch point ($x, $y)")
                if (x >= 0 && y >= 0) {
                    performAutoTouch(x, y)
                }
            }

            "NODE_CLICK" -> {
                val nodeId = settings.penANodeId
                val nodeText = settings.penANodeText
                val nodeDesc = settings.penANodeDesc
                val nodePackage = settings.penANodePackage
                Log.d(TAG, "Single-press confirmed: node click id=$nodeId, text=$nodeText, desc=$nodeDesc")
                performNodeClick(nodeId, nodeText, nodeDesc, nodePackage)
            }
        }
    }

    private fun startDirectReconfigure(actionType: String) {
        val intent = when (actionType) {
            "TOUCH_POINT" -> Intent(this, TouchPointSetupActivity::class.java).apply {
                putExtra(TouchPointSetupActivity.EXTRA_BUTTON, "A")
                putExtra(TouchPointSetupActivity.EXTRA_DIRECT_START, true)
            }

            "NODE_CLICK" -> Intent(this, NodeSelectionActivity::class.java).apply {
                putExtra(NodeSelectionActivity.EXTRA_BUTTON, "A")
                putExtra(NodeSelectionActivity.EXTRA_DIRECT_START, true)
            }

            else -> return
        }

        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start direct reconfigure", e)
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
        val delay = if (retryCount == 0) 0L else NODE_CLICK_DELAY_MS
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
        }, delay)
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
     *
     * 개선사항:
     * - dispatchGesture가 false를 반환하는 경우도 재시도
     * - 콜백에서 onCancelled 시 재시도
     * - 점진적 startDelay 증가로 타이밍 문제 해결
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

                    Log.w(TAG, "Gesture cancelled at ($x, $y) on attempt ${retryCount + 1}")
                    if (retryCount < MAX_RETRIES) {
                        performTouchWithRetry(x, y, retryCount + 1, sessionId)
                    } else {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) reached, giving up")
                    }
                }
            }

            // 점진적 startDelay: 재시도마다 제스처 내부 시작 딜레이를 약간 늘려
            // 시스템이 이전 이벤트를 처리할 시간을 확보
            val gestureStartDelay = (retryCount * 20L).coerceAtMost(100L)
            val dispatched = service.performTap(x, y, callback, gestureStartDelay)

            // dispatchGesture 자체가 false를 반환하면 콜백이 호출되지 않으므로 직접 재시도
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture returned false at ($x, $y) on attempt ${retryCount + 1}")
                if (sessionId == currentSessionId && retryCount < MAX_RETRIES) {
                    performTouchWithRetry(x, y, retryCount + 1, sessionId)
                } else if (retryCount >= MAX_RETRIES) {
                    Log.e(TAG, "Max retries ($MAX_RETRIES) reached after dispatch failure, giving up")
                }
            }
        }, delay)
    }
}
