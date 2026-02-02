package com.minsoo.ultranavbar.util

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * 앱 바로가기(Shortcuts) 관련 유틸리티 클래스
 */
object ShortcutHelper {

    /**
     * 설치된 모든 앱의 바로가기 목록 가져오기
     * (static/dynamic/pinned shortcuts)
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun getAvailableShortcuts(context: Context): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()

        val userHandle = Process.myUserHandle()
        val shortcuts = mutableListOf<ShortcutInfo>()

        // 설치된 모든 앱의 shortcuts 수집
        val packages = context.packageManager.getInstalledApplications(0)
        for (app in packages) {
            try {
                val query = LauncherApps.ShortcutQuery()
                query.setPackage(app.packageName)
                query.setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )

                launcherApps.getShortcuts(query, userHandle)?.let {
                    shortcuts.addAll(it)
                }
            } catch (e: Exception) {
                // 권한 없는 앱은 스킵
                android.util.Log.w("ShortcutHelper", "Failed to get shortcuts for ${app.packageName}: ${e.message}")
            }
        }

        return shortcuts.sortedBy { (it.longLabel ?: it.shortLabel)?.toString() }
    }

    /**
     * 특정 패키지의 바로가기 목록 가져오기
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun getShortcutsForPackage(context: Context, packageName: String): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()

        val userHandle = Process.myUserHandle()

        return try {
            val query = LauncherApps.ShortcutQuery()
            query.setPackage(packageName)
            query.setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )

            launcherApps.getShortcuts(query, userHandle) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("ShortcutHelper", "Failed to get shortcuts for $packageName", e)
            emptyList()
        }
    }

    /**
     * 바로가기 실행
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun launchShortcut(context: Context, shortcutInfo: ShortcutInfo): Boolean {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return false

        return try {
            launcherApps.startShortcut(
                shortcutInfo.`package`,
                shortcutInfo.id,
                null,
                null,
                Process.myUserHandle()
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("ShortcutHelper", "Failed to launch shortcut", e)
            false
        }
    }

    /**
     * 패키지명과 ID로 바로가기 실행
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun launchShortcut(context: Context, packageName: String, shortcutId: String): Boolean {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return false

        return try {
            launcherApps.startShortcut(
                packageName,
                shortcutId,
                null,
                null,
                Process.myUserHandle()
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("ShortcutHelper", "Failed to launch shortcut: $packageName/$shortcutId", e)
            false
        }
    }

    /**
     * 바로가기가 유효한지 확인
     */
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun isShortcutValid(context: Context, packageName: String, shortcutId: String): Boolean {
        val shortcuts = getShortcutsForPackage(context, packageName)
        return shortcuts.any { it.id == shortcutId }
    }
}
