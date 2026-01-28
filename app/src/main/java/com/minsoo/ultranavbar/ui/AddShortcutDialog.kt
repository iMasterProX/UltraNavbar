package com.minsoo.ultranavbar.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.KeyShortcut

class AddShortcutDialog(
    context: Context,
    private val appPickerLauncher: ActivityResultLauncher<Intent>,
    private val onShortcutAdded: (KeyShortcut) -> Unit
) : Dialog(context) {

    private var currentStep = 1
    private val pressedModifiers = mutableSetOf<Int>()
    private var mainKeyCode: Int? = null
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

    // Step 2
    private lateinit var step2View: View
    private lateinit var radioGroup: RadioGroup

    // Step 3
    private lateinit var step3View: View
    private lateinit var editName: TextInputEditText
    private lateinit var btnSelectApp: MaterialButton
    private lateinit var layoutSettings: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_add_shortcut)

        initViews()
        setupStep1()
        showStep(1)
    }

    private fun initViews() {
        txtStepIndicator = findViewById(R.id.txtStepIndicator)
        txtTitle = findViewById(R.id.txtTitle)
        contentContainer = findViewById(R.id.contentContainer)
        btnBack = findViewById(R.id.btnBack)
        btnCancel = findViewById(R.id.btnCancel)
        btnNext = findViewById(R.id.btnNext)

        btnBack.setOnClickListener { previousStep() }
        btnCancel.setOnClickListener { dismiss() }
        btnNext.setOnClickListener { nextStep() }
    }

    private fun setupStep1() {
        step1View = LayoutInflater.from(context).inflate(R.layout.dialog_step1_key_combination, contentContainer, false)
        txtKeyCombination = step1View.findViewById(R.id.txtKeyCombination)
    }

    private fun setupStep2() {
        step2View = LayoutInflater.from(context).inflate(R.layout.dialog_step2_action_type, contentContainer, false)
        radioGroup = step2View.findViewById(R.id.radioGroup)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioApp -> selectedActionType = KeyShortcut.ActionType.APP
                R.id.radioSettings -> selectedActionType = KeyShortcut.ActionType.SETTINGS
            }
            btnNext.isEnabled = selectedActionType != null
        }
    }

    private fun setupStep3() {
        step3View = LayoutInflater.from(context).inflate(R.layout.dialog_step3_select_action, contentContainer, false)
        editName = step3View.findViewById(R.id.editName)
        btnSelectApp = step3View.findViewById(R.id.btnSelectApp)
        layoutSettings = step3View.findViewById(R.id.layoutSettings)

        // Set suggested name
        val suggestedName = buildString {
            if (pressedModifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT)) append("Ctrl + ")
            if (pressedModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) append("Shift + ")
            if (pressedModifiers.contains(KeyEvent.KEYCODE_ALT_LEFT)) append("Alt + ")
            mainKeyCode?.let { append(getKeyName(it)) }
        }
        editName.setText(suggestedName)

        when (selectedActionType) {
            KeyShortcut.ActionType.APP -> {
                btnSelectApp.visibility = View.VISIBLE
                layoutSettings.visibility = View.GONE
                btnSelectApp.setOnClickListener {
                    val intent = Intent(context, AppListActivity::class.java).apply {
                        putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
                    }
                    appPickerLauncher.launch(intent)
                }
            }
            KeyShortcut.ActionType.SETTINGS -> {
                btnSelectApp.visibility = View.GONE
                layoutSettings.visibility = View.VISIBLE
                setupSettingsCards()
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
        val pm = context.packageManager
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            btnSelectApp.text = appName
        } catch (e: Exception) {
            btnSelectApp.text = packageName
        }
        btnNext.isEnabled = true
    }

    private fun showStep(step: Int) {
        currentStep = step

        // Update UI
        txtStepIndicator.text = context.getString(
            when (step) {
                1 -> R.string.step_1_of_3
                2 -> R.string.step_2_of_3
                else -> R.string.step_3_of_3
            }
        )

        txtTitle.text = when (step) {
            1 -> context.getString(R.string.press_keys)
            2 -> context.getString(R.string.action_type)
            else -> context.getString(R.string.select_action)
        }

        btnBack.visibility = if (step > 1) View.VISIBLE else View.GONE
        btnNext.text = if (step == 3) context.getString(R.string.finish) else context.getString(R.string.next)

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
            Toast.makeText(context, context.getString(R.string.shortcut_name_hint), Toast.LENGTH_SHORT).show()
            return
        }

        if (mainKeyCode == null || selectedActionType == null || selectedActionData == null) {
            Toast.makeText(context, context.getString(R.string.shortcut_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val shortcut = KeyShortcut(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            modifiers = pressedModifiers.toSet(),
            keyCode = mainKeyCode!!,
            actionType = selectedActionType!!,
            actionData = selectedActionData!!
        )

        onShortcutAdded(shortcut)
        dismiss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentStep != 1) return super.onKeyDown(keyCode, event)

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
                if (pressedModifiers.isNotEmpty()) {
                    mainKeyCode = keyCode
                    btnNext.isEnabled = true
                }
            }
        }

        updateKeyCombinationDisplay()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentStep != 1) return super.onKeyUp(keyCode, event)

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

    private fun updateKeyCombinationDisplay() {
        val parts = mutableListOf<String>()

        if (pressedModifiers.contains(KeyEvent.KEYCODE_CTRL_LEFT)) parts.add("Ctrl")
        if (pressedModifiers.contains(KeyEvent.KEYCODE_SHIFT_LEFT)) parts.add("Shift")
        if (pressedModifiers.contains(KeyEvent.KEYCODE_ALT_LEFT)) parts.add("Alt")
        if (pressedModifiers.contains(KeyEvent.KEYCODE_META_LEFT)) parts.add("Meta")

        mainKeyCode?.let { parts.add(getKeyName(it)) }

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
