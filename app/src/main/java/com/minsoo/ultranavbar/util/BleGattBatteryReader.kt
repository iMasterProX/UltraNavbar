package com.minsoo.ultranavbar.util

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE GATT를 통해 배터리 레벨을 읽는 유틸리티
 * BLE HID 기기(LG KBA10 등)는 표준 Bluetooth API로 배터리를 읽을 수 없음
 * GATT Battery Service(0x180F)를 통해 읽어야 함
 */
object BleGattBatteryReader {

    private const val TAG = "BleGattBatteryReader"

    // Battery Service UUID
    private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    // Battery Level Characteristic UUID
    private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // 배터리 레벨 캐시 (주소 -> 배터리 레벨)
    private val batteryCache = ConcurrentHashMap<String, Int>()
    // 캐시 타임스탬프 (주소 -> 타임스탬프)
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()
    // 캐시 유효 시간 (10분)
    private const val CACHE_VALIDITY_MS = 10 * 60 * 1000L

    // 현재 진행 중인 연결 (주소 -> GATT)
    private val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()
    // 읽기 진행 중인 기기
    private val readingInProgress = ConcurrentHashMap.newKeySet<String>()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 캐시된 배터리 레벨 반환 (캐시가 유효하면)
     * @return 배터리 레벨 (0-100) 또는 null (캐시 없음/만료)
     */
    fun getCachedBatteryLevel(address: String): Int? {
        val timestamp = cacheTimestamps[address] ?: return null
        val level = batteryCache[address] ?: return null

        if (System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS) {
            // 캐시 만료
            batteryCache.remove(address)
            cacheTimestamps.remove(address)
            return null
        }

        return level
    }

    /**
     * BLE GATT를 통해 배터리 레벨 읽기 (비동기)
     * 결과는 캐시에 저장됨
     *
     * @param context Context
     * @param device 블루투스 기기
     * @param callback 결과 콜백 (배터리 레벨 또는 null)
     */
    fun readBatteryLevel(context: Context, device: BluetoothDevice, callback: ((Int?) -> Unit)? = null) {
        val address = device.address

        // 이미 진행 중이면 무시
        if (!readingInProgress.add(address)) {
            Log.d(TAG, "Battery reading already in progress for $address")
            callback?.invoke(getCachedBatteryLevel(address))
            return
        }

        // 권한 확인
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")
            readingInProgress.remove(address)
            callback?.invoke(null)
            return
        }

        Log.d(TAG, "Starting GATT battery read for ${device.name ?: address}")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Connection state changed: status=$status, newState=$newState")

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to GATT server, discovering services...")
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException during service discovery", e)
                            cleanup(gatt, address, callback)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from GATT server")
                        cleanup(gatt, address, callback)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "Services discovered: status=$status")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed with status $status")
                    cleanup(gatt, address, callback)
                    return
                }

                // Battery Service 찾기
                val batteryService: BluetoothGattService? = gatt.getService(BATTERY_SERVICE_UUID)
                if (batteryService == null) {
                    Log.w(TAG, "Battery Service not found on device")
                    cleanup(gatt, address, callback)
                    return
                }

                // Battery Level Characteristic 찾기
                val batteryLevelChar = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
                if (batteryLevelChar == null) {
                    Log.w(TAG, "Battery Level Characteristic not found")
                    cleanup(gatt, address, callback)
                    return
                }

                // 배터리 레벨 읽기
                Log.d(TAG, "Reading battery level characteristic...")
                try {
                    val success = gatt.readCharacteristic(batteryLevelChar)
                    if (!success) {
                        Log.w(TAG, "Failed to initiate characteristic read")
                        cleanup(gatt, address, callback)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during characteristic read", e)
                    cleanup(gatt, address, callback)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                    val batteryLevel = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                    Log.d(TAG, "Battery level read: $batteryLevel%")

                    if (batteryLevel in 0..100) {
                        // 캐시에 저장
                        batteryCache[address] = batteryLevel
                        cacheTimestamps[address] = System.currentTimeMillis()

                        handler.post {
                            callback?.invoke(batteryLevel)
                        }
                    } else {
                        handler.post {
                            callback?.invoke(null)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to read battery level: status=$status")
                    handler.post {
                        callback?.invoke(null)
                    }
                }

                cleanup(gatt, address, null)
            }

            // Android 13+ 버전용
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                    val batteryLevel = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                    Log.d(TAG, "Battery level read (API 33+): $batteryLevel%")

                    if (batteryLevel in 0..100) {
                        batteryCache[address] = batteryLevel
                        cacheTimestamps[address] = System.currentTimeMillis()

                        handler.post {
                            callback?.invoke(batteryLevel)
                        }
                    } else {
                        handler.post {
                            callback?.invoke(null)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to read battery level (API 33+): status=$status")
                    handler.post {
                        callback?.invoke(null)
                    }
                }

                cleanup(gatt, address, null)
            }
        }

        // GATT 연결
        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            if (gatt != null) {
                activeConnections[address] = gatt

                // 타임아웃 설정 (15초)
                handler.postDelayed({
                    if (readingInProgress.contains(address)) {
                        Log.w(TAG, "GATT operation timed out for $address")
                        cleanup(gatt, address, callback)
                    }
                }, 15000)
            } else {
                Log.w(TAG, "Failed to create GATT connection")
                readingInProgress.remove(address)
                callback?.invoke(null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during GATT connect", e)
            readingInProgress.remove(address)
            callback?.invoke(null)
        }
    }

    private fun cleanup(gatt: BluetoothGatt, address: String, callback: ((Int?) -> Unit)?) {
        readingInProgress.remove(address)
        activeConnections.remove(address)

        try {
            gatt.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT: ${e.message}")
        }

        // 콜백이 아직 호출되지 않았다면 캐시된 값 반환
        if (callback != null) {
            handler.post {
                callback.invoke(getCachedBatteryLevel(address))
            }
        }
    }

    /**
     * 모든 활성 연결 정리
     */
    fun cleanup() {
        activeConnections.values.forEach { gatt ->
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup: ${e.message}")
            }
        }
        activeConnections.clear()
        readingInProgress.clear()
    }

    /**
     * 캐시 클리어 (새로고침 시 사용)
     */
    fun clearCache() {
        batteryCache.clear()
        cacheTimestamps.clear()
        Log.d(TAG, "Battery cache cleared")
    }

    /**
     * 기기가 BLE 전용인지 확인
     */
    fun isBleOnlyDevice(device: BluetoothDevice): Boolean {
        return try {
            device.type == BluetoothDevice.DEVICE_TYPE_LE
        } catch (e: Exception) {
            false
        }
    }
}
