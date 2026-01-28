package com.minsoo.ultranavbar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationView
import com.minsoo.ultranavbar.settings.SettingsManager
import com.minsoo.ultranavbar.ui.AppSettingsFragment
import com.minsoo.ultranavbar.ui.KeyboardSettingsFragment
import com.minsoo.ultranavbar.ui.NavBarSettingsFragment
import com.minsoo.ultranavbar.ui.SetupActivity
import com.minsoo.ultranavbar.ui.WacomPenSettingsFragment
import com.minsoo.ultranavbar.util.DeviceProfile

class MainActivity : AppCompatActivity() {

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
            val fragment = when (item.itemId) {
                R.id.nav_navbar -> NavBarSettingsFragment()
                R.id.nav_keyboard -> KeyboardSettingsFragment()
                R.id.nav_wacom_pen -> WacomPenSettingsFragment()
                R.id.nav_app_settings -> AppSettingsFragment()
                else -> return@setNavigationItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.contentFrame, fragment)
                .commit()
            true
        }

        // 기본: 네비바 설정 표시
        if (savedInstanceState == null) {
            navigationView.setCheckedItem(R.id.nav_navbar)
            supportFragmentManager.beginTransaction()
                .replace(R.id.contentFrame, NavBarSettingsFragment())
                .commit()
        }
    }
}
