package com.minsoo.ultranavbar.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import com.minsoo.ultranavbar.MainActivity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.util.BluetoothUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 키보드 배터리 위젯
 * 홈 화면에 블루투스 키보드 배터리 잔량을 표시
 */
class KeyboardBatteryWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "KeyboardBatteryWidget"

        /**
         * 모든 위젯 인스턴스를 수동으로 업데이트
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, KeyboardBatteryWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds.isNotEmpty()) {
                val widget = KeyboardBatteryWidget()
                widget.onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 각 위젯 인스턴스를 업데이트
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // 첫 위젯이 생성될 때
        super.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        // 마지막 위젯이 삭제될 때
        super.onDisabled(context)
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_keyboard_battery)

        // 블루투스 키보드 정보 가져오기
        val keyboardInfo = getKeyboardBatteryInfo(context)

        if (keyboardInfo != null) {
            views.setTextViewText(R.id.txtKeyboardName, keyboardInfo.name)
            views.setTextViewText(R.id.txtBatteryLevel, "${keyboardInfo.batteryLevel}%")

            // 배터리 레벨에 따른 색상 설정
            val batteryColor = when {
                keyboardInfo.batteryLevel <= 20 -> android.R.color.holo_red_dark
                keyboardInfo.batteryLevel <= 50 -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_green_dark
            }
            views.setTextColor(R.id.txtBatteryLevel, context.getColor(batteryColor))

            // 마지막 업데이트 시간
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val currentTime = timeFormat.format(Date())
            views.setTextViewText(R.id.txtLastUpdated, "${context.getString(R.string.widget_updated)}: $currentTime")
        } else {
            views.setTextViewText(R.id.txtKeyboardName, context.getString(R.string.widget_no_keyboard))
            views.setTextViewText(R.id.txtBatteryLevel, "--")
            views.setTextColor(R.id.txtBatteryLevel, context.getColor(android.R.color.darker_gray))
            views.setTextViewText(R.id.txtLastUpdated, "")
        }

        // 위젯 클릭 시 앱 열기
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.txtKeyboardName, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun getKeyboardBatteryInfo(context: Context): KeyboardInfo? {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter ?: run {
                Log.w(TAG, "Bluetooth adapter not available")
                return null
            }

            // 권한 확인
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")
                return null
            }

            // 연결된 키보드 찾기
            val bondedDevices = bluetoothAdapter.bondedDevices
            Log.d(TAG, "Found ${bondedDevices.size} bonded devices")

            bondedDevices.forEach { device ->
                val isKeyboard = BluetoothUtils.isKeyboardDevice(device, context)
                val deviceName = BluetoothUtils.getDeviceName(device, context)
                Log.d(TAG, "Device: $deviceName (${device.address}), isKeyboard=$isKeyboard")
            }

            val keyboardDevice = bondedDevices.firstOrNull { device ->
                BluetoothUtils.isKeyboardDevice(device, context)
            }

            if (keyboardDevice != null) {
                val deviceName = BluetoothUtils.getDeviceName(keyboardDevice, context)
                Log.d(TAG, "Found keyboard device: $deviceName")
                val batteryLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    BluetoothUtils.getDeviceBatteryLevel(keyboardDevice)
                } else {
                    -1
                }

                Log.d(TAG, "Battery level: $batteryLevel")

                if (batteryLevel >= 0) {
                    val deviceName = keyboardDevice.name ?: "Keyboard"
                    return KeyboardInfo(deviceName, batteryLevel)
                }
            } else {
                Log.w(TAG, "No keyboard device found")
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting keyboard battery info", e)
            return null
        }
    }

    private data class KeyboardInfo(
        val name: String,
        val batteryLevel: Int
    )
}
