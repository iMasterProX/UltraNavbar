package com.minsoo.ultranavbar.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * ExperimentalFeaturesFragment - 실험적 기능 설정을 담당하는 Fragment
 */
class ExperimentalFeaturesFragment : Fragment() {

    private lateinit var settings: SettingsManager
    private lateinit var switchSplitScreenTaskbar: SwitchMaterial
    private lateinit var switchTouchPointExperimental: SwitchMaterial

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

    private fun initViews(view: View) {
        switchSplitScreenTaskbar = view.findViewById(R.id.switchSplitScreenTaskbar)
        switchTouchPointExperimental = view.findViewById(R.id.switchTouchPointExperimental)
    }

    private fun loadSettings() {
        switchSplitScreenTaskbar.isChecked = settings.splitScreenTaskbarEnabled
        switchTouchPointExperimental.isChecked = settings.touchPointExperimentalEnabled
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
    }

    private fun notifySettingsChanged() {
        requireContext().sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }
}
