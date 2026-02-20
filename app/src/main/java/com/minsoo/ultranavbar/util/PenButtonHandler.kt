package com.minsoo.ultranavbar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.minsoo.ultranavbar.model.PaintFunction
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 펜 버튼 이벤트 핸들러
 *
 * 펜 버튼 KeyCode는 기기마다 다를 수 있으므로 실제 테스트 필요
 * 일반적으로:
 * - BTN_STYLUS (버튼 A) = 331
 * - BTN_STYLUS2 (버튼 B) = 332
 *
 * 또는 Android KeyEvent:
 * - KEYCODE_STYLUS_BUTTON_PRIMARY (사용 가능한 경우)
 * - KEYCODE_STYLUS_BUTTON_SECONDARY (사용 가능한 경우)
 */
object PenButtonHandler {

    private const val TAG = "PenButtonHandler"

    // 펜 버튼 KeyCode (기기에 따라 다를 수 있음)
    // Linux input 코드 (원래 BTN_STYLUS/BTN_STYLUS2)
    private const val BTN_STYLUS = 331    // 표준 버튼 A
    private const val BTN_STYLUS2 = 332   // 표준 버튼 B

    // LG UltraTab의 실제 펜 버튼 키코드 (Generic.kl에서 매핑)
    private const val KEYCODE_PENA = 236  // LG UltraTab 버튼 A
    private const val KEYCODE_PENB = 237  // LG UltraTab 버튼 B

    // Android 표준 스타일러스 버튼 키코드
    private const val KEYCODE_STYLUS_BUTTON_PRIMARY = 288   // 버튼 A
    private const val KEYCODE_STYLUS_BUTTON_SECONDARY = 289 // 버튼 B
    private const val KEYCODE_STYLUS_BUTTON_TERTIARY = 290  // 버튼 B (일부 기기)

    @Volatile
    private var buttonADownAt: Long = 0L
    @Volatile
    private var buttonBDownAt: Long = 0L
    @Volatile
    private var lastButtonAHoldDurationMs: Long = 0L
    @Volatile
    private var lastButtonBHoldDurationMs: Long = 0L
    @Volatile
    private var lastButtonAHoldCapturedAt: Long = 0L
    @Volatile
    private var lastButtonBHoldCapturedAt: Long = 0L
    @Volatile
    private var buttonAPressId: Long = 0L
    @Volatile
    private var buttonBPressId: Long = 0L
    @Volatile
    private var lastButtonAReleasedPressId: Long = 0L
    @Volatile
    private var lastButtonBReleasedPressId: Long = 0L

    /**
     * 펜 버튼 이벤트인지 확인
     */
    fun isPenButtonEvent(event: KeyEvent): Boolean {
        // 디바이스 이름으로 확인
        val deviceName = event.device?.name ?: ""
        if (deviceName.contains("stylus", ignoreCase = true) ||
            deviceName.contains("himax", ignoreCase = true)) {
            return true
        }

        // KeyCode로 확인 (표준, LG UltraTab, Android 표준 키코드)
        return event.keyCode == BTN_STYLUS || event.keyCode == BTN_STYLUS2 ||
               event.keyCode == KEYCODE_PENA || event.keyCode == KEYCODE_PENB ||
               event.keyCode == KEYCODE_STYLUS_BUTTON_PRIMARY || event.keyCode == KEYCODE_STYLUS_BUTTON_SECONDARY ||
               event.keyCode == KEYCODE_STYLUS_BUTTON_TERTIARY
    }

    /**
     * 펜 버튼 이벤트 처리
     * @return true if consumed, false otherwise
     */
    fun handlePenButtonEvent(context: Context, event: KeyEvent): Boolean {
        // 버튼 구분 (표준, LG UltraTab, Android 표준 키코드 모두 지원)
        // 주의: 시스템 펜 버튼 설정에서 Bridge Activity를 실행하므로, 여기서는 감지만 하고 처리하지 않음
        // LG UltraTab: keyCode 289 = Button A, keyCode 290 = Button B
        val isButtonA = event.keyCode == BTN_STYLUS ||
                        event.keyCode == KEYCODE_PENA ||
                        event.keyCode == KEYCODE_STYLUS_BUTTON_PRIMARY ||
                        event.keyCode == KEYCODE_STYLUS_BUTTON_SECONDARY  // LG UltraTab에서 289가 버튼 A
        val isButtonB = event.keyCode == BTN_STYLUS2 ||
                        event.keyCode == KEYCODE_PENB ||
                        event.keyCode == KEYCODE_STYLUS_BUTTON_TERTIARY   // LG UltraTab에서 290이 버튼 B
        val buttonName = if (isButtonB) "B" else "A"  // B가 아니면 A로 처리

        if (!isButtonA && !isButtonB) {
            return false
        }

        updatePenButtonState(event, isButtonA)

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return false // UP 이벤트는 무시
        }

        val settings = SettingsManager.getInstance(context)

        val actionType = if (isButtonB) {
            settings.penBActionType
        } else {
            settings.penAActionType
        }

        android.util.Log.d(TAG, "Pen button $buttonName pressed, action type: $actionType")

        return when (actionType) {
            "APP" -> handleAppLaunch(context, isButtonA, settings)
            "SHORTCUT" -> handleShortcutLaunch(context, isButtonA, settings)
            "PAINT_FUNCTION" -> handlePaintFunction(context, isButtonA, settings)
            "TOUCH_POINT" -> {
                // Bridge Activity가 처리하도록 이벤트를 consume하지 않음
                // Bridge Activity에서 딜레이 후 터치를 수행해야 취소되지 않음
                android.util.Log.d(TAG, "Touch point: delegating to Bridge Activity")
                false
            }
            else -> false
        }
    }

    private fun updatePenButtonState(event: KeyEvent, isButtonA: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isButtonA) {
                if (buttonADownAt > 0L) {
                    return
                }
                buttonAPressId += 1L
                buttonADownAt = now
                lastButtonAHoldDurationMs = 0L
                lastButtonAHoldCapturedAt = 0L
            } else {
                if (buttonBDownAt > 0L) {
                    return
                }
                buttonBPressId += 1L
                buttonBDownAt = now
                lastButtonBHoldDurationMs = 0L
                lastButtonBHoldCapturedAt = 0L
            }
            return
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (isButtonA) {
                val downAt = buttonADownAt
                if (downAt > 0L) {
                    lastButtonAHoldDurationMs = now - downAt
                    lastButtonAHoldCapturedAt = now
                    lastButtonAReleasedPressId = buttonAPressId
                    android.util.Log.d(TAG, "Button A hold duration: ${lastButtonAHoldDurationMs}ms")
                }
                buttonADownAt = 0L
            } else {
                val downAt = buttonBDownAt
                if (downAt > 0L) {
                    lastButtonBHoldDurationMs = now - downAt
                    lastButtonBHoldCapturedAt = now
                    lastButtonBReleasedPressId = buttonBPressId
                    android.util.Log.d(TAG, "Button B hold duration: ${lastButtonBHoldDurationMs}ms")
                }
                buttonBDownAt = 0L
            }
            return
        }
    }

    fun isPenButtonCurrentlyPressed(isButtonA: Boolean): Boolean {
        return if (isButtonA) buttonADownAt > 0L else buttonBDownAt > 0L
    }

    fun getCurrentPenButtonPressId(isButtonA: Boolean): Long {
        return if (isButtonA) buttonAPressId else buttonBPressId
    }

    fun getLastReleasedPenButtonPressId(isButtonA: Boolean): Long {
        return if (isButtonA) lastButtonAReleasedPressId else lastButtonBReleasedPressId
    }

    fun getCurrentPenButtonDownAtMs(isButtonA: Boolean): Long {
        return if (isButtonA) buttonADownAt else buttonBDownAt
    }

    fun getLastPenButtonHoldDurationMs(isButtonA: Boolean): Long {
        return if (isButtonA) lastButtonAHoldDurationMs else lastButtonBHoldDurationMs
    }

    fun getLastPenButtonHoldCapturedAtMs(isButtonA: Boolean): Long {
        return if (isButtonA) lastButtonAHoldCapturedAt else lastButtonBHoldCapturedAt
    }

    /**
     * 앱 실행 처리
     */
    private fun handleAppLaunch(context: Context, isButtonA: Boolean, settings: SettingsManager): Boolean {
        val packageName = if (isButtonA) {
            settings.penAAppPackage
        } else {
            settings.penBAppPackage
        }

        val activityName = if (isButtonA) {
            settings.penAAppActivity
        } else {
            settings.penBAppActivity
        }

        if (packageName != null && activityName != null) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(packageName, activityName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to launch app: $packageName/$activityName", e)
            }
        }

        return false
    }

    /**
     * 바로가기 실행 처리
     */
    private fun handleShortcutLaunch(context: Context, isButtonA: Boolean, settings: SettingsManager): Boolean {
        val packageName = if (isButtonA) {
            settings.penAShortcutPackage
        } else {
            settings.penBShortcutPackage
        }

        val shortcutId = if (isButtonA) {
            settings.penAShortcutId
        } else {
            settings.penBShortcutId
        }

        if (packageName != null && shortcutId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            return ShortcutHelper.launchShortcut(context, packageName, shortcutId)
        }

        return false
    }

    /**
     * 페인팅 기능 처리
     *
     * 참고: 실제 키 이벤트 주입은 Bridge Activity에서 수행합니다.
     * 시스템 펜 버튼 설정에서 Bridge Activity를 실행하도록 설정되어 있으므로,
     * 여기서는 이벤트를 consume하지 않고 시스템이 처리하도록 합니다.
     */
    private fun handlePaintFunction(context: Context, isButtonA: Boolean, settings: SettingsManager): Boolean {
        val functionName = if (isButtonA) {
            settings.penAPaintFunction
        } else {
            settings.penBPaintFunction
        }

        val function = PaintFunction.fromName(functionName) ?: return false

        android.util.Log.d(TAG, "Paint function: ${function.name}, keyCode: ${function.keyCode}, metaState: ${function.metaState}")

        // Bridge Activity가 처리하도록 이벤트를 consume하지 않음
        return false
    }

    /**
     * 터치 포인트 처리
     * AccessibilityService의 dispatchGesture를 사용하여 지정된 좌표에 터치 수행
     */
    private fun handleTouchPoint(isButtonA: Boolean, settings: SettingsManager): Boolean {
        val x = if (isButtonA) settings.penATouchX else settings.penBTouchX
        val y = if (isButtonA) settings.penATouchY else settings.penBTouchY

        if (x < 0 || y < 0) {
            android.util.Log.w(TAG, "Touch point not set for button ${if (isButtonA) "A" else "B"}")
            return false
        }

        val service = NavBarAccessibilityService.instance
        if (service == null) {
            android.util.Log.e(TAG, "AccessibilityService not running")
            return false
        }

        android.util.Log.d(TAG, "Performing tap at ($x, $y) for button ${if (isButtonA) "A" else "B"}")
        return service.performTap(x, y)
    }

    /**
     * 키 이벤트 주입 (실험적)
     *
     * 참고: AccessibilityService에서는 직접 키 이벤트 주입이 제한적입니다.
     * 실제 구현을 위해서는 다음 방법을 고려해야 합니다:
     * 1. Instrumentation (시스템 권한 필요)
     * 2. InputManager reflection (불안정)
     * 3. adb shell input keyevent (외부 도구)
     */
    private fun injectKeyEvent(keyCode: Int, metaState: Int): Boolean {
        // TODO: 실제 구현
        // 현재로서는 제한적입니다.
        return false
    }
}
