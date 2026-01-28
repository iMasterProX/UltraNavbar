package com.minsoo.ultranavbar.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.minsoo.ultranavbar.R

class WacomPenSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_wacom_pen_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnOpenPenSettings)?.setOnClickListener {
            openPenSettings()
        }
    }

    private fun openPenSettings() {
        try {
            // 설정 > 편리한 기능 (Extensions Settings)
            val intent = Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$ExtensionsSettingsActivity"
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 실패 시 메인 설정 열기
            try {
                val fallbackIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
                startActivity(fallbackIntent)
                Toast.makeText(requireContext(), R.string.pen_settings_fallback, Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), R.string.pen_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
