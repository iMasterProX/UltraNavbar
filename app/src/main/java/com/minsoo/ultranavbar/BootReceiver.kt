package com.minsoo.ultranavbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 부팅 완료 시 접근성 서비스 상태 확인
 *
 * 참고: 접근성 서비스는 시스템에서 자동으로 시작되므로,
 * 이 리시버는 필요 시 추가 초기화 작업에 사용할 수 있습니다.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed - Accessibility service will be started by system if enabled")
            // 접근성 서비스는 시스템에서 자동으로 시작됨
            // 필요 시 여기서 추가 초기화 작업 수행
        }
    }
}
