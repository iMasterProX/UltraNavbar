package com.minsoo.ultranavbar.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.PaintFunction
import com.minsoo.ultranavbar.model.PenButtonActionType
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 펜 버튼 설정 Activity
 * 다이얼로그 형태로 동작 타입을 선택하고, 세부 설정을 진행합니다.
 */
class PenButtonConfigActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private var buttonName: String = "A" // "A" or "B"

    companion object {
        const val REQUEST_SELECT_APP = 100
        const val REQUEST_SELECT_SHORTCUT = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager.getInstance(this)
        buttonName = intent.getStringExtra("button") ?: "A"

        // 다이얼로그로 동작 타입 선택
        showActionTypeDialog()
    }

    /**
     * 동작 타입 선택 다이얼로그
     */
    private fun showActionTypeDialog() {
        val actionTypes = arrayOf(
            getString(R.string.pen_button_action_none),
            getString(R.string.pen_button_action_app),
            getString(R.string.pen_button_action_shortcut),
            getString(R.string.pen_button_action_paint)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pen_button_select_action))
            .setItems(actionTypes) { _, which ->
                when (which) {
                    0 -> setActionNone()
                    1 -> selectApp()
                    2 -> selectShortcut()
                    3 -> selectPaintFunction()
                }
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    /**
     * 비활성화 설정
     */
    private fun setActionNone() {
        if (buttonName == "A") {
            settingsManager.penAActionType = "NONE"
            settingsManager.penAAppPackage = null
            settingsManager.penAAppActivity = null
            settingsManager.penAShortcutPackage = null
            settingsManager.penAShortcutId = null
            settingsManager.penAPaintFunction = null
        } else {
            settingsManager.penBActionType = "NONE"
            settingsManager.penBAppPackage = null
            settingsManager.penBAppActivity = null
            settingsManager.penBShortcutPackage = null
            settingsManager.penBShortcutId = null
            settingsManager.penBPaintFunction = null
        }

        // Settings.Global 값도 제거
        try {
            if (buttonName == "A") {
                Settings.Global.putString(contentResolver, "a_button_component_name", null)
                Settings.Global.putInt(contentResolver, "a_button_setting", 0)
            } else {
                Settings.Global.putString(contentResolver, "b_button_component_name", null)
                Settings.Global.putInt(contentResolver, "b_button_setting", 0)
            }
        } catch (e: Exception) {
            // 권한 없음
        }

        Toast.makeText(this, R.string.pen_settings_updated, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 앱 선택
     */
    private fun selectApp() {
        val intent = Intent(this, AppListActivity::class.java).apply {
            putExtra("mode", "pen_button")
            putExtra("button", buttonName)
        }
        startActivityForResult(intent, REQUEST_SELECT_APP)
    }

    /**
     * 바로가기 선택
     */
    private fun selectShortcut() {
        val intent = Intent(this, ShortcutPickerActivity::class.java).apply {
            putExtra("button", buttonName)
        }
        startActivityForResult(intent, REQUEST_SELECT_SHORTCUT)
    }

    /**
     * 페인팅 기능 선택
     */
    private fun selectPaintFunction() {
        val functions = PaintFunction.values()
        val functionNames = functions.map { getString(resources.getIdentifier(it.displayNameKey, "string", packageName)) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pen_button_select_paint_function))
            .setItems(functionNames) { _, which ->
                val selectedFunction = functions[which]

                if (buttonName == "A") {
                    settingsManager.penAActionType = "PAINT_FUNCTION"
                    settingsManager.penAPaintFunction = selectedFunction.name
                } else {
                    settingsManager.penBActionType = "PAINT_FUNCTION"
                    settingsManager.penBPaintFunction = selectedFunction.name
                }

                Toast.makeText(this, R.string.pen_settings_updated, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_SELECT_APP -> {
                    val packageName = data?.getStringExtra("package_name")
                    val activityName = data?.getStringExtra("activity_name")

                    if (packageName != null && activityName != null) {
                        if (buttonName == "A") {
                            settingsManager.penAActionType = "APP"
                            settingsManager.penAAppPackage = packageName
                            settingsManager.penAAppActivity = activityName
                        } else {
                            settingsManager.penBActionType = "APP"
                            settingsManager.penBAppPackage = packageName
                            settingsManager.penBAppActivity = activityName
                        }

                        // Settings.Global에도 적용 (ComponentName 형식)
                        applyAppToSystem(packageName, activityName)

                        Toast.makeText(this, R.string.pen_settings_updated, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
                REQUEST_SELECT_SHORTCUT -> {
                    // 바로가기 선택 결과 처리는 ShortcutPickerActivity에서 처리
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    /**
     * 앱 설정을 시스템 Settings.Global에 적용
     */
    private fun applyAppToSystem(packageName: String, activityName: String) {
        try {
            val componentName = "$packageName/$activityName"

            if (buttonName == "A") {
                Settings.Global.putString(contentResolver, "a_button_component_name", componentName)
                Settings.Global.putInt(contentResolver, "a_button_setting", 1)
            } else {
                Settings.Global.putString(contentResolver, "b_button_component_name", componentName)
                Settings.Global.putInt(contentResolver, "b_button_setting", 1)
            }
        } catch (e: Exception) {
            // 권한 없음
            Toast.makeText(this, "Failed to apply to system: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
