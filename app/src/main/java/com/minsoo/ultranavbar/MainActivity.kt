package com.minsoo.ultranavbar

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationView
import com.minsoo.ultranavbar.core.Constants
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.ui.AppSettingsFragment
import com.minsoo.ultranavbar.ui.ExperimentalFeaturesFragment
import com.minsoo.ultranavbar.ui.KeyboardSettingsFragment
import com.minsoo.ultranavbar.ui.NavBarSettingsFragment
import com.minsoo.ultranavbar.ui.SetupActivity
import com.minsoo.ultranavbar.ui.WacomPenSettingsFragment
import com.minsoo.ultranavbar.ui.HardwareInfoFragment
import com.minsoo.ultranavbar.util.DeviceProfile

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val DESTINATION_PEN_SETTINGS = "pen_settings"
        const val DESTINATION_KEYBOARD_SETTINGS = "keyboard_settings"
    }

    private lateinit var navigationView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 태블릿 전용 체크
        if (!DeviceProfile.isTablet(this)) {
            Toast.makeText(this, R.string.tablet_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 최초 실행 시 SetupActivity로 이동
        val settings = SettingsManager.getInstance(this)
        if (!settings.setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        navigationView = findViewById(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { item ->
            if (item.itemId == R.id.nav_launcher) {
                openLauncherSettings()
                return@setNavigationItemSelectedListener false
            }

            val fragment = when (item.itemId) {
                R.id.nav_navbar -> NavBarSettingsFragment.newInstance(NavBarSettingsFragment.MODE_THREE_BUTTON)
                R.id.nav_gesture_navbar -> NavBarSettingsFragment.newInstance(NavBarSettingsFragment.MODE_GESTURE)
                R.id.nav_keyboard -> KeyboardSettingsFragment()
                R.id.nav_wacom_pen -> WacomPenSettingsFragment()
                R.id.nav_hardware -> HardwareInfoFragment()
                R.id.nav_app_settings -> AppSettingsFragment()
                R.id.nav_experimental -> ExperimentalFeaturesFragment()
                else -> return@setNavigationItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.contentFrame, fragment)
                .commit()
            true
        }

        // 기본: 네비바 설정 표시 (또는 intent로 지정된 화면)
        if (savedInstanceState == null) {
            handleNavigationIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNavigationIntent(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity가 재생성되지 않으므로 Fragment는 자동으로 회전 처리됨
    }

    private fun handleNavigationIntent(intent: Intent) {
        val navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)

        when (navigateTo) {
            DESTINATION_PEN_SETTINGS -> {
                navigationView.setCheckedItem(R.id.nav_wacom_pen)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.contentFrame, WacomPenSettingsFragment())
                    .commit()
            }
            DESTINATION_KEYBOARD_SETTINGS -> {
                navigationView.setCheckedItem(R.id.nav_keyboard)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.contentFrame, KeyboardSettingsFragment())
                    .commit()
            }
            else -> {
                navigationView.setCheckedItem(R.id.nav_navbar)
                supportFragmentManager.beginTransaction()
                    .replace(
                        R.id.contentFrame,
                        NavBarSettingsFragment.newInstance(NavBarSettingsFragment.MODE_THREE_BUTTON)
                    )
                    .commit()
            }
        }
    }

    private fun openLauncherSettings() {
        if (!isPackageInstalled(Constants.Launcher.QUICKSTEPPLUS_PACKAGE)) {
            Toast.makeText(this, R.string.launcher_not_installed, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).apply {
            component = ComponentName(
                Constants.Launcher.QUICKSTEPPLUS_PACKAGE,
                "com.android.launcher3.settings.SettingsActivity"
            )
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.launcher_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
