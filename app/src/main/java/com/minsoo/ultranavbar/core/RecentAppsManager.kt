package com.minsoo.ultranavbar.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.Log
import com.minsoo.ultranavbar.settings.SettingsManager

/**
 * 최근 앱 목록 관리
 *
 * 접근성 이벤트(TYPE_WINDOW_STATE_CHANGED)를 통한 실시간 추적을 primary로,
 * UsageStatsManager를 통한 초기 로드를 secondary로 사용
 */
class RecentAppsManager(
    private val context: Context,
    private val listener: RecentAppsChangeListener
) {
    companion object {
        private const val TAG = "RecentAppsManager"
        const val MAX_RECENT_APPS = 6
    }

    /**
     * 최근 앱 변경 리스너
     */
    interface RecentAppsChangeListener {
        fun onRecentAppsChanged(apps: List<RecentAppInfo>)
    }

    /**
     * 최근 앱 정보
     */
    data class RecentAppInfo(
        val packageName: String,
        val icon: Drawable,
        val label: CharSequence,
        val isResizeable: Boolean = true
    )

    // 최근 앱 목록 (index 0 = 가장 최근)
    private val recentApps = mutableListOf<RecentAppInfo>()

    private val settings: SettingsManager = SettingsManager.getInstance(context)
    private val packageManager: PackageManager = context.packageManager

    // 제외할 패키지 목록
    private val excludedPackages: MutableSet<String> = mutableSetOf()

    // 런처 패키지 목록
    private var launcherPackages: Set<String> = emptySet()

    /**
     * 런처 패키지 설정
     */
    fun setLauncherPackages(packages: Set<String>) {
        launcherPackages = packages
        buildExcludedPackages()
    }

    /**
     * 초기 최근 앱 로드 (UsageStatsManager 사용)
     */
    fun loadInitialRecentApps() {
        Log.d(TAG, "Loading initial recent apps")
        val recentPackages = queryUsageStats()

        for (packageName in recentPackages) {
            if (recentApps.size >= MAX_RECENT_APPS) break

            val icon = loadAppIcon(packageName) ?: continue
            val label = loadAppLabel(packageName) ?: packageName

            recentApps.add(RecentAppInfo(packageName, icon, label, true))
        }

        Log.d(TAG, "Loaded ${recentApps.size} initial apps")
        if (recentApps.isNotEmpty()) {
            listener.onRecentAppsChanged(recentApps.toList())
        }
    }

    /**
     * 포그라운드 앱 변경 시 호출
     */
    fun onForegroundAppChanged(packageName: String) {
        if (isExcluded(packageName)) {
            Log.d(TAG, "Excluded package: $packageName")
            return
        }

        // 이미 목록에 있으면 제거 (맨 앞으로 이동하기 위해)
        val existingIndex = recentApps.indexOfFirst { it.packageName == packageName }
        if (existingIndex >= 0) {
            val existing = recentApps.removeAt(existingIndex)
            recentApps.add(0, existing)
            Log.d(TAG, "Moved to front: $packageName")
        } else {
            // 새 앱 추가
            val icon = loadAppIcon(packageName)
            if (icon != null) {
                val label = loadAppLabel(packageName) ?: packageName
                recentApps.add(0, RecentAppInfo(packageName, icon, label, true))
                Log.d(TAG, "Added new app: $packageName")
            }
        }

        // 최대 개수 초과 시 마지막 항목 제거
        while (recentApps.size > MAX_RECENT_APPS) {
            recentApps.removeAt(recentApps.size - 1)
        }

        listener.onRecentAppsChanged(recentApps.toList())
    }

    /**
     * 현재 최근 앱 목록 반환
     */
    fun getRecentApps(): List<RecentAppInfo> = recentApps.toList()

    /**
     * 목록 초기화
     */
    fun clear() {
        recentApps.clear()
        Log.d(TAG, "Cleared recent apps")
    }

    /**
     * 제외 패키지 목록 구성
     */
    private fun buildExcludedPackages() {
        excludedPackages.clear()
        excludedPackages.add("com.android.systemui")
        excludedPackages.add(context.packageName) // 자기 자신
        excludedPackages.addAll(launcherPackages)
        excludedPackages.addAll(settings.disabledApps)

        Log.d(TAG, "Excluded packages: $excludedPackages")
    }

    /**
     * 패키지가 제외 대상인지 확인
     */
    private fun isExcluded(packageName: String): Boolean {
        if (packageName.isEmpty()) return true
        if (excludedPackages.contains(packageName)) return true

        // 런처블 activity가 없는 시스템 전용 패키지 제외
        return packageManager.getLaunchIntentForPackage(packageName) == null
    }

    /**
     * 앱 아이콘 로드
     */
    private fun loadAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Icon not found for: $packageName")
            null
        }
    }

    /**
     * 앱 레이블 로드
     */
    private fun loadAppLabel(packageName: String): CharSequence? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Label not found for: $packageName")
            null
        }
    }

    /**
     * UsageStatsManager를 통한 최근 앱 조회
     */
    private fun queryUsageStats(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return emptyList()
        }

        // 권한 확인
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps == null) {
            Log.w(TAG, "AppOpsManager not available")
            return emptyList()
        }

        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )

        if (mode != AppOpsManager.MODE_ALLOWED) {
            Log.d(TAG, "PACKAGE_USAGE_STATS not granted, skipping initial load")
            return emptyList()
        }

        // UsageStatsManager 사용
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            Log.w(TAG, "UsageStatsManager not available")
            return emptyList()
        }

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000 // 24시간 전

        return try {
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            usageStats
                .sortedByDescending { it.lastTimeUsed }
                .map { it.packageName }
                .distinct()
                .filter { !isExcluded(it) }
                .take(MAX_RECENT_APPS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query usage stats", e)
            emptyList()
        }
    }
}
