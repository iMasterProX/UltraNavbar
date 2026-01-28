package com.minsoo.ultranavbar

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.minsoo.ultranavbar.service.KeyboardBatteryMonitor

class UltraNavbarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 배터리 알림 채널 생성
        KeyboardBatteryMonitor.createNotificationChannel(this)
    }
}
