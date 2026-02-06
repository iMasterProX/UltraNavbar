package com.minsoo.ultranavbar.core

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.minsoo.ultranavbar.util.ShizukuHelper

/**
 * 분할화면 실행 헬퍼
 *
 * 전략:
 * 1. 접근성 서비스의 GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN 사용
 * 2. 대상 앱을 FLAG_ACTIVITY_LAUNCH_ADJACENT로 실행 (secondary)
 */
object SplitScreenHelper {
    private const val TAG = "SplitScreenHelper"

    // 접근성 서비스 참조 (NavBarAccessibilityService에서 설정)
    private var accessibilityService: AccessibilityService? = null

    fun setAccessibilityService(service: AccessibilityService?) {
        accessibilityService = service
    }

    /**
     * 앱이 분할화면(멀티윈도우)을 지원하는지 확인
     * @param context Context
     * @param packageName 확인할 패키지명
     * @return 분할화면 지원 여부
     */
    fun isResizeableActivity(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false // Android N 미만은 분할화면 미지원
        }

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
            val activityInfo = intent.component?.let {
                pm.getActivityInfo(it, 0)
            } ?: return false

            // 리플렉션으로 resizeMode 필드 접근 (API 24+)
            val resizeModeField = ActivityInfo::class.java.getField("resizeMode")
            val resizeMode = resizeModeField.getInt(activityInfo)

            // RESIZE_MODE_UNRESIZEABLE = 0
            resizeMode != 0
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $packageName")
            false
        } catch (e: NoSuchFieldException) {
            Log.w(TAG, "resizeMode field not found, assuming supported")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking resizeable: $packageName", e)
            true // 에러 시 일단 허용
        }
    }

    /**
     * 분할화면 실행
     * @param context Context
     * @param targetPackage 분할화면에 실행할 앱 패키지명
     * @param currentPackage 현재 포그라운드 앱 패키지명
     * @return 분할화면 실행 성공 여부
     */
    fun launchSplitScreen(
        context: Context,
        targetPackage: String,
        currentPackage: String
    ): Boolean {
        Log.d(TAG, "Launch split screen: current=$currentPackage, target=$targetPackage")

        // 접근성 서비스의 GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN 사용
        val service = accessibilityService
        if (service != null) {
            return launchSplitScreenViaAccessibility(context, service, targetPackage)
        } else {
            Log.w(TAG, "Accessibility service not available, using fallback")
            return launchSplitScreenFallback(context, targetPackage)
        }
    }

    /**
     * 접근성 서비스를 사용한 분할화면 실행
     */
    @SuppressLint("InlinedApi")
    private fun launchSplitScreenViaAccessibility(
        context: Context,
        service: AccessibilityService,
        targetPackage: String
    ): Boolean {
        return try {
            // Step 1: 현재 앱을 분할화면 모드로 전환
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            if (!success) {
                Log.e(TAG, "Failed to toggle split screen")
                return launchSplitScreenFallback(context, targetPackage)
            }

            Log.d(TAG, "Split screen toggled successfully")

            // Step 2: 딜레이 후 대상 앱을 실행 (분할화면 secondary로 들어감)
            Handler(Looper.getMainLooper()).postDelayed({
                launchSecondaryApp(context, targetPackage)
            }, Constants.Timing.SPLIT_SCREEN_LAUNCH_DELAY_MS)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Split screen via accessibility failed", e)
            launchSplitScreenFallback(context, targetPackage)
        }
    }

    /**
     * Secondary 앱 실행
     */
    private fun launchSecondaryApp(context: Context, targetPackage: String) {
        // 새 Intent를 직접 생성하여 플래그가 정확히 적용되도록 함
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(targetPackage)
            // FLAG_ACTIVITY_LAUNCH_ADJACENT: 분할화면에서 반대쪽에 실행
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        }

        // 해당 패키지의 launcher activity 확인
        val resolveInfo = context.packageManager.resolveActivity(intent, 0)
        if (resolveInfo == null) {
            Log.e(TAG, "Cannot resolve launch activity for: $targetPackage")
            return
        }

        intent.setClassName(targetPackage, resolveInfo.activityInfo.name)
        Log.d(TAG, "Launching secondary with flags: 0x${Integer.toHexString(intent.flags)}")

        try {
            context.startActivity(intent)
            Log.d(TAG, "Secondary split launched: $targetPackage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch secondary split", e)
        }
    }

    /**
     * 현재 화면 크기 및 방향에 따른 secondary 영역 계산
     * - 가로 모드: 오른쪽 절반
     * - 세로 모드: 아래쪽 절반
     */
    @Suppress("DEPRECATION")
    private fun getSecondaryBounds(context: Context): Rect {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val screenRect = Rect()
        display.getRectSize(screenRect)

        val screenWidth = screenRect.width()
        val screenHeight = screenRect.height()
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        return if (isLandscape) {
            // 가로 모드: 오른쪽 절반 (width/2 + 약간의 간격, 0, width, height)
            val splitX = (screenWidth / 2) + (screenWidth / 300)  // 분할선 여백
            Rect(splitX, 0, screenWidth, screenHeight)
        } else {
            // 세로 모드: 아래쪽 절반 (0, height/2 + 약간의 간격, width, height)
            val splitY = (screenHeight / 2) + (screenHeight / 300)  // 분할선 여백
            Rect(0, splitY, screenWidth, screenHeight)
        }.also {
            Log.d(TAG, "Secondary bounds: $it (landscape=$isLandscape, screen=${screenWidth}x${screenHeight})")
        }
    }

    /**
     * Fallback: FLAG_ACTIVITY_LAUNCH_ADJACENT만 사용
     */
    private fun launchSplitScreenFallback(context: Context, targetPackage: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
        if (intent == null) {
            Log.e(TAG, "Cannot find launch intent for: $targetPackage")
            return false
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        return try {
            context.startActivity(intent)
            Log.d(TAG, "Fallback split launched: $targetPackage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback split launch failed", e)
            false
        }
    }

    /**
     * 패키지의 launch activity 클래스명 조회
     */
    private fun getLaunchActivityForPackage(context: Context, packageName: String): String? {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            intent?.component?.className
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get launch activity for: $packageName", e)
            null
        }
    }
}
