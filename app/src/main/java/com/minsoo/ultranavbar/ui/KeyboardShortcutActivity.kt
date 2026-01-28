package com.minsoo.ultranavbar.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.model.KeyShortcut
import com.minsoo.ultranavbar.settings.KeyShortcutManager

class KeyboardShortcutActivity : AppCompatActivity(), AddShortcutDialog.DialogListener {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var fabAdd: FloatingActionButton

    private lateinit var shortcutManager: KeyShortcutManager
    private lateinit var adapter: KeyboardShortcutAdapter

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packageName = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)
            if (packageName != null) {
                val dialog = supportFragmentManager.findFragmentByTag(DIALOG_TAG) as? AddShortcutDialog
                dialog?.setSelectedApp(packageName)
            }
        }
    }

    private val shortcutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val shortcutIntent = result.data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
            val shortcutName = result.data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)

            if (shortcutIntent != null && shortcutName != null) {
                // Convert the Intent to a URI string for storage
                val shortcutUri = shortcutIntent.toUri(Intent.URI_INTENT_SCHEME)
                val dialog = supportFragmentManager.findFragmentByTag(DIALOG_TAG) as? AddShortcutDialog
                dialog?.setSelectedShortcut(shortcutUri, shortcutName)
            }
        }
    }

    companion object {
        private const val DIALOG_TAG = "AddShortcutDialog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_shortcut)

        shortcutManager = KeyShortcutManager.getInstance(this)

        initViews()
        loadShortcuts()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        fabAdd = findViewById(R.id.fabAdd)

        toolbar.setNavigationOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = KeyboardShortcutAdapter(emptyList()) { shortcut ->
            confirmDelete(shortcut)
        }
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener {
            showAddShortcutDialog()
        }
    }

    private fun loadShortcuts() {
        val shortcuts = shortcutManager.getAllShortcuts()
        adapter.updateShortcuts(shortcuts)

        if (shortcuts.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showAddShortcutDialog() {
        val dialog = AddShortcutDialog()
        dialog.show(supportFragmentManager, DIALOG_TAG)
    }

    override fun onShortcutAdded(shortcut: KeyShortcut) {
        addShortcut(shortcut)
    }

    override fun onAppSelectionRequested() {
        val intent = Intent(this, AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
        }
        appPickerLauncher.launch(intent)
    }

    override fun onShortcutSelectionRequested() {
        showShortcutAppList()
    }

    private fun showShortcutAppList() {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT)
        val resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        if (resolveInfoList.isEmpty()) {
            Toast.makeText(this, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        // Sort by app name
        val sortedList = resolveInfoList.sortedBy { it.loadLabel(packageManager).toString() }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_shortcut_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerShortcuts)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_shortcut_app)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val adapter = ShortcutAppAdapter(sortedList) { resolveInfo ->
            dialog.dismiss()
            launchShortcutCreation(resolveInfo)
        }
        recyclerView.adapter = adapter

        dialog.show()
    }

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
        }
    }

    // Inner adapter for shortcut apps
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

    private fun addShortcut(shortcut: KeyShortcut) {
        // Check for duplicates
        if (shortcutManager.isDuplicate(shortcut.modifiers, shortcut.keyCode)) {
            Toast.makeText(this, R.string.shortcut_duplicate, Toast.LENGTH_SHORT).show()
            return
        }

        shortcutManager.addShortcut(shortcut)
        loadShortcuts()
        Toast.makeText(this, R.string.shortcut_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(shortcut: KeyShortcut) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_shortcut)
            .setMessage(getString(R.string.shortcut_name) + ": ${shortcut.name}")
            .setPositiveButton(R.string.delete_shortcut) { _, _ ->
                deleteShortcut(shortcut)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteShortcut(shortcut: KeyShortcut) {
        shortcutManager.deleteShortcut(shortcut.id)
        loadShortcuts()
        Toast.makeText(this, R.string.shortcut_deleted, Toast.LENGTH_SHORT).show()
    }
}
