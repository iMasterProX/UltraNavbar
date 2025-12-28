package com.minsoo.ultranavbar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.model.HideMode
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.ui.AppListActivity
import com.minsoo.ultranavbar.util.ImageCropUtil

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager

    // UI 요소
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var onboardingCard: MaterialCardView

    private lateinit var seekBarHeight: SeekBar
    private lateinit var seekButtonSize: SeekBar
    private lateinit var seekOpacity: SeekBar
    private lateinit var txtBarHeight: TextView
    private lateinit var txtButtonSize: TextView
    private lateinit var txtOpacity: TextView

    private lateinit var switchAutoHideVideo: SwitchMaterial
    private lateinit var radioBlacklist: RadioButton
    private lateinit var radioWhitelist: RadioButton
    private lateinit var switchHotspot: SwitchMaterial

    // 홈 배경 관련
    private lateinit var switchHomeBg: SwitchMaterial
    private lateinit var txtLandscapeStatus: TextView
    private lateinit var txtPortraitStatus: TextView

    // 이미지 선택 모드 (true = 가로, false = 세로)
    private var selectingLandscape = true

    // 이미지 선택 런처
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager.getInstance(this)

        initViews()
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateBgImageStatus()
    }

    private fun initViews() {
        // 상태 표시
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        onboardingCard = findViewById(R.id.onboardingCard)

        // 접근성 설정 버튼
        findViewById<MaterialButton>(R.id.btnOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        // 오버레이 설정
        seekBarHeight = findViewById(R.id.seekBarHeight)
        seekButtonSize = findViewById(R.id.seekButtonSize)
        seekOpacity = findViewById(R.id.seekOpacity)
        txtBarHeight = findViewById(R.id.txtBarHeight)
        txtButtonSize = findViewById(R.id.txtButtonSize)
        txtOpacity = findViewById(R.id.txtOpacity)

        // 자동 숨김 설정
        switchAutoHideVideo = findViewById(R.id.switchAutoHideVideo)
        radioBlacklist = findViewById(R.id.radioBlacklist)
        radioWhitelist = findViewById(R.id.radioWhitelist)

        // 앱 목록 관리 버튼
        findViewById<MaterialButton>(R.id.btnManageApps).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        // 재호출 설정
        switchHotspot = findViewById(R.id.switchHotspot)

        // 홈 배경 설정
        switchHomeBg = findViewById(R.id.switchHomeBg)
        txtLandscapeStatus = findViewById(R.id.txtLandscapeStatus)
        txtPortraitStatus = findViewById(R.id.txtPortraitStatus)

        // 가로 이미지 선택 버튼
        findViewById<MaterialButton>(R.id.btnSelectLandscape).setOnClickListener {
            selectingLandscape = true
            imagePickerLauncher.launch("image/*")
        }

        // 세로 이미지 선택 버튼
        findViewById<MaterialButton>(R.id.btnSelectPortrait).setOnClickListener {
            selectingLandscape = false
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun loadSettings() {
        // 오버레이 설정 로드
        seekBarHeight.progress = settings.barHeight
        seekButtonSize.progress = settings.buttonSize
        seekOpacity.progress = (settings.barOpacity * 100).toInt()

        txtBarHeight.text = "${settings.barHeight} dp"
        txtButtonSize.text = "${settings.buttonSize} dp"
        txtOpacity.text = "${(settings.barOpacity * 100).toInt()}%"

        // 자동 숨김 설정 로드
        switchAutoHideVideo.isChecked = settings.autoHideOnVideo
        when (settings.hideMode) {
            HideMode.BLACKLIST -> radioBlacklist.isChecked = true
            HideMode.WHITELIST -> radioWhitelist.isChecked = true
        }

        // 재호출 설정 로드
        switchHotspot.isChecked = settings.hotspotEnabled

        // 홈 배경 설정 로드
        switchHomeBg.isChecked = settings.homeBgEnabled
    }

    private fun setupListeners() {
        // 바 높이
        seekBarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val height = progress.coerceAtLeast(24)  // 최소 24dp
                txtBarHeight.text = "$height dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val height = (seekBar?.progress ?: 48).coerceAtLeast(24)
                settings.barHeight = height
                notifySettingsChanged()
            }
        })

        // 버튼 크기
        seekButtonSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceAtLeast(20)  // 최소 20dp
                txtButtonSize.text = "$size dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val size = (seekBar?.progress ?: 40).coerceAtLeast(20)
                settings.buttonSize = size
                notifySettingsChanged()
            }
        })

        // 투명도
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtOpacity.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val opacity = (seekBar?.progress ?: 85) / 100f
                settings.barOpacity = opacity
                notifySettingsChanged()
            }
        })

        // 영상 자동 숨김
        switchAutoHideVideo.setOnCheckedChangeListener { _, isChecked ->
            settings.autoHideOnVideo = isChecked
        }

        // 숨김 모드
        radioBlacklist.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                settings.hideMode = HideMode.BLACKLIST
            }
        }

        radioWhitelist.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                settings.hideMode = HideMode.WHITELIST
            }
        }

        // 재호출 핫스팟
        switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            settings.hotspotEnabled = isChecked
            notifySettingsChanged()
        }

        // 홈 배경 활성화
        switchHomeBg.setOnCheckedChangeListener { _, isChecked ->
            settings.homeBgEnabled = isChecked
            notifySettingsChanged()
        }
    }

    private fun handleImageSelected(uri: Uri) {
        val success = ImageCropUtil.cropAndSaveFromUri(this, uri, selectingLandscape)

        if (success) {
            Toast.makeText(this, R.string.image_crop_success, Toast.LENGTH_SHORT).show()
            updateBgImageStatus()
            notifySettingsChanged()
        } else {
            Toast.makeText(this, R.string.image_crop_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBgImageStatus() {
        val hasLandscape = ImageCropUtil.hasBackgroundImage(this, true)
        val hasPortrait = ImageCropUtil.hasBackgroundImage(this, false)

        txtLandscapeStatus.text = if (hasLandscape) {
            getString(R.string.home_bg_set)
        } else {
            getString(R.string.home_bg_not_set)
        }

        txtPortraitStatus.text = if (hasPortrait) {
            getString(R.string.home_bg_set)
        } else {
            getString(R.string.home_bg_not_set)
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

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun notifySettingsChanged() {
        sendBroadcast(Intent("com.minsoo.ultranavbar.SETTINGS_CHANGED"))
    }

    companion object {
        fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
            val serviceName = "${context.packageName}/${serviceClass.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        }
    }
}
