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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import com.minsoo.ultranavbar.MainActivity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.util.BleGattBatteryReader
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
        private const val ACTION_REFRESH = "com.minsoo.ultranavbar.widget.ACTION_REFRESH"

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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            Log.d(TAG, "Manual refresh requested")
            animateRefreshIcon(context)
            updateAllWidgets(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun animateRefreshIcon(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, KeyboardBatteryWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (appWidgetIds.isEmpty()) return

        val handler = Handler(Looper.getMainLooper())
        val steps = 8
        val totalDuration = 400L
        val stepDelay = totalDuration / steps

        for (i in 1..steps) {
            handler.postDelayed({
                val angle = (360f / steps) * i
                for (id in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_keyboard_battery)
                    views.setFloat(R.id.btnRefresh, "setRotation", angle % 360f)
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }, stepDelay * i)
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

            if (keyboardInfo.batteryLevel >= 0) {
                val level = keyboardInfo.batteryLevel
                views.setTextViewText(R.id.txtBatteryLevel, "${level}%")

                // 색상 결정 (텍스트 + 배터리 아이콘)
                val batteryColor = when {
                    level <= 20 -> 0xFFF44336.toInt()
                    level <= 50 -> 0xFFFF9800.toInt()
                    else -> 0xFF4CAF50.toInt()
                }
                views.setTextColor(R.id.txtBatteryLevel, batteryColor)
                views.setInt(R.id.imgBattery, "setColorFilter", batteryColor)
                views.setViewVisibility(R.id.batteryBar, View.VISIBLE)
                views.setProgressBar(R.id.batteryBar, 100, level, false)
            } else {
                views.setTextViewText(R.id.txtBatteryLevel, "--%")
                views.setTextColor(R.id.txtBatteryLevel, 0xFF999999.toInt())
                views.setInt(R.id.imgBattery, "setColorFilter", 0xFF999999.toInt())
                views.setViewVisibility(R.id.batteryBar, View.INVISIBLE)
            }

            // 마지막 업데이트 시간
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val currentTime = timeFormat.format(Date())
            views.setTextViewText(R.id.txtLastUpdated, "${context.getString(R.string.widget_updated)}: $currentTime")
        } else {
            views.setTextViewText(R.id.txtKeyboardName, context.getString(R.string.widget_no_keyboard))
            views.setTextViewText(R.id.txtBatteryLevel, "--%")
            views.setTextColor(R.id.txtBatteryLevel, 0xFF999999.toInt())
            views.setInt(R.id.imgBattery, "setColorFilter", 0xFF999999.toInt())
            views.setViewVisibility(R.id.batteryBar, View.INVISIBLE)
            views.setTextViewText(R.id.txtLastUpdated, "")
        }

        // 위젯 전체 클릭 시 앱 열기
        val appIntent = Intent(context, MainActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, appPendingIntent)

        // 새로고침 버튼 클릭
        val refreshIntent = Intent(context, KeyboardBatteryWidget::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnRefresh, refreshPendingIntent)

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

            // 연결된 키보드 찾기
            val keyboardDevice = bondedDevices.firstOrNull { device ->
                val isKeyboard = BluetoothUtils.isKeyboardDevice(device, context)
                val isConnected = BluetoothUtils.isDeviceConnected(device)
                isKeyboard && isConnected
            }

            if (keyboardDevice != null) {
                val deviceName = BluetoothUtils.getDeviceName(keyboardDevice, context)
                Log.d(TAG, "Found connected keyboard: $deviceName")

                var batteryLevel = BluetoothUtils.getDeviceBatteryLevel(keyboardDevice)
                Log.d(TAG, "Battery level for $deviceName: $batteryLevel")

                // 배터리 정보를 읽을 수 없고 BLE 기기인 경우 GATT 읽기 트리거
                if (batteryLevel < 0 && BleGattBatteryReader.isBleOnlyDevice(keyboardDevice)) {
                    Log.d(TAG, "Triggering BLE GATT battery read for $deviceName")
                    // 비동기로 BLE GATT 읽기 트리거 (캐시 업데이트용)
                    BleGattBatteryReader.readBatteryLevel(context, keyboardDevice) { bleBatteryLevel ->
                        if (bleBatteryLevel != null) {
                            Log.d(TAG, "BLE GATT battery read complete: $bleBatteryLevel%")
                            // 위젯 업데이트를 다시 트리거
                            updateAllWidgets(context)
                        }
                    }
                }

                return KeyboardInfo(deviceName ?: "Keyboard", batteryLevel)
            } else {
                Log.d(TAG, "No connected keyboard found")
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
