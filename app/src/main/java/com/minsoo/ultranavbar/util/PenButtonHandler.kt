package com.minsoo.ultranavbar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.minsoo.ultranavbar.model.PaintFunction
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
    private const val BTN_STYLUS = 331    // 버튼 A
    private const val BTN_STYLUS2 = 332   // 버튼 B

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

        // KeyCode로 확인
        return event.keyCode == BTN_STYLUS || event.keyCode == BTN_STYLUS2
    }

    /**
     * 펜 버튼 이벤트 처리
     * @return true if consumed, false otherwise
     */
    fun handlePenButtonEvent(context: Context, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false // UP 이벤트는 무시
        }

        val settings = SettingsManager.getInstance(context)

        // 버튼 구분
        val isButtonA = event.keyCode == BTN_STYLUS
        val buttonName = if (isButtonA) "A" else "B"

        val actionType = if (isButtonA) {
            settings.penAActionType
        } else {
            settings.penBActionType
        }

        android.util.Log.d(TAG, "Pen button $buttonName pressed, action type: $actionType")

        return when (actionType) {
            "APP" -> handleAppLaunch(context, isButtonA, settings)
            "SHORTCUT" -> handleShortcutLaunch(context, isButtonA, settings)
            "PAINT_FUNCTION" -> handlePaintFunction(context, isButtonA, settings)
            else -> false
        }
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
     * 페인팅 기능 처리 (키 이벤트 주입)
     */
    private fun handlePaintFunction(context: Context, isButtonA: Boolean, settings: SettingsManager): Boolean {
        val functionName = if (isButtonA) {
            settings.penAPaintFunction
        } else {
            settings.penBPaintFunction
        }

        val function = PaintFunction.fromName(functionName) ?: return false

        // 키 이벤트 주입
        // 참고: AccessibilityService에서 직접 키 이벤트를 주입하는 것은 제한적입니다.
        // performGlobalAction이나 다른 방법을 사용해야 할 수 있습니다.

        // TODO: 실제 키 이벤트 주입 구현
        // 현재로서는 Instrumentation이나 다른 방법이 필요할 수 있습니다.

        android.util.Log.d(TAG, "Paint function: ${function.name}, keyCode: ${function.keyCode}, metaState: ${function.metaState}")

        // 임시: 토스트로 표시
        android.widget.Toast.makeText(
            context,
            "Paint function: ${function.name} (키 이벤트 주입은 추후 구현 예정)",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        return true
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
