package com.minsoo.ultranavbar.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import androidx.appcompat.app.AlertDialog
import android.text.Html

/**
 * AppSettingsFragment - 앱 전반적인 설정 및 권한 관리를 담당하는 Fragment
 *
 * 포함 기능:
 * - 서비스 상태 표시
 * - 권한 상태 확인 및 요청 (접근성, 저장소, 배터리 최적화)
 * - 앱 정보 표시 (버전, 개발자, GitHub 등)
 */
class AppSettingsFragment : Fragment() {

    companion object {
        /**
         * API 레벨에 따라 적절한 저장소 권한 반환
         */
        private fun getStoragePermission(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
    }

    private lateinit var settings: SettingsManager

    // 서비스 상태 UI
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var onboardingCard: MaterialCardView

    // 권한 상태 UI
    private lateinit var txtPermAccessibility: TextView
    private lateinit var txtPermOverlay: TextView
    private lateinit var txtPermWriteSettings: TextView
    private lateinit var txtPermStorage: TextView
    private lateinit var txtPermBattery: TextView
    private lateinit var txtPermBluetooth: TextView
    private lateinit var txtPermAdb: TextView

    // 버전 정보 UI
    private lateinit var txtVersion: TextView

    // 저장소 읽기 권한 요청 런처
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(requireContext(), R.string.permission_granted_storage, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
        updatePermissionStatus()
    }

    // 블루투스 권한 요청 런처
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(requireContext(), R.string.permission_granted_bluetooth, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.permission_denied_bluetooth, Toast.LENGTH_SHORT).show()
        }
        updatePermissionStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_app_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsManager.getInstance(requireContext())

        initViews(view)
        updateServiceStatus()
        updatePermissionStatus()
        loadVersionInfo()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updatePermissionStatus()
    }

    private fun initViews(view: View) {
        // 서비스 상태 UI
        statusIndicator = view.findViewById(R.id.statusIndicator)
        statusText = view.findViewById(R.id.statusText)
        onboardingCard = view.findViewById(R.id.onboardingCard)

        // 접근성 설정 버튼
        view.findViewById<MaterialButton>(R.id.btnOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        // 권한 상태 UI
        txtPermAccessibility = view.findViewById(R.id.txtPermAccessibility)
        txtPermOverlay = view.findViewById(R.id.txtPermOverlay)
        txtPermWriteSettings = view.findViewById(R.id.txtPermWriteSettings)
        txtPermStorage = view.findViewById(R.id.txtPermStorage)
        txtPermBattery = view.findViewById(R.id.txtPermBattery)
        txtPermBluetooth = view.findViewById(R.id.txtPermBluetooth)

        // 접근성 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        // 다른 앱 위에 표시 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermOverlay).setOnClickListener {
            requestOverlayPermission()
        }

        // 시스템 설정 수정 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermWriteSettings).setOnClickListener {
            requestWriteSettingsPermission()
        }

        // 저장소 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermStorage).setOnClickListener {
            requestStoragePermission()
        }

        // 배터리 최적화 버튼
        view.findViewById<MaterialButton>(R.id.btnPermBattery).setOnClickListener {
            requestIgnoreBatteryOptimizations(isTriggeredByUser = true)
        }

        // 블루투스 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermBluetooth).setOnClickListener {
            requestBluetoothPermission()
        }

        // ADB 권한 상태 UI
        txtPermAdb = view.findViewById(R.id.txtPermAdb)

        // ADB 권한 가이드 버튼
        view.findViewById<MaterialButton>(R.id.btnPermAdb).setOnClickListener {
            showAdbPermissionGuideDialog()
        }

        // 버전 정보 UI
        txtVersion = view.findViewById(R.id.txtVersion)

        // GitHub 링크 클릭 → 브라우저
        view.findViewById<TextView>(R.id.txtGithubLink).setOnClickListener {
            val url = getString(R.string.about_github_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // 오픈소스 라이선스 버튼
        view.findViewById<MaterialButton>(R.id.btnOpenSourceLicenses).setOnClickListener {
            showOpenSourceLicensesDialog()
        }
    }

    private fun updateServiceStatus() {
        val isRunning = NavBarAccessibilityService.isRunning()

        if (isRunning) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_active)
            statusText.text = getString(R.string.service_enabled)
            onboardingCard.visibility = View.GONE
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator)
            statusText.text = getString(R.string.service_disabled)
            onboardingCard.visibility = View.VISIBLE
        }
    }

    private fun updatePermissionStatus() {
        // 접근성 서비스 상태 확인
        val isAccessibilityGranted = NavBarAccessibilityService.isRunning()
        txtPermAccessibility.text = if (isAccessibilityGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // 다른 앱 위에 표시 권한 상태 확인
        val isOverlayGranted = Settings.canDrawOverlays(requireContext())
        txtPermOverlay.text = if (isOverlayGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // 시스템 설정 수정 권한 상태 확인
        val isWriteSettingsGranted = Settings.System.canWrite(requireContext())
        txtPermWriteSettings.text = if (isWriteSettingsGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // 저장소 권한 상태 확인
        val isStorageGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            getStoragePermission()
        ) == PackageManager.PERMISSION_GRANTED

        txtPermStorage.text = if (isStorageGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // 배터리 최적화 제외 상태 확인
        val powerManager = requireContext().getSystemService(PowerManager::class.java)
        val isBatteryOptimizationGranted = powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) ?: false

        txtPermBattery.text = if (isBatteryOptimizationGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // 블루투스 권한 상태 확인
        val isBluetoothGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        txtPermBluetooth.text = if (isBluetoothGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        // ADB 권한 (WRITE_SECURE_SETTINGS) 상태 확인
        val isAdbGranted = checkWriteSecureSettingsPermission()
        txtPermAdb.text = if (isAdbGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }
    }

    /**
     * WRITE_SECURE_SETTINGS 권한 확인
     */
    private fun checkWriteSecureSettingsPermission(): Boolean {
        return try {
            val current = Settings.Global.getInt(
                requireContext().contentResolver,
                "pen_pointer",
                0
            )
            Settings.Global.putInt(
                requireContext().contentResolver,
                "pen_pointer",
                current
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * ADB 권한 설정 가이드 다이얼로그
     */
    private fun showAdbPermissionGuideDialog() {
        val message = getString(R.string.setup_adb_guide) +
                "\n\n" + getString(R.string.setup_adb_command) +
                "\n\n" + getString(R.string.pen_settings_permission_note)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.permission_adb_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.pen_settings_check_permission) { _, _ ->
                // 권한 재확인
                if (checkWriteSecureSettingsPermission()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.setup_adb_already_granted,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.pen_permission_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
                updatePermissionStatus()
            }
            .setNeutralButton(R.string.copy_command) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", getString(R.string.setup_adb_command))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.command_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadVersionInfo() {
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            txtVersion.text = packageInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            txtVersion.text = "1.0.0"
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                getStoragePermission()
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(getStoragePermission())
        } else {
            Toast.makeText(requireContext(), R.string.storage_permission_already_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations(isTriggeredByUser: Boolean = false) {
        try {
            val powerManager = requireContext().getSystemService(PowerManager::class.java)
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                if (!isTriggeredByUser && settings.batteryOptRequested) {
                    return
                }
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(intent)
                } else if (isTriggeredByUser) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                settings.batteryOptRequested = true
            } else {
                if (isTriggeredByUser) {
                    Toast.makeText(requireContext(), R.string.battery_opt_already_ignored, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            if (isTriggeredByUser) {
                Toast.makeText(requireContext(), R.string.battery_opt_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(requireContext())) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.setup_overlay_already_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestWriteSettingsPermission() {
        if (!Settings.System.canWrite(requireContext())) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${requireContext().packageName}")
            )
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.setup_write_settings_already_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBluetoothPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            Toast.makeText(requireContext(), R.string.bluetooth_permission_already_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOpenSourceLicensesDialog() {
        val licenses = """
            <b>AndroidX Libraries</b><br>
            Copyright (c) The Android Open Source Project<br>
            (core-ktx, appcompat, constraintlayout, preference, recyclerview, lifecycle)<br>
            License: Apache License 2.0<br><br>

            <b>Material Components for Android</b><br>
            Copyright (c) Google LLC<br>
            License: Apache License 2.0<br><br>

            <b>Kotlin Coroutines</b><br>
            Copyright (c) JetBrains s.r.o.<br>
            License: Apache License 2.0<br><br>

            <hr>
            <small>Apache License 2.0:<br>
            Licensed under the Apache License, Version 2.0. You may obtain a copy of the License at
            <a href="https://www.apache.org/licenses/LICENSE-2.0">https://www.apache.org/licenses/LICENSE-2.0</a></small>
        """.trimIndent()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.open_source_licenses_title)
            .setMessage(Html.fromHtml(licenses, Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // 다이얼로그 내 링크 클릭 가능하게 설정
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            android.text.method.LinkMovementMethod.getInstance()
    }
}
