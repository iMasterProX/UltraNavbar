package com.minsoo.ultranavbar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.PenButtonActionType
import com.minsoo.ultranavbar.settings.SettingsManager

class WacomPenSettingsFragment : Fragment() {

    private lateinit var settingsManager: SettingsManager
    private var hasPermission = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        settingsManager = SettingsManager.getInstance(requireContext())
        hasPermission = checkWriteSecureSettingsPermission()

        return inflater.inflate(R.layout.fragment_wacom_pen_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI(view)
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
     * UI 설정
     */
    private fun setupUI(view: View) {
        val permissionBanner = view.findViewById<MaterialCardView>(R.id.cardPermissionBanner)
        val switchPenCustomEnable = view.findViewById<SwitchMaterial>(R.id.switchPenCustomEnable)
        val switchPenPointer = view.findViewById<SwitchMaterial>(R.id.switchPenPointer)
        val switchIgnoreGestures = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreGestures)
        val switchIgnoreCustomNavbar = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreCustomNavbar)
        val cardButtonA = view.findViewById<MaterialCardView>(R.id.cardPenButtonA)
        val cardButtonB = view.findViewById<MaterialCardView>(R.id.cardPenButtonB)
        val btnShowGuide = view.findViewById<MaterialButton>(R.id.btnShowPermissionGuide)

        if (!hasPermission) {
            // 권한 없을 때: 배너 표시 및 UI 비활성화
            permissionBanner?.visibility = View.VISIBLE

            switchPenCustomEnable?.isEnabled = false
            switchPenPointer?.isEnabled = false
            switchIgnoreGestures?.isEnabled = false
            switchIgnoreCustomNavbar?.isEnabled = false
            cardButtonA?.isEnabled = false
            cardButtonA?.alpha = 0.5f
            cardButtonB?.isEnabled = false
            cardButtonB?.alpha = 0.5f

            btnShowGuide?.setOnClickListener {
                showPermissionGuideDialog()
            }
        } else {
            // 권한 있을 때: 배너 숨기고 정상 동작
            permissionBanner?.visibility = View.GONE

            // 펜 커스텀 기능 활성화 토글
            val isPenCustomEnabled = settingsManager.isPenCustomFunctionEnabled()
            switchPenCustomEnable?.isChecked = isPenCustomEnabled
            updatePenCustomUI(view, isPenCustomEnabled)

            switchPenCustomEnable?.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) {
                    if (settingsManager.isPenCustomFunctionEnabled()) {
                        // 기능이 설정되어 있으면 확인 다이얼로그
                        showDisableConfirmDialog(switchPenCustomEnable, view)
                    } else {
                        // 기능이 설정되어 있지 않으면 바로 UI 비활성화
                        updatePenCustomUI(view, false)
                    }
                } else {
                    updatePenCustomUI(view, true)
                    // 시스템 펜 설정과의 충돌 경고 표시
                    Toast.makeText(
                        requireContext(),
                        R.string.pen_settings_system_warning,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // 펜 포인터 토글
            switchPenPointer?.apply {
                isChecked = settingsManager.penPointerEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.penPointerEnabled = isChecked
                    applyPenPointerSetting(isChecked)
                }
            }

            // 기본 네비바 제스처 무시 토글
            switchIgnoreGestures?.apply {
                isChecked = settingsManager.penIgnoreNavGestures
                setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.penIgnoreNavGestures = isChecked
                    applyIgnoreGesturesSetting(isChecked)
                }
            }

            // 커스텀 네비바 제스처 무시 토글
            switchIgnoreCustomNavbar?.apply {
                isChecked = settingsManager.ignoreStylus
                setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.ignoreStylus = isChecked
                }
            }

            // 펜 버튼 A 설정
            cardButtonA?.setOnClickListener {
                openButtonConfig("A")
            }

            // 펜 버튼 B 설정
            cardButtonB?.setOnClickListener {
                openButtonConfig("B")
            }

            // 버튼 상태 업데이트
            updateButtonStatus(view)
        }
    }

    /**
     * 권한 설정 가이드 다이얼로그
     */
    private fun showPermissionGuideDialog() {
        val message = getString(R.string.setup_adb_guide) +
                "\n\n" + getString(R.string.setup_adb_command) +
                "\n\n" + getString(R.string.pen_settings_permission_note)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pen_settings_permission_required_title)
            .setMessage(message)
            .setPositiveButton(R.string.pen_settings_check_permission) { _, _ ->
                // 권한 재확인
                if (checkWriteSecureSettingsPermission()) {
                    hasPermission = true
                    // UI 새로고침
                    view?.let { setupUI(it) }
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

    /**
     * 펜 포인터 설정 적용
     */
    private fun applyPenPointerSetting(enabled: Boolean) {
        try {
            Settings.Global.putInt(
                requireContext().contentResolver,
                "pen_pointer",
                if (enabled) 1 else 0
            )
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to apply setting: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 네비바 제스처 무시 설정 적용
     */
    private fun applyIgnoreGesturesSetting(enabled: Boolean) {
        try {
            Settings.Global.putInt(
                requireContext().contentResolver,
                "ignore_navigation_bar_gestures",
                if (enabled) 1 else 0
            )
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to apply setting: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 버튼 설정 화면 열기
     */
    private fun openButtonConfig(button: String) {
        val intent = Intent(requireContext(), PenButtonConfigActivity::class.java).apply {
            putExtra("button", button)
        }
        startActivity(intent)
    }

    /**
     * 버튼 상태 텍스트 업데이트
     */
    private fun updateButtonStatus(view: View) {
        val tvButtonAStatus = view.findViewById<android.widget.TextView>(R.id.tvPenButtonAStatus)
        tvButtonAStatus?.text = getButtonStatusText("A")

        val tvButtonBStatus = view.findViewById<android.widget.TextView>(R.id.tvPenButtonBStatus)
        tvButtonBStatus?.text = getButtonStatusText("B")
    }

    /**
     * 버튼 상태 텍스트 가져오기
     */
    private fun getButtonStatusText(button: String): String {
        val actionType = if (button == "A") {
            settingsManager.penAActionType
        } else {
            settingsManager.penBActionType
        }

        return when (actionType) {
            "NONE" -> getString(R.string.pen_button_action_none)
            "APP" -> {
                val pkg = if (button == "A") {
                    settingsManager.penAAppPackage
                } else {
                    settingsManager.penBAppPackage
                }
                getString(R.string.pen_button_action_app) + ": ${pkg ?: "None"}"
            }
            "SHORTCUT" -> {
                val shortcutName = if (button == "A") {
                    settingsManager.penAShortcutId  // 이름이 저장되어 있음
                } else {
                    settingsManager.penBShortcutId
                }
                getString(R.string.pen_button_action_shortcut) + ": ${shortcutName ?: "None"}"
            }
            "PAINT_FUNCTION" -> {
                val function = if (button == "A") {
                    settingsManager.penAPaintFunction
                } else {
                    settingsManager.penBPaintFunction
                }
                getString(R.string.pen_button_action_paint) + ": ${function ?: "None"}"
            }
            "TOUCH_POINT" -> {
                val x = if (button == "A") settingsManager.penATouchX else settingsManager.penBTouchX
                val y = if (button == "A") settingsManager.penATouchY else settingsManager.penBTouchY
                getString(R.string.pen_button_action_touch_point) + ": (${x.toInt()}, ${y.toInt()})"
            }
            "NODE_CLICK" -> {
                val nodeId = if (button == "A") settingsManager.penANodeId else settingsManager.penBNodeId
                val nodeText = if (button == "A") settingsManager.penANodeText else settingsManager.penBNodeText
                val nodeDesc = if (button == "A") settingsManager.penANodeDesc else settingsManager.penBNodeDesc
                val displayName = nodeText ?: nodeDesc ?: nodeId ?: "Unknown"
                getString(R.string.pen_button_action_node_click) + ": $displayName"
            }
            else -> getString(R.string.pen_button_action_none)
        }
    }

    /**
     * 설정 초기화 확인 다이얼로그
     */
    private fun showResetConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pen_reset_settings)
            .setMessage(R.string.pen_reset_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetPenSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 펜 설정 초기화
     */
    private fun resetPenSettings() {
        try {
            Settings.Global.putString(
                requireContext().contentResolver,
                "a_button_component_name",
                null
            )
            Settings.Global.putString(
                requireContext().contentResolver,
                "b_button_component_name",
                null
            )
            Settings.Global.putInt(
                requireContext().contentResolver,
                "a_button_setting",
                0
            )
            Settings.Global.putInt(
                requireContext().contentResolver,
                "b_button_setting",
                0
            )
            Settings.Global.putInt(
                requireContext().contentResolver,
                "pen_pointer",
                0
            )
            Settings.Global.putInt(
                requireContext().contentResolver,
                "ignore_navigation_bar_gestures",
                0
            )

            settingsManager.penPointerEnabled = false
            settingsManager.penIgnoreNavGestures = false
            settingsManager.penAActionType = "NONE"
            settingsManager.penBActionType = "NONE"
            settingsManager.penAAppPackage = null
            settingsManager.penAAppActivity = null
            settingsManager.penBAppPackage = null
            settingsManager.penBAppActivity = null
            settingsManager.penAShortcutPackage = null
            settingsManager.penAShortcutId = null
            settingsManager.penBShortcutPackage = null
            settingsManager.penBShortcutId = null
            settingsManager.penAPaintFunction = null
            settingsManager.penBPaintFunction = null
            settingsManager.penATouchX = -1f
            settingsManager.penATouchY = -1f
            settingsManager.penBTouchX = -1f
            settingsManager.penBTouchY = -1f
            // NODE_CLICK 설정 초기화
            settingsManager.penANodeId = null
            settingsManager.penANodeText = null
            settingsManager.penANodeDesc = null
            settingsManager.penANodePackage = null
            settingsManager.penBNodeId = null
            settingsManager.penBNodeText = null
            settingsManager.penBNodeDesc = null
            settingsManager.penBNodePackage = null

            Toast.makeText(
                requireContext(),
                R.string.pen_reset_done,
                Toast.LENGTH_LONG
            ).show()

            view?.let {
                updateButtonStatus(it)
                it.findViewById<SwitchMaterial>(R.id.switchPenPointer)?.isChecked = false
                it.findViewById<SwitchMaterial>(R.id.switchPenIgnoreGestures)?.isChecked = false
            }
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to reset settings: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission) {
            view?.let {
                updateButtonStatus(it)
                // 토글 상태 업데이트
                val isPenCustomEnabled = settingsManager.isPenCustomFunctionEnabled()
                it.findViewById<SwitchMaterial>(R.id.switchPenCustomEnable)?.isChecked = isPenCustomEnabled
                updatePenCustomUI(it, isPenCustomEnabled)

                // 시스템 설정과 동기화 (기존 설정이 시스템에 적용되지 않은 경우 대비)
                if (isPenCustomEnabled) {
                    syncPenSettingsToSystem()
                }
            }
        }
    }

    /**
     * 앱 설정을 시스템 Settings.Global에 동기화
     * 기존 설정이 있지만 시스템에 적용되지 않은 경우를 위한 처리
     */
    private fun syncPenSettingsToSystem() {
        try {
            // Button A 동기화 (PAINT_FUNCTION, TOUCH_POINT, SHORTCUT, NODE_CLICK)
            val actionA = settingsManager.penAActionType
            if (actionA == "PAINT_FUNCTION" || actionA == "TOUCH_POINT" || actionA == "SHORTCUT" || actionA == "NODE_CLICK") {
                val componentName = "com.minsoo.ultranavbar/com.minsoo.ultranavbar.ui.PenButtonABridgeActivity"
                val currentA = Settings.Global.getString(requireContext().contentResolver, "a_button_component_name")
                if (currentA != componentName) {
                    Settings.Global.putString(requireContext().contentResolver, "a_button_component_name", componentName)
                    Settings.Global.putInt(requireContext().contentResolver, "a_button_setting", 1)
                    android.util.Log.d("WacomPenSettings", "Synced button A to system: $componentName")
                }
            }

            // Button B 동기화 (PAINT_FUNCTION, TOUCH_POINT, SHORTCUT, NODE_CLICK)
            val actionB = settingsManager.penBActionType
            if (actionB == "PAINT_FUNCTION" || actionB == "TOUCH_POINT" || actionB == "SHORTCUT" || actionB == "NODE_CLICK") {
                val componentName = "com.minsoo.ultranavbar/com.minsoo.ultranavbar.ui.PenButtonBBridgeActivity"
                val currentB = Settings.Global.getString(requireContext().contentResolver, "b_button_component_name")
                if (currentB != componentName) {
                    Settings.Global.putString(requireContext().contentResolver, "b_button_component_name", componentName)
                    Settings.Global.putInt(requireContext().contentResolver, "b_button_setting", 1)
                    android.util.Log.d("WacomPenSettings", "Synced button B to system: $componentName")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WacomPenSettings", "Failed to sync settings to system", e)
        }
    }

    /**
     * 펜 커스텀 기능 UI 활성화/비활성화
     */
    private fun updatePenCustomUI(view: View, enabled: Boolean) {
        // 카드 뷰들
        val cardButtonA = view.findViewById<MaterialCardView>(R.id.cardPenButtonA)
        val cardButtonB = view.findViewById<MaterialCardView>(R.id.cardPenButtonB)

        // 스위치들
        val switchPenPointer = view.findViewById<SwitchMaterial>(R.id.switchPenPointer)
        val switchIgnoreGestures = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreGestures)
        val switchIgnoreCustomNavbar = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreCustomNavbar)

        // 카드 활성화/비활성화
        cardButtonA?.isEnabled = enabled
        cardButtonA?.alpha = if (enabled) 1f else 0.5f
        cardButtonB?.isEnabled = enabled
        cardButtonB?.alpha = if (enabled) 1f else 0.5f

        // 스위치 활성화/비활성화
        switchPenPointer?.isEnabled = enabled
        switchIgnoreGestures?.isEnabled = enabled
        switchIgnoreCustomNavbar?.isEnabled = enabled
    }

    /**
     * 펜 커스텀 기능 비활성화 확인 다이얼로그
     */
    private fun showDisableConfirmDialog(switchView: SwitchMaterial, rootView: View) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pen_custom_disable_confirm_title)
            .setMessage(R.string.pen_custom_disable_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 설정 초기화 및 UI 업데이트
                resetPenSettings()
                updatePenCustomUI(rootView, false)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // 취소 시 토글 복원
                switchView.isChecked = true
            }
            .setOnCancelListener {
                // 다이얼로그 닫기 시 토글 복원
                switchView.isChecked = true
            }
            .show()
    }

}
