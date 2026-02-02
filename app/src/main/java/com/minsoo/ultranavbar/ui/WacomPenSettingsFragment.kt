package com.minsoo.ultranavbar.ui

import android.content.ComponentName
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
        val switchPenPointer = view.findViewById<SwitchMaterial>(R.id.switchPenPointer)
        val switchIgnoreGestures = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreGestures)
        val switchIgnoreCustomNavbar = view.findViewById<SwitchMaterial>(R.id.switchPenIgnoreCustomNavbar)
        val cardButtonA = view.findViewById<MaterialCardView>(R.id.cardPenButtonA)
        val cardButtonB = view.findViewById<MaterialCardView>(R.id.cardPenButtonB)
        val cardReset = view.findViewById<MaterialCardView>(R.id.cardResetSettings)
        val btnShowGuide = view.findViewById<MaterialButton>(R.id.btnShowPermissionGuide)
        val btnSyncFromSystem = view.findViewById<MaterialButton>(R.id.btnSyncFromSystem)
        val btnOpenSystemSettings = view.findViewById<MaterialButton>(R.id.btnOpenSystemSettings)
        val cardTestCanvas = view.findViewById<MaterialCardView>(R.id.cardTestCanvas)

        if (!hasPermission) {
            // 권한 없을 때: 배너 표시 및 UI 비활성화
            permissionBanner?.visibility = View.VISIBLE

            switchPenPointer?.isEnabled = false
            switchIgnoreGestures?.isEnabled = false
            switchIgnoreCustomNavbar?.isEnabled = false
            cardButtonA?.isEnabled = false
            cardButtonA?.alpha = 0.5f
            cardButtonB?.isEnabled = false
            cardButtonB?.alpha = 0.5f
            cardReset?.isEnabled = false
            cardReset?.alpha = 0.5f

            btnShowGuide?.setOnClickListener {
                showPermissionGuideDialog()
            }
        } else {
            // 권한 있을 때: 배너 숨기고 정상 동작
            permissionBanner?.visibility = View.GONE

            // 시스템 설정 동기화 및 열기 버튼
            btnSyncFromSystem?.setOnClickListener {
                syncFromSystemSettings()
            }

            btnOpenSystemSettings?.setOnClickListener {
                openSystemPenSettings()
            }

            // 테스트 캔버스 열기
            cardTestCanvas?.setOnClickListener {
                openTestCanvas()
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
                    Toast.makeText(
                        requireContext(),
                        R.string.pen_settings_updated,
                        Toast.LENGTH_SHORT
                    ).show()
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

            // 설정 초기화
            cardReset?.setOnClickListener {
                showResetConfirmDialog()
            }

            // 버튼 상태 업데이트
            updateButtonStatus(view)
        }
    }

    /**
     * 권한 설정 가이드 다이얼로그
     */
    private fun showPermissionGuideDialog() {
        val message = getString(R.string.pen_settings_permission_guide) +
                "\n\n" + getString(R.string.pen_settings_adb_command) +
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
                        "Permission granted!",
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
            Toast.makeText(
                requireContext(),
                R.string.pen_settings_updated,
                Toast.LENGTH_SHORT
            ).show()
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
            Toast.makeText(
                requireContext(),
                R.string.pen_settings_updated,
                Toast.LENGTH_SHORT
            ).show()
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
            "SHORTCUT" -> getString(R.string.pen_button_action_shortcut)
            "PAINT_FUNCTION" -> {
                val function = if (button == "A") {
                    settingsManager.penAPaintFunction
                } else {
                    settingsManager.penBPaintFunction
                }
                getString(R.string.pen_button_action_paint) + ": ${function ?: "None"}"
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
            view?.let { updateButtonStatus(it) }
        }
    }

    /**
     * 시스템 펜 설정에서 동기화
     */
    private fun syncFromSystemSettings() {
        try {
            // 시스템 설정 읽기
            val penPointer = Settings.Global.getInt(
                requireContext().contentResolver,
                "pen_pointer",
                0
            )
            val ignoreGestures = Settings.Global.getInt(
                requireContext().contentResolver,
                "ignore_navigation_bar_gestures",
                0
            )

            // SettingsManager에 반영
            settingsManager.penPointerEnabled = (penPointer == 1)
            settingsManager.penIgnoreNavGestures = (ignoreGestures == 1)

            // UI 업데이트
            view?.let {
                it.findViewById<SwitchMaterial>(R.id.switchPenPointer)?.isChecked = (penPointer == 1)
                it.findViewById<SwitchMaterial>(R.id.switchPenIgnoreGestures)?.isChecked = (ignoreGestures == 1)
            }

            Toast.makeText(
                requireContext(),
                R.string.pen_settings_synced,
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to sync settings: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 시스템 펜 설정 열기
     */
    private fun openSystemPenSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to open system settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 테스트 캔버스 열기
     */
    private fun openTestCanvas() {
        val intent = Intent(requireContext(), PenTestCanvasActivity::class.java)
        startActivity(intent)
    }
}
