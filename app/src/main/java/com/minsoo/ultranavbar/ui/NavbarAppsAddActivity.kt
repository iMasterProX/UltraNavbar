package com.minsoo.ultranavbar.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class NavbarAppsAddActivity : Activity() {
    companion object {
        private const val REQUEST_NAVBAR_APPS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pickIntent = Intent(this, AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_SELECTION_MODE, AppListActivity.MODE_NAVBAR_APPS)
        }
        startActivityForResult(pickIntent, REQUEST_NAVBAR_APPS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // AppListActivity가 MODE_NAVBAR_APPS 모드에서 저장 + 브로드캐스트를 직접 처리
        finish()
    }
}
