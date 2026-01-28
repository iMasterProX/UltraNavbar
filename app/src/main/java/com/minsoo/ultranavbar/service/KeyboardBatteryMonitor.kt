package com.minsoo.ultranavbar.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.minsoo.ultranavbar.MainActivity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 키보드 배터리 모니터
 * 정기적으로 블루투스 키보드 배터리를 확인하고 부족할 때 알림 표시
 */
object KeyboardBatteryMonitor {

    private const val TAG = "KeyboardBatteryMonitor"
    private const val CHANNEL_ID = "keyboard_battery_alerts"
    private const val NOTIFICATION_ID = 1001
    private const val BATTERY_LOW_THRESHOLD = 20

    // 마지막 알림 표시 시간 추적 (중복 방지)
    private val lastNotificationTimes = mutableMapOf<String, Long>()
    private const val NOTIFICATION_COOLDOWN_MS = 3600000L // 1시간

    /**
     * 알림 채널 생성 (Android 8.0+)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.battery_notification_channel_name)
            val descriptionText = context.getString(R.string.battery_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 모든 연결된 키보드의 배터리를 확인
     */
    fun checkBatteryLevels(context: Context) {
        val settings = SettingsManager.getInstance(context)

        // 알림이 비활성화되어 있으면 종료
        if (!settings.batteryNotificationEnabled) {
            return
        }

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter ?: return

            // 권한 확인
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val bondedDevices = bluetoothAdapter.bondedDevices
            bondedDevices.forEach { device ->
                if (isKeyboardDevice(device)) {
                    checkDeviceBattery(context, device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery levels", e)
        }
    }

    /**
     * 특정 기기의 배터리 확인
     */
    private fun checkDeviceBattery(context: Context, device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        try {
            @Suppress("NewApi")
            val batteryLevel = device.getBatteryLevel()
            if (batteryLevel in 0..BATTERY_LOW_THRESHOLD) {
                showLowBatteryNotification(context, device, batteryLevel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery level for device: ${device.address}", e)
        }
    }

    /**
     * 배터리 부족 알림 표시
     */
    private fun showLowBatteryNotification(context: Context, device: BluetoothDevice, batteryLevel: Int) {
        // 중복 알림 방지 (1시간 이내에 같은 기기로 알림 표시한 경우)
        val deviceId = device.address
        val now = System.currentTimeMillis()
        val lastNotification = lastNotificationTimes[deviceId] ?: 0L

        if (now - lastNotification < NOTIFICATION_COOLDOWN_MS) {
            return
        }

        // 권한 확인 (POST_NOTIFICATIONS는 Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        // 기기 이름 가져오기
        val deviceName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    "Keyboard"
                } else {
                    device.name ?: "Keyboard"
                }
            } else {
                device.name ?: "Keyboard"
            }
        } catch (e: Exception) {
            "Keyboard"
        }

        // 알림 생성
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(context.getString(R.string.battery_low_title))
            .setContentText(context.getString(R.string.battery_low_message, deviceName, batteryLevel))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // 마지막 알림 시간 기록
        lastNotificationTimes[deviceId] = now
    }

    /**
     * 키보드 기기인지 확인
     */
    private fun isKeyboardDevice(device: BluetoothDevice): Boolean {
        try {
            val deviceClass = device.bluetoothClass ?: return false
            val majorDeviceClass = deviceClass.majorDeviceClass
            val deviceClassCode = deviceClass.deviceClass

            // Major Device Class: PERIPHERAL (0x500) 및 Keyboard (0x40)
            return majorDeviceClass == 0x500 || (deviceClassCode and 0x40) != 0
        } catch (e: Exception) {
            return false
        }
    }
}
