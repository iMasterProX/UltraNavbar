package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.NavbarAppsPanel
import com.minsoo.ultranavbar.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTION_MODE = "selection_mode"
        const val EXTRA_SELECTED_PACKAGE = "selected_package"
        const val MODE_SINGLE = "single"
        const val MODE_MULTIPLE = "multiple"
        const val MODE_DISABLED_APPS = "disabled_apps"
        const val MODE_SHORTCUT_DISABLED_APPS = "shortcut_disabled_apps"
        const val MODE_NAVBAR_APPS = "navbar_apps"
    }

    private lateinit var settings: SettingsManager
    private lateinit var recyclerApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var editSearch: TextInputEditText
    private lateinit var adapter: AppListAdapter
    private lateinit var btnSave: MaterialButton

    private var allApps: List<AppInfo> = emptyList()
    private var selectedPackages: MutableSet<String> = mutableSetOf()
    private var selectionMode: String = MODE_MULTIPLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        settings = SettingsManager.getInstance(this)
        selectionMode = intent.getStringExtra(EXTRA_SELECTION_MODE) ?: MODE_DISABLED_APPS

        // 모드별 초기 선택 앱 목록 로드
        when (selectionMode) {
            MODE_DISABLED_APPS -> selectedPackages = settings.disabledApps.toMutableSet()
            MODE_SHORTCUT_DISABLED_APPS -> selectedPackages = settings.shortcutDisabledApps.toMutableSet()
            MODE_NAVBAR_APPS -> selectedPackages = settings.navbarAppsItems.toMutableSet()
        }

        initViews()
        loadApps()
    }

    private fun initViews() {
        // 툴바
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        // 검색
        editSearch = findViewById(R.id.editSearch)
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        // 진행 표시
        progressBar = findViewById(R.id.progressBar)

        // RecyclerView
        recyclerApps = findViewById(R.id.recyclerApps)
        recyclerApps.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            selectionMode = selectionMode,
            onItemClick = { packageName -> // Single-selection
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_SELECTED_PACKAGE, packageName)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemChecked = { packageName, isChecked -> // Multi-selection
                if (isChecked) {
                    if (selectionMode == MODE_NAVBAR_APPS && selectedPackages.size >= NavbarAppsPanel.MAX_APPS) {
                        Toast.makeText(this, getString(R.string.navbar_apps_max_limit, NavbarAppsPanel.MAX_APPS), Toast.LENGTH_SHORT).show()
                        // 체크 되돌리기
                        adapter.submitList(sortAppsWithSelectedFirst(allApps), selectedPackages)
                        return@AppListAdapter
                    }
                    selectedPackages.add(packageName)
                } else {
                    selectedPackages.remove(packageName)
                }
            }
        )
        recyclerApps.adapter = adapter

        // 저장 버튼
        btnSave = findViewById(R.id.btnSave)
        btnSave.setOnClickListener {
            saveAndFinish()
        }
        
        // MODE_SINGLE에서만 저장 버튼 숨기기, 다중 선택 모드에서는 표시
        btnSave.visibility = when (selectionMode) {
            MODE_SINGLE -> View.GONE
            else -> View.VISIBLE
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            recyclerApps.visibility = View.GONE

            val apps = withContext(Dispatchers.IO) {
                getInstalledApps()
            }

            allApps = apps
            adapter.submitList(apps, selectedPackages)

            progressBar.visibility = View.GONE
            recyclerApps.visibility = View.VISIBLE
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val apps = mutableListOf<AppInfo>()
        val seen = mutableSetOf<String>()

        // LAUNCHER 카테고리로 런처에 표시되는 앱 조회 (QUERY_ALL_PACKAGES 불필요)
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.activityInfo.packageName
            if (!seen.add(packageName)) continue

            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }

            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val name = pm.getApplicationLabel(appInfo).toString()
            val icon = try {
                pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {
                null
            }

            apps.add(AppInfo(
                packageName = packageName,
                name = name,
                icon = icon,
                isSystemApp = isSystemApp
            ))
        }

        // 선택된 앱을 상단에, 그 외는 이름순 정렬
        return sortAppsWithSelectedFirst(apps)
    }

    /**
     * 선택된 앱을 상단에 배치하고, 나머지는 이름순 정렬
     */
    private fun sortAppsWithSelectedFirst(apps: List<AppInfo>): List<AppInfo> {
        val selected = apps.filter { selectedPackages.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
        val notSelected = apps.filter { !selectedPackages.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
        return selected + notSelected
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            // 선택된 앱 상단 정렬 적용
            adapter.submitList(sortAppsWithSelectedFirst(allApps), selectedPackages)
        } else {
            val filtered = allApps.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            // 검색 결과에도 선택된 앱 상단 정렬 적용
            adapter.submitList(sortAppsWithSelectedFirst(filtered), selectedPackages)
        }
    }

    private fun saveAndFinish() {
        // 모드에 따라 선택된 앱 목록 저장
        when (selectionMode) {
            MODE_DISABLED_APPS -> settings.disabledApps = selectedPackages
            MODE_SHORTCUT_DISABLED_APPS -> settings.shortcutDisabledApps = selectedPackages
            MODE_NAVBAR_APPS -> {
                settings.navbarAppsItems = selectedPackages.toList()
                sendBroadcast(Intent(com.minsoo.ultranavbar.core.Constants.Action.SETTINGS_CHANGED))
            }
        }
        finish()
    }

    data class AppInfo(
        val packageName: String,
        val name: String,
        val icon: Drawable?,
        val isSystemApp: Boolean
    )
}
