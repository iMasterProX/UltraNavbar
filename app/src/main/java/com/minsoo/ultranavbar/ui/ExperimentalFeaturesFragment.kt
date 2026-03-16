package com.minsoo.ultranavbar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.util.CustomAppIconStore
import com.minsoo.ultranavbar.util.IconPackManager

/**
 * ExperimentalFeaturesFragment - 실험적 기능 설정을 담당하는 Fragment
 */
class ExperimentalFeaturesFragment : Fragment() {

    private enum class CustomIconAction {
        ASSIGN,
        REMOVE
    }

    private lateinit var settings: SettingsManager
    private lateinit var switchSplitScreenTaskbar: SwitchMaterial
    private lateinit var switchTouchPointExperimental: SwitchMaterial
    private lateinit var txtCustomAppIconStatus: TextView
    private lateinit var btnAssignCustomAppIcon: MaterialButton
    private lateinit var btnRemoveCustomAppIcon: MaterialButton
    private lateinit var txtIconPackStatus: TextView
    private lateinit var btnSelectIconPack: MaterialButton
    private lateinit var btnClearIconPack: MaterialButton

    private var pendingCustomIconAction: CustomIconAction? = null
    private var pendingCustomIconPackage: String? = null

    private val customIconAppPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val packageName = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)?.trim().orEmpty()
        if (packageName.isEmpty()) return@registerForActivityResult

        when (pendingCustomIconAction) {
            CustomIconAction.ASSIGN -> {
                pendingCustomIconPackage = packageName
                customIconImagePickerLauncher.launch("image/*")
            }
            CustomIconAction.REMOVE -> {
                val removed = CustomAppIconStore.deleteCustomIcon(requireContext(), packageName)
                val message = if (removed) {
                    getString(R.string.custom_app_icon_removed, packageName)
                } else {
                    getString(R.string.custom_app_icon_remove_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                pendingCustomIconAction = null
                pendingCustomIconPackage = null
                updateCustomAppIconStatus()
                notifySettingsChanged()
            }
            null -> Unit
        }
    }

    private val customIconImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val packageName = pendingCustomIconPackage
        pendingCustomIconPackage = null
        pendingCustomIconAction = null

        if (uri == null || packageName.isNullOrEmpty()) return@registerForActivityResult

        val saved = CustomAppIconStore.saveIconFromUri(requireContext(), packageName, uri)
        val message = if (saved) {
            getString(R.string.custom_app_icon_saved, packageName)
        } else {
            getString(R.string.custom_app_icon_save_failed)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        updateCustomAppIconStatus()
        notifySettingsChanged()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_experimental_features, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsManager.getInstance(requireContext())
        initViews(view)
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateCustomAppIconStatus()
    }

    private fun initViews(view: View) {
        switchSplitScreenTaskbar = view.findViewById(R.id.switchSplitScreenTaskbar)
        switchTouchPointExperimental = view.findViewById(R.id.switchTouchPointExperimental)
        txtCustomAppIconStatus = view.findViewById(R.id.txtCustomAppIconStatus)
        btnAssignCustomAppIcon = view.findViewById(R.id.btnAssignCustomAppIcon)
        btnRemoveCustomAppIcon = view.findViewById(R.id.btnRemoveCustomAppIcon)
        txtIconPackStatus = view.findViewById(R.id.txtIconPackStatus)
        btnSelectIconPack = view.findViewById(R.id.btnSelectIconPack)
        btnClearIconPack = view.findViewById(R.id.btnClearIconPack)
    }

    private fun loadSettings() {
        switchSplitScreenTaskbar.isChecked = settings.splitScreenTaskbarEnabled
        switchTouchPointExperimental.isChecked = settings.touchPointExperimentalEnabled
        updateCustomAppIconStatus()
        updateIconPackStatus()
    }

    private fun setupListeners() {
        switchSplitScreenTaskbar.setOnCheckedChangeListener { _, isChecked ->
            settings.splitScreenTaskbarEnabled = isChecked
            notifySettingsChanged()
        }

        switchTouchPointExperimental.setOnCheckedChangeListener { _, isChecked ->
            settings.touchPointExperimentalEnabled = isChecked
            notifySettingsChanged()
        }

        btnAssignCustomAppIcon.setOnClickListener {
            pendingCustomIconAction = CustomIconAction.ASSIGN
            launchAppPicker(getString(R.string.custom_app_icon_pick_app_title))
        }

        btnRemoveCustomAppIcon.setOnClickListener {
            if (CustomAppIconStore.getCustomIconCount(requireContext()) <= 0) {
                Toast.makeText(requireContext(), R.string.custom_app_icon_none_assigned, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingCustomIconAction = CustomIconAction.REMOVE
            launchAppPicker(getString(R.string.custom_app_icon_remove_title))
        }

        btnSelectIconPack.setOnClickListener {
            showIconPackPicker()
        }

        btnClearIconPack.setOnClickListener {
            settings.iconPackPackage = null
            updateIconPackStatus()
            Toast.makeText(requireContext(), R.string.icon_pack_cleared, Toast.LENGTH_SHORT).show()
            notifySettingsChanged()
        }
    }

    private fun updateCustomAppIconStatus() {
        val count = CustomAppIconStore.getCustomIconCount(requireContext())
        txtCustomAppIconStatus.text = getString(R.string.custom_app_icon_status, count)
        btnRemoveCustomAppIcon.isEnabled = count > 0
        btnRemoveCustomAppIcon.alpha = if (count > 0) 1f else 0.5f
    }

    private fun launchAppPicker(title: String) {
        val intent = Intent(requireContext(), AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_SINGLE)
            putExtra(AppListActivity.EXTRA_TITLE, title)
        }
        customIconAppPickerLauncher.launch(intent)
    }

    private fun updateIconPackStatus() {
        val installedPacks = IconPackManager.getInstalledIconPacks(requireContext())
        val selectedPackage = settings.iconPackPackage
        val selectedPack = installedPacks.firstOrNull { it.packageName == selectedPackage }

        txtIconPackStatus.text = if (selectedPack != null) {
            getString(R.string.icon_pack_selected_status, selectedPack.label)
        } else {
            getString(R.string.icon_pack_not_selected_status, installedPacks.size)
        }

        btnSelectIconPack.isEnabled = installedPacks.isNotEmpty()
        btnSelectIconPack.alpha = if (installedPacks.isNotEmpty()) 1f else 0.5f
        btnClearIconPack.isEnabled = !selectedPackage.isNullOrBlank()
        btnClearIconPack.alpha = if (!selectedPackage.isNullOrBlank()) 1f else 0.5f
    }

    private fun showIconPackPicker() {
        val iconPacks = IconPackManager.getInstalledIconPacks(requireContext())
        if (iconPacks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.icon_pack_none_installed, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = iconPacks.map { it.label.toString() }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.icon_pack_picker_title)
            .setItems(labels) { _, which ->
                val selected = iconPacks[which]
                settings.iconPackPackage = selected.packageName
                updateIconPackStatus()
                Toast.makeText(requireContext(), getString(R.string.icon_pack_selected_toast, selected.label), Toast.LENGTH_SHORT).show()
                notifySettingsChanged()
            }
            .show()
    }

    private fun notifySettingsChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }
}
