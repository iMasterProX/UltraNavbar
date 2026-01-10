package com.minsoo.ultranavbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed - Accessibility service will be started by system if enabled")
            
        }
    }
}
