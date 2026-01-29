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
import com.minsoo.ultranavbar.util.BleGattBatteryReader
import com.minsoo.ultranavbar.util.BluetoothUtils

/**
 * 키보드 배터리 모니터
 * 정기적으로 블루투스 키보드 배터리를 확인하고 부족할 때 알림 표시
 */
object KeyboardBatteryMonitor {

    private const val TAG = "KeyboardBatteryMonitor"
    private const val CHANNEL_ID = "keyboard_battery_alerts"
    private const val CHANNEL_ID_STATUS = "keyboard_battery_status"
    private const val NOTIFICATION_ID = 1001
    private const val NOTIFICATION_ID_PERSISTENT = 1002

    // 마지막 알림 표시 시간 추적 (중복 방지)
    private val lastNotificationTimes = mutableMapOf<String, Long>()
    private const val NOTIFICATION_COOLDOWN_MS = 3600000L // 1시간

    /**
     * 알림 채널 생성 (Android 8.0+)
     */
    fun createNotificationChannel(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 배터리 부족 알림 채널 (높은 중요도)
        val alertChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.battery_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.battery_notification_channel_description)
        }
        notificationManager.createNotificationChannel(alertChannel)

        // 배터리 상태 알림 채널 (낮은 중요도 - ongoing 알림용)
        val statusChannel = NotificationChannel(
            CHANNEL_ID_STATUS,
            context.getString(R.string.battery_status_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.battery_status_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(statusChannel)
    }

    /**
     * 모든 연결된 키보드의 배터리를 확인
     */
    fun checkBatteryLevels(context: Context) {
        val settings = SettingsManager.getInstance(context)

        // 알림이 비활성화되어 있고 지속 알림도 비활성화면 종료
        if (!settings.batteryNotificationEnabled && !settings.batteryPersistentNotificationEnabled) {
            cancelPersistentNotification(context)
            return
        }

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter ?: return

            // 권한 확인
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val bondedDevices = bluetoothAdapter.bondedDevices
            Log.d(TAG, "Found ${bondedDevices.size} bonded Bluetooth devices")

            var hasConnectedKeyboard = false

            bondedDevices.forEach { device ->
                val deviceName = BluetoothUtils.getDeviceName(device, context)
                val isConnected = BluetoothUtils.isDeviceConnected(device)
                val isKeyboard = BluetoothUtils.isKeyboardDevice(device, context)

                Log.d(TAG, "Device: $deviceName (${device.address}) - isKeyboard=$isKeyboard, isConnected=$isConnected")

                // 연결된 키보드만 배터리 체크
                if (isKeyboard && isConnected) {
                    hasConnectedKeyboard = true
                    checkDeviceBattery(context, device)
                } else if (isKeyboard && !isConnected) {
                    Log.d(TAG, "Skipping $deviceName - not connected")
                }
            }

            // 연결된 키보드가 없으면 지속 알림 취소
            if (!hasConnectedKeyboard) {
                cancelPersistentNotification(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery levels", e)
        }
    }

    /**
     * 특정 기기의 배터리 확인
     */
    private fun checkDeviceBattery(context: Context, device: BluetoothDevice) {
        try {
            val settings = SettingsManager.getInstance(context)
            val threshold = settings.batteryLowThreshold
            val deviceName = BluetoothUtils.getDeviceName(device, context)

            // 먼저 일반적인 방법으로 배터리 확인
            val batteryLevel = BluetoothUtils.getDeviceBatteryLevel(device)

            if (batteryLevel >= 0) {
                Log.d(TAG, "Device $deviceName (${device.address}): battery=$batteryLevel%, threshold=$threshold%")

                // 배터리 부족 알림 체크
                if (settings.batteryNotificationEnabled && batteryLevel in 0..threshold) {
                    showLowBatteryNotification(context, device, batteryLevel)
                }

                // 지속 알림 업데이트
                if (settings.batteryPersistentNotificationEnabled) {
                    updatePersistentNotification(context, device, batteryLevel)
                }
                return
            }

            // 일반 방법으로 읽을 수 없으면 BLE GATT 시도
            if (BleGattBatteryReader.isBleOnlyDevice(device)) {
                Log.d(TAG, "Device $deviceName (${device.address}): Trying BLE GATT battery read")
                BleGattBatteryReader.readBatteryLevel(context, device) { bleBatteryLevel ->
                    if (bleBatteryLevel != null) {
                        Log.d(TAG, "Device $deviceName (${device.address}): BLE battery=$bleBatteryLevel%, threshold=$threshold%")

                        // 배터리 부족 알림 체크
                        if (settings.batteryNotificationEnabled && bleBatteryLevel in 0..threshold) {
                            showLowBatteryNotification(context, device, bleBatteryLevel)
                        }

                        // 지속 알림 업데이트
                        if (settings.batteryPersistentNotificationEnabled) {
                            updatePersistentNotification(context, device, bleBatteryLevel)
                        }
                    } else {
                        Log.w(TAG, "Device $deviceName (${device.address}): Battery information not available via BLE GATT")
                    }
                }
            } else {
                Log.w(TAG, "Device $deviceName (${device.address}): Battery information not available. " +
                        "This keyboard may not support battery reporting via Bluetooth.")
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
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                "Keyboard"
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
     * 지속 알림 업데이트/표시
     * ongoing 알림으로 키보드 배터리 상태를 항상 표시
     */
    private fun updatePersistentNotification(context: Context, device: BluetoothDevice, batteryLevel: Int) {
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
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                "Keyboard"
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STATUS)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(context.getString(R.string.battery_status_title, deviceName))
            .setContentText(context.getString(R.string.battery_status_message, batteryLevel))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID_PERSISTENT, notification)
        Log.d(TAG, "Persistent notification updated: $deviceName at $batteryLevel%")
    }

    /**
     * 지속 알림 취소
     */
    fun cancelPersistentNotification(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(NOTIFICATION_ID_PERSISTENT)
        Log.d(TAG, "Persistent notification cancelled")
    }
}
