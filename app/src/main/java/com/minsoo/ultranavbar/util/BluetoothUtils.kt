package com.minsoo.ultranavbar.util

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * 블루투스 관련 유틸리티 함수
 */
object BluetoothUtils {

    private const val TAG = "BluetoothUtils"

    /**
     * 키보드 기기인지 확인
     *
     * 검증 방법:
     * 1. BluetoothClass 기반 감지 (PERIPHERAL + Keyboard bit)
     * 2. 이름 기반 fallback 감지 (keyboard, LG KBA 등)
     *
     * @param device 확인할 블루투스 기기
     * @param context Context (기기 이름 접근용)
     * @return 키보드 기기이면 true
     */
    fun isKeyboardDevice(device: BluetoothDevice, context: Context? = null): Boolean {
        try {
            // 1. BluetoothClass 기반 감지
            val deviceClass = device.bluetoothClass
            if (deviceClass != null) {
                val majorDeviceClass = deviceClass.majorDeviceClass
                val deviceClassCode = deviceClass.deviceClass

                // Major Device Class: PERIPHERAL (0x500)
                // Minor Device Class: Keyboard (0x40)
                val isPeripheral = majorDeviceClass == 0x500
                val isKeyboard = (deviceClassCode and 0x40) != 0

                if (isPeripheral && isKeyboard) {
                    Log.d(TAG, "Device ${device.address} identified as keyboard by class")
                    return true
                }
            }

            // 2. 이름 기반 fallback 감지
            val deviceName = getDeviceName(device, context)
            if (deviceName != null) {
                val nameLower = deviceName.lowercase()

                // 일반적인 키보드 이름 패턴
                val keyboardPatterns = listOf(
                    "keyboard",
                    "kb",
                    "lg kba",  // LG 키보드
                    "magic keyboard",  // Apple
                    "surface keyboard",  // Microsoft
                    "designer keyboard",  // Microsoft
                    "k380",  // Logitech
                    "k480",
                    "mx keys"
                )

                for (pattern in keyboardPatterns) {
                    if (nameLower.contains(pattern)) {
                        Log.d(TAG, "Device ${device.address} ($deviceName) identified as keyboard by name")
                        return true
                    }
                }
            }

            Log.d(TAG, "Device ${device.address} ($deviceName) is NOT a keyboard")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking if device is keyboard: ${device.address}", e)
            return false
        }
    }

    /**
     * 기기 이름 가져오기 (권한 체크 포함)
     */
    fun getDeviceName(device: BluetoothDevice, context: Context?): String? {
        return try {
            if (context != null && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                null
            } else {
                device.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting device name: ${device.address}", e)
            null
        }
    }

    /**
     * 기기 연결 상태 확인 (리플렉션 사용)
     */
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine connection status for ${device.address}")
            false
        }
    }

    /**
     * 배터리 레벨 가져오기 (여러 방법 시도)
     *
     * 시도 순서:
     * 1. BLE GATT 캐시 (BLE 기기일 경우)
     * 2. getBatteryLevel() - Android 13+ 공식 API
     * 3. getBattery() - 일부 제조사 hidden API
     * 4. getMetadata(6) - METADATA_UNTETHERED_LEFT_BATTERY
     * 5. getMetadata(7) - METADATA_UNTETHERED_RIGHT_BATTERY
     * 6. getMetadata(8) - METADATA_UNTETHERED_CASE_BATTERY
     *
     * @return 배터리 레벨 (0-100) 또는 -1 (사용 불가)
     */
    fun getDeviceBatteryLevel(device: BluetoothDevice): Int {
        val address = device.address

        // 방법 0: BLE GATT 캐시 확인 (BLE 기기일 경우)
        val cachedLevel = BleGattBatteryReader.getCachedBatteryLevel(address)
        if (cachedLevel != null) {
            Log.d(TAG, "Battery via BLE GATT cache: $cachedLevel%")
            return cachedLevel
        }

        // 방법 1: 공식 API (Android 13+)
        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val result = method.invoke(device) as? Int
            if (result != null && result >= 0) {
                Log.d(TAG, "Battery via getBatteryLevel(): $result%")
                return result
            }
        } catch (e: Exception) {
            Log.v(TAG, "getBatteryLevel() failed: ${e.message}")
        }

        // 방법 2: getBattery() hidden API
        try {
            val method = device.javaClass.getMethod("getBattery")
            val result = method.invoke(device) as? Int
            if (result != null && result >= 0) {
                Log.d(TAG, "Battery via getBattery(): $result%")
                return result
            }
        } catch (e: Exception) {
            Log.v(TAG, "getBattery() failed: ${e.message}")
        }

        // 방법 3-5: getMetadata() - 일부 기기에서 사용
        val metadataKeys = listOf(
            6,  // METADATA_UNTETHERED_LEFT_BATTERY
            7,  // METADATA_UNTETHERED_RIGHT_BATTERY
            8   // METADATA_UNTETHERED_CASE_BATTERY
        )

        for (key in metadataKeys) {
            try {
                val method = device.javaClass.getMethod("getMetadata", Int::class.javaPrimitiveType)
                val result = method.invoke(device, key) as? ByteArray
                if (result != null && result.isNotEmpty()) {
                    val batteryStr = String(result)
                    val battery = batteryStr.toIntOrNull()
                    if (battery != null && battery >= 0 && battery <= 100) {
                        Log.d(TAG, "Battery via getMetadata($key): $battery%")
                        return battery
                    }
                }
            } catch (e: Exception) {
                // 다음 방법 시도
            }
        }

        Log.w(TAG, "Could not get battery level for ${device.address} using any method")
        return -1
    }

    /**
     * BLE GATT를 통해 배터리 레벨 읽기 시작 (비동기)
     * UI 업데이트가 필요한 경우 callback 사용
     */
    fun triggerBleGattBatteryRead(context: Context, device: BluetoothDevice, callback: ((Int?) -> Unit)? = null) {
        if (BleGattBatteryReader.isBleOnlyDevice(device)) {
            Log.d(TAG, "Triggering BLE GATT battery read for ${device.address}")
            BleGattBatteryReader.readBatteryLevel(context, device, callback)
        } else {
            // BLE 전용 기기가 아니면 일반 방법으로 읽기
            val level = getDeviceBatteryLevel(device)
            callback?.invoke(if (level >= 0) level else null)
        }
    }
}
