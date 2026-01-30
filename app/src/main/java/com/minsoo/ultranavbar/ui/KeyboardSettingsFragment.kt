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
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.util.BleGattBatteryReader
import com.minsoo.ultranavbar.util.BluetoothUtils

class KeyboardSettingsFragment : Fragment() {

    companion object {
        private const val TAG = "KeyboardSettings"
    }

    private lateinit var deviceListContainer: LinearLayout
    private lateinit var txtNoDevices: TextView
    private lateinit var layoutKba10NotFound: LinearLayout
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnBluetoothSettings: MaterialButton
    private lateinit var btnManageShortcuts: MaterialButton
    private lateinit var btnShortcutDisabledApps: MaterialButton
    private lateinit var switchKeyboardShortcuts: SwitchMaterial
    private lateinit var switchBatteryNotification: SwitchMaterial
    private lateinit var switchPersistentNotification: SwitchMaterial
    private lateinit var sliderBatteryThreshold: Slider
    private lateinit var txtThresholdValue: TextView
    private lateinit var layoutBatteryThreshold: View
    private lateinit var layoutShortcutButtons: LinearLayout

    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var settings: com.minsoo.ultranavbar.settings.SettingsManager

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
        settings = com.minsoo.ultranavbar.settings.SettingsManager.getInstance(requireContext())

        deviceListContainer = view.findViewById(R.id.deviceListContainer)
        txtNoDevices = view.findViewById(R.id.txtNoDevices)
        layoutKba10NotFound = view.findViewById(R.id.layoutKba10NotFound)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnBluetoothSettings = view.findViewById(R.id.btnBluetoothSettings)
        btnManageShortcuts = view.findViewById(R.id.btnManageShortcuts)
        btnShortcutDisabledApps = view.findViewById(R.id.btnShortcutDisabledApps)
        switchKeyboardShortcuts = view.findViewById(R.id.switchKeyboardShortcuts)
        switchBatteryNotification = view.findViewById(R.id.switchBatteryNotification)
        switchPersistentNotification = view.findViewById(R.id.switchPersistentNotification)
        sliderBatteryThreshold = view.findViewById(R.id.sliderBatteryThreshold)
        txtThresholdValue = view.findViewById(R.id.txtThresholdValue)
        layoutBatteryThreshold = view.findViewById(R.id.layoutBatteryThreshold)
        layoutShortcutButtons = view.findViewById(R.id.layoutShortcutButtons)

        btnRefresh.setOnClickListener {
            // 캐시 클리어 후 새로 로드 (BLE GATT 배터리 새로 읽기)
            BleGattBatteryReader.clearCache()
            loadDevices()
        }

        btnBluetoothSettings.setOnClickListener {
            openBluetoothSettings()
        }

        btnManageShortcuts.setOnClickListener {
            openShortcutManagement()
        }

        btnShortcutDisabledApps.setOnClickListener {
            openShortcutDisabledApps()
        }

        // 키보드 단축키 토글
        switchKeyboardShortcuts.isChecked = settings.keyboardShortcutsEnabled
        updateShortcutButtonsVisibility(settings.keyboardShortcutsEnabled)
        switchKeyboardShortcuts.setOnCheckedChangeListener { _, isChecked ->
            settings.keyboardShortcutsEnabled = isChecked
            updateShortcutButtonsVisibility(isChecked)
        }

        // 배터리 알림 스위치
        switchBatteryNotification.isChecked = settings.batteryNotificationEnabled
        switchBatteryNotification.setOnCheckedChangeListener { _, isChecked ->
            settings.batteryNotificationEnabled = isChecked
            layoutBatteryThreshold.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 지속 알림 스위치
        switchPersistentNotification.isChecked = settings.batteryPersistentNotificationEnabled
        switchPersistentNotification.setOnCheckedChangeListener { _, isChecked ->
            settings.batteryPersistentNotificationEnabled = isChecked
            if (isChecked) {
                // 활성화 시 즉시 배터리 체크 수행하여 알림 표시
                com.minsoo.ultranavbar.service.KeyboardBatteryMonitor.checkBatteryLevels(requireContext())
            } else {
                // 비활성화 시 지속 알림 취소
                com.minsoo.ultranavbar.service.KeyboardBatteryMonitor.cancelPersistentNotification(requireContext())
            }
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

    private fun updateShortcutButtonsVisibility(enabled: Boolean) {
        layoutShortcutButtons.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateThresholdText(threshold: Int) {
        txtThresholdValue.text = getString(R.string.battery_low_threshold_summary, threshold)
    }

    private fun openShortcutManagement() {
        val intent = Intent(requireContext(), KeyboardShortcutActivity::class.java)
        startActivity(intent)
    }

    private fun openShortcutDisabledApps() {
        val intent = Intent(requireContext(), AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SHORTCUT_DISABLED_APPS)
        }
        startActivity(intent)
    }

    private fun initBluetooth() {
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    private fun loadDevices() {
        deviceListContainer.removeAllViews()
        layoutKba10NotFound.visibility = View.GONE

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            showNoDevices()
            showKba10NotFoundWarning()
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
                BluetoothUtils.isKeyboardDevice(device, requireContext())
            } ?: emptyList()

            // LG KBA10 존재 여부 확인
            val hasLgKba10 = keyboardDevices.any { device ->
                try {
                    device.name?.startsWith("LG KBA10", ignoreCase = true) == true
                } catch (e: Exception) {
                    false
                }
            }

            if (keyboardDevices.isEmpty()) {
                showNoDevices()
                showKba10NotFoundWarning()
            } else {
                txtNoDevices.visibility = View.GONE

                // LG KBA10이 없으면 안내 표시
                if (!hasLgKba10) {
                    showKba10NotFoundWarning()
                }

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
                    val isLgKeyboard = try {
                        device.name?.startsWith("LG KBA10", ignoreCase = true) == true
                    } catch (e: Exception) {
                        false
                    }

                    // 서드파티 키보드인 경우 확인 다이얼로그 표시
                    if (!isLgKeyboard && !settings.thirdPartyKeyboardAccepted) {
                        showThirdPartyKeyboardDialog(device)
                    }

                    addDeviceCard(device, isLgKeyboard)

                    // BLE 기기일 경우 캐시가 없을 때만 GATT 배터리 읽기 트리거
                    if (BleGattBatteryReader.isBleOnlyDevice(device) &&
                        BleGattBatteryReader.getCachedBatteryLevel(device.address) == null) {
                        BluetoothUtils.triggerBleGattBatteryRead(requireContext(), device) { batteryLevel ->
                            // 배터리 정보가 새로 읽혔으면 UI 갱신 (한 번만)
                            if (batteryLevel != null && isAdded) {
                                activity?.runOnUiThread {
                                    loadDevices()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            showNoDevices()
            showKba10NotFoundWarning()
        }
    }

    private fun showKba10NotFoundWarning() {
        layoutKba10NotFound.visibility = View.VISIBLE
    }

    private fun showThirdPartyKeyboardDialog(device: BluetoothDevice) {
        if (!isAdded) return

        val deviceName = try {
            device.name ?: "Unknown Device"
        } catch (e: Exception) {
            "Unknown Device"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.third_party_keyboard_title)
            .setMessage(getString(R.string.third_party_keyboard_message))
            .setPositiveButton(R.string.third_party_keyboard_use) { _, _ ->
                settings.thirdPartyKeyboardAccepted = true
            }
            .setNegativeButton(R.string.third_party_keyboard_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun addDeviceCard(device: BluetoothDevice, isLgKeyboard: Boolean = false) {
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
            val displayName = if (isLgKeyboard) {
                "LG UltraTab Keyboard ($deviceName)"
            } else {
                "$deviceName (Third-Party)"
            }
            val nameTextView = TextView(requireContext()).apply {
                text = displayName
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            cardLayout.addView(nameTextView)

            // 연결 상태
            val isConnected = BluetoothUtils.isDeviceConnected(device)
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

            // 배터리 정보
            try {
                val batteryLevel = BluetoothUtils.getDeviceBatteryLevel(device)

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
                Log.w(TAG, "Failed to get battery level for ${device.address}", e)
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
