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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.CustomAppIconStore
import com.minsoo.ultranavbar.util.IconPackManager
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

    private enum class CustomIconAction {
        ASSIGN,
        REMOVE
    }

    private lateinit var settings: SettingsManager

    // 네비게이션 바 활성화
    private lateinit var switchNavbarEnabled: SwitchMaterial

    // 최근 앱 작업 표시줄
    private lateinit var switchRecentAppsTaskbar: SwitchMaterial
    private lateinit var toggleTaskbarMode: MaterialButtonToggleGroup
    private lateinit var btnTaskbarModeRecentApps: MaterialButton
    private lateinit var btnTaskbarModeCustomApps: MaterialButton
    private lateinit var btnManageTaskbarCustomApps: MaterialButton
    private lateinit var switchRecentAppsTaskbarShowOnHome: SwitchMaterial
    private lateinit var toggleRecentAppsIconShape: MaterialButtonToggleGroup
    private lateinit var btnTaskbarShapeCircle: MaterialButton
    private lateinit var btnTaskbarShapeSquare: MaterialButton
    private lateinit var btnTaskbarShapeSquircle: MaterialButton
    private lateinit var btnTaskbarShapeRoundedRect: MaterialButton
    private lateinit var sliderRecentAppsIconCount: Slider
    private lateinit var txtRecentAppsIconCountValue: TextView

    // 버튼 배치 반전 (Android 12L 스타일)
    private lateinit var switchNavButtonsSwap: SwitchMaterial
    private lateinit var switchAndroid12lNavbarLayoutTuning: SwitchMaterial


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
    private lateinit var switchUnifiedNormalBgColor: SwitchMaterial

    // 다크 모드 배경 관련
    private lateinit var switchHomeBgDark: SwitchMaterial
    private lateinit var layoutDarkLandscape: View
    private lateinit var layoutDarkPortrait: View
    private lateinit var btnGenerateDarkLandscape: MaterialButton
    private lateinit var btnGenerateDarkPortrait: MaterialButton
    private lateinit var txtDarkLandscapeStatus: TextView
    private lateinit var txtDarkPortraitStatus: TextView

    // 아이콘 팩
    private lateinit var txtIconPackStatus: TextView
    private lateinit var btnSelectIconPack: MaterialButton
    private lateinit var btnClearIconPack: MaterialButton

    // 커스텀 앱 아이콘
    private lateinit var txtCustomAppIconStatus: TextView
    private lateinit var btnAssignCustomAppIcon: MaterialButton
    private lateinit var btnRemoveCustomAppIcon: MaterialButton
    private var pendingCustomIconAction: CustomIconAction? = null
    private var pendingCustomIconPackage: String? = null

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

    // 커스텀 앱 아이콘 앱 선택 런처
    private val customIconAppPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val packageName = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)?.trim().orEmpty()
        if (packageName.isEmpty()) return@registerForActivityResult

        when (pendingCustomIconAction) {
            CustomIconAction.ASSIGN -> {
                pendingCustomIconPackage = packageName
                customIconImagePickerLauncher.launch("image/*")
            }
            CustomIconAction.REMOVE -> {
                val removed = CustomAppIconStore.deleteCustomIcon(requireContext(), packageName)
                val message = if (removed) {
                    getString(R.string.custom_app_icon_removed, packageName)
                } else {
                    getString(R.string.custom_app_icon_remove_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                pendingCustomIconAction = null
                pendingCustomIconPackage = null
                updateCustomAppIconStatus()
                notifySettingsChanged()
            }
            null -> Unit
        }
    }

    // 커스텀 앱 아이콘 이미지 선택 런처
    private val customIconImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val packageName = pendingCustomIconPackage
        pendingCustomIconPackage = null
        pendingCustomIconAction = null

        if (uri == null || packageName.isNullOrEmpty()) return@registerForActivityResult

        val saved = CustomAppIconStore.saveIconFromUri(requireContext(), packageName, uri)
        val message = if (saved) {
            getString(R.string.custom_app_icon_saved, packageName)
        } else {
            getString(R.string.custom_app_icon_save_failed)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        updateCustomAppIconStatus()
        notifySettingsChanged()
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
        updateCustomAppIconStatus()
    }

    private fun initViews(view: View) {
        // 네비게이션 바 활성화 스위치
        switchNavbarEnabled = view.findViewById(R.id.switchNavbarEnabled)

        // 최근 앱 작업 표시줄 스위치
        switchRecentAppsTaskbar = view.findViewById(R.id.switchRecentAppsTaskbar)
        toggleTaskbarMode = view.findViewById(R.id.toggleTaskbarMode)
        btnTaskbarModeRecentApps = view.findViewById(R.id.btnTaskbarModeRecentApps)
        btnTaskbarModeCustomApps = view.findViewById(R.id.btnTaskbarModeCustomApps)
        btnManageTaskbarCustomApps = view.findViewById<MaterialButton>(R.id.btnManageTaskbarCustomApps).apply {
            setOnClickListener {
                val intent = Intent(requireContext(), AppListActivity::class.java).apply {
                    putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_TASKBAR_CUSTOM_APPS)
                }
                startActivity(intent)
            }
        }
        switchRecentAppsTaskbarShowOnHome = view.findViewById(R.id.switchRecentAppsTaskbarShowOnHome)
        toggleRecentAppsIconShape = view.findViewById(R.id.toggleRecentAppsIconShape)
        btnTaskbarShapeCircle = view.findViewById(R.id.btnTaskbarShapeCircle)
        btnTaskbarShapeSquare = view.findViewById(R.id.btnTaskbarShapeSquare)
        btnTaskbarShapeSquircle = view.findViewById(R.id.btnTaskbarShapeSquircle)
        btnTaskbarShapeRoundedRect = view.findViewById(R.id.btnTaskbarShapeRoundedRect)
        sliderRecentAppsIconCount = view.findViewById(R.id.sliderRecentAppsIconCount)
        txtRecentAppsIconCountValue = view.findViewById(R.id.txtRecentAppsIconCountValue)

        // 버튼 배치 반전 스위치
        switchNavButtonsSwap = view.findViewById(R.id.switchNavButtonsSwap)
        switchAndroid12lNavbarLayoutTuning = view.findViewById(R.id.switchAndroid12lNavbarLayoutTuning)


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
        switchUnifiedNormalBgColor = view.findViewById(R.id.switchUnifiedNormalBgColor)

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

        // 아이콘 팩
        txtIconPackStatus = view.findViewById(R.id.txtIconPackStatus)
        btnSelectIconPack = view.findViewById(R.id.btnSelectIconPack)
        btnClearIconPack = view.findViewById(R.id.btnClearIconPack)

        // 커스텀 앱 아이콘
        txtCustomAppIconStatus = view.findViewById(R.id.txtCustomAppIconStatus)
        btnAssignCustomAppIcon = view.findViewById(R.id.btnAssignCustomAppIcon)
        btnRemoveCustomAppIcon = view.findViewById(R.id.btnRemoveCustomAppIcon)
    }

    private fun loadSettings() {
        // 네비게이션 바 활성화 상태 로드
        switchNavbarEnabled.isChecked = settings.navbarEnabled

        // 최근 앱 작업 표시줄 상태 로드
        switchRecentAppsTaskbar.isChecked = settings.recentAppsTaskbarEnabled
        updateTaskbarModeUi(settings.taskbarMode)
        switchRecentAppsTaskbarShowOnHome.isChecked = settings.recentAppsTaskbarShowOnHome
        updateRecentAppsIconShapeUi(settings.recentAppsTaskbarIconShape)
        sliderRecentAppsIconCount.value = settings.recentAppsTaskbarIconCount.toFloat()
        updateRecentAppsIconCountValueText(settings.recentAppsTaskbarIconCount)
        setRecentAppsTaskbarControlsEnabled(settings.recentAppsTaskbarEnabled)
        updateRecentAppsTaskbarShowOnHomeForcedUi()
        syncTaskbarCustomManageVisibility(settings.taskbarMode, settings.recentAppsTaskbarEnabled)

        // 버튼 배치 반전 상태 로드
        switchNavButtonsSwap.isChecked = settings.navButtonsSwapped
        switchAndroid12lNavbarLayoutTuning.isChecked = settings.android12lNavbarLayoutTuningEnabled


        // 재호출 설정 로드
        switchHotspot.isChecked = settings.hotspotEnabled

        // 홈 배경 설정 로드
        switchHomeBg.isChecked = settings.homeBgEnabled
        switchUnifiedNormalBgColor.isChecked = settings.unifiedNormalBgColorEnabled
        updateHomeBgButtonColorUi(settings.homeBgButtonColorMode)
        setHomeBgButtonColorControlsEnabled(settings.homeBgEnabled)

        // 다크 모드 배경 설정 로드
        switchHomeBgDark.isChecked = settings.homeBgDarkEnabled
        setDarkBgControlsEnabled(settings.homeBgDarkEnabled)

        // 아이콘 팩 / 커스텀 앱 아이콘 상태 로드
        updateIconPackStatus()
        updateCustomAppIconStatus()
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

    private fun updateRecentAppsIconShapeUi(shape: SettingsManager.RecentAppsTaskbarIconShape) {
        val targetId = when (shape) {
            SettingsManager.RecentAppsTaskbarIconShape.CIRCLE -> R.id.btnTaskbarShapeCircle
            SettingsManager.RecentAppsTaskbarIconShape.SQUARE -> R.id.btnTaskbarShapeSquare
            SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE -> R.id.btnTaskbarShapeSquircle
            SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT -> R.id.btnTaskbarShapeRoundedRect
        }
        if (toggleRecentAppsIconShape.checkedButtonId != targetId) {
            toggleRecentAppsIconShape.check(targetId)
        }
    }

    private fun setRecentAppsTaskbarControlsEnabled(enabled: Boolean) {
        toggleTaskbarMode.isEnabled = enabled
        btnTaskbarModeRecentApps.isEnabled = enabled
        btnTaskbarModeCustomApps.isEnabled = enabled
        switchRecentAppsTaskbarShowOnHome.isEnabled = enabled
        toggleRecentAppsIconShape.isEnabled = enabled
        btnTaskbarShapeCircle.isEnabled = enabled
        btnTaskbarShapeSquare.isEnabled = enabled
        btnTaskbarShapeSquircle.isEnabled = enabled
        btnTaskbarShapeRoundedRect.isEnabled = enabled
        sliderRecentAppsIconCount.isEnabled = enabled
        btnManageTaskbarCustomApps.isEnabled = enabled
        txtRecentAppsIconCountValue.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateRecentAppsTaskbarShowOnHomeForcedUi() {
        val forced = settings.isRecentAppsTaskbarShowOnHomeForced()
        if (forced && !switchRecentAppsTaskbarShowOnHome.isChecked) {
            switchRecentAppsTaskbarShowOnHome.isChecked = true
        }
        switchRecentAppsTaskbarShowOnHome.isEnabled = settings.recentAppsTaskbarEnabled
        switchRecentAppsTaskbarShowOnHome.alpha = if (settings.recentAppsTaskbarEnabled) 1f else 0.5f
        sliderRecentAppsIconCount.valueTo = if (settings.isRecentAppsTaskbarShowOnHomeForced()) 6f else 7f
    }

    private fun updateTaskbarModeUi(mode: SettingsManager.TaskbarMode) {
        val targetId = when (mode) {
            SettingsManager.TaskbarMode.RECENT_APPS -> R.id.btnTaskbarModeRecentApps
            SettingsManager.TaskbarMode.CUSTOM_APPS -> R.id.btnTaskbarModeCustomApps
        }
        if (toggleTaskbarMode.checkedButtonId != targetId) {
            toggleTaskbarMode.check(targetId)
        }
    }

    private fun syncTaskbarCustomManageVisibility(mode: SettingsManager.TaskbarMode, taskbarEnabled: Boolean) {
        val isCustomMode = mode == SettingsManager.TaskbarMode.CUSTOM_APPS
        btnManageTaskbarCustomApps.visibility = if (isCustomMode) View.VISIBLE else View.GONE
        btnManageTaskbarCustomApps.alpha = if (taskbarEnabled) 1f else 0.5f
    }

    private fun updateRecentAppsIconCountValueText(count: Int) {
        txtRecentAppsIconCountValue.text = getString(R.string.recent_apps_taskbar_icon_count_value, count)
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

        // 최근 앱 작업 표시줄
        switchRecentAppsTaskbar.setOnCheckedChangeListener { _, isChecked ->
            val before = settings.recentAppsTaskbarEnabled
            settings.recentAppsTaskbarEnabled = isChecked
            setRecentAppsTaskbarControlsEnabled(settings.recentAppsTaskbarEnabled)
            updateRecentAppsTaskbarShowOnHomeForcedUi()
            syncTaskbarCustomManageVisibility(settings.taskbarMode, settings.recentAppsTaskbarEnabled)
            if (before != settings.recentAppsTaskbarEnabled) {
                notifySettingsChanged()
            }
        }

        toggleTaskbarMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val mode = when (checkedId) {
                R.id.btnTaskbarModeRecentApps -> SettingsManager.TaskbarMode.RECENT_APPS
                R.id.btnTaskbarModeCustomApps -> SettingsManager.TaskbarMode.CUSTOM_APPS
                else -> return@addOnButtonCheckedListener
            }

            syncTaskbarCustomManageVisibility(mode, settings.recentAppsTaskbarEnabled)

            if (settings.taskbarMode != mode) {
                settings.taskbarMode = mode
                notifySettingsChanged()
            }
        }

        switchRecentAppsTaskbarShowOnHome.setOnCheckedChangeListener { _, isChecked ->
            if (settings.isRecentAppsTaskbarShowOnHomeForced()) {
                if (!switchRecentAppsTaskbarShowOnHome.isChecked) {
                    switchRecentAppsTaskbarShowOnHome.isChecked = true
                }
                Toast.makeText(
                    requireContext(),
                    R.string.quickstep_plus_taskbar_show_on_home_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnCheckedChangeListener
            }
            settings.recentAppsTaskbarShowOnHome = isChecked
            notifySettingsChanged()
        }

        toggleRecentAppsIconShape.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val shape = when (checkedId) {
                R.id.btnTaskbarShapeCircle -> SettingsManager.RecentAppsTaskbarIconShape.CIRCLE
                R.id.btnTaskbarShapeSquare -> SettingsManager.RecentAppsTaskbarIconShape.SQUARE
                R.id.btnTaskbarShapeSquircle -> SettingsManager.RecentAppsTaskbarIconShape.SQUIRCLE
                R.id.btnTaskbarShapeRoundedRect -> SettingsManager.RecentAppsTaskbarIconShape.ROUNDED_RECT
                else -> return@addOnButtonCheckedListener
            }

            if (settings.recentAppsTaskbarIconShape != shape) {
                settings.recentAppsTaskbarIconShape = shape
                notifySettingsChanged()
            }
        }

        sliderRecentAppsIconCount.addOnChangeListener { _, value, _ ->
            val maxCount = if (settings.isRecentAppsTaskbarShowOnHomeForced()) 6 else 7
            val count = value.toInt().coerceIn(3, maxCount)
            if (settings.isRecentAppsTaskbarShowOnHomeForced() && value > 6f) {
                sliderRecentAppsIconCount.value = 6f
                Toast.makeText(
                    requireContext(),
                    R.string.quickstep_plus_taskbar_icon_count_limited,
                    Toast.LENGTH_SHORT
                ).show()
            }
            updateRecentAppsIconCountValueText(count)
            if (settings.recentAppsTaskbarIconCount != count) {
                settings.recentAppsTaskbarIconCount = count
                notifySettingsChanged()
            }
        }

        // 버튼 배치 반전 (Android 12L 스타일)
        switchNavButtonsSwap.setOnCheckedChangeListener { _, isChecked ->
            settings.navButtonsSwapped = isChecked
            notifySettingsChanged()
        }

        switchAndroid12lNavbarLayoutTuning.setOnCheckedChangeListener { _, isChecked ->
            val before = settings.android12lNavbarLayoutTuningEnabled
            settings.android12lNavbarLayoutTuningEnabled = isChecked
            if (before != settings.android12lNavbarLayoutTuningEnabled) {
                notifySettingsChanged()
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
            setHomeBgButtonColorControlsEnabled(isChecked)
        }

        switchUnifiedNormalBgColor.setOnCheckedChangeListener { _, isChecked ->
            val before = settings.unifiedNormalBgColorEnabled
            settings.unifiedNormalBgColorEnabled = isChecked
            if (before != settings.unifiedNormalBgColorEnabled) {
                notifySettingsChanged()
            }
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

        // 아이콘 팩
        btnSelectIconPack.setOnClickListener { showIconPackPicker() }
        btnClearIconPack.setOnClickListener {
            settings.iconPackPackage = null
            updateIconPackStatus()
            Toast.makeText(requireContext(), R.string.icon_pack_cleared, Toast.LENGTH_SHORT).show()
            notifySettingsChanged()
        }

        // 커스텀 앱 아이콘
        btnAssignCustomAppIcon.setOnClickListener {
            pendingCustomIconAction = CustomIconAction.ASSIGN
            launchCustomIconAppPicker(getString(R.string.custom_app_icon_pick_app_title))
        }
        btnRemoveCustomAppIcon.setOnClickListener {
            if (CustomAppIconStore.getCustomIconCount(requireContext()) <= 0) {
                Toast.makeText(requireContext(), R.string.custom_app_icon_none_assigned, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCustomIconAction = CustomIconAction.REMOVE
            launchCustomIconAppPicker(getString(R.string.custom_app_icon_remove_title))
        }
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

    private fun updateIconPackStatus() {
        val installedPacks = IconPackManager.getInstalledIconPacks(requireContext())
        val selectedPackage = settings.iconPackPackage
        val selectedPack = installedPacks.firstOrNull { it.packageName == selectedPackage }

        txtIconPackStatus.text = if (selectedPack != null) {
            getString(R.string.icon_pack_selected_status, selectedPack.label)
        } else {
            getString(R.string.icon_pack_not_selected_status, installedPacks.size)
        }

        btnSelectIconPack.isEnabled = installedPacks.isNotEmpty()
        btnSelectIconPack.alpha = if (installedPacks.isNotEmpty()) 1f else 0.5f
        btnClearIconPack.isEnabled = !selectedPackage.isNullOrBlank()
        btnClearIconPack.alpha = if (!selectedPackage.isNullOrBlank()) 1f else 0.5f
    }

    private fun showIconPackPicker() {
        val iconPacks = IconPackManager.getInstalledIconPacks(requireContext())
        if (iconPacks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.icon_pack_none_installed, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = iconPacks.map { it.label.toString() }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.icon_pack_picker_title)
            .setItems(labels) { _, which ->
                val selected = iconPacks[which]
                settings.iconPackPackage = selected.packageName
                updateIconPackStatus()
                Toast.makeText(requireContext(), getString(R.string.icon_pack_selected_toast, selected.label), Toast.LENGTH_SHORT).show()
                notifySettingsChanged()
            }
            .show()
    }

    private fun updateCustomAppIconStatus() {
        val count = CustomAppIconStore.getCustomIconCount(requireContext())
        txtCustomAppIconStatus.text = getString(R.string.custom_app_icon_status, count)
        btnRemoveCustomAppIcon.isEnabled = count > 0
        btnRemoveCustomAppIcon.alpha = if (count > 0) 1f else 0.5f
    }

    private fun launchCustomIconAppPicker(title: String) {
        val intent = Intent(requireContext(), AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
            putExtra(AppListActivity.EXTRA_TITLE, title)
        }
        customIconAppPickerLauncher.launch(intent)
    }

    private fun notifySettingsChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }

    private fun notifyBackgroundStyleChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.UPDATE_BUTTON_COLORS))
    }
}
