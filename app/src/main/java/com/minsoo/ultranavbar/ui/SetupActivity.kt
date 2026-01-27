package com.minsoo.ultranavbar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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

    private lateinit var settings: SettingsManager

    // UI 요소
    private lateinit var setupStepTitle: TextView
    private lateinit var setupStepDesc: TextView
    private lateinit var setupStepStatus: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnNext: MaterialButton

    // 현재 단계 (0: 접근성, 1: 저장소, 2: 배터리, 3: 블루투스)
    private var currentStep = 0

    // 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
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
                // 접근성 서비스 단계
                setupStepTitle.text = getString(R.string.setup_accessibility_title)
                setupStepDesc.text = getString(R.string.setup_accessibility_desc)
                btnNext.text = getString(R.string.setup_next)
            }
            1 -> {
                // 저장소 접근 단계
                setupStepTitle.text = getString(R.string.setup_storage_title)
                setupStepDesc.text = getString(R.string.setup_storage_desc)
                btnNext.text = getString(R.string.setup_next)
            }
            2 -> {
                // 배터리 최적화 단계
                setupStepTitle.text = getString(R.string.setup_battery_title)
                setupStepDesc.text = getString(R.string.setup_battery_desc)
                btnNext.text = getString(R.string.setup_next)
            }
            3 -> {
                // 블루투스 권한 단계
                setupStepTitle.text = getString(R.string.setup_bluetooth_title)
                setupStepDesc.text = getString(R.string.setup_bluetooth_desc)
                btnNext.text = getString(R.string.setup_complete)
            }
        }

        updateStepStatus()
    }

    private fun updateStepStatus() {
        val isGranted = when (currentStep) {
            0 -> NavBarAccessibilityService.isRunning()
            1 -> ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            2 -> {
                val powerManager = getSystemService(PowerManager::class.java)
                powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
            }
            3 -> {
                // 블루투스 권한 확인
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true  // Android 12 미만에서는 매니페스트 권한만으로 충분
                }
            }
            else -> false
        }

        setupStepStatus.text = if (isGranted) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }
    }

    private fun grantCurrentStepPermission() {
        when (currentStep) {
            0 -> {
                // 접근성 서비스 설정 열기
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            1 -> {
                // 저장소 권한 요청
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                // 배터리 최적화 제외 요청
                requestIgnoreBatteryOptimizations()
            }
            3 -> {
                // 블루투스 권한 요청
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                }
            }
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
        if (currentStep < 3) {
            currentStep++
            showStep(currentStep)
        } else {
            // 마지막 단계 완료
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
