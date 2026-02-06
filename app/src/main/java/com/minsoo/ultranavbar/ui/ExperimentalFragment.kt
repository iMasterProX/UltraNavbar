package com.minsoo.ultranavbar.ui

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minsoo.ultranavbar.R
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * ExperimentalFragment - 실험적 기능 설정을 담당하는 Fragment
 *
 * 포함 기능:
 * - 최근 앱 작업 표시줄 (분할 화면 기능)
 * - 기타 실험적 기능들
 */
class ExperimentalFragment : Fragment() {

    private lateinit var settings: SettingsManager

    // 최근 앱 작업 표시줄
    private lateinit var switchRecentAppsTaskbar: SwitchMaterial
    private lateinit var txtRecentAppsDescription: TextView
    private lateinit var cardRecentAppsTaskbar: MaterialCardView

    // Shizuku 자동 터치
    private lateinit var switchShizukuAutoTouch: SwitchMaterial


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_experimental, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsManager.getInstance(requireContext())

        initViews(view)
        loadSettings()
        setupListeners()
    }

    private fun initViews(view: View) {
        // 경고 카드
        view.findViewById<TextView>(R.id.txtWarningTitle)?.text = getString(R.string.experimental_warning_title)
        view.findViewById<TextView>(R.id.txtWarningMessage)?.text = getString(R.string.experimental_warning_message)

        // 최근 앱 작업 표시줄
        cardRecentAppsTaskbar = view.findViewById(R.id.cardRecentAppsTaskbar)
        switchRecentAppsTaskbar = view.findViewById(R.id.switchRecentAppsTaskbar)
        txtRecentAppsDescription = view.findViewById(R.id.txtRecentAppsDescription)

        // HTML 형식의 설명 적용
        txtRecentAppsDescription.text = Html.fromHtml(
            getString(R.string.experimental_recent_apps_taskbar_summary),
            Html.FROM_HTML_MODE_COMPACT
        )

        // Shizuku 자동 터치
        switchShizukuAutoTouch = view.findViewById(R.id.switchShizukuAutoTouch)
    }

    private fun loadSettings() {
        switchRecentAppsTaskbar.isChecked = settings.recentAppsTaskbarEnabled
        switchShizukuAutoTouch.isChecked = settings.shizukuAutoTouchEnabled
    }

    private fun setupListeners() {
        switchRecentAppsTaskbar.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 활성화 시 경고 표시
                showEnableWarningDialog()
            } else {
                settings.recentAppsTaskbarEnabled = false
                notifySettingsChanged()
            }
        }

        switchShizukuAutoTouch.setOnCheckedChangeListener { _, isChecked ->
            settings.shizukuAutoTouchEnabled = isChecked
        }
    }

    private fun showEnableWarningDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.experimental_warning_title)
            .setMessage(R.string.experimental_warning_message)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.recentAppsTaskbarEnabled = true
                notifySettingsChanged()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                switchRecentAppsTaskbar.isChecked = false
            }
            .setCancelable(false)
            .show()
    }

    private fun notifySettingsChanged() {
        context?.sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
    }
}
