package com.minsoo.ultranavbar.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.KeyShortcut
import com.minsoo.ultranavbar.model.ReservedShortcuts

class AddShortcutDialog : DialogFragment() {

    interface DialogListener {
        fun onShortcutAdded(shortcut: KeyShortcut)
        fun onAppSelectionRequested()
        fun onShortcutSelectionRequested()
    }

    private var listener: DialogListener? = null

    private var currentStep = 1
    private val pressedModifiers = mutableSetOf<Int>()  // 실시간 추적 (KEY_UP에서 제거)
    private val capturedModifiers = mutableSetOf<Int>()  // 메인키 입력 시점에 확정된 modifier
    private var mainKeyCode: Int? = null
    private var isLongPress: Boolean = false  // 길게 누름 모드 여부
    private var selectedActionType: KeyShortcut.ActionType? = null
    private var selectedActionData: String? = null

    private lateinit var txtStepIndicator: TextView
    private lateinit var txtTitle: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var btnBack: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnNext: MaterialButton

    // Step 1
    private lateinit var step1View: View
    private lateinit var txtKeyCombination: TextView
    private lateinit var radioPressMode: RadioGroup

    // Step 2
    private lateinit var step2View: View
    private lateinit var radioGroup: RadioGroup

    // Step 3
    private lateinit var step3View: View
    private lateinit var editName: TextInputEditText
    private lateinit var btnSelectApp: MaterialButton
    private lateinit var layoutSettings: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_shortcut, null)

        initViews(dialogView)
        setupStep1()
        showStep(1)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        // Set up key event handling for the dialog
        dialog.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }

        return dialog
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? DialogListener
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun initViews(view: View) {
        txtStepIndicator = view.findViewById(R.id.txtStepIndicator)
        txtTitle = view.findViewById(R.id.txtTitle)
        contentContainer = view.findViewById(R.id.contentContainer)
        btnBack = view.findViewById(R.id.btnBack)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnNext = view.findViewById(R.id.btnNext)

        btnBack.setOnClickListener { previousStep() }
        btnCancel.setOnClickListener { dismiss() }
        btnNext.setOnClickListener { nextStep() }
    }

    private fun setupStep1() {
        step1View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_step1_key_combination, contentContainer, false)
        txtKeyCombination = step1View.findViewById(R.id.txtKeyCombination)
        radioPressMode = step1View.findViewById(R.id.radioPressMode)

        radioPressMode.setOnCheckedChangeListener { _, checkedId ->
            isLongPress = (checkedId == R.id.radioLongPress)
        }
    }

    private fun setupStep2() {
        step2View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_step2_action_type, contentContainer, false)
        radioGroup = step2View.findViewById(R.id.radioGroup)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioApp -> selectedActionType = KeyShortcut.ActionType.APP
                R.id.radioSettings -> selectedActionType = KeyShortcut.ActionType.SETTINGS
                R.id.radioShortcut -> selectedActionType = KeyShortcut.ActionType.SHORTCUT
            }
            btnNext.isEnabled = selectedActionType != null
        }
    }

    private fun setupStep3() {
        step3View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_step3_select_action, contentContainer, false)
        editName = step3View.findViewById(R.id.editName)
        btnSelectApp = step3View.findViewById(R.id.btnSelectApp)
        layoutSettings = step3View.findViewById(R.id.layoutSettings)

        // Set suggested name
        val suggestedName = if (isLongPress) {
            // 길게 누름: 단일 키만
            mainKeyCode?.let { "${getKeyName(it)} (Long)" } ?: ""
        } else {
            // 일반 누름: modifier + 키 조합
            buildString {
                if (capturedModifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT)) append("Ctrl + ")
                if (capturedModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) append("Shift + ")
                if (capturedModifiers.contains(KeyEvent.KEYCODE_ALT_LEFT)) append("Alt + ")
                mainKeyCode?.let { append(getKeyName(it)) }
            }
        }
        editName.setText(suggestedName)

        when (selectedActionType) {
            KeyShortcut.ActionType.APP -> {
                btnSelectApp.visibility = View.VISIBLE
                layoutSettings.visibility = View.GONE
                btnSelectApp.setOnClickListener {
                    listener?.onAppSelectionRequested()
                }
            }
            KeyShortcut.ActionType.SETTINGS -> {
                btnSelectApp.visibility = View.GONE
                layoutSettings.visibility = View.VISIBLE
                setupSettingsCards()
            }
            KeyShortcut.ActionType.SHORTCUT -> {
                btnSelectApp.visibility = View.VISIBLE
                btnSelectApp.text = requireContext().getString(R.string.select_action)
                layoutSettings.visibility = View.GONE
                btnSelectApp.setOnClickListener {
                    listener?.onShortcutSelectionRequested()
                }
                btnNext.isEnabled = false
            }
            else -> {}
        }
    }

    private fun setupSettingsCards() {
        val settingsMap = mapOf(
            R.id.cardWifi to "wifi",
            R.id.cardBluetooth to "bluetooth",
            R.id.cardDisplay to "display",
            R.id.cardSound to "sound",
            R.id.cardApps to "app_settings",
            R.id.cardAccessibility to "accessibility"
        )

        settingsMap.forEach { (cardId, action) ->
            step3View.findViewById<MaterialCardView>(cardId).setOnClickListener {
                selectedActionData = action
                btnNext.isEnabled = true
                // Visual feedback
                settingsMap.keys.forEach { id ->
                    step3View.findViewById<MaterialCardView>(id).isChecked = (id == cardId)
                }
            }
        }
    }

    fun setSelectedApp(packageName: String) {
        selectedActionData = packageName
        val pm = requireContext().packageManager
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            btnSelectApp.text = appName
        } catch (e: Exception) {
            btnSelectApp.text = packageName
        }
        btnNext.isEnabled = true
    }

    fun setSelectedShortcut(shortcutData: String, shortcutName: String) {
        selectedActionData = shortcutData
        btnSelectApp.text = shortcutName
        btnNext.isEnabled = true
    }

    private fun showStep(step: Int) {
        currentStep = step

        // Update UI
        txtStepIndicator.text = requireContext().getString(
            when (step) {
                1 -> R.string.step_1_of_3
                2 -> R.string.step_2_of_3
                else -> R.string.step_3_of_3
            }
        )

        txtTitle.text = when (step) {
            1 -> requireContext().getString(R.string.press_keys)
            2 -> requireContext().getString(R.string.action_type)
            else -> requireContext().getString(R.string.select_action)
        }

        btnBack.visibility = if (step > 1) View.VISIBLE else View.GONE
        btnNext.text = if (step == 3) requireContext().getString(R.string.finish) else requireContext().getString(R.string.next)

        // Load content
        contentContainer.removeAllViews()
        when (step) {
            1 -> {
                if (!::step1View.isInitialized) setupStep1()
                contentContainer.addView(step1View)
                btnNext.isEnabled = mainKeyCode != null
            }
            2 -> {
                if (!::step2View.isInitialized) setupStep2()
                contentContainer.addView(step2View)
                btnNext.isEnabled = selectedActionType != null
            }
            3 -> {
                setupStep3()
                contentContainer.addView(step3View)
                btnNext.isEnabled = selectedActionData != null
            }
        }
    }

    private fun nextStep() {
        if (currentStep < 3) {
            showStep(currentStep + 1)
        } else {
            // Finish
            finishShortcut()
        }
    }

    private fun previousStep() {
        if (currentStep > 1) {
            showStep(currentStep - 1)
        }
    }

    private fun finishShortcut() {
        val name = editName.text?.toString() ?: ""
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), requireContext().getString(R.string.shortcut_name_hint), Toast.LENGTH_SHORT).show()
            return
        }

        if (mainKeyCode == null || selectedActionType == null || selectedActionData == null) {
            Toast.makeText(requireContext(), requireContext().getString(R.string.shortcut_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        // 검증: 일반 누름은 modifier 필수, 길게 누름은 modifier 없어야 함
        if (isLongPress) {
            // 길게 누름: modifier가 있으면 안됨
            if (capturedModifiers.isNotEmpty()) {
                Toast.makeText(requireContext(), "길게 누름 단축키는 단일 키만 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // 일반 누름: modifier가 반드시 있어야 함
            if (capturedModifiers.isEmpty()) {
                Toast.makeText(requireContext(), requireContext().getString(R.string.shortcut_invalid), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val shortcut = KeyShortcut(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            modifiers = if (isLongPress) emptySet() else capturedModifiers.toSet(),
            keyCode = mainKeyCode!!,
            actionType = selectedActionType!!,
            actionData = selectedActionData!!,
            isLongPress = isLongPress,
            longPressThreshold = 500L
        )

        listener?.onShortcutAdded(shortcut)
        dismiss()
    }

    private fun showReservedWarning(modifiers: Set<Int>, keyCode: Int) {
        val description = ReservedShortcuts.getDescription(modifiers, keyCode) ?: "Unknown"
        val message = "이 단축키는 시스템에서 예약된 단축키입니다.\n\n" +
                "기능: $description\n\n" +
                "다른 키 조합을 사용해주세요."

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("예약된 단축키")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (currentStep != 1) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                        pressedModifiers.add(KeyEvent.KEYCODE_CTRL_LEFT)
                    }
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                        pressedModifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT)
                    }
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                        pressedModifiers.add(KeyEvent.KEYCODE_ALT_LEFT)
                    }
                    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                        pressedModifiers.add(KeyEvent.KEYCODE_META_LEFT)
                    }
                    else -> {
                        // 길게 누름 모드: 단일 키만 허용 (modifier 무시)
                        if (isLongPress) {
                            mainKeyCode = keyCode
                            btnNext.isEnabled = true
                        } else {
                            // 일반 누름 모드: modifier + 키 조합 필요
                            if (pressedModifiers.isNotEmpty()) {
                                // 메인키 입력 시 현재 눌린 modifier를 확정 캡처
                                capturedModifiers.clear()
                                capturedModifiers.addAll(pressedModifiers)
                                mainKeyCode = keyCode

                                // 시스템 예약 단축키 확인
                                if (ReservedShortcuts.isReserved(capturedModifiers, keyCode)) {
                                    showReservedWarning(capturedModifiers, keyCode)
                                    // 예약된 단축키이므로 입력 무효화
                                    capturedModifiers.clear()
                                    mainKeyCode = null
                                    btnNext.isEnabled = false
                                } else {
                                    btnNext.isEnabled = true
                                }
                            }
                        }
                    }
                }
                updateKeyCombinationDisplay()
                return true
            }
            KeyEvent.ACTION_UP -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                        pressedModifiers.remove(KeyEvent.KEYCODE_CTRL_LEFT)
                    }
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                        pressedModifiers.remove(KeyEvent.KEYCODE_SHIFT_LEFT)
                    }
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                        pressedModifiers.remove(KeyEvent.KEYCODE_ALT_LEFT)
                    }
                    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                        pressedModifiers.remove(KeyEvent.KEYCODE_META_LEFT)
                    }
                }
                updateKeyCombinationDisplay()
                return true
            }
        }

        return false
    }

    private fun updateKeyCombinationDisplay() {
        val parts = mutableListOf<String>()

        // 길게 누름 모드: 단일 키만 표시
        if (isLongPress) {
            mainKeyCode?.let { parts.add("${getKeyName(it)} (Long)") }
        } else {
            // 일반 누름 모드: modifier + 키 조합 표시
            val displayModifiers = if (mainKeyCode != null) capturedModifiers else pressedModifiers

            if (displayModifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT)) parts.add("Ctrl")
            if (displayModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) parts.add("Shift")
            if (displayModifiers.contains(KeyEvent.KEYCODE_ALT_LEFT)) parts.add("Alt")
            if (displayModifiers.contains(KeyEvent.KEYCODE_META_LEFT)) parts.add("Meta")

            mainKeyCode?.let { parts.add(getKeyName(it)) }
        }

        txtKeyCombination.text = if (parts.isEmpty()) "---" else parts.joinToString(" + ")
    }

    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            else -> "Key$keyCode"
        }
    }
}
