package com.minsoo.ultranavbar

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.ui.AppListActivity
import com.minsoo.ultranavbar.ui.WallpaperPreviewActivity
import com.minsoo.ultranavbar.util.DeviceProfile
import com.minsoo.ultranavbar.util.ImageCropUtil
import com.minsoo.ultranavbar.util.WallpaperProcessor
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager

    // UI 요소
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var onboardingCard: MaterialCardView

    // 롱프레스 설정
    private lateinit var txtLongPressAction: TextView
    private lateinit var btnChangeLongPressAction: MaterialButton
    private lateinit var btnSelectShortcut: MaterialButton
    private lateinit var btnResetLongPressAction: MaterialButton

    private lateinit var switchHotspot: SwitchMaterial
    private lateinit var switchIgnoreStylus: SwitchMaterial

    // 홈 배경 관련
    private lateinit var switchHomeBg: SwitchMaterial
    private lateinit var btnGenerateLandscape: MaterialButton
    private lateinit var btnGeneratePortrait: MaterialButton
    private lateinit var txtLandscapeStatus: TextView
    private lateinit var txtPortraitStatus: TextView
    private lateinit var toggleHomeBgButtonColor: MaterialButtonToggleGroup
    private lateinit var btnHomeBgColorAuto: MaterialButton
    private lateinit var btnHomeBgColorWhite: MaterialButton
    private lateinit var btnHomeBgColorBlack: MaterialButton

    // 이미지 선택 모드 (true = 가로, false = 세로)
    private var selectingLandscape = true

    // 이미지 선택 런처
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    // 롱프레스 앱 선택 런처
    private val longPressActionPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val packageName = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)
            settings.longPressAction = packageName
            updateLongPressActionUI()
        }
    }

    // 바로가기 선택 런처
    @Suppress("DEPRECATION")
    private val shortcutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
                val shortcutName = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
                if (shortcutIntent != null) {
                    // shortcut: 접두사와 함께 인텐트 URI 저장
                    val intentUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
                    settings.longPressAction = "shortcut:$intentUri"
                    settings.shortcutName = shortcutName ?: "Shortcut"
                    updateLongPressActionUI()
                    Toast.makeText(this, getString(R.string.shortcut_selected, shortcutName), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 배경화면 미리보기 및 생성 런처
    private val wallpaperPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, R.string.wallpaper_generated, Toast.LENGTH_SHORT).show()
            updateBgImageStatus()
            sendBroadcast(Intent(Constants.Action.RELOAD_BACKGROUND))
        }
    }

    // 저장소 읽기 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceProfile.isTablet(this)) {
            Toast.makeText(this, R.string.tablet_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        settings = SettingsManager.getInstance(this)

        initViews()
        loadSettings()
        setupListeners()
        checkAndRequestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateBgImageStatus()
        // 자동으로 배터리 최적화 상태 확인 및 요청
        requestIgnoreBatteryOptimizations(isTriggeredByUser = false)
        updateLongPressActionUI()
    }

    private fun updateLongPressActionUI() {
        val action = settings.longPressAction
        if (action == null) {
            txtLongPressAction.text = getString(R.string.long_press_action_default)
        } else if (action.startsWith("shortcut:")) {
            // 바로가기인 경우 저장된 이름 표시
            val shortcutName = settings.shortcutName ?: "Shortcut"
            txtLongPressAction.text = shortcutName
        } else {
            // 앱인 경우
            try {
                val appInfo = packageManager.getApplicationInfo(action, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo)
                txtLongPressAction.text = appLabel
            } catch (e: PackageManager.NameNotFoundException) {
                txtLongPressAction.text = getString(R.string.app_not_found)
                // 앱이 제거된 경우, 설정을 기본값으로 리셋
                settings.longPressAction = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchShortcutPicker() {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        val chooserIntent = Intent.createChooser(intent, getString(R.string.long_press_action_shortcut))
        try {
            shortcutPickerLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun initViews() {
        // 상태 표시
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        onboardingCard = findViewById(R.id.onboardingCard)

        // 롱프레스 설정
        txtLongPressAction = findViewById(R.id.txtLongPressAction)
        btnChangeLongPressAction = findViewById<MaterialButton>(R.id.btnChangeLongPressAction).apply {
            setOnClickListener {
                val intent = Intent(this@MainActivity, AppListActivity::class.java).apply {
                    putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
                }
                longPressActionPickerLauncher.launch(intent)
            }
        }
        btnSelectShortcut = findViewById<MaterialButton>(R.id.btnSelectShortcut).apply {
            setOnClickListener {
                launchShortcutPicker()
            }
        }
        btnResetLongPressAction = findViewById<MaterialButton>(R.id.btnResetLongPressAction).apply {
            setOnClickListener {
                settings.longPressAction = null
                settings.shortcutName = null
                updateLongPressActionUI()
                Toast.makeText(this@MainActivity, R.string.default_action_set, Toast.LENGTH_SHORT).show()
            }
        }

        // 접근성 설정 버튼
        findViewById<MaterialButton>(R.id.btnOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        // 비활성화 앱 관리 버튼
        findViewById<MaterialButton>(R.id.btnManageDisabledApps).setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java).apply {
                putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_DISABLED_APPS)
            }
            startActivity(intent)
        }

        // 재호출 설정
        switchHotspot = findViewById(R.id.switchHotspot)

        // 스타일러스 설정
        switchIgnoreStylus = findViewById(R.id.switchIgnoreStylus)

        // 홈 배경 설정
        switchHomeBg = findViewById(R.id.switchHomeBg)
        btnGenerateLandscape = findViewById(R.id.btnGenerateLandscape)
        btnGeneratePortrait = findViewById(R.id.btnGeneratePortrait)
        txtLandscapeStatus = findViewById(R.id.txtLandscapeStatus)
        txtPortraitStatus = findViewById(R.id.txtPortraitStatus)
        toggleHomeBgButtonColor = findViewById(R.id.toggleHomeBgButtonColor)
        btnHomeBgColorAuto = findViewById(R.id.btnHomeBgColorAuto)
        btnHomeBgColorWhite = findViewById(R.id.btnHomeBgColorWhite)
        btnHomeBgColorBlack = findViewById(R.id.btnHomeBgColorBlack)

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

        // 배터리 최적화 버튼
        findViewById<MaterialButton>(R.id.btnBatteryOptimization).setOnClickListener {
            // 사용자가 직접 눌렀음을 명시
            requestIgnoreBatteryOptimizations(isTriggeredByUser = true)
        }
    }

    private fun loadSettings() {
        // 재호출 설정 로드
        switchHotspot.isChecked = settings.hotspotEnabled
        switchIgnoreStylus.isChecked = settings.ignoreStylus

        // 홈 배경 설정 로드
        switchHomeBg.isChecked = settings.homeBgEnabled
        updateHomeBgButtonColorUi(settings.homeBgButtonColorMode)
        setHomeBgButtonColorControlsEnabled(settings.homeBgEnabled)
    }

    private fun updateHomeBgButtonColorUi(mode: SettingsManager.HomeBgButtonColorMode) {
        val targetId = when (mode) {
            SettingsManager.HomeBgButtonColorMode.AUTO -> R.id.btnHomeBgColorAuto
            SettingsManager.HomeBgButtonColorMode.WHITE -> R.id.btnHomeBgColorWhite
            SettingsManager.HomeBgButtonColorMode.BLACK -> R.id.btnHomeBgColorBlack
        }
        if (toggleHomeBgButtonColor.checkedButtonId != targetId) {
            toggleHomeBgButtonColor.check(targetId)
        }
    }

    private fun setHomeBgButtonColorControlsEnabled(enabled: Boolean) {
        toggleHomeBgButtonColor.isEnabled = enabled
        btnHomeBgColorAuto.isEnabled = enabled
        btnHomeBgColorWhite.isEnabled = enabled
        btnHomeBgColorBlack.isEnabled = enabled
    }

    private fun setupListeners() {
        // 재호출 핫스팟
        switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            settings.hotspotEnabled = isChecked
            notifySettingsChanged()
        }

        // 스타일러스 입력 무시
        switchIgnoreStylus.setOnCheckedChangeListener { _, isChecked ->
            settings.ignoreStylus = isChecked
        }

        // 홈 배경 활성화
        switchHomeBg.setOnCheckedChangeListener { _, isChecked ->
            settings.homeBgEnabled = isChecked
            notifySettingsChanged()
            setHomeBgButtonColorControlsEnabled(isChecked)
        }

        toggleHomeBgButtonColor.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val mode = when (checkedId) {
                R.id.btnHomeBgColorAuto -> SettingsManager.HomeBgButtonColorMode.AUTO
                R.id.btnHomeBgColorWhite -> SettingsManager.HomeBgButtonColorMode.WHITE
                R.id.btnHomeBgColorBlack -> SettingsManager.HomeBgButtonColorMode.BLACK
                else -> return@addOnButtonCheckedListener
            }

            if (settings.homeBgButtonColorMode != mode) {
                settings.homeBgButtonColorMode = mode
                notifyBackgroundStyleChanged()
            }
        }

        btnGenerateLandscape.setOnClickListener { generateBackground(isLandscape = true) }
        btnGeneratePortrait.setOnClickListener { generateBackground(isLandscape = false) }
    }

    private fun generateBackground(isLandscape: Boolean) {
        val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
            putExtra("is_landscape", isLandscape)
        }
        wallpaperPreviewLauncher.launch(intent)
    }

    private fun handleImageSelected(uri: Uri) {
        val success = ImageCropUtil.cropAndSaveFromUri(this, uri, selectingLandscape)

        if (success) {
            Toast.makeText(this, R.string.image_crop_success, Toast.LENGTH_SHORT).show()
            updateBgImageStatus()
            sendBroadcast(Intent(Constants.Action.RELOAD_BACKGROUND))
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
        sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }

    private fun notifyBackgroundStyleChanged() {
        sendBroadcast(Intent(Constants.Action.UPDATE_BUTTON_COLORS))
    }

    private fun checkAndRequestStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestIgnoreBatteryOptimizations(isTriggeredByUser: Boolean = false) {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                if (!isTriggeredByUser && settings.batteryOptRequested) {
                    return
                }
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else if (isTriggeredByUser) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                settings.batteryOptRequested = true
            } else {
                if (isTriggeredByUser) {
                    Toast.makeText(this, R.string.battery_opt_already_ignored, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            if (isTriggeredByUser) {
                Toast.makeText(this, R.string.battery_opt_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
