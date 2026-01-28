package com.minsoo.ultranavbar.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
