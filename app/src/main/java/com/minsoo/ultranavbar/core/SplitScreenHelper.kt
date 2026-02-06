package com.minsoo.ultranavbar.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 분할화면 실행 헬퍼 (단순화 버전)
 *
 * 접근성 서비스 기반으로 분할화면 실행:
 * 1. GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN으로 분할화면 모드 진입
 * 2. FLAG_ACTIVITY_LAUNCH_ADJACENT로 두 번째 앱 실행
 */
object SplitScreenHelper {
    private const val TAG = "SplitScreenHelper"
    private val handler = Handler(Looper.getMainLooper())

    // 접근성 서비스 참조 (NavBarAccessibilityService에서 설정)
    private var accessibilityService: AccessibilityService? = null

    // 분할화면 활성 상태 추적 (UI 표시용)
    @Volatile
    private var isSplitScreenActive = false

    fun setAccessibilityService(service: AccessibilityService?) {
        accessibilityService = service
    }

    /**
     * 분할화면이 현재 활성 상태인지 확인
     */
    fun isSplitScreenActive(): Boolean = isSplitScreenActive

    /**
     * 분할화면 활성 상태 설정 (NavBarAccessibilityService에서 호출)
     */
    fun setSplitScreenActive(active: Boolean) {
        if (isSplitScreenActive != active) {
            Log.d(TAG, "Split screen active: $isSplitScreenActive -> $active")
            isSplitScreenActive = active
        }
    }

    /**
     * 분할화면 상태 강제 리셋
     */
    fun forceResetSplitScreenState() {
        Log.d(TAG, "Force reset split screen state")
        isSplitScreenActive = false
    }

    // 하위 호환성을 위한 더미 메서드들
    fun markSplitScreenUsed() {}
    fun wasSplitScreenUsedAndReset(): Boolean = false
    fun isInRecoveryGracePeriod(): Boolean = false

    /**
     * 앱이 분할화면(멀티윈도우)을 지원하는지 확인
     */
    fun isResizeableActivity(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
            val activityInfo = intent.component?.let {
                pm.getActivityInfo(it, 0)
            } ?: return false

            val resizeModeField = ActivityInfo::class.java.getField("resizeMode")
            val resizeMode = resizeModeField.getInt(activityInfo)
            // RESIZE_MODE_UNRESIZEABLE = 0 일 때만 분할화면 불가
            val isResizeable = resizeMode != 0
            Log.d(TAG, "isResizeableActivity: $packageName, resizeMode=$resizeMode, result=$isResizeable")
            isResizeable
        } catch (e: Exception) {
            Log.w(TAG, "isResizeableActivity check failed for $packageName", e)
            false // 에러 시 안전하게 분할화면 불허
        }
    }

    /**
     * 분할화면 실행 (단순화)
     *
     * 주의: 이 함수 호출 전에 isResizeableActivity()로 분할화면 지원 여부를 확인해야 함
     */
    fun launchSplitScreen(
        context: Context,
        targetPackage: String,
        currentPackage: String
    ): Boolean {
        Log.d(TAG, "Launch split screen: target=$targetPackage, current=$currentPackage")

        // 분할화면 지원 여부 재확인 (안전장치)
        if (!isResizeableActivity(context, targetPackage)) {
            Log.w(TAG, "Target app does not support split screen: $targetPackage")
            return false
        }

        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "Accessibility service not available, launching app normally")
            launchAppNormally(context, targetPackage)
            return false
        }

        return try {
            val isOnLauncher = isLauncherPackage(context, currentPackage)

            Log.d(TAG, "isOnLauncher=$isOnLauncher")

            if (isOnLauncher) {
                // 런처에서: 그냥 앱 실행 (분할화면 불필요)
                launchAppNormally(context, targetPackage)
            } else {
                // 다른 앱에서: 분할화면 토글 → 대상 앱 실행
                toggleThenLaunch(context, service, targetPackage)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Split screen failed", e)
            launchAppNormally(context, targetPackage)
            false
        }
    }

    /**
     * 앱 일반 실행 (분할화면 없이)
     */
    private fun launchAppNormally(context: Context, targetPackage: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        if (intent == null) {
            Log.e(TAG, "Cannot find launch intent: $targetPackage")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.d(TAG, "Launched normally: $targetPackage")
    }

    /**
     * 분할화면 토글 후 앱 실행 (대상 앱이 이미 실행 중)
     */
    private fun toggleThenLaunch(
        context: Context,
        service: AccessibilityService,
        targetPackage: String
    ) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        Log.d(TAG, "Toggled split screen")

        // 즉시 대상 앱 실행
        handler.postDelayed({
            launchAdjacent(context, targetPackage)
        }, 100L)
    }

    /**
     * FLAG_ACTIVITY_LAUNCH_ADJACENT로 앱 실행
     */
    private fun launchAdjacent(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

        try {
            context.startActivity(intent)
            Log.d(TAG, "Launched adjacent: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch adjacent: $packageName", e)
        }
    }

    /**
     * 런처 패키지인지 확인
     */
    private fun isLauncherPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == packageName
        } catch (e: Exception) {
            false
        }
    }
}
