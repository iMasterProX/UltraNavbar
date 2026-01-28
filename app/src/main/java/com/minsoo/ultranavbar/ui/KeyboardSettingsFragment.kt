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
import com.minsoo.ultranavbar.R

class KeyboardSettingsFragment : Fragment() {

    private lateinit var deviceListContainer: LinearLayout
    private lateinit var txtNoDevices: TextView
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnBluetoothSettings: MaterialButton
    private lateinit var btnManageShortcuts: MaterialButton

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

        btnRefresh.setOnClickListener {
            loadDevices()
        }

        btnBluetoothSettings.setOnClickListener {
            openBluetoothSettings()
        }

        btnManageShortcuts.setOnClickListener {
            openShortcutManagement()
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 권한 요청
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
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
            val deviceClass = device.bluetoothClass ?: return false
            val majorDeviceClass = deviceClass.majorDeviceClass
            val deviceClassCode = deviceClass.deviceClass

            // Major Device Class: PERIPHERAL (0x500) 및 Keyboard (0x40)
            return majorDeviceClass == 0x500 || (deviceClassCode and 0x40) != 0
        } catch (e: Exception) {
            return false
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
            val statusTextView = TextView(requireContext()).apply {
                text = "${getString(R.string.keyboard_connection_status)}: ${getString(R.string.keyboard_connected)}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
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
