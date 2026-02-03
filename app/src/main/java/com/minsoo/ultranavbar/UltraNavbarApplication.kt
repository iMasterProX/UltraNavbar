package com.minsoo.ultranavbar

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.minsoo.ultranavbar.service.KeyboardBatteryMonitor
import com.minsoo.ultranavbar.util.ShizukuHelper
import rikka.shizuku.Shizuku

class UltraNavbarApplication : Application() {

    companion object {
        private const val TAG = "UltraNavbarApp"
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received, permission: ${ShizukuHelper.hasShizukuPermission()}")
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 배터리 알림 채널 생성
        KeyboardBatteryMonitor.createNotificationChannel(this)

        // Shizuku 리스너 등록
        initShizuku()
    }

    private fun initShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Shizuku", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Shizuku", e)
        }
    }
}
