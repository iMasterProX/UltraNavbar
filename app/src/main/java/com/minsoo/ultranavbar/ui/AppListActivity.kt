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

        
        if (selectionMode == MODE_DISABLED_APPS) {
            selectedPackages = settings.disabledApps.toMutableSet()
        }

        initViews()
        loadApps()
    }

    private fun initViews() {
        
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        
        progressBar = findViewById(R.id.progressBar)

        
        recyclerApps = findViewById(R.id.recyclerApps)
        recyclerApps.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            selectionMode = selectionMode,
            onItemClick = { packageName -> 
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_SELECTED_PACKAGE, packageName)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemChecked = { packageName, isChecked -> 
                if (isChecked) {
                    selectedPackages.add(packageName)
                } else {
                    selectedPackages.remove(packageName)
                }
            }
        )
        recyclerApps.adapter = adapter

        
        btnSave = findViewById(R.id.btnSave)
        btnSave.setOnClickListener {
            saveAndFinish()
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

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in installedApps) {
            
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            
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

        
        return sortAppsWithSelectedFirst(apps)
    }

    
    private fun sortAppsWithSelectedFirst(apps: List<AppInfo>): List<AppInfo> {
        val selected = apps.filter { selectedPackages.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
        val notSelected = apps.filter { !selectedPackages.contains(it.packageName) }
            .sortedBy { it.name.lowercase() }
        return selected + notSelected
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            
            adapter.submitList(sortAppsWithSelectedFirst(allApps), selectedPackages)
        } else {
            val filtered = allApps.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            
            adapter.submitList(sortAppsWithSelectedFirst(filtered), selectedPackages)
        }
    }

    private fun saveAndFinish() {
        
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
