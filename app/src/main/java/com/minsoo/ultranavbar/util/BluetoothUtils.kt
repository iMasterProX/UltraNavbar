package com.minsoo.ultranavbar.util

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
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
     * 배터리 레벨 가져오기 (리플렉션 사용)
     */
    fun getDeviceBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
