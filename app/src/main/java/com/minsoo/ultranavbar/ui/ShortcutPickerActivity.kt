package com.minsoo.ultranavbar.ui

import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.ShortcutHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱 바로가기 선택 Activity
 */
class ShortcutPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var settingsManager: SettingsManager
    private var buttonName: String = "A"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut_picker)

        settingsManager = SettingsManager.getInstance(this)
        buttonName = intent.getStringExtra("button") ?: "A"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.pen_button_select_shortcut)

        recyclerView = findViewById(R.id.recyclerShortcuts)
        progressBar = findViewById(R.id.progressBar)
        emptyText = findViewById(R.id.tvEmptyShortcuts)

        recyclerView.layoutManager = LinearLayoutManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            loadShortcuts()
        } else {
            emptyText.text = "Shortcuts are not supported on this Android version"
            emptyText.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun loadShortcuts() {
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val shortcuts = ShortcutHelper.getAvailableShortcuts(this@ShortcutPickerActivity)

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE

                if (shortcuts.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                } else {
                    val adapter = ShortcutAdapter(shortcuts) { shortcut ->
                        onShortcutSelected(shortcut)
                    }
                    recyclerView.adapter = adapter
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun onShortcutSelected(shortcut: ShortcutInfo) {
        if (buttonName == "A") {
            settingsManager.penAActionType = "SHORTCUT"
            settingsManager.penAShortcutPackage = shortcut.`package`
            settingsManager.penAShortcutId = shortcut.id
        } else {
            settingsManager.penBActionType = "SHORTCUT"
            settingsManager.penBShortcutPackage = shortcut.`package`
            settingsManager.penBShortcutId = shortcut.id
        }

        Toast.makeText(this, R.string.pen_settings_updated, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 바로가기 어댑터
     */
    private class ShortcutAdapter(
        private val shortcuts: List<ShortcutInfo>,
        private val onItemClick: (ShortcutInfo) -> Unit
    ) : RecyclerView.Adapter<ShortcutAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val subtitle: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        @RequiresApi(Build.VERSION_CODES.N_MR1)
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val shortcut = shortcuts[position]

            holder.title.text = shortcut.longLabel ?: shortcut.shortLabel
            holder.subtitle.text = shortcut.`package`

            holder.itemView.setOnClickListener {
                onItemClick(shortcut)
            }
        }

        override fun getItemCount() = shortcuts.size
    }
}
