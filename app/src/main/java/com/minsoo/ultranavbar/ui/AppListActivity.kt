package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.minsoo.ultranavbar.R
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

        // MODE_DISABLED_APPS 모드에서 비활성화된 앱 목록 로드
        if (selectionMode == MODE_DISABLED_APPS) {
            selectedPackages = settings.disabledApps.toMutableSet()
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
        
        // MODE_SINGLE에서만 저장 버튼 숨기기
        btnSave.visibility = if (selectionMode == MODE_SINGLE) View.GONE else View.VISIBLE
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

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in installedApps) {
            // 시스템 앱이 아닌 것만 (선택적으로 시스템 앱도 포함 가능)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            // 런처에 표시되는 앱만 필터링
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null || isSystemApp) {
                val name = pm.getApplicationLabel(appInfo).toString()
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    null
                }

                apps.add(AppInfo(
                    packageName = appInfo.packageName,
                    name = name,
                    icon = icon,
                    isSystemApp = isSystemApp
                ))
            }
        }

        // 이름순 정렬
        return apps.sortedBy { it.name.lowercase() }
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            adapter.submitList(allApps, selectedPackages)
        } else {
            val filtered = allApps.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            adapter.submitList(filtered, selectedPackages)
        }
    }

    private fun saveAndFinish() {
        // MODE_DISABLED_APPS 모드에서 비활성화된 앱 목록 저장
        if (selectionMode == MODE_DISABLED_APPS) {
            settings.disabledApps = selectedPackages
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
