package com.minsoo.ultranavbar.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.minsoo.ultranavbar.R

class KeyboardSettingsFragment : Fragment() {

    companion object {
        private const val TAG = "KeyboardSettings"
    }

    private lateinit var deviceListContainer: LinearLayout
    private lateinit var txtNoDevices: TextView
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnBluetoothSettings: MaterialButton
    private lateinit var btnManageShortcuts: MaterialButton
    private lateinit var switchBatteryNotification: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var sliderBatteryThreshold: Slider
    private lateinit var txtThresholdValue: TextView
    private lateinit var layoutBatteryThreshold: View

    private var bluetoothAdapter: BluetoothAdapter? = null

    // 블루투스 권한 요청 런처
    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            loadDevices()
        } else {
            showNoDevices()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_keyboard_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        initBluetooth()
        loadDevices()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    private fun initViews(view: View) {
        deviceListContainer = view.findViewById(R.id.deviceListContainer)
        txtNoDevices = view.findViewById(R.id.txtNoDevices)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnBluetoothSettings = view.findViewById(R.id.btnBluetoothSettings)
        btnManageShortcuts = view.findViewById(R.id.btnManageShortcuts)
        switchBatteryNotification = view.findViewById(R.id.switchBatteryNotification)
        sliderBatteryThreshold = view.findViewById(R.id.sliderBatteryThreshold)
        txtThresholdValue = view.findViewById(R.id.txtThresholdValue)
        layoutBatteryThreshold = view.findViewById(R.id.layoutBatteryThreshold)

        btnRefresh.setOnClickListener {
            loadDevices()
        }

        btnBluetoothSettings.setOnClickListener {
            openBluetoothSettings()
        }

        btnManageShortcuts.setOnClickListener {
            openShortcutManagement()
        }

        // 배터리 알림 설정
        val settings = com.minsoo.ultranavbar.settings.SettingsManager.getInstance(requireContext())

        // 배터리 알림 스위치
        switchBatteryNotification.isChecked = settings.batteryNotificationEnabled
        switchBatteryNotification.setOnCheckedChangeListener { _, isChecked ->
            settings.batteryNotificationEnabled = isChecked
            layoutBatteryThreshold.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 배터리 임계값 슬라이더
        sliderBatteryThreshold.value = settings.batteryLowThreshold.toFloat()
        updateThresholdText(settings.batteryLowThreshold)
        sliderBatteryThreshold.addOnChangeListener { _, value, _ ->
            val threshold = value.toInt()
            settings.batteryLowThreshold = threshold
            updateThresholdText(threshold)
        }

        // 초기 가시성 설정
        layoutBatteryThreshold.visibility = if (settings.batteryNotificationEnabled) View.VISIBLE else View.GONE
    }

    private fun updateThresholdText(threshold: Int) {
        txtThresholdValue.text = getString(R.string.battery_low_threshold_summary, threshold)
    }

    private fun openShortcutManagement() {
        val intent = Intent(requireContext(), KeyboardShortcutActivity::class.java)
        startActivity(intent)
    }

    private fun initBluetooth() {
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    private fun loadDevices() {
        deviceListContainer.removeAllViews()

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            showNoDevices()
            return
        }

        // 블루투스 권한 확인
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 권한 요청
            requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        try {
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            val keyboardDevices = pairedDevices?.filter { device ->
                isKeyboardDevice(device)
            } ?: emptyList()

            if (keyboardDevices.isEmpty()) {
                showNoDevices()
            } else {
                txtNoDevices.visibility = View.GONE
                // LG KBA10을 최우선으로 정렬
                val sortedDevices = keyboardDevices.sortedByDescending { device ->
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                return@sortedByDescending false
                            }
                        }
                        device.name?.startsWith("LG KBA10", ignoreCase = true) == true
                    } catch (e: Exception) {
                        false
                    }
                }
                sortedDevices.forEach { device ->
                    addDeviceCard(device)
                }
            }
        } catch (e: SecurityException) {
            showNoDevices()
        }
    }

    private fun isKeyboardDevice(device: BluetoothDevice): Boolean {
        try {
            // BluetoothDevice의 클래스를 확인하여 키보드 여부 판단
            val deviceClass = device.bluetoothClass ?: run {
                Log.w(TAG, "Device ${device.address} has no Bluetooth class")
                return false
            }
            val majorDeviceClass = deviceClass.majorDeviceClass
            val deviceClassCode = deviceClass.deviceClass

            // Major Device Class: PERIPHERAL (0x500)
            // Minor Device Class: Keyboard (0x40)
            val isPeripheral = majorDeviceClass == 0x500
            val isKeyboard = (deviceClassCode and 0x40) != 0

            Log.d(TAG, "Device ${device.address}: class=$deviceClassCode, major=$majorDeviceClass, " +
                    "isPeripheral=$isPeripheral, isKeyboard=$isKeyboard")

            return isPeripheral && isKeyboard
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device class: ${device.address}", e)
            return false
        }
    }

    /**
     * 배터리 레벨 가져오기 (리플렉션 사용)
     */
    private fun getDeviceBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 기기의 실제 연결 상태 확인 (리플렉션 사용)
     */
    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine connection status for ${device.address}")
            false
        }
    }

    private fun addDeviceCard(device: BluetoothDevice) {
        val cardView = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            radius = 8f
            cardElevation = 2f
            setContentPadding(16, 16, 16, 16)
        }

        val cardLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 기기 이름
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val deviceName = device.name ?: "Unknown Device"
            val displayName = if (deviceName.startsWith("LG KBA10", ignoreCase = true)) {
                "LG UltraTab Keyboard ($deviceName)"
            } else {
                deviceName
            }
            val nameTextView = TextView(requireContext()).apply {
                text = displayName
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            cardLayout.addView(nameTextView)

            // 연결 상태
            val isConnected = isDeviceConnected(device)
            val statusTextView = TextView(requireContext()).apply {
                val statusString = if (isConnected) {
                    getString(R.string.keyboard_connected)
                } else {
                    getString(R.string.keyboard_paired)
                }
                text = "${getString(R.string.keyboard_connection_status)}: $statusString"
                textSize = 14f
                val statusColor = if (isConnected) {
                    android.R.color.holo_green_dark
                } else {
                    android.R.color.darker_gray
                }
                setTextColor(ContextCompat.getColor(requireContext(), statusColor))
                setPadding(0, 8, 0, 0)
            }
            cardLayout.addView(statusTextView)

            // 주소
            val addressTextView = TextView(requireContext()).apply {
                text = device.address
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                setPadding(0, 4, 0, 0)
            }
            cardLayout.addView(addressTextView)

            // 배터리 정보 (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val batteryLevel = getDeviceBatteryLevel(device)

                    val batteryText = if (batteryLevel >= 0) {
                        "${getString(R.string.keyboard_battery_level)}: $batteryLevel%"
                    } else {
                        "${getString(R.string.keyboard_battery_level)}: ${getString(R.string.keyboard_battery_unknown)}"
                    }

                    val batteryTextView = TextView(requireContext()).apply {
                        text = batteryText
                        textSize = 14f
                        val color = when {
                            batteryLevel < 0 -> ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                            batteryLevel <= 20 -> ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                            batteryLevel <= 50 -> ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                            else -> ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                        }
                        setTextColor(color)
                        setPadding(0, 8, 0, 0)
                    }
                    cardLayout.addView(batteryTextView)
                } catch (e: Exception) {
                    // getBatteryLevel() 실패 시 무시
                }
            }

        } catch (e: SecurityException) {
            // 권한이 없는 경우 기본 정보만 표시
            val errorTextView = TextView(requireContext()).apply {
                text = "Bluetooth permission required"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            }
            cardLayout.addView(errorTextView)
        }

        cardView.addView(cardLayout)
        deviceListContainer.addView(cardView)
    }

    private fun showNoDevices() {
        txtNoDevices.visibility = View.VISIBLE
        deviceListContainer.removeAllViews()
    }

    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        startActivity(intent)
    }
}
