package com.minsoo.ultranavbar.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.minsoo.ultranavbar.MainActivity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager

class SetupActivity : AppCompatActivity() {

    companion object {
        // 0: 접근성, 1: 오버레이, 2: 시스템 설정 수정, 3: 배터리, 4: 배경화면 읽기, 5: 블루투스, 6: ADB
        private const val TOTAL_STEPS = 7
    }

    private lateinit var settings: SettingsManager

    private lateinit var setupStepTitle: TextView
    private lateinit var setupStepDesc: TextView
    private lateinit var setupStepStatus: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var currentStep = 0

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted_bluetooth, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.permission_denied_bluetooth, Toast.LENGTH_SHORT).show()
        }
        updateStepStatus()
    }

    private val wallpaperPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, R.string.setup_wallpaper_already_granted, Toast.LENGTH_SHORT).show()
        }
        updateStepStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        settings = SettingsManager.getInstance(this)

        initViews()
        showStep(currentStep)
    }

    override fun onResume() {
        super.onResume()
        updateStepStatus()
    }

    private fun initViews() {
        setupStepTitle = findViewById(R.id.setupStepTitle)
        setupStepDesc = findViewById(R.id.setupStepDesc)
        setupStepStatus = findViewById(R.id.setupStepStatus)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnSkip = findViewById(R.id.btnSkip)
        btnNext = findViewById(R.id.btnNext)

        btnGrantPermission.setOnClickListener {
            grantCurrentStepPermission()
        }

        btnSkip.setOnClickListener {
            nextStep()
        }

        btnNext.setOnClickListener {
            nextStep()
        }
    }

    private fun showStep(step: Int) {
        when (step) {
            0 -> {
                setupStepTitle.text = getString(R.string.setup_accessibility_title)
                setupStepDesc.text = getString(R.string.setup_accessibility_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            1 -> {
                // 다른 앱 위에 표시
                setupStepTitle.text = getString(R.string.setup_overlay_title)
                setupStepDesc.text = getString(R.string.setup_overlay_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            2 -> {
                // 시스템 설정 수정
                setupStepTitle.text = getString(R.string.setup_write_settings_title)
                setupStepDesc.text = getString(R.string.setup_write_settings_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            3 -> {
                setupStepTitle.text = getString(R.string.setup_battery_title)
                setupStepDesc.text = getString(R.string.setup_battery_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            4 -> {
                // 배경화면 읽기 권한
                setupStepTitle.text = getString(R.string.setup_wallpaper_title)
                setupStepDesc.text = getString(R.string.setup_wallpaper_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            5 -> {
                setupStepTitle.text = getString(R.string.setup_bluetooth_title)
                setupStepDesc.text = getString(R.string.setup_bluetooth_desc)
                btnGrantPermission.text = getString(R.string.setup_grant_permission)
                btnNext.text = getString(R.string.setup_next)
            }
            6 -> {
                // ADB 권한 단계 (마지막, 선택사항)
                setupStepTitle.text = getString(R.string.setup_adb_title)
                val guideText = getString(R.string.setup_adb_guide) +
                        "\n\n" + getString(R.string.setup_adb_command) +
                        "\n\n" + getString(R.string.pen_settings_permission_note)
                setupStepDesc.text = guideText
                btnGrantPermission.text = getString(R.string.setup_check_permission)
                btnNext.text = getString(R.string.setup_complete)
            }
        }

        updateStepStatus()
    }

    private fun updateStepStatus() {
        val isGranted = when (currentStep) {
            0 -> NavBarAccessibilityService.isRunning()
            1 -> Settings.canDrawOverlays(this)
            2 -> Settings.System.canWrite(this)
            3 -> {
                val powerManager = getSystemService(PowerManager::class.java)
                powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
            }
            4 -> {
                ContextCompat.checkSelfPermission(
                    this,
                    getWallpaperPermission()
                ) == PackageManager.PERMISSION_GRANTED
            }
            5 -> {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            }
            6 -> {
                checkWriteSecureSettingsPermission()
            }
            else -> false
        }

        if (currentStep == 6) {
            setupStepStatus.text = if (isGranted) {
                getString(R.string.setup_adb_status_granted)
            } else {
                getString(R.string.setup_adb_status_not_granted)
            }
        } else {
            setupStepStatus.text = if (isGranted) {
                getString(R.string.permission_status_granted)
            } else {
                getString(R.string.permission_status_not_granted)
            }
        }
    }

    private fun checkWriteSecureSettingsPermission(): Boolean {
        return try {
            val current = Settings.Global.getInt(
                contentResolver,
                "pen_pointer",
                0
            )
            Settings.Global.putInt(
                contentResolver,
                "pen_pointer",
                current
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun grantCurrentStepPermission() {
        when (currentStep) {
            0 -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            1 -> {
                // 다른 앱 위에 표시 권한
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.setup_overlay_already_granted, Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                // 시스템 설정 수정 권한
                if (!Settings.System.canWrite(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.setup_write_settings_already_granted, Toast.LENGTH_SHORT).show()
                }
            }
            3 -> {
                requestIgnoreBatteryOptimizations()
            }
            4 -> {
                // 배경화면 읽기 권한
                val perm = getWallpaperPermission()
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    wallpaperPermissionLauncher.launch(perm)
                } else {
                    Toast.makeText(this, R.string.setup_wallpaper_already_granted, Toast.LENGTH_SHORT).show()
                }
            }
            5 -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    Toast.makeText(this, R.string.bluetooth_permission_already_granted, Toast.LENGTH_SHORT).show()
                }
            }
            6 -> {
                if (checkWriteSecureSettingsPermission()) {
                    Toast.makeText(this, R.string.setup_adb_already_granted, Toast.LENGTH_SHORT).show()
                } else {
                    copyAdbCommandToClipboard()
                    Toast.makeText(this, R.string.pen_permission_failed, Toast.LENGTH_LONG).show()
                }
                updateStepStatus()
            }
        }
    }

    private fun copyAdbCommandToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ADB Command", getString(R.string.setup_adb_command))
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
    }

    private fun getWallpaperPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, R.string.battery_opt_already_ignored, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.battery_opt_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextStep() {
        if (currentStep < TOTAL_STEPS - 1) {
            currentStep++
            showStep(currentStep)
        } else {
            completeSetup()
        }
    }

    private fun completeSetup() {
        settings.setupComplete = true
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
