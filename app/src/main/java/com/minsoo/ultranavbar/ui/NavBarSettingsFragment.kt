package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ImageCropUtil

/**
 * NavBarSettingsFragment - 네비게이션 바 관련 설정을 담당하는 Fragment
 *
 * 포함 기능:
 * - 롱프레스 동작 설정
 * - 비활성화 앱 관리
 * - 재호출 설정 (핫스팟, 스타일러스)
 * - 홈 화면 배경 설정 (다크 모드 포함)
 */
class NavBarSettingsFragment : Fragment() {

    private lateinit var settings: SettingsManager

    // 네비게이션 바 활성화
    private lateinit var switchNavbarEnabled: SwitchMaterial

    // 버튼 배치 반전 (Android 12L 스타일)
    private lateinit var switchNavButtonsSwap: SwitchMaterial

    // 롱프레스 설정
    private lateinit var txtLongPressAction: TextView
    private lateinit var btnChangeLongPressAction: MaterialButton
    private lateinit var btnSelectShortcut: MaterialButton
    private lateinit var btnResetLongPressAction: MaterialButton

    // 재호출 설정
    private lateinit var switchHotspot: SwitchMaterial

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

    // 다크 모드 배경 관련
    private lateinit var switchHomeBgDark: SwitchMaterial
    private lateinit var layoutDarkLandscape: View
    private lateinit var layoutDarkPortrait: View
    private lateinit var btnGenerateDarkLandscape: MaterialButton
    private lateinit var btnGenerateDarkPortrait: MaterialButton
    private lateinit var txtDarkLandscapeStatus: TextView
    private lateinit var txtDarkPortraitStatus: TextView

    // 이미지 선택 모드 (true = 가로, false = 세로)
    private var selectingLandscape = true
    // 다크 모드 배경 선택 모드
    private var selectingDarkMode = false

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
                    Toast.makeText(requireContext(), getString(R.string.shortcut_selected, shortcutName), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 가이드 프리뷰 런처 (결과 처리 없음 - 가이드 모드)
    private val guidePreviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Guide 모드: 사용자가 직접 스크린샷을 찍고 이미지 선택으로 적용해야 함
        // 결과 처리 없음
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_navbar_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsManager.getInstance(requireContext())

        initViews(view)
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateBgImageStatus()
        updateLongPressActionUI()
    }

    private fun initViews(view: View) {
        // 네비게이션 바 활성화 스위치
        switchNavbarEnabled = view.findViewById(R.id.switchNavbarEnabled)

        // 버튼 배치 반전 스위치
        switchNavButtonsSwap = view.findViewById(R.id.switchNavButtonsSwap)

        // 롱프레스 설정
        txtLongPressAction = view.findViewById(R.id.txtLongPressAction)
        btnChangeLongPressAction = view.findViewById<MaterialButton>(R.id.btnChangeLongPressAction).apply {
            setOnClickListener {
                val intent = Intent(requireContext(), AppListActivity::class.java).apply {
                    putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
                }
                longPressActionPickerLauncher.launch(intent)
            }
        }
        btnSelectShortcut = view.findViewById<MaterialButton>(R.id.btnSelectShortcut).apply {
            setOnClickListener {
                launchShortcutPicker()
            }
        }
        btnResetLongPressAction = view.findViewById<MaterialButton>(R.id.btnResetLongPressAction).apply {
            setOnClickListener {
                settings.longPressAction = null
                settings.shortcutName = null
                updateLongPressActionUI()
                Toast.makeText(requireContext(), R.string.default_action_set, Toast.LENGTH_SHORT).show()
            }
        }

        // 비활성화 앱 관리 버튼
        view.findViewById<MaterialButton>(R.id.btnManageDisabledApps).setOnClickListener {
            val intent = Intent(requireContext(), AppListActivity::class.java).apply {
                putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_DISABLED_APPS)
            }
            startActivity(intent)
        }

        // 재호출 설정
        switchHotspot = view.findViewById(R.id.switchHotspot)

        // 홈 배경 설정
        switchHomeBg = view.findViewById(R.id.switchHomeBg)
        btnGenerateLandscape = view.findViewById(R.id.btnGenerateLandscape)
        btnGeneratePortrait = view.findViewById(R.id.btnGeneratePortrait)
        txtLandscapeStatus = view.findViewById(R.id.txtLandscapeStatus)
        txtPortraitStatus = view.findViewById(R.id.txtPortraitStatus)
        toggleHomeBgButtonColor = view.findViewById(R.id.toggleHomeBgButtonColor)
        btnHomeBgColorAuto = view.findViewById(R.id.btnHomeBgColorAuto)
        btnHomeBgColorWhite = view.findViewById(R.id.btnHomeBgColorWhite)
        btnHomeBgColorBlack = view.findViewById(R.id.btnHomeBgColorBlack)

        // 가로 이미지 선택 버튼
        view.findViewById<MaterialButton>(R.id.btnSelectLandscape).setOnClickListener {
            selectingLandscape = true
            selectingDarkMode = false
            imagePickerLauncher.launch("image/*")
        }

        // 세로 이미지 선택 버튼
        view.findViewById<MaterialButton>(R.id.btnSelectPortrait).setOnClickListener {
            selectingLandscape = false
            selectingDarkMode = false
            imagePickerLauncher.launch("image/*")
        }

        // 다크 모드 배경 UI 초기화
        switchHomeBgDark = view.findViewById(R.id.switchHomeBgDark)
        layoutDarkLandscape = view.findViewById(R.id.layoutDarkLandscape)
        layoutDarkPortrait = view.findViewById(R.id.layoutDarkPortrait)
        btnGenerateDarkLandscape = view.findViewById(R.id.btnGenerateDarkLandscape)
        btnGenerateDarkPortrait = view.findViewById(R.id.btnGenerateDarkPortrait)
        txtDarkLandscapeStatus = view.findViewById(R.id.txtDarkLandscapeStatus)
        txtDarkPortraitStatus = view.findViewById(R.id.txtDarkPortraitStatus)

        // 다크 모드 가로 이미지 선택 버튼
        view.findViewById<MaterialButton>(R.id.btnSelectDarkLandscape).setOnClickListener {
            selectingLandscape = true
            selectingDarkMode = true
            imagePickerLauncher.launch("image/*")
        }

        // 다크 모드 세로 이미지 선택 버튼
        view.findViewById<MaterialButton>(R.id.btnSelectDarkPortrait).setOnClickListener {
            selectingLandscape = false
            selectingDarkMode = true
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun loadSettings() {
        // 네비게이션 바 활성화 상태 로드
        switchNavbarEnabled.isChecked = settings.navbarEnabled

        // 버튼 배치 반전 상태 로드
        switchNavButtonsSwap.isChecked = settings.navButtonsSwapped

        // 재호출 설정 로드
        switchHotspot.isChecked = settings.hotspotEnabled

        // 홈 배경 설정 로드
        switchHomeBg.isChecked = settings.homeBgEnabled
        updateHomeBgButtonColorUi(settings.homeBgButtonColorMode)
        setHomeBgButtonColorControlsEnabled(settings.homeBgEnabled)

        // 다크 모드 배경 설정 로드
        switchHomeBgDark.isChecked = settings.homeBgDarkEnabled
        setDarkBgControlsEnabled(settings.homeBgDarkEnabled)
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

    private fun setDarkBgControlsEnabled(enabled: Boolean) {
        layoutDarkLandscape.alpha = if (enabled) 1f else 0.5f
        layoutDarkPortrait.alpha = if (enabled) 1f else 0.5f
        btnGenerateDarkLandscape.isEnabled = enabled
        btnGenerateDarkPortrait.isEnabled = enabled
        view?.findViewById<MaterialButton>(R.id.btnSelectDarkLandscape)?.isEnabled = enabled
        view?.findViewById<MaterialButton>(R.id.btnSelectDarkPortrait)?.isEnabled = enabled
    }

    private fun setupListeners() {
        // 네비게이션 바 활성화/비활성화
        switchNavbarEnabled.setOnCheckedChangeListener { _, isChecked ->
            settings.navbarEnabled = isChecked
            notifySettingsChanged()
        }

        // 버튼 배치 반전 (Android 12L 스타일)
        switchNavButtonsSwap.setOnCheckedChangeListener { _, isChecked ->
            settings.navButtonsSwapped = isChecked
            notifySettingsChanged()
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

        btnGenerateLandscape.setOnClickListener { openGuidePreview(isLandscape = true, isDarkMode = false) }
        btnGeneratePortrait.setOnClickListener { openGuidePreview(isLandscape = false, isDarkMode = false) }

        // 다크 모드 배경 리스너
        switchHomeBgDark.setOnCheckedChangeListener { _, isChecked ->
            settings.homeBgDarkEnabled = isChecked
            setDarkBgControlsEnabled(isChecked)
            notifySettingsChanged()
        }

        btnGenerateDarkLandscape.setOnClickListener { openGuidePreview(isLandscape = true, isDarkMode = true) }
        btnGenerateDarkPortrait.setOnClickListener { openGuidePreview(isLandscape = false, isDarkMode = true) }
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
                val appInfo = requireContext().packageManager.getApplicationInfo(action, 0)
                val appLabel = requireContext().packageManager.getApplicationLabel(appInfo)
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
            Toast.makeText(requireContext(), R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGuidePreview(isLandscape: Boolean, isDarkMode: Boolean = false) {
        selectingDarkMode = isDarkMode
        val intent = Intent(requireContext(), WallpaperPreviewActivity::class.java).apply {
            putExtra("is_landscape", isLandscape)
            putExtra("is_dark_mode", isDarkMode)
        }
        guidePreviewLauncher.launch(intent)
    }

    private fun handleImageSelected(uri: Uri) {
        val success = ImageCropUtil.cropAndSaveFromUri(requireContext(), uri, selectingLandscape, selectingDarkMode)

        if (success) {
            Toast.makeText(requireContext(), R.string.image_crop_success, Toast.LENGTH_SHORT).show()
            updateBgImageStatus()
            requireContext().sendBroadcast(Intent(Constants.Action.RELOAD_BACKGROUND))
        } else {
            Toast.makeText(requireContext(), R.string.image_crop_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBgImageStatus() {
        // 일반 배경 상태
        val hasLandscape = ImageCropUtil.hasBackgroundImage(requireContext(), true, false)
        val hasPortrait = ImageCropUtil.hasBackgroundImage(requireContext(), false, false)

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

        // 다크 모드 배경 상태
        val hasDarkLandscape = ImageCropUtil.hasBackgroundImage(requireContext(), true, true)
        val hasDarkPortrait = ImageCropUtil.hasBackgroundImage(requireContext(), false, true)

        txtDarkLandscapeStatus.text = if (hasDarkLandscape) {
            getString(R.string.home_bg_set)
        } else {
            getString(R.string.home_bg_not_set)
        }

        txtDarkPortraitStatus.text = if (hasDarkPortrait) {
            getString(R.string.home_bg_set)
        } else {
            getString(R.string.home_bg_not_set)
        }
    }

    private fun notifySettingsChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }

    private fun notifyBackgroundStyleChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.UPDATE_BUTTON_COLORS))
    }
}
