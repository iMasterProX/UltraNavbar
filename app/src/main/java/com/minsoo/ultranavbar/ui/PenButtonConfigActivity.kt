package com.minsoo.ultranavbar.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.service.NavBarAccessibilityService
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
        const val REQUEST_TOUCH_POINT = 102
    }

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packageName = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)
            if (packageName != null) {
                handleAppSelected(packageName)
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    private val shortcutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val shortcutIntent = result.data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
            val shortcutName = result.data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)

            if (shortcutIntent != null && shortcutName != null) {
                handleShortcutCreated(shortcutIntent, shortcutName)
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    private val touchPointLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // TouchPointSetupActivity에서 이미 저장함
        finish()
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
            getString(R.string.pen_button_action_touch_point)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pen_button_select_action))
            .setItems(actionTypes) { _, which ->
                when (which) {
                    0 -> setActionNone()
                    1 -> selectApp()
                    2 -> selectShortcut()
                    3 -> selectTouchPoint()
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
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
        }
        appPickerLauncher.launch(intent)
    }

    /**
     * 앱 선택 결과 처리
     */
    private fun handleAppSelected(packageName: String) {
        // 앱의 메인 액티비티 찾기
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val activityName = launchIntent?.component?.className ?: "$packageName.MainActivity"

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
        finish()
    }

    /**
     * 바로가기 선택 - ACTION_CREATE_SHORTCUT을 지원하는 앱 목록 표시
     */
    private fun selectShortcut() {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        val resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        if (resolveInfoList.isEmpty()) {
            Toast.makeText(this, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 앱 이름으로 정렬
        val sortedList = resolveInfoList.sortedBy { it.loadLabel(packageManager).toString() }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_shortcut_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerShortcuts)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_shortcut_app)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .create()

        val adapter = ShortcutAppAdapter(sortedList) { resolveInfo ->
            dialog.dismiss()
            launchShortcutCreation(resolveInfo)
        }
        recyclerView.adapter = adapter

        dialog.show()
    }

    /**
     * 바로가기 생성 Activity 실행
     */
    private fun launchShortcutCreation(resolveInfo: ResolveInfo) {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            component = ComponentName(
                resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name
            )
        }
        try {
            shortcutPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 바로가기 생성 결과 처리
     */
    private fun handleShortcutCreated(shortcutIntent: Intent, shortcutName: String) {
        // Intent를 URI 문자열로 변환하여 저장
        val shortcutUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)

        if (buttonName == "A") {
            settingsManager.penAActionType = "SHORTCUT"
            settingsManager.penAShortcutPackage = shortcutUri  // URI 저장
            settingsManager.penAShortcutId = shortcutName      // 이름 저장
        } else {
            settingsManager.penBActionType = "SHORTCUT"
            settingsManager.penBShortcutPackage = shortcutUri
            settingsManager.penBShortcutId = shortcutName
        }

        // 시스템에 브릿지 Activity 등록
        applyBridgeToSystem()

        Toast.makeText(this, R.string.pen_settings_updated, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 브릿지 Activity를 시스템 Settings.Global에 적용
     */
    private fun applyBridgeToSystem() {
        try {
            val bridgeActivity = if (buttonName == "A") {
                "com.minsoo.ultranavbar/com.minsoo.ultranavbar.ui.PenButtonABridgeActivity"
            } else {
                "com.minsoo.ultranavbar/com.minsoo.ultranavbar.ui.PenButtonBBridgeActivity"
            }

            if (buttonName == "A") {
                Settings.Global.putString(contentResolver, "a_button_component_name", bridgeActivity)
                Settings.Global.putInt(contentResolver, "a_button_setting", 1)
            } else {
                Settings.Global.putString(contentResolver, "b_button_component_name", bridgeActivity)
                Settings.Global.putInt(contentResolver, "b_button_setting", 1)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to apply to system: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 터치 포인트 설정 - 안내 다이얼로그 표시 후 설정 시작
     */
    private fun selectTouchPoint() {
        // 접근성 서비스가 실행 중인지 확인
        if (!NavBarAccessibilityService.isRunning()) {
            Toast.makeText(this, R.string.touch_point_accessibility_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 안내 다이얼로그 표시
        AlertDialog.Builder(this)
            .setTitle(R.string.touch_point_guide_title)
            .setMessage(R.string.touch_point_guide_message)
            .setPositiveButton(R.string.touch_point_guide_start) { _, _ ->
                val intent = Intent(this, TouchPointSetupActivity::class.java).apply {
                    putExtra(TouchPointSetupActivity.EXTRA_BUTTON, buttonName)
                }
                touchPointLauncher.launch(intent)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
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

    /**
     * 바로가기 앱 목록 어댑터
     */
    private inner class ShortcutAppAdapter(
        private val items: List<ResolveInfo>,
        private val onItemClick: (ResolveInfo) -> Unit
    ) : RecyclerView.Adapter<ShortcutAppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_shortcut_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.loadIcon(packageManager))
            holder.title.text = item.loadLabel(packageManager)
            holder.subtitle.text = item.activityInfo.packageName
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
