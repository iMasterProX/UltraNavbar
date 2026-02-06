package com.minsoo.ultranavbar.ui

import android.Manifest
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
import com.minsoo.ultranavbar.util.ShizukuHelper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private lateinit var txtPermStorage: TextView
    private lateinit var txtPermBattery: TextView
    private lateinit var txtPermBluetooth: TextView
    private var txtPermShizuku: TextView? = null  // 레이아웃에 없을 수 있음

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
        txtPermStorage = view.findViewById(R.id.txtPermStorage)
        txtPermBattery = view.findViewById(R.id.txtPermBattery)
        txtPermBluetooth = view.findViewById(R.id.txtPermBluetooth)

        // 접근성 권한 버튼
        view.findViewById<MaterialButton>(R.id.btnPermAccessibility).setOnClickListener {
            openAccessibilitySettings()
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

        // Shizuku 권한 (레이아웃에 없으면 null)
        txtPermShizuku = view.findViewById(R.id.txtPermShizuku)
        view.findViewById<MaterialButton?>(R.id.btnPermShizuku)?.setOnClickListener {
            showShizukuSetupGuide()
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

        // Shizuku 권한 상태 확인 (레이아웃에 있는 경우에만)
        try {
            val isShizukuAvailable = ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission()
            txtPermShizuku?.text = when {
                !ShizukuHelper.isShizukuAvailable() -> getString(R.string.shizuku_status_not_installed)
                !ShizukuHelper.hasShizukuPermission() -> getString(R.string.shizuku_status_no_permission)
                else -> getString(R.string.shizuku_status_ready)
            }
        } catch (e: Exception) {
            // 레이아웃에 없으면 무시
        }
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
            <b>Shizuku</b><br>
            Copyright (c) RikkaApps<br>
            License: Apache License 2.0<br>
            <a href="https://github.com/RikkaApps/Shizuku">https://github.com/RikkaApps/Shizuku</a><br><br>

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

    private fun showShizukuSetupGuide() {
        val message = Html.fromHtml(getString(R.string.shizuku_setup_guide_message_experimental), Html.FROM_HTML_MODE_LEGACY)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shizuku_setup_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.shizuku_copy_command) { _, _ ->
                // ADB 명령어 클립보드 복사
                val command = "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Shizuku ADB Command", command)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.shizuku_command_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.shizuku_grant_permission) { _, _ ->
                // Shizuku 권한 요청
                if (ShizukuHelper.isShizukuAvailable()) {
                    if (ShizukuHelper.hasShizukuPermission()) {
                        Toast.makeText(requireContext(), R.string.shizuku_permission_already_granted, Toast.LENGTH_SHORT).show()
                    } else {
                        ShizukuHelper.requestShizukuPermission()
                        Toast.makeText(requireContext(), "권한 요청을 보냈습니다", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
